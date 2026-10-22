import ActivityKit
import Foundation
import Shared
import UIKit

/// An optional observer/controller of the existing app session, never a keepalive.
@MainActor
final class LiveActivityCoordinator {
    private weak var controller: MicController?
    private var subscription: MicStateSubscription?
    private var policy = LiveActivitySessionPolicy()
    private var latest: MicState?
    private var content: MineMicActivityAttributes.ContentState?
    private var activity: Activity<MineMicActivityAttributes>?
    private var worker: Task<Void, Never>?
    private var monitor: Task<Void, Never>?
    private var heartbeat: Task<Void, Never>?
    private var authorizationMonitor: Task<Void, Never>?
    private var revision = 0
    private var foreground = false
    private var enabled = UserDefaults.standard.bool(forKey: "MineMic.liveActivity.enabled")
    private var failure: String?

    lazy var controls = LockScreenControls(onChange: { [weak self] value in
        Task { @MainActor [weak self] in self?.setEnabled(value.boolValue) }
    })

    init(controller: MicController) {
        self.controller = controller
        foreground = UIApplication.shared.applicationState == .active
        refreshControls()
        // A new process has no authenticated in-memory PC session. Remove stale
        // cards without claiming the PC was released or restarting recording.
        let stale = Activity<MineMicActivityAttributes>.activities
        Task { for activity in stale { await activity.end(nil, dismissalPolicy: .immediate) } }
        MineMicLiveActionRouter.handler = { [weak self] id, muted in
            guard let self, self.enabled, !self.policy.dismissed,
                  self.activity?.attributes.sessionID == id, self.policy.acceptsAction(for: id),
                  self.latest?.ownsPc == true, let controller = self.controller,
                  self.latest?.phase != .disconnecting else { return false }
            if let muted { controller.setMuted(value: muted) }
            else {
                // Keep the user-initiated intent alive until the existing async
                // release protocol completes; do not return before it can run.
                let cleanup = DisconnectCleanup()
                defer { cleanup.end() }
                controller.disconnect()
                var releasing = false
                for _ in 0..<70 {
                    if Task.isCancelled || cleanup.expired { break }
                    try? await Task.sleep(for: .milliseconds(100))
                    if self.policy.sessionID != id || self.latest?.ownsPc != true { break }
                    if self.latest?.phase == .disconnecting { releasing = true }
                    if releasing && self.latest?.phase == .reconnecting { break }
                }
            }
            return true
        }
        subscription = controller.observeState { [weak self] state in
            Task { @MainActor [weak self] in self?.receive(state) }
        }
        authorizationMonitor = Task { [weak self] in
            for await _ in ActivityAuthorizationInfo().activityEnablementUpdates {
                guard !Task.isCancelled else { return }
                self?.failure = nil; self?.refreshControls(); self?.schedule()
            }
        }
    }

    func applicationStateChanged(active: Bool) {
        foreground = active
        if active { failure = nil; refreshControls(); schedule() }
    }

    private func receive(_ state: MicState) {
        let oldID = policy.sessionID
        policy.update(ownsPC: state.ownsPc)
        latest = state
        let next: MineMicActivityAttributes.ContentState?
        if let start = policy.startedAt {
            let phase = state.phase == .reconnecting ? "reconnecting" :
                state.phase == .disconnecting ? "disconnecting" : "connected"
            next = .init(pcName: String(state.pcName.prefix(80)), phase: phase,
                         recording: state.recording, muted: state.muted, startedAt: start)
        } else { next = nil }
        guard next != content || oldID != policy.sessionID else { return }
        content = next; failure = nil; refreshControls(); schedule()
    }

    private func setEnabled(_ value: Bool) {
        enabled = value
        UserDefaults.standard.set(value, forKey: "MineMic.liveActivity.enabled")
        failure = nil
        if value { policy.allowPresentation() }
        refreshControls(); schedule()
    }

    private func refreshControls() {
        let available = ActivityAuthorizationInfo().areActivitiesEnabled
        let message: String
        if !available { message = "请在系统设置中允许 MineMic 实时活动；录音不受影响" }
        else if let failure { message = failure }
        else if policy.dismissed && enabled { message = "本次锁屏卡片已关闭，录音继续；重新开关此项可再次显示" }
        else if enabled && content != nil && activity == nil && !foreground {
            message = "返回 MineMic 后显示锁屏卡片；录音继续"
        } else { message = "在锁屏和灵动岛查看状态、静音或断开；关闭不影响录音" }
        controls.update(enabled: enabled, available: available, message: message)
    }

    private func schedule() {
        revision &+= 1
        guard worker == nil else { return }
        worker = Task { [weak self] in
            guard let self else { return }
            repeat {
                let current = self.revision
                await self.reconcile()
                if current == self.revision { break }
            } while !Task.isCancelled
            self.worker = nil
        }
    }

    private func reconcile() async {
        let allowed = enabled && ActivityAuthorizationInfo().areActivitiesEnabled && !policy.dismissed
        guard allowed, let id = policy.sessionID, let content else {
            monitor?.cancel(); monitor = nil
            heartbeat?.cancel(); heartbeat = nil
            if let old = activity {
                activity = nil
                await old.end(nil, dismissalPolicy: .immediate)
            }
            refreshControls(); return
        }
        let update = ActivityContent(state: content, staleDate: Date().addingTimeInterval(120))
        if let activity, activity.attributes.sessionID == id {
            await activity.update(update)
            return
        }
        if let old = activity {
            monitor?.cancel(); monitor = nil; heartbeat?.cancel(); heartbeat = nil; activity = nil
            await old.end(nil, dismissalPolicy: .immediate)
        }
        // Starting is foreground-only; background updates may continue while the
        // independent, genuine audio background session gives us execution time.
        guard foreground, failure == nil, enabled, !policy.dismissed,
              policy.sessionID == id, ActivityAuthorizationInfo().areActivitiesEnabled else {
            refreshControls(); return
        }
        do {
            let created = try Activity.request(attributes: MineMicActivityAttributes(sessionID: id), content: update, pushType: nil)
            activity = created
            // No timer when this feature is off. While a card exists, mark it
            // fresh at low frequency; a stranded card can then become stale.
            heartbeat = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(30))
                    guard !Task.isCancelled, self?.activity?.id == created.id else { return }
                    self?.schedule()
                }
            }
            monitor = Task { [weak self] in
                for await state in created.activityStateUpdates {
                    guard !Task.isCancelled, let self, self.activity?.id == created.id else { return }
                    if state == .dismissed || state == .ended {
                        self.heartbeat?.cancel(); self.heartbeat = nil
                        self.activity = nil; self.policy.dismiss(); self.refreshControls()
                        return
                    }
                }
            }
        } catch { failure = "锁屏卡片暂不可用，录音继续；返回应用或重新开关此项可重试" }
        refreshControls()
    }

    deinit {
        subscription?.cancel(); worker?.cancel(); monitor?.cancel()
        heartbeat?.cancel(); authorizationMonitor?.cancel()
    }
}

/// A bounded background assertion for explicit disconnect cleanup, never for
/// recording or general residency. Failure leaves the PC owner retained.
@MainActor
private final class DisconnectCleanup {
    private var identifier = UIBackgroundTaskIdentifier.invalid
    private(set) var expired = false
    init() {
        identifier = UIApplication.shared.beginBackgroundTask(withName: "MineMic.disconnect") { [weak self] in
            Task { @MainActor [weak self] in self?.expired = true; self?.end() }
        }
    }
    func end() {
        guard identifier != .invalid else { return }
        let old = identifier; identifier = .invalid
        UIApplication.shared.endBackgroundTask(old)
    }
}
