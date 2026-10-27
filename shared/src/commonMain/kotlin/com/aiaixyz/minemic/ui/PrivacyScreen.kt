package com.aiaixyz.minemic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiaixyz.minemic.AppVersion

@Composable
internal fun PrivacyScreen(document: PrivacyDocument, onBack: () -> Unit, onContact: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures {} }
        .onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { onBack(); true } else false
        }.focusRequester(focus).focusable().semantics { paneTitle = document.title }, color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding().fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val backInteraction = remember { MutableInteractionSource() }
                    IconButton(onClick = onBack, interactionSource = backInteraction,
                        modifier = Modifier.pressFeedback(backInteraction).semantics { contentDescription = "返回关于 MineMic" }) {
                        val color = MaterialTheme.colorScheme.onSurface
                        Canvas(Modifier.size(24.dp)) {
                            val u = size.width / 24f
                            drawLine(color, Offset(19*u, 12*u), Offset(5*u, 12*u), 1.8f*u, StrokeCap.Round)
                            drawLine(color, Offset(5*u, 12*u), Offset(11*u, 6*u), 1.8f*u, StrokeCap.Round)
                            drawLine(color, Offset(5*u, 12*u), Offset(11*u, 18*u), 1.8f*u, StrokeCap.Round)
                        }
                    }
                    Text(document.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 8.dp).semantics { heading() })
                }
                key(document) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("更新日期：$PRIVACY_UPDATED_AT\n适用：MineMic 移动端 ${AppVersion.name}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        when (document) {
                            PrivacyDocument.PRIVACY_POLICY -> privacySections.forEach { section ->
                                PolicyCard(section.title) { section.paragraphs.forEach { PolicyParagraph(it) } }
                            }
                            PrivacyDocument.DATA_COLLECTION -> {
                                PolicyParagraph("本页列明功能运行时访问、处理、传输与保存的资料，不把“没有开发者云端收集”等同于“没有资料处理”。这些页面可离线阅读，不会因此启动采音或连接。")
                                dataPractices.forEach { practice ->
                                    PolicyCard(practice.title) {
                                        PolicyField("资料范围", practice.scope)
                                        PolicyField("处理目的", practice.purpose)
                                        PolicyField("去向与接收方", practice.destination)
                                        PolicyField("保留方式", practice.retention)
                                    }
                                }
                                PolicyCard("不涉及的资料与用途") {
                                    PolicyParagraph("当前版本不读取定位、通讯录、相册、相机画面、Wi-Fi 密码、永久设备标识或广告标识；不记录键盘和鼠标操作。不做广告跟踪、语音转写、声纹识别或模型训练。不在后台主动读取剪贴板；你主动粘贴配对码或 IP 时，系统输入控件会按你的操作使用剪贴板内容。")
                                    PolicyParagraph("主动打开 GitHub 或提交反馈时，网站会处理相应访问资料及你主动填写的内容。MineMic 不会自动附上录音、配对凭据或诊断包；系统自身的备份与诊断由系统设置管理。")
                                }
                            }
                        }
                        TextButton(onClick = onContact, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "项目反馈，GitHub，公开" }.actionHint("在浏览器打开 GitHub，提交内容会公开")) {
                            Text("项目反馈（GitHub，公开）", Modifier.clearAndSetSemantics {})
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PolicyCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
private fun PolicyParagraph(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun PolicyField(label: String, text: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PolicyParagraph(text)
    }
}
