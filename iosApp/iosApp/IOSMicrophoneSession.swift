import AVFoundation
import Foundation

/// Owns audio hardware for the app session, independently of view/lock state.
final class IOSMicrophoneSession {
    private let queue = DispatchQueue(label: "MineMic.capture", qos: .userInteractive)
    private var intent = CaptureIntent()
    private var generation: UInt64 = 0
    private var engine: AVAudioEngine?
    private var capture: IOSAudioCapture?
    private var observers: [NSObjectProtocol] = []
    private var engineObserver: NSObjectProtocol?
    private var onFrame: ((Data, Int64) -> Void)?
    private var onError: ((String) -> Void)?
    private var onState: ((Bool, String) -> Void)?
    private var routeRestart: DispatchWorkItem?

    func start(onFrame: @escaping (Data, Int64) -> Void, onError: @escaping (String) -> Void,
               onState: @escaping (Bool, String) -> Void) {
        queue.async { [self] in
            stopIO(); generation &+= 1; let epoch = generation
            intent.start(); self.onFrame = onFrame; self.onError = onError; self.onState = onState
            observeSession()
            AVAudioApplication.requestRecordPermission { [weak self] allowed in
                guard let self else { return }
                self.queue.async {
                    guard self.generation == epoch, self.intent.requested else { return }
                    guard allowed else { self.fail("请在系统设置中允许麦克风权限"); return }
                    if !self.intent.interrupted { self.startIO() }
                }
            }
        }
    }
    func stop() { queue.async { [self] in
        generation &+= 1; intent.stop(); stopIO()
        observers.forEach { NotificationCenter.default.removeObserver($0) }; observers.removeAll()
        onFrame = nil; onError = nil; onState = nil
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    } }
    func becameActive() { queue.async { [self] in
        if intent.foreground(), engine?.isRunning != true { stopIO(); startIO() }
    } }
    private func observeSession() {
        guard observers.isEmpty else { return }
        let center = NotificationCenter.default, session = AVAudioSession.sharedInstance()
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: nil) { [weak self] notification in
            let type = (notification.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt) ?? 0
            let options = (notification.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt) ?? 0
            self?.queue.async { [weak self] in
                guard let self, self.intent.requested else { return }
                if type == AVAudioSession.InterruptionType.began.rawValue {
                    self.intent.interruptionBegan(); self.stopIO()
                    self.onState?(false, "麦克风被系统音频打断，连接保留")
                } else if self.intent.interruptionEnded(mayResume: options & AVAudioSession.InterruptionOptions.shouldResume.rawValue != 0) {
                    self.startIO()
                } else {
                    self.onState?(false, "音频中断已结束，请重新启用麦克风")
                }
            }
        })
        observers.append(center.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: nil) { [weak self] notification in
            let reason = (notification.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt) ?? 0
            guard let reason = AVAudioSession.RouteChangeReason(rawValue: reason),
                  [.newDeviceAvailable, .oldDeviceUnavailable, .override, .routeConfigurationChange].contains(reason) else { return }
            self?.queue.async { self?.scheduleRouteRestart() }
        })
        observers.append(center.addObserver(forName: AVAudioSession.mediaServicesWereResetNotification, object: session, queue: nil) { [weak self] _ in
            self?.queue.async {
                guard let self else { return }
                self.stopIO(); self.intent.mediaServicesReset()
                if self.intent.requested { self.onState?(false, "系统音频服务已重置，请重新启用麦克风") }
            }
        })
    }
    private func scheduleRouteRestart() {
        guard intent.requested, !intent.interrupted, !intent.requiresUserAction, engine != nil else { return }
        routeRestart?.cancel()
        let epoch = generation
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.generation == epoch, self.intent.requested,
                  !self.intent.interrupted, !self.intent.requiresUserAction else { return }
            self.stopIO(); self.startIO()
        }
        routeRestart = work; queue.asyncAfter(deadline: .now() + .milliseconds(100), execute: work)
    }
    private func startIO() {
        guard intent.requested, !intent.interrupted, !intent.requiresUserAction, engine == nil else { return }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.record, mode: .measurement, options: [])
            try session.setPreferredSampleRate(48000)
            try session.setPreferredIOBufferDuration(0.01)
            try session.setActive(true)
            let engine = AVAudioEngine(), input = engine.inputNode
            let source = input.outputFormat(forBus: 0)
            let capture = try IOSAudioCapture(format: source, queue: queue) { [weak self] data, micros in self?.onFrame?(data, micros) }
            let sink = AVAudioSinkNode { timestamp, frames, buffers in
                let hardware = timestamp.pointee
                let micros = hardware.mFlags.contains(.hostTimeValid) ? Int64(AVAudioTime.seconds(forHostTime: hardware.mHostTime) * 1000000) : 0
                capture.receive(timestamp: hardware.mSampleTime, hostMicros: micros, frames: frames, buffers: buffers)
                return noErr
            }
            engine.attach(sink); engine.connect(input, to: sink, format: source)
            self.engine = engine; self.capture = capture
            engine.prepare(); try engine.start()
            engineObserver = NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil) { [weak self, weak engine] _ in
                self?.queue.async {
                    guard let self, self.engine === engine else { return }
                    self.scheduleRouteRestart()
                }
            }
            onState?(true, "手机麦克风正在传输")
        } catch {
            stopIO()
            // A temporary hardware failure keeps the frame channel and owner alive.
            onState?(false, "麦克风暂不可用，请返回应用重新启用：\(error.localizedDescription)")
        }
    }
    private func fail(_ message: String) { stopIO(); intent.stop(); onError?(message) }
    private func stopIO() {
        routeRestart?.cancel(); routeRestart = nil
        if let engineObserver { NotificationCenter.default.removeObserver(engineObserver); self.engineObserver = nil }
        engine?.stop(); engine = nil; capture?.stop(); capture = nil
    }
    deinit {
        routeRestart?.cancel(); observers.forEach { NotificationCenter.default.removeObserver($0) }
        if let engineObserver { NotificationCenter.default.removeObserver(engineObserver) }
        engine?.stop(); capture?.stop()
    }
}
