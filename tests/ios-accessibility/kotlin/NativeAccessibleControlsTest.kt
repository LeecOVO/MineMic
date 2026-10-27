@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.aiaixyz.minemic.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.toKString
import platform.Foundation.*
import platform.darwin.NSObject
import platform.objc.class_getInstanceMethod
import platform.objc.method_getNumberOfArguments
import platform.objc.method_getTypeEncoding
import platform.objc.object_getClass
import platform.UIKit.*
import platform.posix.fflush

/** UIKit entry-point tests; these do not simulate or certify system VoiceOver gestures. */
class NativeAccessibleControlsTest {
    private fun trace(stage: String) {
        println("[NativeControls] $stage")
        // Keep the last completed stage in simulator logs even if native code terminates.
        fflush(null)
    }

    private fun invokeVoidSelector(receiver: NSObject, name: String) {
        val selector = NSSelectorFromString(name)
        val method = requireNotNull(class_getInstanceMethod(object_getClass(receiver), selector)) { "Missing selector: $name" }
        assertTrue(method_getTypeEncoding(method)?.toKString()?.startsWith("v") == true,
            "Expected a void Objective-C action: $name")
        assertEquals(2U, method_getNumberOfArguments(method), "Only self and selector arguments are expected: $name")
        // performSelector returns id. Calling a void action through it would make the Kotlin
        // bridge interpret an unspecified return register as an Objective-C object.
        // Apple's void-returning API also accepts a selector without explicit arguments.
        // On the main thread with waitUntilDone=true, it invokes the action synchronously.
        receiver.performSelectorOnMainThread(selector, withObject = null, waitUntilDone = true)
    }

    @Test
    fun objectiveCAdjustableSelectorsWalkEveryGainValueInBothDirections() {
        trace("adjustable test entered; mainThread=${NSThread.isMainThread}")
        assertTrue(NSThread.isMainThread, "UIKit controls must be tested on the main thread")
        val changed = mutableListOf<Int>()
        trace("before UISlider construction")
        val slider = NativeGainSlider()
        trace("after UISlider construction")
        slider.configure(0, true, "收音增强", "范围 0 到 18 分贝", changed::add)
        trace("after UISlider configuration")
        assertEquals("minemic.gain.native", slider.valueForKey("accessibilityIdentifier"))
        val increment = NSSelectorFromString("accessibilityIncrement")
        val decrement = NSSelectorFromString("accessibilityDecrement")
        assertTrue(slider.respondsToSelector(increment))
        assertTrue(slider.respondsToSelector(decrement))
        trace("before first void selector invocation")
        for (expected in 1..18) {
            // Objective-C selector dispatch, rather than a Kotlin-only function call.
            invokeVoidSelector(slider, "accessibilityIncrement")
            if (expected == 1) trace("first increment dispatched and returned")
            assertEquals(expected.toFloat(), slider.value)
            assertEquals(expected, changed.last())
            assertEquals("增加 $expected 分贝", slider.accessibilityValue)
        }
        trace("all 18 increments verified")
        invokeVoidSelector(slider, "accessibilityIncrement")
        assertEquals((1..18).toList(), changed)
        for (expected in 17 downTo 0) {
            invokeVoidSelector(slider, "accessibilityDecrement")
            assertEquals(expected.toFloat(), slider.value)
            assertEquals(expected, changed.last())
            assertEquals(if (expected == 0) "0 分贝，不增强收音" else "增加 $expected 分贝", slider.accessibilityValue)
        }
        invokeVoidSelector(slider, "accessibilityDecrement")
        assertEquals((1..18).toList() + (17 downTo 0).toList(), changed)
        slider.dispose()
        trace("all decrements, endpoints, and release verified")
    }

    @Test
    fun gainUsesNativeTargetActionAndOneDecibelAdjustments() {
        val changed = mutableListOf<Int>()
        val slider = NativeGainSlider()
        slider.configure(3, true, "收音增强", "范围 0 到 18 分贝", changed::add)
        slider.accessibilityIncrement()
        slider.accessibilityDecrement()
        assertEquals(listOf(4, 3), changed)

        // This is the same UIControl target/action used by UISlider's native touch tracking.
        slider.setValue(11.4f, animated = false)
        slider.sendActionsForControlEvents(UIControlEventValueChanged)
        assertEquals(11, changed.last())
        assertEquals(11f, slider.value)
        assertEquals("增加 11 分贝", slider.accessibilityValue)
        assertTrue(slider.accessibilityTraits and UIAccessibilityTraitAdjustable != 0UL)
        assertEquals(0UL, slider.accessibilityTraits and UIAccessibilityTraitAllowsDirectInteraction)
        slider.dispose()
    }

