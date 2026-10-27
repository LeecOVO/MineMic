@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.aiaixyz.minemic.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.UIKitInteropInteractionMode
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSelectorFromString
import platform.Foundation.setValue
import platform.UIKit.*
import kotlin.math.ceil
import kotlin.math.roundToInt

/** This is a touch-owning UIKit control, not an accessibility-only overlay over Compose. */
private val nativeControlProperties = UIKitInteropProperties(
    interactionMode = UIKitInteropInteractionMode.NonCooperative,
    isNativeAccessibilityEnabled = true,
    // Regular interop preserves Compose page/dialog occlusion; a glass backdrop alone needs overlay placement.
    placedAsOverlay = false,
)

/** UIView's public category property is not a Kotlin supertype on Kotlin UIKit subclasses. */
private fun UIView.identify(identifier: String) {
    setValue(identifier, forKey = "accessibilityIdentifier")
}

@Composable
private fun rememberVoiceOverRunning(): Boolean {
    var running by remember { mutableStateOf(UIAccessibilityIsVoiceOverRunning()) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observer = center.addObserverForName(UIAccessibilityVoiceOverStatusDidChangeNotification,
            `object` = null, queue = NSOperationQueue.mainQueue) {
            running = UIAccessibilityIsVoiceOverRunning()
        }
        onDispose { center.removeObserver(observer) }
    }
    return running
}

@Composable
internal actual fun PlatformAccessibleGainSlider(
    value: Int, enabled: Boolean, onValueChange: (Int) -> Unit, modifier: Modifier,
    visible: Boolean,
    label: String, hint: String,
): Boolean {
    val voiceOver = rememberVoiceOverRunning()
    if (!voiceOver || !visible) return false
    val latestChange by rememberUpdatedState(onValueChange)
    val dark = MaterialTheme.colorScheme.background.luminance() < .3f
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    UIKitView(
        factory = { NativeGainSlider() }, modifier = modifier,
        properties = nativeControlProperties,
        update = { slider ->
            slider.overrideUserInterfaceStyle = if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
            slider.semanticContentAttribute = if (rtl) UISemanticContentAttributeForceRightToLeft else UISemanticContentAttributeForceLeftToRight
            slider.configure(value, enabled, label, hint) { latestChange(it) }
        },
        onRelease = { it.dispose() },
    )
    return true
}

