import XCTest
import UIKit
import NativeControlsTestSupport

final class NativeControlsTests: XCTestCase {
    @MainActor
    private func check(_ name: String, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(Thread.isMainThread, file: file, line: line)
        if let failure = NativeControlTestHarness().runCase(name: name) {
            XCTFail(failure, file: file, line: line)
        }
    }

    @MainActor func testObjectiveCAdjustableSelectors() async {
        check("objectiveCAdjustableSelectorsWalkEveryGainValueInBothDirections")
    }
    @MainActor func testNativeSliderTargetAction() async {
        check("gainUsesNativeTargetActionAndOneDecibelAdjustments")
    }
    @MainActor func testGainBoundsDisabledAndRelease() async {
        check("gainBoundsDisabledAndReleaseDoNotDispatch")
    }
    @MainActor func testNativeSegmentedSelection() async {
        check("segmentedSelectionUsesNativeValueChangedAndRetainsCategoryValues")
    }
    @MainActor func testLargeTextNativeButtons() async {
        check("largeTextChoicesAreIndividuallyActivatableNativeButtons")
    }
}
