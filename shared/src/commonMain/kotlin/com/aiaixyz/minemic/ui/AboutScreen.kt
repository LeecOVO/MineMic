package com.aiaixyz.minemic.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiaixyz.minemic.AppVersion

@Composable
internal fun AboutScreen(
    active: Boolean,
    onBack: () -> Unit,
    onProject: () -> Unit,
    onPrivacyDocument: (PrivacyDocument) -> Unit,
    onLicenses: () -> Unit,
    mark: @Composable () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) focus.requestFocus() }
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures {} }
        .onPreviewKeyEvent {
            if (active && it.key == Key.Escape && it.type == KeyEventType.KeyUp) { onBack(); true } else false
        }.focusRequester(focus).focusable(), color = colors.background) {
        Box(Modifier.safeDrawingPadding().fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val backInteraction = remember { MutableInteractionSource() }
                    IconButton(onClick = onBack, interactionSource = backInteraction,
                        modifier = Modifier.pressFeedback(backInteraction).semantics { contentDescription = "返回" }) {
                        AboutIcon(AboutGlyph.Back)
                    }
                    Text("关于", Modifier.weight(1f).padding(start = 8.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Surface(color = colors.surface, shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxWidth().padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Surface(color = colors.primaryContainer, shape = MaterialTheme.shapes.medium) {
                                    Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) { mark() }
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text("MineMic", style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
                                    Text("把手机变成电脑麦克风", style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant)
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 18.dp), color = colors.outlineVariant)
                            Text("当前版本", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Text(AppVersion.name, Modifier.padding(top = 5.dp), style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                            Text(AppVersion.stage, Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall,
                                color = colors.primary)
                        }
                    }
                    AboutSection("项目") {
                        AboutRow("项目主页", "GitHub · LeecOVO/MineMic", AboutGlyph.Project, external = true, onClick = onProject)
                    }
                    AboutSection("政策与许可") {
                        AboutRow("隐私权政策", "了解资料的处理方式与隐私保护", AboutGlyph.Privacy,
                            onClick = { onPrivacyDocument(PrivacyDocument.PRIVACY_POLICY) })
                        AboutDivider()
                        AboutRow("资料收集政策与范围", "查看资料范围、用途与保留方式", AboutGlyph.Document,
                            onClick = { onPrivacyDocument(PrivacyDocument.DATA_COLLECTION) })
                        AboutDivider()
                        AboutRow("WebRTC 与第三方许可", "开源组件与许可声明", AboutGlyph.License, onClick = onLicenses)
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp).semantics { heading() })
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
private fun AboutDivider() {
    HorizontalDivider(Modifier.padding(start = 64.dp, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun AboutRow(title: String, detail: String, glyph: AboutGlyph, external: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(
        if (pressed) colors.onSurface.copy(alpha = .06f) else colors.onSurface.copy(alpha = 0f),
        tween(if (pressed) 80 else 180))
    val arrowOffset by animateDpAsState(if (pressed) 3.dp else 0.dp, tween(if (pressed) 100 else 180))
    Row(Modifier.fillMaxWidth().pressFeedback(interaction, 16.dp)
        .clip(MaterialTheme.shapes.medium).background(fill)
        .clickable(role = Role.Button, interactionSource = interaction, indication = null,
            onClickLabel = if (external) "在浏览器打开项目主页" else "打开$title") {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onClick()
        }.heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        AboutIcon(glyph, Modifier.size(24.dp), accent = true)
        Column(Modifier.weight(1f).padding(start = 24.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AboutIcon(if (external) AboutGlyph.External else AboutGlyph.Forward,
            Modifier.offset(x = arrowOffset).size(18.dp))
    }
}

private enum class AboutGlyph { Back, Forward, External, Project, Privacy, Document, License }

@Composable
private fun AboutIcon(glyph: AboutGlyph, modifier: Modifier = Modifier.size(24.dp), accent: Boolean = false) {
    val color = if (accent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val stroke = Stroke(1.65f * u, cap = StrokeCap.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color,
            Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), stroke.width, StrokeCap.Round)
        when (glyph) {
            AboutGlyph.Back -> { line(19f, 12f, 5f, 12f); line(5f, 12f, 11f, 6f); line(5f, 12f, 11f, 18f) }
            AboutGlyph.Forward -> { line(9f, 6f, 15f, 12f); line(15f, 12f, 9f, 18f) }
            AboutGlyph.External -> { line(6f, 18f, 18f, 6f); line(8f, 6f, 18f, 6f); line(18f, 6f, 18f, 16f) }
            AboutGlyph.Project -> {
                drawRoundRect(color, Offset(3*u, 4*u), Size(18*u, 16*u), CornerRadius(3*u), style = stroke)
                line(3f, 9f, 21f, 9f); line(7f, 6.5f, 7.2f, 6.5f); line(10f, 6.5f, 10.2f, 6.5f)
            }
            AboutGlyph.Privacy -> {
                drawRoundRect(color, Offset(5*u, 10*u), Size(14*u, 11*u), CornerRadius(2*u), style = stroke)
                drawArc(color, 180f, 180f, false, Offset(8*u, 2*u), Size(8*u, 12*u), style = stroke)
                line(12f, 14f, 12f, 17f)
            }
            AboutGlyph.Document -> {
                drawRoundRect(color, Offset(5*u, 2*u), Size(14*u, 20*u), CornerRadius(2*u), style = stroke)
                line(9f, 8f, 15f, 8f); line(9f, 12f, 15f, 12f); line(9f, 16f, 13f, 16f)
            }
            AboutGlyph.License -> {
                drawCircle(color, 9*u, Offset(12*u, 12*u), style = stroke)
                line(7f, 12f, 10.5f, 15.5f); line(10.5f, 15.5f, 17f, 9f)
            }
        }
    }
}