@Composable
internal actual fun PlatformAccessibleChoice(
    labels: List<String>, hints: List<String>, groupLabel: String,
    selectedIndex: Int, enabled: Boolean, visible: Boolean,
    onSelect: (Int) -> Unit, modifier: Modifier,
): Boolean {
    val voiceOver = rememberVoiceOverRunning()
    if (!voiceOver || !visible) return false
    val latestSelect by rememberUpdatedState(onSelect)
    val dark = MaterialTheme.colorScheme.background.luminance() < .3f
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val fontSize = style.fontSize.value * density.fontScale
    BoxWithConstraints(modifier) {
        val widest = labels.maxOf { textWidth(it, style) }
        val stacked = widest + 32.dp > maxWidth / 2
        val lines = ceil(widest.value / (maxWidth.value - 24f).coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        val rowHeight = maxOf(48.dp, (fontSize * 1.5f * if (stacked) lines else 1).dp + 16.dp)
        UIKitView(
            factory = { NativeChoiceView() },
            modifier = Modifier.fillMaxWidth().height(if (stacked) rowHeight * 2 + 8.dp else rowHeight),
            properties = nativeControlProperties,
            update = { view ->
                view.overrideUserInterfaceStyle = if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark else UIUserInterfaceStyle.UIUserInterfaceStyleLight
                view.configure(labels, hints, groupLabel, selectedIndex, enabled, stacked, fontSize.toDouble(), rtl) { latestSelect(it) }
            },
            onRelease = { it.dispose() },
        )
    }
    return true
}

/** Internal so iOS tests can exercise the real UIKit target/action and adjustable entry points. */
internal class NativeGainSlider : UISlider(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private var onGainChange: ((Int) -> Unit)? = null
    private var currentGain = 0

    init {
        minimumValue = 0f
        maximumValue = 18f
        continuous = true
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitAdjustable
        identify("minemic.gain.native")
        addTarget(this, NSSelectorFromString("gainChanged"), UIControlEventValueChanged)
    }

    fun configure(value: Int, enabled: Boolean, label: String, hint: String, onChange: (Int) -> Unit) {
        this.enabled = enabled
        // Keep this subclass's adjustable role and disabled state explicit for native accessibility.
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitAdjustable or
            (if (enabled) UIAccessibilityTraitNone else UIAccessibilityTraitNotEnabled)
        onGainChange = onChange
        // A capture-state refresh must not pull the thumb back while UIKit is tracking a drag.
        currentGain = if (tracking) this.value.roundToInt().coerceIn(0, 18) else value.coerceIn(0, 18)
        if (!tracking) setValue(currentGain.toFloat(), animated = false)
        accessibilityLabel = label
        accessibilityHint = hint
        updateSpokenValue()
    }

    @ObjCAction
    fun gainChanged() {
        if (!enabled || onGainChange == null) return
        val next = value.roundToInt().coerceIn(0, 18)
        setValue(next.toFloat(), animated = false)
        if (next == currentGain) return
        currentGain = next
        updateSpokenValue()
        onGainChange?.invoke(next)
    }

    // UIKit declares these on an NSObject category, which Kotlin cannot override. Export the
    // exact Objective-C action selectors so VoiceOver dispatch reaches this UISlider subclass.
    @ObjCAction fun accessibilityIncrement() = adjust(1)
    @ObjCAction fun accessibilityDecrement() = adjust(-1)

    private fun adjust(delta: Int) {
        if (!enabled || onGainChange == null) return
        val next = (currentGain + delta).coerceIn(0, 18)
        if (next == currentGain) return
        setValue(next.toFloat(), animated = false)
        sendActionsForControlEvents(UIControlEventValueChanged)
    }

    private fun updateSpokenValue() {
        accessibilityValue = if (currentGain == 0) "0 分贝，不增强收音" else "增加 $currentGain 分贝"
    }

    fun dispose() {
        onGainChange = null
        removeTarget(this, NSSelectorFromString("gainChanged"), UIControlEventAllEvents)
        userInteractionEnabled = false
        isAccessibilityElement = false
    }
}

/** System segmented choices; at large fonts, system buttons keep every category label readable. */
internal class NativeChoiceView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    internal val segments = UISegmentedControl(items = listOf("", ""))
    internal val optionButtons = List(2) { UIButton.buttonWithType(UIButtonTypeSystem) }
    private var onSelection: ((Int) -> Unit)? = null
    private var currentIndex = 0
    private var available = true
    private var stacked = false

    init {
        isAccessibilityElement = false
        segments.isAccessibilityElement = false
        addSubview(segments)
        optionButtons.forEach { addSubview(it) }
        segments.addTarget(this, NSSelectorFromString("segmentChanged"), UIControlEventValueChanged)
        optionButtons[0].addTarget(this, NSSelectorFromString("firstSelected"), UIControlEventTouchUpInside)
        optionButtons[1].addTarget(this, NSSelectorFromString("secondSelected"), UIControlEventTouchUpInside)
    }

    fun configure(labels: List<String>, hints: List<String>, groupLabel: String, selectedIndex: Int,
        enabled: Boolean, stacked: Boolean, fontSize: Double, rtl: Boolean, onSelect: (Int) -> Unit) {
        require(labels.size == 2 && hints.size == 2)
        onSelection = onSelect
        available = enabled
        this.stacked = stacked
        val direction = if (rtl) UISemanticContentAttributeForceRightToLeft else UISemanticContentAttributeForceLeftToRight
        semanticContentAttribute = direction
        segments.semanticContentAttribute = direction
        val font = UIFont.systemFontOfSize(fontSize, UIFontWeightSemibold)
        segments.setTitleTextAttributes(mapOf(NSFontAttributeName to font), UIControlStateNormal)
        segments.enabled = enabled
        segments.hidden = stacked
        segments.accessibilityLabel = groupLabel
        segments.accessibilityHint = "选择一个选项后双击，切换$groupLabel"
        segments.identify("minemic.choice.$groupLabel.native")
        labels.forEachIndexed { index, label ->
            segments.setTitle(label, index.toULong())
            val button = optionButtons[index]
            button.setTitle(label, UIControlStateNormal)
            button.titleLabel?.font = font
            button.titleLabel?.numberOfLines = 0
            button.titleLabel?.lineBreakMode = NSLineBreakByWordWrapping
            button.enabled = enabled
            button.hidden = !stacked
            button.accessibilityLabel = label
            button.accessibilityHint = hints[index]
            button.identify("minemic.choice.$groupLabel.$index.native")
        }
        accessibilityElements = if (stacked) optionButtons else listOf(segments)
        updateSelection(selectedIndex.coerceIn(0, 1))
        setNeedsLayout()
    }

    @ObjCAction fun segmentChanged() = select(segments.selectedSegmentIndex.toInt())
    @ObjCAction fun firstSelected() = select(0)
    @ObjCAction fun secondSelected() = select(1)

    private fun select(index: Int) {
        if (!available || onSelection == null || index !in 0..1 || index == currentIndex) return
        updateSelection(index)
        onSelection?.invoke(index)
    }

    private fun updateSelection(index: Int) {
        currentIndex = index
        segments.selectedSegmentIndex = index.toLong()
        optionButtons.forEachIndexed { i, button ->
            button.selected = i == index
            button.accessibilityTraits = UIAccessibilityTraitButton or
                (if (i == index) UIAccessibilityTraitSelected else UIAccessibilityTraitNone) or
                (if (!available) UIAccessibilityTraitNotEnabled else UIAccessibilityTraitNone)
        }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val width = bounds.useContents { size.width }
        val height = bounds.useContents { size.height }
        segments.setFrame(CGRectMake(0.0, 0.0, width, height))
        if (stacked) {
            val row = (height - 8.0) / 2.0
            optionButtons.forEachIndexed { index, button -> button.setFrame(CGRectMake(0.0, index * (row + 8.0), width, row)) }
        }
    }

    fun dispose() {
        onSelection = null
        available = false
        segments.removeTarget(this, NSSelectorFromString("segmentChanged"), UIControlEventAllEvents)
        optionButtons[0].removeTarget(this, NSSelectorFromString("firstSelected"), UIControlEventAllEvents)
        optionButtons[1].removeTarget(this, NSSelectorFromString("secondSelected"), UIControlEventAllEvents)
        accessibilityElementsHidden = true
        userInteractionEnabled = false
    }
}
