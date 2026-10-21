# MineMic development context

- Mobile app: Kotlin Multiplatform + Compose; Swift owns iOS discovery/network/capture adapters. PC lives in the independent sibling MineMic-PC repository.
- Current baseline is 0.0.1, first usable EA release, with SemVer prerelease 0.0.1-ea.1. Read docs/VERSIONING.md; do not infer product maturity from builds or installations.
- version.xcconfig is the mobile version source for both Gradle and Xcode. AppVersion.kt is generated; edit the source configuration instead.
- Keep pairing, first-owner exclusivity, retained ownership on network loss, and explicit-disconnect code rotation intact. Application versions and protocol v2 are independent.
- For audio changes, run JVM tests (set MINEMIC_PC_TEST_SERVER to the C++ fixture for integration), tests/ios-audio/run.sh on macOS, and affected platform builds. These do not establish real iPhone-to-Windows audio latency or stability.
- Preserve local Xcode signing edits and never commit signing credentials, provisioning profiles, or built packages.

- User confirmed the iPhone-to-Windows stutter fix accepted for 0.0.1-ea.1. This is not quantitative latency, background, or all-device acceptance.

- Put packaged APK/IPA files in the existing iCloud Drive 构建物 archive, under Android-APK or iOS-IPA / MineMic / version / local (development signing). Do not create a 构件物 folder in the repository.
