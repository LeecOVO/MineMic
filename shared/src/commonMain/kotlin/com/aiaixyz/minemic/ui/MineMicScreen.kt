package com.aiaixyz.minemic.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiaixyz.minemic.AppVersion
import com.aiaixyz.minemic.connection.ConnectionPhase
import com.aiaixyz.minemic.connection.MicState
import com.aiaixyz.minemic.connection.LockScreenState
import com.aiaixyz.minemic.connection.Wire
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** State-only UI; platform adapters and connection ownership stay with MicController. */
@Composable
fun MineMicScreen(
    state: MicState,
    controlsEnabled: Boolean = true,
    connectEnabled: Boolean = controlsEnabled,
    onConnect: (code: String, host: String) -> Unit = { _, _ -> },
    onMutedChange: (Boolean) -> Unit = {},
    onNoiseSuppressionChange: (Boolean) -> Unit = {},
    onEchoCancellationChange: (Boolean) -> Unit = {},
    onRetryMicrophone: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onShowLicenses: () -> Unit = {},
    onInputGainChange: (Float) -> Unit = {},
    onDeskNoiseSuppressionChange: (Boolean) -> Unit = {},
    lockScreenState: LockScreenState? = null,
    onLockScreenControlsChange: (Boolean) -> Unit = {},
) {
    var about by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val dismissInput = {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        Unit
    }
    val uriHandler = LocalUriHandler.current
    Surface(Modifier.fillMaxSize().pointerInput(focusManager, keyboard) {
        detectTapGestures(onTap = { dismissInput() })
    }, color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxSize(),
            ) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 28.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        WaveMark(Modifier.size(30.dp), MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("MineMic", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.6).sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { dismissInput(); about = true }, modifier = Modifier.semantics { contentDescription = "关于 MineMic" }) {
                            ToolIcon(Glyph.Info, MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (state.ownsPc) {
                        SessionPanel(state, controlsEnabled, onMutedChange, onRetryMicrophone, onDisconnect)
                    } else {
                        PairingPanel(state, connectEnabled, onConnect, onDisconnect,
                            onInputFocusChange = { inputFocused = it }, dismissInput = dismissInput)
                    }
                    Spacer(Modifier.height(30.dp))
                    EnhancementPanel(state, controlsEnabled,
                        onNoise = { dismissInput(); onNoiseSuppressionChange(it) },
                        onEcho = { dismissInput(); onEchoCancellationChange(it) },
                        onGain = { dismissInput(); onInputGainChange(it) },
                        onDeskNoise = { dismissInput(); onDeskNoiseSuppressionChange(it) },
                        onInteraction = dismissInput)
                    lockScreenState?.let { lockState ->
                        Spacer(Modifier.height(12.dp))
                        LockScreenPanel(lockState, onInteraction = dismissInput,
                            onChange = { dismissInput(); onLockScreenControlsChange(it) })
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${AppVersion.name}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { dismissInput(); help = true }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                            Text("连接帮助", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                // Number pads on iOS have no IME action key. Keep a completion action
                // outside the scrolling form so it stays immediately above the keyboard.
                if (inputFocused && !state.ownsPc) Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = dismissInput) { Text("完成", fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
        if (about) AlertDialog(
            onDismissRequest = { about = false },
            icon = { WaveMark(Modifier.size(32.dp), MaterialTheme.colorScheme.primary) },
            title = { Text("MineMic") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("把手机变成电脑麦克风。")
                    Text("${AppVersion.name}\n${AppVersion.stage}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { uriHandler.openUri("https://github.com/LeecOVO/MineMic") },
                        contentPadding = PaddingValues(0.dp)) { Text("项目主页") }
                    TextButton(onClick = { about = false; onShowLicenses() }, contentPadding = PaddingValues(0.dp)) {
                        Text("WebRTC 与第三方许可")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { about = false }) { Text("完成") } },
        )
        if (help) AlertDialog(
            onDismissRequest = { help = false }, title = { Text("连接帮助") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    HelpItem("连接前", "手机和电脑需在同一局域网。打开 MineMic PC 并开启接收，再输入电脑上的配对码。找不到电脑时，可以切换到 IP 地址。")
                    HelpItem("连接后", "一台电脑同时由一台手机独占。网络中断时会保留占用，并尝试连接原电脑。")
                    HelpItem("结束使用", "点击「断开连接」释放占用，电脑会更新配对码。若网络中断，请重试断开，或在电脑端手动断开。")
                    HelpItem("回声抑制", "需要支持参考音频的新版 Windows PC，并在电脑端选择实际使用的扬声器。参考暂不可用时，麦克风仍会继续传输。")
                }
            },
            confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } },
        )
    }
}

@Composable
private fun PairingPanel(
    state: MicState, enabled: Boolean, onConnect: (String, String) -> Unit, onCancel: () -> Unit,
    onInputFocusChange: (Boolean) -> Unit, dismissInput: () -> Unit,
) {
    var manual by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    val pending = state.phase == ConnectionPhase.SEARCHING || state.phase == ConnectionPhase.CONNECTING ||
        state.phase == ConnectionPhase.DISCONNECTING
    val valid = if (manual) Wire.validIpv4(host) else Wire.validCode(code)
    val connect = {
        dismissInput()
        if (enabled && !pending && valid) {
            onConnect(if (manual) "" else code, if (manual) host else "")
        }
    }
    Text("连接电脑", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.8).sp)
    Text("你的声音，从这里传到电脑。", Modifier.padding(top = 8.dp, bottom = 26.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.background).padding(4.dp)) {
                ConnectionTab("配对码", !manual, !pending, Modifier.weight(1f)) { dismissInput(); manual = false }
                ConnectionTab("IP 地址", manual, !pending, Modifier.weight(1f)) { dismissInput(); manual = true }
            }
            Spacer(Modifier.height(22.dp))
            Text(if (manual) "电脑的 IPv4 地址" else "电脑上的 6 位配对码",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = if (manual) host else code,
                onValueChange = { value ->
                    if (manual) host = value.filter { it in '0'..'9' || it == '.' }.take(15)
                    else code = value.filter { it in '0'..'9' }.take(6)
                },
                modifier = Modifier.fillMaxWidth().onFocusChanged { onInputFocusChange(it.isFocused) }.semantics {
                    contentDescription = if (manual) "电脑的 IPv4 地址" else "6 位配对码"
                },
                placeholder = {
                    Text(if (manual) "192.168.1.100" else "000000", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace,
                            letterSpacing = if (manual) 0.sp else 5.sp))
                },
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium, letterSpacing = if (manual) 0.sp else 5.sp),
                singleLine = true, enabled = !pending,
                shape = MaterialTheme.shapes.small,
                keyboardOptions = KeyboardOptions(keyboardType = if (manual) KeyboardType.Decimal else KeyboardType.Number,
                    imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { connect() }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
            Text(if (manual) "在 MineMic PC 中查看，无需配对码。" else "打开 MineMic PC，开启接收后查看。",
                Modifier.padding(top = 10.dp, bottom = 22.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = connect, enabled = enabled && !pending && valid,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = MaterialTheme.shapes.small) {
                if (pending) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                }
                Text(when (state.phase) {
                    ConnectionPhase.SEARCHING -> "正在查找电脑"
                    ConnectionPhase.CONNECTING -> "正在连接"
                    ConnectionPhase.DISCONNECTING -> "正在断开"
                    else -> "连接电脑"
                }, fontWeight = FontWeight.SemiBold)
                if (!pending) {
                    Spacer(Modifier.width(12.dp))
                    ToolIcon(Glyph.Arrow, Modifier.size(18.dp), LocalContentColor.current)
                }
            }
            if (state.phase == ConnectionPhase.SEARCHING || state.phase == ConnectionPhase.CONNECTING) {
                TextButton(onClick = { dismissInput(); onCancel() }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) { Text("取消连接") }
            }
        }
    }
    val showStatus = pending || state.phase == ConnectionPhase.ERROR || state.message != MicState().message
    if (showStatus) {
        Spacer(Modifier.height(12.dp))
        StatusNote(state.message, error = state.phase == ConnectionPhase.ERROR, progress = pending)
    } else {
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolIcon(Glyph.Network, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(16.dp))
            Text("手机和电脑需连接同一局域网", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ConnectionTab(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.selectable(selected, enabled = enabled, role = Role.Tab, onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp,
    ) {
        Box(Modifier.heightIn(min = 44.dp).padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SessionPanel(
    state: MicState, enabled: Boolean, onMutedChange: (Boolean) -> Unit,
    onRetryMicrophone: () -> Unit, onDisconnect: () -> Unit,
) {
    val disconnecting = state.phase == ConnectionPhase.DISCONNECTING
    val reconnecting = state.phase == ConnectionPhase.RECONNECTING
    val active = state.recording && state.phase == ConnectionPhase.CONNECTED
    val status = when {
        disconnecting -> "正在断开"
        reconnecting -> "正在重连"
        !state.recording -> "麦克风未就绪"
        state.muted -> "麦克风已静音"
        else -> "正在传输声音"
    }
    val statusColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(7.dp).background(statusColor, CircleShape))
        Text(status, style = MaterialTheme.typography.labelLarge, color = statusColor,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    Text(state.pcName.ifBlank { "已连接的电脑" }, Modifier.padding(top = 10.dp, bottom = 24.dp),
        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.8).sp)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (active) {
                Surface(
                    onClick = { onMutedChange(!state.muted) },
                    enabled = enabled && !disconnecting,
                    modifier = Modifier.sizeIn(minWidth = 120.dp, minHeight = 120.dp).semantics {
                        role = Role.Switch
                        stateDescription = if (state.muted) "已静音" else "未静音"
                        contentDescription = if (state.muted) "取消麦克风静音" else "麦克风静音"
                        toggleableState = if (state.muted) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
                    },
                    shape = CircleShape,
                    color = if (state.muted) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer,
                    contentColor = if (state.muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(Modifier.padding(32.dp), contentAlignment = Alignment.Center) {
                        ToolIcon(if (state.muted) Glyph.MicOff else Glyph.Mic, LocalContentColor.current, Modifier.size(48.dp))
                    }
                }
                Text(if (state.muted) "点击取消静音" else "点击静音", Modifier.padding(top = 14.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(28.dp))
                AudioMeter(if (!state.muted) state.level else 0f, !state.muted)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("输入电平", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (state.muted) "静音" else "实时",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                StatusNote(state.message, progress = reconnecting || disconnecting)
                if (state.phase == ConnectionPhase.CONNECTED) {
                    FilledTonalButton(onClick = onRetryMicrophone, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.small) { Text("重新启用麦克风") }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).toggleable(
                        value = state.muted, enabled = enabled && !disconnecting,
                        role = Role.Switch, onValueChange = onMutedChange,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("恢复时静音", Modifier.weight(1f).padding(end = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Switch(checked = state.muted, onCheckedChange = null, enabled = enabled && !disconnecting,
                        modifier = Modifier.clearAndSetSemantics {})
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(Glyph.Lock, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(14.dp))
            Text("由本机独占", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onDisconnect, enabled = enabled && !disconnecting,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text(if (disconnecting) "正在断开…" else "断开连接", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun AudioMeter(level: Float, active: Boolean) {
    val value = level.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val filled = (sqrt(value) * 36).roundToInt()
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(32.dp).semantics {
        contentDescription = "麦克风输入电平"
        progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
    }) {
        val gap = 3.dp.toPx()
        val width = (size.width - gap * 35) / 36
        repeat(36) { index ->
            val color = if (active && index < filled) colors.primary else colors.outlineVariant
            drawRoundRect(color, Offset(index * (width + gap), 0f), Size(width, size.height), CornerRadius(2.dp.toPx()))
        }
    }
}

@Composable
private fun EnhancementPanel(
    state: MicState, enabled: Boolean, onNoise: (Boolean) -> Unit, onEcho: (Boolean) -> Unit,
    onGain: (Float) -> Unit, onDeskNoise: (Boolean) -> Unit,
    onInteraction: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val gain = state.inputGainDb.takeIf { it.isFinite() }?.coerceIn(0f, 18f)?.roundToInt() ?: 0
    val selection = buildList {
        if (gain > 0) add("收音 +$gain dB")
        if (state.noiseSuppression) add(if (state.deskNoiseSuppression) "桌面游戏降噪" else "通用降噪")
        if (state.echoCancellation) add("回声抑制")
    }.joinToString(" · ").ifEmpty { "均已关闭" }
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
        .clickable(role = Role.Button, onClickLabel = if (expanded) "收起声音处理" else "展开声音处理") {
            onInteraction(); expanded = !expanded
        }
        .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
        .heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("声音处理", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(selection, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
    }
    if (expanded) {
        Column {
            GainControl(gain, enabled, onGain)
            HorizontalDivider(Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            EnhancementRow("降噪", "减轻风扇、空调等背景声", Glyph.Noise,
                state.noiseSuppression, enabled, onNoise)
            if (state.noiseSuppression) {
                Column(Modifier.padding(start = 44.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("降噪场景", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().selectableGroup().clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp)) {
                        NoiseSceneChoice("通用", !state.deskNoiseSuppression, enabled, Modifier.weight(1f)) { onDeskNoise(false) }
                        NoiseSceneChoice("桌面游戏", state.deskNoiseSuppression, enabled, Modifier.weight(1f)) { onDeskNoise(true) }
                    }
                    Text(if (state.deskNoiseSuppression) "针对键盘、鼠标和桌面摩擦声，可能影响轻声，并增加约 20 ms 算法延迟。关闭降噪可停止此项处理。" else "适合日常通话与持续的背景噪声。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outlineVariant)
            EnhancementRow("回声抑制", "减少电脑外放带来的回声", Glyph.Echo,
                state.echoCancellation, enabled, onEcho)
        }
        val requested = state.noiseSuppression || state.echoCancellation || gain > 0
        val processingActive = state.phase == ConnectionPhase.CONNECTED && state.recording
        val waitingMessage = when {
            !state.ownsPc -> "待连接，所选设置将在连接后应用"
            state.phase == ConnectionPhase.RECONNECTING -> "待连接恢复，所选设置将在恢复后应用"
            state.phase == ConnectionPhase.DISCONNECTING -> "音频已停止，正在断开连接"
            else -> "待麦克风恢复，所选设置将在恢复后应用"
        }
        Surface(shape = MaterialTheme.shapes.small,
            color = if (requested) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolIcon(Glyph.Info, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (!processingActive) Text(waitingMessage,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(if (processingActive) "当前处理状态" else "上次处理状态", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(state.enhancementMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (processingActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
    }
}

@Composable
private fun GainControl(gain: Int, enabled: Boolean, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("收音增强", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(if (gain == 0) "关闭 · 0 dB" else "+$gain dB", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = { onChange(0f) }, enabled = enabled && gain != 0,
                modifier = Modifier.semantics { contentDescription = "恢复 0 dB，关闭收音增强" }) { Text("恢复 0 dB") }
        }
        Slider(value = gain.toFloat(), onValueChange = { onChange(it.roundToInt().coerceIn(0, 18).toFloat()) },
            valueRange = 0f..18f, steps = 17, enabled = enabled,
            modifier = Modifier.fillMaxWidth().semantics {
                contentDescription = "收音增强"
                stateDescription = if (gain == 0) "关闭，0 分贝" else "增加 $gain 分贝"
            })
        Text("手机放桌面时可提高音量，也会放大残余噪声。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NoiseSceneChoice(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shape = MaterialTheme.shapes.extraSmall) {
        Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LockScreenPanel(state: LockScreenState, onInteraction: () -> Unit, onChange: (Boolean) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
        .clickable(role = Role.Button, onClickLabel = if (expanded) "收起锁屏控制" else "展开锁屏控制") {
            onInteraction(); expanded = !expanded
        }
        .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
        .heightIn(min = 64.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("锁屏控制", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(when { !state.available -> "当前不可用"; state.enabled -> "已开启"; else -> "已关闭" },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
    }
    if (expanded) {
        Row(Modifier.fillMaxWidth().toggleable(state.enabled, enabled = state.available || state.enabled,
            role = Role.Switch, onValueChange = onChange).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("在锁屏上显示", Modifier.weight(1f).padding(end = 12.dp),
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Switch(checked = state.enabled, onCheckedChange = null, enabled = state.available || state.enabled,
                modifier = Modifier.clearAndSetSemantics {})
        }
        Text("仅展示连接状态与快捷操作，关闭不影响录音。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(state.message, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun EnhancementRow(label: String, detail: String, icon: Glyph, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
        .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolIcon(icon, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(24.dp))
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                disabledUncheckedThumbColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                disabledUncheckedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            ),
            modifier = Modifier.clearAndSetSemantics {})
    }
}

@Composable
private fun StatusNote(message: String, error: Boolean = false, progress: Boolean = false) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
        if (progress) CircularProgressIndicator(Modifier.padding(top = 2.dp).size(15.dp), strokeWidth = 1.5.dp, color = color)
        else ToolIcon(Glyph.Info, color, Modifier.padding(top = 1.dp).size(16.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun HelpItem(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun WaveMark(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val heights = listOf(.32f, .68f, 1f, .54f, .26f)
        val stroke = size.width / 8.5f
        heights.forEachIndexed { index, h ->
            val x = size.width * (index + 1) / 6
            drawLine(color, Offset(x, size.height * (1 - h) / 2 + stroke / 2),
                Offset(x, size.height * (1 + h) / 2 - stroke / 2), stroke, StrokeCap.Round)
        }
    }
}

private enum class Glyph { Mic, MicOff, Info, Arrow, Network, Lock, Noise, Echo }

@Composable
private fun ToolIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier.size(24.dp)) = ToolIcon(glyph, modifier, color)

@Composable
private fun ToolIcon(glyph: Glyph, modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val u = size.minDimension / 24f
        val stroke = Stroke(1.65f * u, cap = StrokeCap.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), stroke.width, StrokeCap.Round)
        when (glyph) {
            Glyph.Mic, Glyph.MicOff -> {
                drawRoundRect(color, Offset(9 * u, 2 * u), Size(6 * u, 13 * u), CornerRadius(3 * u), style = stroke)
                drawArc(color, 0f, 180f, false, Offset(5.5f * u, 6 * u), Size(13 * u, 13 * u), style = stroke)
                line(12f, 19f, 12f, 22f); line(8.5f, 22f, 15.5f, 22f)
                if (glyph == Glyph.MicOff) line(3f, 3f, 21f, 21f)
            }
            Glyph.Info -> {
                drawCircle(color, 9 * u, Offset(12 * u, 12 * u), style = stroke)
                drawCircle(color, 1 * u, Offset(12 * u, 7.5f * u))
                line(12f, 11f, 12f, 16.5f)
            }
            Glyph.Arrow -> { line(4f, 12f, 20f, 12f); line(14f, 6f, 20f, 12f); line(14f, 18f, 20f, 12f) }
            Glyph.Network -> {
                drawRoundRect(color, Offset(3 * u, 3 * u), Size(18 * u, 13 * u), CornerRadius(2 * u), style = stroke)
                line(12f, 16f, 12f, 21f); line(7f, 21f, 17f, 21f)
            }
            Glyph.Lock -> {
                drawRoundRect(color, Offset(5 * u, 10 * u), Size(14 * u, 11 * u), CornerRadius(2 * u), style = stroke)
                drawArc(color, 180f, 180f, false, Offset(8 * u, 2 * u), Size(8 * u, 12 * u), style = stroke)
                line(12f, 14f, 12f, 17f)
            }
            Glyph.Noise -> {
                line(3f, 10f, 3f, 14f); line(7.5f, 6f, 7.5f, 18f); line(12f, 3f, 12f, 21f)
                line(16.5f, 7f, 16.5f, 17f); line(21f, 10f, 21f, 14f)
            }
            Glyph.Echo -> {
                drawRoundRect(color, Offset(3 * u, 8 * u), Size(5 * u, 8 * u), CornerRadius(1 * u), style = stroke)
                line(8f, 8f, 12f, 4f); line(12f, 4f, 12f, 20f); line(12f, 20f, 8f, 16f)
                drawArc(color, -60f, 120f, false, Offset(11 * u, 7 * u), Size(8 * u, 10 * u), style = stroke)
                drawArc(color, -55f, 110f, false, Offset(11 * u, 3 * u), Size(14 * u, 18 * u), style = stroke)
            }
        }
    }
}
