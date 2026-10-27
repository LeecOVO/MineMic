# 版本管理

项目遵循 [SemVer 2.0.0](https://semver.org/lang/zh-CN/)。当前版本为 `0.1.0-ea.6`，属于 Early Access 预发布。

- `0.y.z` 表示初始开发阶段，接口仍可能变化。
- `-dev.N` 表示开发快照，`-ea.N` 表示 Early Access 预发布。有内容变化的新分发递增相应编号，不覆盖已发布版本。
- 构建号用于区分构建，与 SemVer 版本分开管理。
- `1.0.0` 表示稳定公共接口；此后不兼容修改升主版本，兼容新增升次版本，兼容修复升修订号。
- 手机与 PC 的应用版本独立管理，协议版本 `v2` 独立于应用版本。涉及配对、独占、重连、断开、控制消息或音频格式的变更需记录兼容性影响。

移动端的版本来源是根目录 `version.xcconfig`，Gradle 和 Xcode 共同读取。共享代码中的 `AppVersion.kt` 由构建生成。

Android `versionCode` 和 iOS `CFBundleVersion` 使用构建号。iOS `CFBundleShortVersionString` 使用三段基础版本，完整预发布版本保存在 `MineMicVersion`，并显示在应用中。
