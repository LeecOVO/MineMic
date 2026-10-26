import XCTest

final class MineMicScreenshots: XCTestCase {
    private let app = XCUIApplication()

    override func setUpWithError() throws {
        continueAfterFailure = false
        app.launchArguments = ["-AppleLanguages", "(zh-Hans)", "-AppleLocale", "zh_CN"]
        app.launch()
        XCTAssertTrue(text("连接电脑").waitForExistence(timeout: 30))
    }

    override func tearDownWithError() throws {
        if let testRun, testRun.hasSucceeded == false {
            let tree = XCTAttachment(string: app.debugDescription)
            tree.name = "accessibility-tree"
            tree.lifetime = .keepAlways
            add(tree)
        }
        app.terminate()
    }

    func testCaptureScenes() {
        selectTheme("黑色")
        snapshot("01-home-dark")
        enableAudioOptions()
        snapshot("02-processing-dark")

        disableAudioOptions()
        selectTheme("白色")
        snapshot("03-home-light")
        enableAudioOptions()
        snapshot("04-processing-light")

        top()
        tap(button("设置"))
        XCTAssertTrue(text("外观").waitForExistence(timeout: 10))
        snapshot("05-theme-selection")
    }

    private func text(_ label: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", label)).firstMatch
    }

    private func button(_ prefix: String) -> XCUIElement {
        let predicate = NSPredicate(format: "label BEGINSWITH %@", prefix)
        let result = app.buttons.matching(predicate).firstMatch
        return result.exists ? result : app.descendants(matching: .any).matching(predicate).firstMatch
    }

    private func toggle(_ prefix: String) -> XCUIElement {
        app.switches.matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
    }

    private func tap(_ element: XCUIElement) {
        reveal(element)
        element.tap()
        settle()
    }

    private func settle() { Thread.sleep(forTimeInterval: 0.8) }

    private func drag(from: CGFloat, to: CGFloat) {
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: from))
            .press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: to)))
        settle()
    }

    private func top() {
        for _ in 0..<8 {
            if button("设置").isHittable { return }
            drag(from: 0.3, to: 0.8)
        }
        XCTAssertTrue(button("设置").isHittable, "Cannot return to the top of the home page")
    }

    private func reveal(_ element: XCUIElement) {
        for _ in 0..<10 {
            if element.exists && element.isHittable { return }
            if element.exists && element.frame.maxY < app.frame.minY + 80 { drag(from: 0.3, to: 0.65) }
            else { drag(from: 0.75, to: 0.42) }
        }
        XCTAssertTrue(element.isHittable, "Cannot reveal \(element.label)")
    }

    private func selectTheme(_ label: String) {
        top()
        tap(button("设置"))
        tap(button(label))
        tap(button("返回"))
        top()
    }

    private func audioHeader() -> XCUIElement { button("声音处理") }

    private func showProcessing() {
        top()
        let header = audioHeader()
        reveal(header)
        // Anchor the expanded card near the top, keeping the audio controls in view.
        for _ in 0..<6 {
            let distance = header.frame.minY - app.frame.minY - 90
            if abs(distance) < 45 { break }
            let fraction = min(abs(distance) / app.frame.height, 0.32)
            if distance > 0 { drag(from: 0.75, to: 0.75 - fraction) }
            else { drag(from: 0.3, to: 0.3 + fraction) }
        }
        settle()
    }

    private func enableAudioOptions() {
        tap(audioHeader())
        // Compose exposes its custom slider as an adjustable Other element on iOS.
        let gain = app.otherElements.matching(NSPredicate(format: "label == %@", "收音增强")).firstMatch
        reveal(gain)
        gain.coordinate(withNormalizedOffset: CGVector(dx: 0.02, dy: 0.5))
            .press(forDuration: 0.1, thenDragTo: gain.coordinate(withNormalizedOffset: CGVector(dx: 1.0 / 3.0, dy: 0.5)))
        settle()
        XCTAssertTrue(audioHeader().label.contains("收音 +"), "Input gain must be enabled in the screenshot")
        tap(toggle("降噪"))
        tap(button("桌面游戏"))
        XCTAssertTrue(audioHeader().label.contains("桌面游戏降噪"), "Desktop noise suppression must be selected")
        tap(toggle("回声抑制"))
        XCTAssertTrue(text("处理监测").waitForExistence(timeout: 10))

        tap(button("锁屏控制"))
        let lockScreen = toggle("在锁屏上显示")
        reveal(lockScreen)
        if lockScreen.isEnabled, (lockScreen.value as? String) == "0" { tap(lockScreen) }
        tap(button("锁屏控制"))
        showProcessing()
    }

    private func disableAudioOptions() {
        top()
        tap(toggle("回声抑制"))
        top()
        tap(toggle("降噪"))
        top()
        tap(button("恢复 0 dB"))
        top()
        tap(audioHeader())
        top()
    }

    private func snapshot(_ name: String) {
        settle()
        XCTAssertFalse(app.alerts.firstMatch.exists, "A system or app dialog would obscure the screenshot")
        let attachment = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
