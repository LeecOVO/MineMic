package com.aiaixyz.minemic.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun LicensesScreen(text: String?, error: Boolean, active: Boolean, onBack: () -> Unit, onRetry: () -> Unit) {
    val bundle = remember(text) { text?.let(::parseLicenseBundle) }
    var selected by remember { mutableStateOf<LicenseDocument?>(null) }
    var detailOpen by remember { mutableStateOf(false) }
    val detailMotion = rememberPageMotion(detailOpen)
    val back = { if (detailOpen) detailOpen = false else onBack() }
    SettingsBackHandler(active, back)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (!active || detailMotion.coversContent) Modifier.clearAndSetSemantics {} else Modifier)) {
            LicensePage(active && !detailMotion.coversContent, onBack = onBack) {
                LicenseHeader("第三方许可", "返回关于 MineMic", onBack)
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("音频处理组件", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("WebRTC、RNNoise 及相关组件的许可声明。选择组件查看完整内容，支持离线阅读。",
                                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    when {
                        error -> item {
                            Text("许可文件暂时无法读取，请重试。", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = onRetry) { Text("重新读取") }
                        }
                        bundle == null -> item {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Text("正在读取本地许可…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        else -> {
                            items(bundle.documents, key = { it.source }) { document ->
                                LicenseRow(document) { selected = document; detailOpen = true }
                            }
                            if (bundle.introduction.isNotBlank()) item {
                                Text("发行声明", Modifier.padding(top = 12.dp).semantics { heading() },
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                SelectionContainer {
                                    Text(licenseParagraphs(bundle.introduction).joinToString("\n\n"), Modifier.padding(top = 10.dp),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            if (!active || detailMotion.coversContent) Box(Modifier.matchParentSize().blockPageInput())
        }
        PageMotion(detailMotion) {
            selected?.let { document ->
                LicenseDetail(document, active && detailOpen, onBack = { detailOpen = false })
            }
        }
    }
}

@Composable
private fun LicenseDetail(document: LicenseDocument, active: Boolean, onBack: () -> Unit) {
    var original by remember(document.source) { mutableStateOf(false) }
    val paragraphs = remember(document.rawText) { licenseParagraphs(document.rawText) }
    // Separate positions make switching between reflowed and original text reversible.
    val readingScroll = rememberLazyListState()
    val originalScroll = rememberLazyListState()
    LicensePage(active, onBack) {
        LicenseHeader("许可详情", "返回许可列表", onBack) {
            TextButton(onClick = { original = !original }, modifier = Modifier.semantics {
                contentDescription = if (original) "切换为阅读排版" else "查看许可原文"
            }) { Text(if (original) "阅读" else "原文") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = if (original) originalScroll else readingScroll,
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Text(document.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { heading() })
                Text(document.source, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(Modifier.padding(top = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (original) item {
                SelectionContainer { Text(document.rawText, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            } else items(paragraphs.size) { index ->
                SelectionContainer { Text(paragraphs[index], style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun LicensePage(active: Boolean, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(active) { if (active) focus.requestFocus() }
    Surface(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures {} }
        .onPreviewKeyEvent {
            if (active && it.key == Key.Escape && it.type == KeyEventType.KeyUp) { onBack(); true } else false
        }.focusRequester(focus).focusable(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding().fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize(), content = content)
        }
    }
}

@Composable
private fun LicenseHeader(title: String, backDescription: String, onBack: () -> Unit, action: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val interaction = remember { MutableInteractionSource() }
        IconButton(onClick = onBack, interactionSource = interaction,
            modifier = Modifier.pressFeedback(interaction).semantics { contentDescription = backDescription }) {
            val color = MaterialTheme.colorScheme.onSurface
            Canvas(Modifier.size(24.dp)) {
                val u = size.width / 24f
                drawLine(color, Offset(19*u,12*u), Offset(5*u,12*u), 1.8f*u, StrokeCap.Round)
                drawLine(color, Offset(5*u,12*u), Offset(11*u,6*u), 1.8f*u, StrokeCap.Round)
                drawLine(color, Offset(5*u,12*u), Offset(11*u,18*u), 1.8f*u, StrokeCap.Round)
            }
        }
        Text(title, Modifier.weight(1f).padding(start = 8.dp).semantics { heading() },
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        action()
    }
}

@Composable
private fun LicenseRow(document: LicenseDocument, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (pressed) colors.surfaceVariant else colors.surface, tween(120))
    val haptic = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().pressFeedback(interaction, 20.dp).clip(MaterialTheme.shapes.large).background(fill)
        .clickable(role = Role.Button, interactionSource = interaction, indication = null, onClickLabel = "查看完整许可") {
            haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onClick()
        }.heightIn(min = 80.dp).padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(document.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(document.source, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Canvas(Modifier.size(18.dp)) {
            val u = size.width / 24f
            drawLine(colors.onSurfaceVariant, Offset(9*u,6*u), Offset(15*u,12*u), 1.8f*u, StrokeCap.Round)
            drawLine(colors.onSurfaceVariant, Offset(15*u,12*u), Offset(9*u,18*u), 1.8f*u, StrokeCap.Round)
        }
    }
}