    @Test
    fun gainBoundsDisabledAndReleaseDoNotDispatch() {
        val changed = mutableListOf<Int>()
        val slider = NativeGainSlider()
        slider.configure(0, true, "收音增强", "", changed::add)
        slider.accessibilityDecrement()
        assertTrue(changed.isEmpty())
        slider.configure(18, true, "收音增强", "", changed::add)
        slider.accessibilityIncrement()
        assertTrue(changed.isEmpty())
        slider.configure(5, false, "收音增强", "", changed::add)
        slider.accessibilityIncrement()
        slider.setValue(8f, animated = false)
        slider.sendActionsForControlEvents(UIControlEventValueChanged)
        assertTrue(changed.isEmpty())
        slider.configure(5, true, "收音增强", "", changed::add)
        slider.dispose()
        slider.accessibilityIncrement()
        slider.sendActionsForControlEvents(UIControlEventValueChanged)
        assertTrue(changed.isEmpty())
        assertFalse(slider.userInteractionEnabled)
    }

    @Test
    fun segmentedSelectionUsesNativeValueChangedAndRetainsCategoryValues() {
        val changed = mutableListOf<Int>()
        val choice = NativeChoiceView()
        choice.configure(listOf("配对码", "IP 地址"), listOf("使用 6 位数字", "输入电脑 IP"),
            "连接方式", 0, true, false, 17.0, false, changed::add)
        assertFalse(choice.segments.hidden)
        assertFalse(choice.segments.isAccessibilityElement)
        assertFalse(choice.isAccessibilityElement)
        assertEquals("minemic.choice.连接方式.native", choice.segments.valueForKey("accessibilityIdentifier"))
        assertTrue(choice.optionButtons.all { it.hidden })
        choice.segments.selectedSegmentIndex = 1
        choice.segments.sendActionsForControlEvents(UIControlEventValueChanged)
        assertEquals(listOf(1), changed)
        choice.segments.sendActionsForControlEvents(UIControlEventValueChanged)
        assertEquals(listOf(1), changed)
        choice.configure(listOf("配对码", "IP 地址"), listOf("使用 6 位数字", "输入电脑 IP"),
            "连接方式", 1, false, false, 17.0, false, changed::add)
        choice.segments.selectedSegmentIndex = 0
        choice.segments.sendActionsForControlEvents(UIControlEventValueChanged)
        assertEquals(listOf(1), changed)
        choice.dispose()
    }

    @Test
    fun largeTextChoicesAreIndividuallyActivatableNativeButtons() {
        val changed = mutableListOf<Int>()
        val choice = NativeChoiceView()
        choice.configure(listOf("通用", "桌面游戏"), listOf("日常背景声", "键盘和鼠标声"),
            "降噪场景", 0, true, true, 40.0, true, changed::add)
        assertTrue(choice.segments.hidden)
        assertTrue(choice.optionButtons.all { !it.hidden })
        choice.optionButtons[1].sendActionsForControlEvents(UIControlEventTouchUpInside)
        assertEquals(listOf(1), changed)
        assertTrue(choice.optionButtons[1].selected)
        assertFalse(choice.optionButtons[0].selected)
        assertEquals("桌面游戏", choice.optionButtons[1].accessibilityLabel)
        assertEquals("minemic.choice.降噪场景.1.native", choice.optionButtons[1].valueForKey("accessibilityIdentifier"))
        assertTrue(choice.optionButtons[1].accessibilityTraits and UIAccessibilityTraitSelected != 0UL)
        assertEquals(2, choice.accessibilityElements?.size)
        choice.dispose()
        choice.optionButtons[0].sendActionsForControlEvents(UIControlEventTouchUpInside)
        assertEquals(listOf(1), changed)
        assertTrue(choice.accessibilityElementsHidden)
    }
}

/** Exported only by the test-compilation framework, never by the production Shared framework. */
class NativeControlTestHarness {
    fun runCase(name: String): String? = try {
        val application = sharedApplicationOrNull()
        println("[NativeControlHost] case=$name mainThread=${NSThread.isMainThread} application=$application")
        fflush(null)
        checkNotNull(application) { "UIKit target-action tests require an application host started by UIApplicationMain" }
        check(NSThread.isMainThread) { "UIKit target-action tests must run on the main thread" }
        val tests = NativeAccessibleControlsTest()
        when (name) {
            "objectiveCAdjustableSelectorsWalkEveryGainValueInBothDirections" -> tests.objectiveCAdjustableSelectorsWalkEveryGainValueInBothDirections()
            "gainUsesNativeTargetActionAndOneDecibelAdjustments" -> tests.gainUsesNativeTargetActionAndOneDecibelAdjustments()
            "gainBoundsDisabledAndReleaseDoNotDispatch" -> tests.gainBoundsDisabledAndReleaseDoNotDispatch()
            "segmentedSelectionUsesNativeValueChangedAndRetainsCategoryValues" -> tests.segmentedSelectionUsesNativeValueChangedAndRetainsCategoryValues()
            "largeTextChoicesAreIndividuallyActivatableNativeButtons" -> tests.largeTextChoicesAreIndividuallyActivatableNativeButtons()
            else -> error("Unknown native-control test case: $name")
        }
        null
    } catch (failure: Throwable) {
        failure.stackTraceToString().also {
            println("[NativeControlHost] failed: $name\n$it")
            fflush(null)
        }
    }

    // The SDK declaration is non-null, but a standalone Kotlin test process has no UIApplication.
    private fun sharedApplicationOrNull(): UIApplication? = UIApplication.sharedApplication
}
