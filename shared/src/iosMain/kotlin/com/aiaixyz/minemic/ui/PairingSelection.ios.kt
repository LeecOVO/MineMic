@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.aiaixyz.minemic.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGRectMake
import platform.UIKit.*
import kotlin.math.PI
import kotlin.math.sin

internal actual val usesSystemPairingAppearance: Boolean = true

@Composable
internal actual fun PlatformPairingSelection(
    labels: List<String>, position: Float, enabled: Boolean, engaged: Boolean, rtl: Boolean, compact: Boolean,
    modifier: Modifier, reducedMotion: Boolean,
): Boolean {
    val factory = LocalNativeGlassEffectFactory.current ?: return false
    // The native material tracks system transparency/contrast preferences, including Reduce Transparency.
    val glass = remember(factory, enabled) { factory.create(enabled) } ?: return false
    val dark = MaterialTheme.colorScheme.background.luminance() < .3f
    val fontSize = MaterialTheme.typography.labelLarge.fontSize.value * LocalDensity.current.fontScale
    UIKitView(
        factory = { PairingGlassView() },
        modifier = modifier,
        // The glass must sit above Metal to sample it. Compose owns gestures and accessibility.
        properties = UIKitInteropProperties(interactionMode = null,
            isNativeAccessibilityEnabled = false, placedAsOverlay = true),
        update = { view ->
            // The overlay is hosted outside Compose's controller; mirror only the app's light/dark mode.
            view.overrideUserInterfaceStyle = if (dark) UIUserInterfaceStyle.UIUserInterfaceStyleDark
                else UIUserInterfaceStyle.UIUserInterfaceStyleLight
            view.update(labels, position.toDouble(), enabled, engaged, rtl, compact, glass, fontSize.toDouble(), reducedMotion)
        },
    )
    return true
}

/** A transparent backdrop, one real glass lens, and sharp foreground labels. */
private class PairingGlassView : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val lens = UIVisualEffectView(effect = null)
    private val backLabels = List(2) { UILabel() }
    private val frontLabels = List(2) { UILabel() }
    private var currentEffect: UIVisualEffect? = null
    private var position = 0.0
    private var rtl = false
    private var compact = false
    private var engaged = false
    private var fontSize = 0.0
    private var reducedMotion = false

    init {
        backgroundColor = UIColor.clearColor
        opaque = false
        userInteractionEnabled = false
        isAccessibilityElement = false
        accessibilityElementsHidden = true
        backLabels.forEach { addSubview(it) }
        addSubview(lens)
        lens.backgroundColor = UIColor.clearColor
        lens.opaque = false
        lens.clipsToBounds = true
        lens.userInteractionEnabled = false
        frontLabels.forEach { lens.contentView.addSubview(it) }
        (backLabels + frontLabels).forEach {
            it.textAlignment = NSTextAlignmentCenter
            it.numberOfLines = 0
            it.lineBreakMode = NSLineBreakByWordWrapping
            it.backgroundColor = UIColor.clearColor
            it.isAccessibilityElement = false
        }
    }

    fun update(
        labels: List<String>, position: Double, enabled: Boolean, engaged: Boolean, rtl: Boolean, compact: Boolean,
        glass: UIVisualEffect, fontSize: Double, reducedMotion: Boolean,
    ) {
        this.position = position.coerceIn(0.0, 1.0)
        this.rtl = rtl
        this.compact = compact
        this.reducedMotion = reducedMotion || UIAccessibilityIsReduceMotionEnabled()
        if (this.fontSize != fontSize) {
            this.fontSize = fontSize
            val font = UIFont.systemFontOfSize(fontSize, weight = UIFontWeightSemibold)
            (backLabels + frontLabels).forEach { it.font = font }
        }
        labels.forEachIndexed { index, text ->
            backLabels[index].text = text
            backLabels[index].textColor = if (enabled) UIColor.secondaryLabelColor else UIColor.tertiaryLabelColor
            frontLabels[index].text = text
            frontLabels[index].textColor = if (enabled) UIColor.labelColor else UIColor.tertiaryLabelColor
        }
        // Reuse the effect through pager frames; recreate only for appearance/availability changes.
        if (currentEffect !== glass) {
            lens.effect = glass
            currentEffect = glass
        }
        setNeedsLayout()
        layoutIfNeeded()
        val active = engaged && enabled && !this.reducedMotion
        if (this.engaged != active) {
            this.engaged = active
            val animate = {
                lens.transform = CGAffineTransformMakeScale(if (active) 1.025 else 1.0, if (active) 1.08 else 1.0)
            }
            if (this.reducedMotion || window == null) {
                lens.layer.removeAllAnimations()
                animate()
            } else UIView.animateWithDuration(.28, delay = 0.0, usingSpringWithDamping = .78,
                initialSpringVelocity = 0.0, options = UIViewAnimationOptionBeginFromCurrentState,
                animations = animate, completion = null)
        }
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        val width = bounds.useContents { size.width }
        val height = bounds.useContents { size.height }
        if (width <= 0.0 || height <= 0.0) return
        val cell = width / 2.0
        val physicalPosition = if (rtl) 1.0 - position else position
        // Stretch slightly while crossing the divider, without a second position animation.
        val stretch = if (reducedMotion) 0.0 else sin(PI * position) * cell * .055
        val lensWidth = cell + stretch
        val x = (physicalPosition * cell - stretch / 2.0).coerceIn(0.0, width - lensWidth)
        // Changing bounds/center keeps layout independent from the press transform.
        lens.setBounds(CGRectMake(0.0, 0.0, lensWidth, height))
        lens.setCenter(platform.CoreGraphics.CGPointMake(x + lensWidth / 2.0, height / 2.0))
        lens.layer.cornerRadius = if (compact) 4.0 else height / 2.0
        labelsLayout(cell, height, x)
    }

    private fun labelsLayout(cell: Double, height: Double, lensX: Double) {
        for (index in 0..1) {
            val physicalIndex = if (rtl) 1 - index else index
            val x = physicalIndex * cell
            val inset = if (compact) 6.0 else 8.0
            val textWidth = (cell - inset * 2).coerceAtLeast(1.0)
            backLabels[index].setFrame(CGRectMake(x + inset, 0.0, textWidth, height))
            // Clipping by the glass lens keeps the selected text sharp as it follows the pager.
            frontLabels[index].setFrame(CGRectMake(x - lensX + inset, 0.0, textWidth, height))
        }
    }
}
