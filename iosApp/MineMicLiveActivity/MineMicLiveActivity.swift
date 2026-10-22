import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

@main
struct MineMicWidgetBundle: WidgetBundle {
    var body: some Widget { MineMicLiveActivity() }
}

private struct WaveMark: View {
    var body: some View {
        GeometryReader { geometry in
            Path { path in
                let size = geometry.size
                let stroke = size.width / 8.5
                for (index, height) in [0.32, 0.68, 1.0, 0.54, 0.26].enumerated() {
                    let x = size.width * Double(index + 1) / 6
                    path.move(to: CGPoint(x: x, y: size.height * (1 - height) / 2 + stroke / 2))
                    path.addLine(to: CGPoint(x: x, y: size.height * (1 + height) / 2 - stroke / 2))
                }
            }.stroke(Color.mint, style: StrokeStyle(lineWidth: geometry.size.width / 8.5, lineCap: .round))
        }.accessibilityHidden(true)
    }
}

struct MineMicLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: MineMicActivityAttributes.self) { context in
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 10) {
                    WaveMark().frame(width: 26, height: 26)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(context.state.pcName.isEmpty ? "MineMic" : context.state.pcName)
                            .font(.headline).lineLimit(1).privacySensitive()
                        Text(context.isStale ? "状态待刷新，请打开 MineMic 查看" : context.state.status)
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 6)
                    Text(context.state.startedAt, style: .timer)
                        .font(.caption.monospacedDigit()).multilineTextAlignment(.trailing)
                        .accessibilityLabel("连接时长")
                }
                HStack(spacing: 12) {
                    Button(intent: SetMineMicMuteIntent(sessionID: context.attributes.sessionID, muted: !context.state.muted)) {
                        Label(context.state.muted ? "取消静音" : "静音", systemImage: context.state.muted ? "mic.fill" : "mic.slash.fill")
                            .frame(maxWidth: .infinity)
                    }.disabled(context.isStale || context.state.phase == "disconnecting")
                    Button(intent: DisconnectMineMicIntent(sessionID: context.attributes.sessionID)) {
                        Label("断开", systemImage: "xmark.circle").frame(maxWidth: .infinity)
                    }.disabled(context.state.phase == "disconnecting")
                }.buttonStyle(.bordered).tint(.mint)
            }
            .padding(16)
            .activityBackgroundTint(Color(red: 0.07, green: 0.10, blue: 0.08))
            .activitySystemActionForegroundColor(.mint)
            .foregroundStyle(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    WaveMark().frame(width: 30, height: 30)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(context.state.startedAt, style: .timer).font(.caption.monospacedDigit())
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.state.pcName.isEmpty ? "MineMic" : context.state.pcName).font(.headline).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 8) {
                        Text(context.isStale ? "状态待刷新" : context.state.status).font(.caption)
                        HStack {
                            Button(intent: SetMineMicMuteIntent(sessionID: context.attributes.sessionID, muted: !context.state.muted)) {
                                Label(context.state.muted ? "取消静音" : "静音", systemImage: context.state.muted ? "mic.fill" : "mic.slash.fill")
                            }.disabled(context.isStale || context.state.phase == "disconnecting")
                            Spacer()
                            Button(intent: DisconnectMineMicIntent(sessionID: context.attributes.sessionID)) {
                                Label("断开", systemImage: "xmark.circle")
                            }.disabled(context.state.phase == "disconnecting")
                        }.buttonStyle(.bordered).tint(.mint)
                    }
                }
            } compactLeading: {
                WaveMark().frame(width: 20, height: 20)
            } compactTrailing: {
                Image(systemName: context.state.muted ? "mic.slash.fill" : (context.state.recording ? "mic.fill" : "exclamationmark.triangle.fill"))
                    .foregroundStyle(context.isStale ? .gray : .mint)
                    .accessibilityLabel(context.isStale ? "状态待刷新" : context.state.status)
            } minimal: {
                Image(systemName: context.state.muted ? "mic.slash.fill" : (context.state.recording ? "mic.fill" : "exclamationmark.triangle.fill"))
                    .foregroundStyle(context.isStale ? .gray : .mint)
                    .accessibilityLabel(context.isStale ? "状态待刷新" : context.state.status)
            }.keylineTint(.mint)
        }
    }
}
