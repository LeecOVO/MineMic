package com.aiaixyz.minemic.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(
    mode: ThemeMode, onChange: (ThemeMode) -> Unit,
    manualReducedMotion: Boolean = false, systemReducedMotion: Boolean = false,
    onReducedMotionChange: (Boolean) -> Unit = {}, onBack: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Surface(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures {} }
        .onPreviewKeyEvent {
            if (it.key == Key.Escape && it.type == KeyEventType.KeyUp) { onBack(); true } else false
        }.focusRequester(focus).focusable().semantics { paneTitle = "设置" }, color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding().fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 560.dp).fillMaxSize()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "返回主页" }.actionHint("关闭设置")) {
                        val color = MaterialTheme.colorScheme.onSurface
                        Canvas(Modifier.size(24.dp)) {
                            val u = size.width / 24f
                            drawLine(color, Offset(19*u, 12*u), Offset(5*u, 12*u), 1.8f*u, StrokeCap.Round)
                            drawLine(color, Offset(5*u, 12*u), Offset(11*u, 6*u), 1.8f*u, StrokeCap.Round)
                            drawLine(color, Offset(5*u, 12*u), Offset(11*u, 18*u), 1.8f*u, StrokeCap.Round)
                        }
                    }
                    Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp).semantics { heading() })
                }
                Text("外观", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().semantics { heading() })
                Text("选择适合你的明暗风格", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp))
                Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ThemeMode.entries.forEach { option -> ThemeOption(option, option == mode) { onChange(option) } }
                }
                Text("选择自动保存，下次打开依然生效。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 24.dp))
                MotionOption(manualReducedMotion, systemReducedMotion, onReducedMotionChange)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun MotionOption(manual: Boolean, system: Boolean, onChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large) {
        BoxWithConstraints(Modifier.fillMaxWidth().pressFeedback(interaction, 18.dp)
            .toggleable(value = manual || system, enabled = !system, role = Role.Switch,
                interactionSource = interaction, indication = null, onValueChange = onChange)
            .semantics {
                contentDescription = "减少动态效果"
                stateDescription = if (system) "已跟随系统开启，当前不可修改" else if (manual) "已开启" else "已关闭"
                onClick(label = if (system) "关闭系统设置后，可在应用内单独控制" else "减少页面移动、缩放和展开动画", action = null)
            }
            .padding(18.dp)) {
            val detail = if (system) "系统已开启，当前自动遵循。关闭系统设置后，可在此单独控制。"
                else "减少页面移动、缩放和展开动画。"
            if (maxWidth / LocalDensity.current.fontScale < 180.dp) {
                Column(Modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ControlHeading("减少动态效果") {
                        MotionSwitch(manual || system, !system)
                    }
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("减少动态效果", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MotionSwitch(manual || system, !system)
            }
        }
    }
}

@Composable
private fun MotionSwitch(checked: Boolean, enabled: Boolean) {
    Switch(checked = checked, enabled = enabled, onCheckedChange = null, modifier = Modifier.clearAndSetSemantics {})
}

@Composable
private fun ThemeOption(mode: ThemeMode, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val fill by animateColorAsState(if (selected) colors.primaryContainer else colors.surface, tween(160))
    val border by animateColorAsState(if (selected) colors.primary else colors.outlineVariant, tween(160))
    val interaction = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    BoxWithConstraints(Modifier.fillMaxWidth().pressFeedback(interaction, 18.dp).clip(RoundedCornerShape(18.dp))
        .background(fill).border(1.dp, border, RoundedCornerShape(18.dp))
        .selectable(selected, interactionSource = interaction, indication = null, role = Role.RadioButton) {
            if (!selected) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onClick() }
        }.semantics {
            contentDescription = mode.label
            stateDescription = if (selected) "已选择" else "未选择"
            onClick(label = when (mode) {
                ThemeMode.SYSTEM -> "随系统自动切换日间与夜间"
                ThemeMode.LIGHT -> "使用明亮清晰的白色界面"
                ThemeMode.DARK -> "使用柔和低光的黑色界面"
            }, action = null)
        }.padding(18.dp)) {
        val detail = when (mode) {
            ThemeMode.SYSTEM -> "随系统自动切换日间与夜间"
            ThemeMode.LIGHT -> "明亮清晰的白色界面"
            ThemeMode.DARK -> "柔和低光的黑色界面"
        }
        if (maxWidth / LocalDensity.current.fontScale < 150.dp) {
            Column(Modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ControlHeading(mode.label, reserveLabel = "跟随系统") {
                    RadioButton(selected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        } else Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            ThemePreview(mode)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(mode.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            RadioButton(selected, onClick = null, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
private fun ThemePreview(mode: ThemeMode) {
    Canvas(Modifier.size(40.dp, 52.dp).clip(RoundedCornerShape(9.dp))
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(9.dp))) {
        fun half(dark: Boolean) {
            drawRect(if (dark) Color(0xFF080808) else Color.White)
            drawRoundRect(if (dark) Color(0xFF292929) else Color(0xFFE8EAEC),
                Offset(size.width * .16f, size.height * .40f), Size(size.width * .68f, size.height * .43f), CornerRadius(4.dp.toPx()))
            drawRoundRect(if (dark) Color(0xFF76D9C7) else Color(0xFF006F68),
                Offset(size.width * .16f, size.height * .18f), Size(size.width * .42f, size.height * .08f), CornerRadius(2.dp.toPx()))
        }
        half(mode == ThemeMode.DARK)
        if (mode == ThemeMode.SYSTEM) clipRect(left = size.width / 2) { half(true) }
    }
}
