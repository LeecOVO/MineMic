# MineMic

将 Android 手机或 iPhone 变成电脑麦克风，通过局域网传输声音。移动端采用 Kotlin Multiplatform + Compose Multiplatform，配合独立的 [MineMic-PC](https://github.com/LeecOVO/MineMic-PC) 使用。

已发布版本：**0.0.1-ea.1 · Early Access**。当前开发版本：**0.1.0-dev.1**。项目处于初始开发阶段，版本规则见 [VERSIONING.md](docs/VERSIONING.md)。

## 使用

1. 电脑启动 MineMic-PC，选择输出设备并开始接收。
2. 手机与电脑连接同一局域网，输入 PC 显示的 **6 位配对码**；也可手动填写 PC 的 IPv4 地址，默认端口为 48721。
3. 首台手机独占连接，其他手机无法抢占。网络中断后保留占用，原手机可自动重连。
4. 手机或 PC 主动断开后释放占用，并更新配对码。

在会议、游戏或录音软件中作为系统麦克风使用时，PC 需要安装虚拟声卡：MineMic-PC 输出到其播放端，目标软件使用对应的录音端。选择实体扬声器可以试听声音。

首次使用需允许麦克风和局域网访问。Android 最低版本为 7.0，iOS 工程部署目标为 18.2。连接凭据保存在手机应用进程中；进程结束后，可在 PC 手动释放占用。传输尚未加密，请在可信局域网使用。

## 开发版功能

- 可选 WebRTC AEC3 回声抑制与降噪，默认关闭。回声抑制需要支持外放参考的 Windows PC 端；单独降噪可连接旧版 PC。
- 收音增强支持 0–18 dB，默认 0 dB；可从 +6 dB 开始试听。
- 降噪提供通用与桌面游戏场景，桌面游戏场景使用本地 RNNoise，额外算法延迟约 20 ms。
- iOS 可选锁屏／灵动岛状态及静音、断开控制。关闭实时活动不会停止录音或释放 PC 占用；后台录音仍受系统音频会话与权限限制。

声音处理和锁屏控制均可展开设置。开发版的声音效果、锁屏运行及延迟表现仍需在目标设备和网络环境中测试。

## 构建与测试

使用 JDK 21。Android 构建需要 Android SDK、NDK 28.2.13676358；iOS 构建需要 macOS 和 Xcode。原生音频依赖首次构建还需要 Python 3.9+、curl 和网络，由 Gradle/CMake 自动构建。固定来源与 SHA-256 摘要见 [build-apm.py](scripts/build-apm.py)，应用运行时无需下载依赖。

```sh
./gradlew :shared:jvmTest :androidApp:assembleDebug
```

APK 输出：`androidApp/build/outputs/apk/debug/androidApp-debug.apk`。

iOS 使用 Xcode 打开 `iosApp/iosApp.xcodeproj`，配置自己的签名后运行。也可单独生成模拟器共享框架：

```sh
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
```

在 macOS 运行原生音频与采集回归：

```sh
tests/native-audio/run.sh
tests/ios-audio/run.sh
```

构建 PC 仓库的网络测试目标后，可运行 Kotlin → C++ 互通测试：

```sh
MINEMIC_PC_TEST_SERVER=/path/to/MineMic-PC/build/desktop/minemic_network_tests ./gradlew :shared:jvmTest --rerun-tasks
```

未设置该环境变量时，互通项跳过。协议见 [PC 协议文档](https://github.com/LeecOVO/MineMic-PC/blob/v0.1.0-dev.1/docs/PROTOCOL.md)。自动测试不能代替真机音频、后台运行和网络测试。

## iOS 发布包

Release IPA 完全无签名，不包含 Mach-O 代码签名、`_CodeSignature` 或描述文件，安装者需自行签名。

```sh
python3 scripts/package-ios-unsigned.py /path/to/device.ipa /path/to/MineMic-VERSION-ios-unsigned.ipa
```

脚本保留输入文件，要求输出文件不存在，并去除、校验签名及生成 SHA-256 校验文件。

## 项目结构

- `shared`：共享界面、会话、协议及声音处理。
- `androidApp`：Android 权限、采集、网络和麦克风前台服务。
- `iosApp`：Swift 采集、网络、音频会话和实时活动。
- `native`：原生声音处理及第三方许可证。
- `desktopApp`：共享界面的桌面开发预览，可通过 `./gradlew :desktopApp:run` 启动。
- `tests`、`scripts`：音频回归与构建工具。

## 许可证

原创代码采用 [MIT License](LICENSE)，版权所有 © 2026 LeeC。第三方组件保留各自的版权与许可声明，见 [native/licenses](native/licenses) 及应用内许可信息。
