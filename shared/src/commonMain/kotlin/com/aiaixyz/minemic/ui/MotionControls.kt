package com.aiaixyz.minemic.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.roundToInt

/** Shared, reversible press feedback with a visible keyboard focus outline. */
@Composable
internal fun Modifier.pressFeedback(
    source: MutableInteractionSource, cornerRadius: Dp = 12.dp, focusTint: Color? = null,
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val focused by source.collectIsFocusedAsState()
    val focusColor = focusTint ?: MaterialTheme.colorScheme.primary
    val scale by animateFloatAsState(if (pressed) .98f else 1f,
        tween(if (pressed) 80 else 160, easing = FastOutSlowInEasing))
    return graphicsLayer { scaleX = scale; scaleY = scale }.drawWithContent {
        drawContent()
        if (focused) drawRoundRect(focusColor, cornerRadius = CornerRadius(cornerRadius.toPx()), style = Stroke(2.dp.toPx()))
    }
}

@Composable
internal fun Reveal(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible,
        enter = expandVertically(tween(240, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(160, delayMillis = 40)),
        exit = shrinkVertically(tween(200, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(100)),
    ) { content() }
}

/** Position is shared with the pager, so the pill follows a finger instead of playing a separate animation. */
internal enum class SlidingChoiceStyle { Default, Pairing }

@Composable
internal fun SlidingChoice(
    labels: List<String>, position: Float, enabled: Boolean,
    onSelect: (Int) -> Unit, onInteraction: () -> Unit = {},
    onDragPosition: ((Float) -> Unit)? = null,
    selectedIndex: Int = position.roundToInt(),
    onDraggingChange: (Boolean) -> Unit = {},
    style: SlidingChoiceStyle = SlidingChoiceStyle.Default,
    nativeMaterialVisible: Boolean = true,
) {
    require(labels.size == 2)
    val latestPosition by rememberUpdatedState(position)
    val latestSelect by rememberUpdatedState(onSelect)
    val latestDrag by rememberUpdatedState(onDragPosition)
    val latestInteraction by rememberUpdatedState(onInteraction)
    val latestDraggingChange by rememberUpdatedState(onDraggingChange)
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val direction = if (rtl) -1 else 1
    val localPosition = remember { Animatable(position) }
    LaunchedEffect(position, dragging) {
        if (!dragging && latestDrag == null) localPosition.animateTo(position, tween(240, easing = FastOutSlowInEasing))
    }
    val colors = MaterialTheme.colorScheme
    val pairing = style == SlidingChoiceStyle.Pairing
    val systemPairing = pairing && usesSystemPairingAppearance
    val pillSource = remember { MutableInteractionSource() }
    val pillPressed by pillSource.collectIsPressedAsState()
    val dark = colors.background.luminance() < .3f
    val trackColor = when {
        systemPairing -> Color.Transparent
        pairing && !dark -> Color(0xFFEAF0EF)
        else -> colors.surfaceContainer
    }
    val trackBorder = if (dark) colors.primary.copy(alpha = .28f) else Color(0xFFB8CEC9)
    val pillBorder = if (dark) colors.primary.copy(alpha = .75f) else Color(0xFF398D7D)
    BoxWithConstraints(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(trackColor)
        .then(if (pairing && !systemPairing) Modifier.border(1.dp, trackBorder.copy(alpha = if (enabled) trackBorder.alpha else .35f),
            RoundedCornerShape(14.dp)) else Modifier)
        .padding(4.dp)) {
        val cellWidth = maxWidth / 2
        val cellPx = with(LocalDensity.current) { cellWidth.toPx() }
        val shown = if (dragging) dragPosition else if (onDragPosition != null) position else localPosition.value
        val drag = Modifier.draggable(
            state = rememberDraggableState { delta ->
                dragPosition = (dragPosition + direction * delta / cellPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                latestDrag?.invoke(dragPosition)
            },
            orientation = Orientation.Horizontal, enabled = enabled,
            onDragStarted = {
                latestInteraction()
                dragPosition = (if (latestDrag != null) latestPosition else localPosition.value).coerceIn(0f, 1f)
                dragging = true
                latestDraggingChange(true)
            },
            onDragStopped = { velocity ->
                val target = if (abs(velocity) > cellPx * 2f) {
                    if (velocity * direction > 0f) 1 else 0
                } else dragPosition.roundToInt()
                if (latestDrag == null) {
                    // Settle from the finger's release position, including a drag back to the same choice.
                    localPosition.snapTo(dragPosition)
                }
                latestSelect(target)
                dragging = false
                latestDraggingChange(false)
            },
        )
        val nativeGlass = if (pairing && nativeMaterialVisible) PlatformPairingSelection(
            labels = labels, position = shown.coerceIn(0f, 1f), enabled = enabled,
            engaged = dragging || pillPressed, rtl = rtl, modifier = Modifier.matchParentSize(),
        ) else false
        if (!nativeGlass) Box(Modifier.matchParentSize()) {
            val pill = Modifier.offset(x = cellWidth * shown.coerceIn(0f, 1f))
                .width(cellWidth).fillMaxHeight().clip(RoundedCornerShape(10.dp))
            if (systemPairing) {
                Box(pill.background(colors.surfaceContainerHigh)
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(10.dp)))
            } else if (pairing) {
                Box(pill.background(colors.primaryContainer)
                    .border(1.dp, pillBorder.copy(alpha = if (enabled) pillBorder.alpha else .35f), RoundedCornerShape(10.dp)))
            } else Box(pill.background(colors.surface))
        }
        Row(Modifier.fillMaxWidth().then(drag).selectableGroup()) {
            labels.forEachIndexed { index, label ->
                val individualSource = remember { MutableInteractionSource() }
                val source = if (pairing) pillSource else individualSource
                val emphasis = (1f - abs(shown - index)).coerceIn(0f, 1f)
                Box(Modifier.weight(1f).pressFeedback(source,
                    focusTint = if (systemPairing) colors.outline else null).clip(RoundedCornerShape(10.dp))
                    .selectable(selected = selectedIndex == index, enabled = enabled,
                        role = Role.Tab, interactionSource = source, indication = null,
                        onClick = { latestInteraction(); latestSelect(index) })
                    .heightIn(min = 46.dp).padding(horizontal = 8.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (nativeGlass) Color.Transparent else lerp(colors.onSurfaceVariant,
                            when {
                                systemPairing -> colors.onSurface
                                pairing -> colors.onPrimaryContainer
                                else -> colors.primary
                            }, emphasis)
                            .copy(alpha = if (enabled) 1f else .5f))
                }
            }
        }
    }
}

@Composable
internal fun DisclosureCard(
    title: String, summary: String, expanded: Boolean, onToggle: () -> Unit,
    icon: @Composable () -> Unit, content: @Composable ColumnScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val angle by animateFloatAsState(if (expanded) 180f else 0f, tween(240, easing = FastOutSlowInEasing))
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().pressFeedback(source).clip(RoundedCornerShape(20.dp))
                .clickable(interactionSource = source, indication = null, role = Role.Button,
                    onClickLabel = if (expanded) "收起$title" else "展开$title", onClick = onToggle)
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) { icon() }
                Column(Modifier.weight(1f).animateContentSize(tween(180, easing = FastOutSlowInEasing)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val color = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(Modifier.size(20.dp).graphicsLayer { rotationZ = angle }) {
                    val unit = size.width / 20f
                    drawLine(color, Offset(5f * unit, 8f * unit), Offset(10f * unit, 13f * unit), 1.7f * unit, StrokeCap.Round)
                    drawLine(color, Offset(10f * unit, 13f * unit), Offset(15f * unit, 8f * unit), 1.7f * unit, StrokeCap.Round)
                }
            }
            Reveal(expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 18.dp), content = content)
            }
        }
    }
}
