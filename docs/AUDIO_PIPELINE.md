# iOS 音频卡顿修复与验证

2026-10-21，用户提供的 Windows 接收端截图显示：已收 1765、拒绝 115、缺包 1693、重缓冲 11，并确认声音断续、延迟大。这些计数不能直接等同于 Wi-Fi 丢包率：PC 的“缺包”也包含播放耗尽时的静音补偿。

## 原因与修改

旧 iOS 适配使用 `installTap(bufferSize: 480)`，但这个参数只是请求大小，系统可以交付更大的录音块。Swift 将一块录音连续拆成多个 10 ms 帧，Kotlin 发送通道却只能存放 4 帧；发送协程无法及时运行时，会主动丢掉这批录音中的旧帧。用一次同步交付 12 帧的回归测试，旧容量下稳定失败，新容量下完整保留样本和序号。此测试证明代码缺陷，不代表已测到用户手机实际回调大小。

- iOS 改为 `AVAudioSinkNode`，以硬件录音块接收音频，避免 tap 的额外批量交付。
- 实时回调只向预分配的 PCM 槽位复制数据，通过非阻塞 try-lock 交接给工作队列；转换、PCM 分包和 Kotlin 调用都在工作队列执行。没有硬实时保证。
- 原生队列最多 32 个槽，且录音总量不超过 160 ms；超过容量时丢弃新块。通过采样时间识别中断并清除转换器和不足一帧的残留，避免拼接跨中断样本。
- Kotlin 通道改为最多 16 帧（160 ms），足以接收短时批量回调；持续过载时丢弃旧帧，以限制积压。正常情况下没有新增等待或定时延迟。
- iOS 网络发送在进入 Dispatch 队列前预留名额，最多 16 个未完成的数据报，避免网络停顿时无界积压。
- 协议仍为 v2，PC 无需为此更新；配对、独占、恢复、断开换码策略保持原样。

## 自动验证

```sh
# macOS / Xcode：直接测试生产使用的 Swift 采集缓冲和转换器，不打开麦克风
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer tests/ios-audio/run.sh

# 单独复现批量输入
./gradlew :shared:jvmTest --tests '*AudioBurstTest'

# 包含真实 C++ 服务的会话回归
MINEMIC_PC_TEST_SERVER=/absolute/path/MineMic-PC/build-desktop/minemic_network_tests ./gradlew :shared:jvmTest
```

Swift 测试覆盖：48 kHz 和 44.1 kHz 转 48 kHz、批量输入、128 样本块拼包、PCM 幅度、160 ms 积压上限、过载后恢复、停止后不再回调。它运行在 macOS 上，不能代替 iOS 音频硬件和局域网实测。

## 开发包状态

当前修复归入 `0.0.1-ea.1` 开发预览（EA），构建号为 4。用户随后明确反馈“卡顿已经验收”，本版作为首个可用 EA 版本；这不扩展为全部平台、后台和性能验收。

## 真机验收

使用修复后的手机应用重新连接现有 PC。正常网络下连续说话/录音至少 10 分钟，检查是否还有连续缺包、播放耗尽和延迟增长；分别测试前台、锁屏、来电后重新启用。用同时录到的拍手声或同步信号测端到端延迟，不能把 PC 显示的排队时长当作总延迟。用户已确认本次真实 iPhone → Windows 的卡顿修复验收通过；仍没有量化延迟、丢帧率、资源占用或全部设备兼容性的结论。

参考：[Apple tap 缓冲大小说明](https://developer.apple.com/documentation/avfaudio/avaudionode/installtap(onbus:buffersize:format:block:))、[AVAudioSinkNode 实时回调约束](https://developer.apple.com/documentation/avfaudio/avaudiosinknode/init(receiverblock:))。
