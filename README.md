# MineMic

**0.0.1-ea.1 · Early Access · 首个可用版本**。首个可用的 EA 版本，仍处于初始开发阶段。版本规则见 [版本约定](docs/VERSIONING.md)。

将 Android/iPhone 当作电脑麦克风的手机端，采用 Kotlin Multiplatform + Compose Multiplatform。电脑端是相邻的独立 C++/Qt 项目 **MineMic-PC**，两个目录分别管理 Git，不互相嵌套。

## 连接方式

1. 在 PC 的 MineMic 中选好音频输出设备并开启接收。
2. 手机与 PC 连接同一局域网，选择“配对码连接”，输入 PC 显示的 **6 位数字码**（前导零有效）。
3. 若局域网发现受路由器限制，选择“手动连接”，输入 PC 的 IPv4；默认端口为 48721，不另外输入配对码。
4. PC 按首次连接原则锁定手机，其他手机被拒绝；断网仍保留占用，原手机可自动重连。
5. 手机或 PC 主动断开后，PC 释放占用并更新配对码，旧码失效。

当前凭据在手机应用进程内保存。系统结束应用进程后不会自动释放 PC，需要在 PC 手动断开。控制与音频尚未加密，请用于可信局域网。

## 代码

- `shared/.../connection`：共同的配对、独占会话、重连、主动断开、音频协议与状态。
- `shared/.../App.kt`：配对码/手动 IP 入口、麦克风状态、静音与断开。
- `androidApp`：系统 NSD、Java TCP/UDP、AudioRecord、麦克风前台服务及权限入口；ViewModel 保留配置变化期间的会话。
- `iosApp`：Bonjour、Network.framework、AVAudioEngine/转换器的 Swift 平台适配；界面与会话逻辑仍在 Kotlin。
- `desktopApp`：原模板保留的开发预览入口，不作为正式 PC 客户端，不提供手机麦克风能力。

Android 需麦克风权限；目标 Android 17/API 37 的设备还需局域网运行时权限。iOS 已声明麦克风、局域网、Bonjour 服务类型及后台音频用途。真实后台/锁屏和权限恢复仍需真机验收。

## 构建和测试

```sh
./gradlew :shared:jvmTest :androidApp:assembleDebug
```

Android 调试包输出 `androidApp/build/outputs/apk/debug/androidApp-debug.apk`。iOS 用 Xcode 打开 `iosApp/iosApp.xcodeproj`，或在配置好的 Xcode 环境生成共享 framework：

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

可运行实际 Kotlin → C++ 会话互通测试，先构建相邻 PC 项目的测试目标，再设置：

```sh
MINEMIC_PC_TEST_SERVER=/absolute/path/MineMic-PC/build-desktop/minemic_network_tests ./gradlew :shared:jvmTest --rerun-tasks
```

没有提供此环境变量时，互通项明确跳过；其余协议单元测试照常运行。协议契约在相邻 PC 仓库的 `docs/PROTOCOL.md`，需保持 Kotlin、C++、Python 三者一致。

## 当前验证边界

2026-10-21：Android Debug APK、Kotlin iOS Simulator framework、完整 iOS 模拟器应用构建通过；Kotlin JVM 7 项测试通过（包括真实 C++ 接收器互通）；PC 回归 5 项通过。系统 Bonjour 在本机发现 PC 服务成功。

用户已在真实 iPhone → Windows 连接中反馈声音断续、延迟大。已修复 iOS 批量采集与发送队列过小导致的丢帧，并增加原生采集测试；用户已确认卡顿验收通过。具体改动、验证方法和限制见 [音频链路修复记录](docs/AUDIO_PIPELINE.md)。

用户已确认本次 iPhone → Windows 卡顿修复验收通过；尚无量化端到端延迟、后台锁屏长时稳定性及 CPU/内存结论，其他设备和虚拟声卡组合仍需测试。PC 仍依赖独立虚拟声卡，本仓库没有自有驱动。

## 许可证

本项目原创代码采用 [MIT License](LICENSE)，版权所有 © 2026 LeeC。第三方组件、依赖及工具保留其各自的版权声明与许可证。
