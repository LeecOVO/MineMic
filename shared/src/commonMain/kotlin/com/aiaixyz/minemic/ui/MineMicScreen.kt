package com.aiaixyz.minemic.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiaixyz.minemic.AppVersion
import com.aiaixyz.minemic.connection.ConnectionPhase
import com.aiaixyz.minemic.connection.MicState
import com.aiaixyz.minemic.connection.LockScreenState
import com.aiaixyz.minemic.connection.Wire
import com.aiaixyz.minemic.connection.IpVersion
import kotlinx.coroutines.launch
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
    audioLevel: (() -> Float)? = null,
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    nativeMaterialVisible: Boolean = true,
    licenseText: String? = null,
    licenseError: Boolean = false,
    onConnectWithIpVersion: ((String, String, IpVersion) -> Unit)? = null,
    reducedMotion: Boolean = LocalReducedMotion.current,
    manualReducedMotion: Boolean = false,
    systemReducedMotion: Boolean = false,
    onReducedMotionChange: (Boolean) -> Unit = {},
) {
    CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
        var settingsOpen by rememberSaveable { mutableStateOf(false) }
        SettingsBackHandler(settingsOpen) { settingsOpen = false }
        var about by remember { mutableStateOf(false) }
        var privacyDocument by remember { mutableStateOf<PrivacyDocument?>(null) }
        var privacyOpen by remember { mutableStateOf(false) }
        var licensesOpen by remember { mutableStateOf(false) }
        val closePrivacy = { privacyOpen = false }
        val settingsMotion = rememberPageMotion(settingsOpen)
        val aboutMotion = rememberPageMotion(about)
        val privacyMotion = rememberPageMotion(privacyOpen)
        val licensesMotion = rememberPageMotion(licensesOpen)
        val pageCoversHome = settingsMotion.coversContent || aboutMotion.coversContent || privacyMotion.coversContent || licensesMotion.coversContent
        SettingsBackHandler(about && !privacyOpen && !licensesOpen) { about = false }
        SettingsBackHandler(privacyOpen, closePrivacy)
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
            Box(Modifier.safeDrawingPadding().imePadding()
                .then(if (pageCoversHome) Modifier.clearAndSetSemantics {} else Modifier), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxSize(),
                ) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                        HomeHeader(onSettings = { dismissInput(); settingsOpen = true },
                            onAbout = { dismissInput(); about = true })
                        if (state.ownsPc) {
                            SessionPanel(state, controlsEnabled, onMutedChange, onRetryMicrophone, onDisconnect, audioLevel)
                        } else {
                            PairingPanel(state, connectEnabled,
                                onConnect = { code, host, version ->
                                    if (onConnectWithIpVersion != null) onConnectWithIpVersion(code, host, version)
                                    else onConnect(code, host)
                                }, onCancel = onDisconnect,
                                reducedMotion = reducedMotion,
                                nativeMaterialVisible = nativeMaterialVisible && !pageCoversHome && !help,
                                onInputFocusChange = { inputFocused = it }, dismissInput = dismissInput)
                        }
                        Spacer(Modifier.height(20.dp))
                        EnhancementPanel(state, controlsEnabled,
                            reducedMotion = reducedMotion,
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
                        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp)) {
                            val version: @Composable () -> Unit = {
                                Text(AppVersion.name, modifier = Modifier.clearAndSetSemantics { text = AnnotatedString("版本，${AppVersion.name}") }, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val helpButton: @Composable () -> Unit = {
                                TextButton(onClick = { dismissInput(); help = true }, modifier = Modifier.actionHint("查看连接前、连接后和结束使用的说明"), contentPadding = PaddingValues(horizontal = 10.dp)) {
                                    Text("连接帮助", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            if (maxWidth / LocalDensity.current.fontScale < 180.dp) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    version()
                                    Box(Modifier.align(Alignment.End)) { helpButton() }
                                }
                            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { version() }
                                helpButton()
                            }
                        }
                    }
                    // Number pads on iOS have no IME action key. Keep a completion action
                    // outside the scrolling form so it stays immediately above the keyboard.
                    if (inputFocused && !state.ownsPc) Surface(color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = dismissInput, modifier = Modifier.semantics { contentDescription = "完成输入" }.actionHint("收起键盘")) { Text("完成", Modifier.clearAndSetSemantics {}, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
                if (pageCoversHome) Box(Modifier.matchParentSize().blockPageInput())
            }
            PageMotion(aboutMotion, covered = privacyMotion.coversContent || licensesMotion.coversContent) {
                AboutScreen(
                    active = about && !privacyMotion.coversContent && !licensesMotion.coversContent,
                    onBack = { about = false },
                    onProject = { uriHandler.openUri("https://github.com/LeecOVO/MineMic") },
                    onPrivacyDocument = { document -> privacyDocument = document; privacyOpen = true },
                    onLicenses = { licensesOpen = true; onShowLicenses() },
                    mark = { WaveMark(Modifier.size(32.dp), MaterialTheme.colorScheme.primary) },
                )
            }
            if (help) AlertDialog(
                onDismissRequest = { help = false }, title = { Text("连接帮助", Modifier.semantics { heading() }) },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        HelpItem("连接前", "手机和电脑需在同一局域网。打开 MineMic PC 并开启接收，再输入电脑上的配对码。找不到电脑时，可以切换到 IP 地址。")
                        HelpItem("连接后", "一台电脑同时由一台手机独占。网络中断时会保留占用，并尝试连接原电脑。")
                        HelpItem("结束使用", "点击「断开连接」释放占用。配对码仅在电脑停止接收或手动更新时改变。若网络中断，请重试断开，或在电脑端手动断开。")
                        HelpItem("回声抑制", "新版 Windows PC 默认跟随系统外放设备；若游戏单独指定了输出设备，可在 PC 的“外放参考”中选择。参考暂不可用时，麦克风仍会继续传输。")
                    }
                },
                confirmButton = { TextButton(onClick = { help = false }, modifier = Modifier.actionHint("关闭连接帮助")) { Text("知道了") } },
            )
            PageMotion(settingsMotion) {
                SettingsScreen(themeMode, onThemeModeChange,
                    manualReducedMotion = manualReducedMotion, systemReducedMotion = systemReducedMotion,
                    onReducedMotionChange = onReducedMotionChange) { settingsOpen = false }
            }
            PageMotion(privacyMotion) {
                privacyDocument?.let { document ->
                    PrivacyScreen(document, onBack = closePrivacy,
                        onContact = { uriHandler.openUri(PRIVACY_CONTACT_URL) })
                }
            }
            PageMotion(licensesMotion) {
                LicensesScreen(licenseText, licenseError, active = licensesOpen,
                    onBack = { licensesOpen = false }, onRetry = onShowLicenses)
            }
        }
    }
}

@Composable
private fun HomeHeader(onSettings: () -> Unit, onAbout: () -> Unit) {
    val style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
    val brandWidth = textWidth("MineMic", style)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp)) {
        val wrapBrand = brandWidth > maxWidth
        if (brandWidth + 136.dp <= maxWidth) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WaveMark(Modifier.size(30.dp), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text("MineMic", style = style, modifier = Modifier.weight(1f))
                HomeHeaderActions(onSettings, onAbout)
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (wrapBrand) "Mine\nMic" else "MineMic", style = style)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WaveMark(Modifier.size(30.dp), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                HomeHeaderActions(onSettings, onAbout)
            }
        }
    }
}

@Composable
private fun HomeHeaderActions(onSettings: () -> Unit, onAbout: () -> Unit) {
    Row {
        IconButton(onClick = onSettings, modifier = Modifier.semantics { contentDescription = "设置" }.actionHint("打开设置")) {
            ToolIcon(Glyph.Settings, MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onAbout, modifier = Modifier.semantics { contentDescription = "关于 MineMic" }.actionHint("打开关于 MineMic")) {
            ToolIcon(Glyph.Info, MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PairingPanel(
    state: MicState, enabled: Boolean, onConnect: (String, String, IpVersion) -> Unit, onCancel: () -> Unit,
    nativeMaterialVisible: Boolean, reducedMotion: Boolean,
    onInputFocusChange: (Boolean) -> Unit, dismissInput: () -> Unit,
) {
    val pager = rememberPagerState(pageCount = { 2 })
    val scope = rememberCoroutineScope()
    var selectorDragging by remember { mutableStateOf(false) }
    var ipVersionDragging by remember { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var ipv4Host by rememberSaveable { mutableStateOf("") }
    var ipv6Host by rememberSaveable { mutableStateOf("") }
    var ipv6 by rememberSaveable { mutableStateOf(false) }
    val pending = state.phase == ConnectionPhase.SEARCHING || state.phase == ConnectionPhase.CONNECTING ||
        state.phase == ConnectionPhase.DISCONNECTING
    val manual = pager.settledPage == 1
    val pageMoving = selectorDragging || pager.isScrollInProgress || pager.currentPageOffsetFraction != 0f
    val moving = pageMoving || ipVersionDragging
    val host = if (ipv6) ipv6Host else ipv4Host
    val version = if (manual && ipv6) IpVersion.IPV6 else IpVersion.IPV4
    val valid = if (manual) { if (ipv6) Wire.validIpv6(host) else Wire.validIpv4(host) } else Wire.validCode(code)
    val connect = {
        dismissInput()
        if (enabled && !pending && !moving && valid) {
            onConnect(if (manual) "" else code, if (manual) host else "", version)
        }
    }
    LaunchedEffect(pager.targetPage) { dismissInput() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp)
        Text(if (textWidth("连接电脑", style) > maxWidth) "连接\n电脑" else "连接电脑", style = style,
            modifier = Modifier.clearAndSetSemantics { text = AnnotatedString("连接电脑"); heading() })
    }
    Text("你的声音，从这里传到电脑。", Modifier.padding(top = 8.dp, bottom = 24.dp),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            SlidingChoice(listOf("配对码", "IP 地址"), pager.currentPage + pager.currentPageOffsetFraction,
                hints = listOf("使用电脑显示的 6 位数字配对码连接", "输入电脑 IP 直接连接，无需配对码"),
                enabled = !pending,
                style = SlidingChoiceStyle.Pairing,
                nativeMaterialVisible = nativeMaterialVisible,
                onInteraction = dismissInput,
                onSelect = { index -> scope.launch {
                    if (reducedMotion) pager.scrollToPage(index)
                    else pager.animateScrollToPage(index, animationSpec = tween(260, easing = FastOutSlowInEasing))
                } },
                onDragPosition = { position ->
                    val page = position.roundToInt()
                    pager.requestScrollToPage(page, position - page)
                }, selectedIndex = pager.settledPage, onDraggingChange = { selectorDragging = it })
            Spacer(Modifier.height(24.dp))
            HorizontalPager(state = pager, userScrollEnabled = !pending && !ipVersionDragging, pageSpacing = 20.dp,
                modifier = Modifier.fillMaxWidth().animateContentSize(tween(if (reducedMotion) 0 else 200,
                    easing = FastOutSlowInEasing)), verticalAlignment = Alignment.Top,
                key = { it }) { page ->
                val isIp = page == 1
                Column(Modifier.fillMaxWidth().then(if (page != pager.settledPage) Modifier.clearAndSetSemantics {} else Modifier)) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val title = if (!isIp) "电脑上的 6 位配对码" else if (ipv6) "IPv6 地址" else "IPv4 地址"
                        val labelStyle = MaterialTheme.typography.labelLarge
                        val switchWidth = maxOf(112.dp, textWidth("IPv6", labelStyle.copy(fontWeight = FontWeight.SemiBold)) * 2 + 28.dp)
                            .coerceAtMost(maxWidth)
                        val stacked = isIp && textWidth("IPv6 地址", labelStyle) + switchWidth + 10.dp > maxWidth
                        val titleContent: @Composable () -> Unit = {
                            AnimatedContent(title, transitionSpec = {
                                (fadeIn(tween(if (reducedMotion) 0 else 150)) togetherWith fadeOut(tween(if (reducedMotion) 0 else 90)))
                                    .using(SizeTransform { _, _ -> tween(if (reducedMotion) 0 else 180) })
                            }, label = "IP address family title") { text ->
                                Text(text, style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        val versionContent: @Composable () -> Unit = {
                            if (isIp) Box(Modifier.width(switchWidth).semantics {
                                isTraversalGroup = true
                            }) {
                                SlidingChoice(listOf("IPv4", "IPv6"), if (ipv6) 1f else 0f,
                                    hints = listOf("使用 IPv4 地址连接电脑", "使用 IPv6 地址连接电脑"),
                                    enabled = !pending && !pageMoving,
                                    onSelect = { index -> dismissInput(); ipv6 = index == 1 },
                                    onInteraction = dismissInput,
                                    style = SlidingChoiceStyle.Pairing,
                                    nativeMaterialVisible = nativeMaterialVisible && page == pager.settledPage && !pageMoving,
                                    onDraggingChange = { ipVersionDragging = it },
                                    compact = true, reducedMotion = reducedMotion)
                            }
                        }
                        if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            titleContent()
                            Box(Modifier.align(Alignment.End)) { versionContent() }
                        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) { titleContent() }
                            versionContent()
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = if (isIp) host else code,
                        onValueChange = { value ->
                            if (isIp && ipv6) ipv6Host = value.filterNot { it.isWhitespace() }.take(128)
                            else if (isIp) ipv4Host = value.replace(',', '.').replace('，', '.').replace('٫', '.')
                                .filter { it in '0'..'9' || it == '.' }.take(15)
                            else code = value.filter { it in '0'..'9' }.take(6)
                        },
                        modifier = Modifier.fillMaxWidth().onFocusChanged { onInputFocusChange(it.isFocused) }.semantics {
                            contentDescription = if (!isIp) "6 位配对码" else if (ipv6) "电脑的 IPv6 地址" else "电脑的 IPv4 地址"
                            editableText = spelledInput(if (isIp) host else code)
                            onClick(label = if (!isIp) "输入电脑显示的 6 位数字" else if (ipv6) "输入包含冒号的 IPv6 地址" else "输入点分十进制地址", action = null)
                        },
                        placeholder = {
                            Text(if (!isIp) "000000" else if (ipv6) "fd12:3456::10" else "192.168.1.100", color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.clearAndSetSemantics {}, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                                style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace,
                                    letterSpacing = if (isIp) 0.sp else 5.sp))
                        },
                        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium, letterSpacing = if (isIp) 0.sp else 5.sp),
                        singleLine = true, enabled = !pending,
                        shape = MaterialTheme.shapes.small,
                        keyboardOptions = KeyboardOptions(keyboardType = if (!isIp) KeyboardType.Number else if (ipv6) KeyboardType.Ascii else KeyboardType.Decimal,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { connect() }),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = controlBoundaryColor,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        ),
                    )
                    AnimatedContent(if (isIp) "输入 IP 直接连接，无需配对码" else "在 MineMic PC 查看 6 位数字配对码",
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 24.dp), transitionSpec = {
                            (fadeIn(tween(if (reducedMotion) 0 else 150)) togetherWith fadeOut(tween(if (reducedMotion) 0 else 90)))
                                .using(SizeTransform { _, _ -> tween(if (reducedMotion) 0 else 180) })
                        }, label = "Pairing input guidance") { hint ->
                        Text(hint, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                            textAlign = if (isIp) TextAlign.End else TextAlign.Start,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val source = remember { MutableInteractionSource() }
            Button(onClick = connect, enabled = enabled && !pending && !moving && valid,
                interactionSource = source,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).pressFeedback(source)
                    .actionHint("连接电脑并开始传输手机麦克风声音")
                    .semantics {
                        stateDescription = when {
                            pending -> "正在处理连接"
                            !enabled -> "当前不可连接"
                            !valid -> if (manual) "请输入有效的电脑 IP 地址" else "请输入完整的 6 位配对码"
                            else -> "可连接"
                        }
                    }, shape = MaterialTheme.shapes.small) {
                if (pending) {
                    CircularProgressIndicator(Modifier.size(18.dp).clearAndSetSemantics {}, strokeWidth = 2.dp,
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
            Reveal(state.phase == ConnectionPhase.SEARCHING || state.phase == ConnectionPhase.CONNECTING) {
                TextButton(onClick = { dismissInput(); onCancel() }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).actionHint("停止本次查找或连接尝试")) { Text("取消连接") }
            }
        }
    }
    val showStatus = pending || state.phase == ConnectionPhase.ERROR || state.message != MicState().message
    if (showStatus) {
        Spacer(Modifier.height(14.dp))
        StatusNote(state.message, error = state.phase == ConnectionPhase.ERROR, progress = pending)
    } else Reveal(!manual, reducedMotion = reducedMotion) {
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ToolIcon(Glyph.Network, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(16.dp))
            Text("手机和电脑需连接同一局域网", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SessionPanel(
    state: MicState, enabled: Boolean, onMutedChange: (Boolean) -> Unit,
    onRetryMicrophone: () -> Unit, onDisconnect: () -> Unit,
    audioLevel: (() -> Float)?,
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
                val muteSource = remember { MutableInteractionSource() }
                val muteColor by animateColorAsState(if (state.muted) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.primaryContainer,
                    tween(180))
                Surface(
                    onClick = { onMutedChange(!state.muted) },
                    enabled = enabled && !disconnecting,
                    interactionSource = muteSource,
                    modifier = Modifier.sizeIn(minWidth = 112.dp, minHeight = 112.dp).pressFeedback(muteSource).semantics {
                        role = Role.Switch
                        stateDescription = if (state.muted) "已开启，声音暂停传输" else "已关闭，允许传输声音"
                        contentDescription = "麦克风静音"
                        onClick(label = if (state.muted) "恢复传输麦克风声音" else "暂停传输麦克风声音", action = null)
                        toggleableState = if (state.muted) androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
                    },
                    shape = CircleShape,
                    color = muteColor,
                    contentColor = if (state.muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Box(Modifier.padding(32.dp), contentAlignment = Alignment.Center) {
                        ToolIcon(if (state.muted) Glyph.MicOff else Glyph.Mic, LocalContentColor.current, Modifier.size(48.dp))
                    }
                }
                Text(if (state.muted) "点击取消静音" else "点击静音", Modifier.padding(top = 14.dp).clearAndSetSemantics {},
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(28.dp))
                AudioMeter(level = { if (state.muted) 0f else audioLevel?.invoke() ?: state.level }, active = !state.muted)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("输入电平", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (state.muted) "静音" else "实时",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                StatusNote(state.message, progress = reconnecting || disconnecting, announce = false)
                if (state.phase == ConnectionPhase.CONNECTED) {
                    FilledTonalButton(onClick = onRetryMicrophone, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp).actionHint("重新请求使用麦克风"),
                        shape = MaterialTheme.shapes.small) { Text("重新启用麦克风") }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp).toggleable(
                        value = state.muted, enabled = enabled && !disconnecting,
                        role = Role.Switch, onValueChange = onMutedChange,
                    ).semantics { stateDescription = if (state.muted) "已开启" else "已关闭" }
                        .actionHint("重连后保持麦克风静音"),
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
            Text("由本机独占", modifier = Modifier.clearAndSetSemantics { text = AnnotatedString("当前电脑仅接收这台手机") }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onDisconnect, enabled = enabled && !disconnecting,
            modifier = Modifier.actionHint("停止传输并释放电脑占用"),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
            Text(if (disconnecting) "正在断开…" else "断开连接", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun AudioMeter(level: () -> Float, active: Boolean) {
    val reducedMotion = LocalReducedMotion.current
    val value = level().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val target = if (active) sqrt(value) else 0f
    val display = remember { Animatable(0f) }
    LaunchedEffect(target, reducedMotion) {
        if (reducedMotion) display.snapTo(target)
        else display.animateTo(target, tween(if (target > display.value) 70 else 240))
    }
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.fillMaxWidth().height(24.dp).semantics {
        contentDescription = "麦克风输入电平"
        stateDescription = if (!active) "已静音" else "百分之 ${(value * 100).roundToInt()}"
        progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
    }) {
        val count = 36
        val gap = 3.dp.toPx()
        val width = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
        val height = 14.dp.toPx()
        repeat(count) { index ->
            val amount = (display.value * count - index).coerceIn(0f, 1f)
            val color = androidx.compose.ui.graphics.lerp(colors.outlineVariant.copy(alpha = .55f), colors.primary, amount)
            drawRoundRect(color, Offset(index * (width + gap), (size.height - height) / 2),
                Size(width, height), CornerRadius(width / 2))
        }
    }
}

@Composable
private fun EnhancementPanel(
    state: MicState, enabled: Boolean, onNoise: (Boolean) -> Unit, onEcho: (Boolean) -> Unit,
    onGain: (Float) -> Unit, onDeskNoise: (Boolean) -> Unit,
    onInteraction: () -> Unit, reducedMotion: Boolean,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val gain = state.inputGainDb.takeIf { it.isFinite() }?.coerceIn(0f, 18f)?.roundToInt() ?: 0
    val selection = buildList {
        if (gain > 0) add("收音 +$gain dB")
        if (state.noiseSuppression) add(if (state.deskNoiseSuppression) "桌面游戏降噪" else "通用降噪")
        if (state.echoCancellation) add("回声抑制")
    }.joinToString(" · ").ifEmpty { "均已关闭" }
    val nestedInset = if (LocalDensity.current.fontScale >= 1.5f) 0.dp else 44.dp
    DisclosureCard("声音处理", selection, expanded,
        onToggle = { onInteraction(); expanded = !expanded },
        icon = { ToolIcon(Glyph.Noise, MaterialTheme.colorScheme.primary, Modifier.size(20.dp)) },
        footer = { ProcessingStatusPanel(state) },
        footerVisible = state.echoCancellation, footerReducedMotion = reducedMotion) {
        Column {
            GainControl(gain, enabled, onGain)
            HorizontalDivider(Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            EnhancementRow("降噪", "减轻风扇、空调等背景声", Glyph.Noise,
                state.noiseSuppression, enabled, onNoise)
            Reveal(state.noiseSuppression) {
                Column(Modifier.padding(start = nestedInset, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("降噪场景", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SlidingChoice(listOf("通用", "桌面游戏"), if (state.deskNoiseSuppression) 1f else 0f, enabled,
                        hints = listOf("选择通用降噪，适合日常通话与持续背景声", "选择桌面游戏降噪，减轻键盘、鼠标与桌面摩擦声"),
                        onSelect = { onDeskNoise(it == 1) }, onInteraction = onInteraction)
                    AnimatedContent(state.deskNoiseSuppression, transitionSpec = {
                        (fadeIn(tween(if (reducedMotion) 0 else 160, delayMillis = if (reducedMotion) 0 else 50)) togetherWith fadeOut(tween(if (reducedMotion) 0 else 100)))
                            .using(SizeTransform { _, _ -> tween(if (reducedMotion) 0 else 240, easing = FastOutSlowInEasing) })
                    }) { desk ->
                        Text(if (desk) "针对键盘、鼠标和桌面摩擦声，可能影响轻声，并增加约 20 ms 算法延迟。关闭降噪可停止此项处理。" else "适合日常通话与持续的背景噪声。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider(Modifier.padding(start = nestedInset), color = MaterialTheme.colorScheme.outlineVariant)
            EnhancementRow("回声抑制", "减少电脑外放带来的回声", Glyph.Echo,
                state.echoCancellation, enabled, onEcho)
        }
    }
}

@Composable
private fun ProcessingStatusPanel(state: MicState) {
    val colors = MaterialTheme.colorScheme
    val selected = state.noiseSuppression || state.echoCancellation || state.inputGainDb > 0f
    val running = state.phase == ConnectionPhase.CONNECTED && state.recording
    val phase = when {
        state.phase == ConnectionPhase.RECONNECTING -> "重连中"
        !state.ownsPc -> "待连接"
        !state.recording -> "已暂停"
        !selected -> "未启用"
        else -> "运行中"
    }
    val stats = state.processingStatistics
    val measured = running && state.echoCancellation && stats.available
    var showDetail by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(14.dp), color = colors.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stacked = maxWidth / LocalDensity.current.fontScale < 220.dp
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("处理监测", modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                        if (stacked) ProcessingPhase(phase, running && selected)
                    }
                    if (!stacked) ProcessingPhase(phase, running && selected)
                    IconButton(onClick = { showDetail = true }, modifier = Modifier.size(48.dp)
                        .semantics { contentDescription = "查看音频处理详情" }.actionHint("查看指标含义与当前处理说明")) {
                        ToolIcon(Glyph.Info, colors.onSurfaceVariant, Modifier.size(18.dp))
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val metrics = listOf(
                    Triple("参考流", if (measured) "${stats.referenceFramesPerSecond}" else "—", "帧/秒"),
                    Triple("回声处理", if (measured) "${stats.echoPercent}" else "—", "%"),
                    Triple("原声回退", if (measured) "${stats.fallbackFramesPerSecond}" else "—", "帧/秒"))
                val labelStyle = MaterialTheme.typography.labelSmall
                val labelWidth = metrics.maxOf { textWidth(it.first, labelStyle) }
                // Reserve four digits so ordinary live counter changes cannot switch the layout.
                val valueWidth = textWidth(metricValue("9999", "帧/秒"), MaterialTheme.typography.titleMedium)
                val columnWidth = maxOf(labelWidth, valueWidth)
                val columns = columnWidth * 3 + 16.dp <= maxWidth
                val inlineMetric = labelWidth + valueWidth + 16.dp <= maxWidth
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!columns) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        metrics.forEach { (label, value, unit) ->
                            ProcessingMetric(label, value, unit, Modifier.fillMaxWidth(), inline = inlineMetric)
                        }
                    } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        metrics.forEach { (label, value, unit) -> ProcessingMetric(label, value, unit, Modifier.weight(1f)) }
                    }
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
                    val realignments = "重新对齐 ${if (running) stats.realignments else 0} 次"
                    val timeouts = "处理超时 ${if (running) stats.timeouts else 0} 次"
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = if (columns) 2 else 1) {
                        Text(realignments, style = labelStyle, color = colors.onSurfaceVariant)
                        Text(timeouts, style = labelStyle, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
    if (showDetail) AlertDialog(onDismissRequest = { showDetail = false },
        title = { Text("音频处理详情", Modifier.semantics { heading() }) },
        text = { Text("参考流表示收到的完整立体声参考帧；回声处理表示最近一秒成功使用回声抑制的帧占比，并非消除效果评分。参考暂不可用时继续传输原声。\n\n${state.enhancementMessage}") },
        confirmButton = { TextButton(onClick = { showDetail = false }, modifier = Modifier.actionHint("关闭音频处理详情")) { Text("知道了") } })
}

@Composable
private fun ProcessingPhase(phase: String, running: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(6.dp).background(if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
        Text(phase, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun metricValue(value: String, unit: String): AnnotatedString = buildAnnotatedString {
    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold))
    append(value)
    pop()
    pushStyle(SpanStyle(fontSize = MaterialTheme.typography.labelSmall.fontSize,
        color = MaterialTheme.colorScheme.onSurfaceVariant))
    append(" $unit")
    pop()
}

@Composable
private fun ProcessingMetric(label: String, value: String, unit: String, modifier: Modifier, inline: Boolean = false) {
    val spokenValue = if (value == "—") "暂无数据" else if (unit == "%") "百分之 $value" else "每秒 $value 帧"
    val accessible = modifier.clearAndSetSemantics { text = AnnotatedString("$label，$spokenValue") }
    if (inline) {
        Row(accessible, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(metricValue(value, unit), style = MaterialTheme.typography.titleMedium)
        }
    } else {
        Column(accessible, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(metricValue(value, unit), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GainControl(gain: Int, enabled: Boolean, onChange: (Float) -> Unit) {
    val reducedMotion = LocalReducedMotion.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val dragged by source.collectIsDraggedAsState()
    val engaged = pressed || dragged
    var gestureValue by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(engaged) { if (!engaged) gestureValue = null }
    val restingValue by animateFloatAsState(gain.toFloat(), tween(if (engaged || reducedMotion) 0 else 180, easing = FastOutSlowInEasing))
    val halo by animateFloatAsState(if (engaged && !reducedMotion) 1f else 0f, tween(if (reducedMotion) 0 else 140))
    val colors = MaterialTheme.colorScheme
    val boundary = controlBoundaryColor
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val titleStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
            val badgeStyle = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            if (textWidth("收音增强", titleStyle) + textWidth("+18 dB", badgeStyle) + 32.dp <= maxWidth) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("收音增强", Modifier.weight(1f).clearAndSetSemantics {}, style = titleStyle)
                    GainBadge(gain)
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("收音增强", Modifier.clearAndSetSemantics {}, style = titleStyle)
                GainBadge(gain)
            }
        }
        Slider(value = if (engaged) gestureValue ?: restingValue else restingValue, onValueChange = {
                gestureValue = it
                onChange(it.roundToInt().coerceIn(0, 18).toFloat())
            }, valueRange = 0f..18f, steps = 17, enabled = enabled, interactionSource = source,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                contentDescription = "收音增强"
                stateDescription = if (gain == 0) "关闭，0 分贝" else "增加 $gain 分贝"
                setProgress(label = "调整收音增强，范围 0 到 18 分贝，每次 1 分贝", action = null)
            },
            thumb = {
                Canvas(Modifier.size(28.dp)) {
                    val primary = if (enabled) colors.primary else colors.outline
                    drawCircle(primary.copy(alpha = .10f * halo), radius = 20.dp.toPx())
                    drawCircle(colors.surface, radius = (10f + halo).dp.toPx())
                    drawCircle(primary, radius = (10f + halo).dp.toPx(), style = Stroke(2.5.dp.toPx()))
                }
            },
            track = { slider ->
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    val start = if (layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl) size.width else 0f
                    val end = size.width - start
                    drawLine(boundary.copy(alpha = if (enabled) 1f else .4f), Offset(start, center.y), Offset(end, center.y),
                        size.height, StrokeCap.Round)
                    val fraction = (slider.value / 18f).coerceIn(0f, 1f)
                    if (fraction > 0f) drawLine(if (enabled) colors.primary else colors.outline,
                        Offset(start, center.y), Offset(start + (end - start) * fraction, center.y), size.height, StrokeCap.Round)
                }
            })
        Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("关闭", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            Text("+18 dB", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            val hint = "手机放桌面时可适度提高，也会放大残余噪声。"
            if (maxWidth / LocalDensity.current.fontScale < 240.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    GainReset(enabled && gain != 0) { onChange(0f) }
                }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(hint, Modifier.weight(1f).padding(end = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                GainReset(enabled && gain != 0) { onChange(0f) }
            }
        }
    }
}

@Composable
private fun GainBadge(gain: Int) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
        Text(if (gain == 0) "0 dB" else "+$gain dB", Modifier.padding(horizontal = 10.dp, vertical = 5.dp).clearAndSetSemantics {},
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun GainReset(enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp),
        modifier = Modifier.semantics { contentDescription = "恢复 0 分贝" }.actionHint("关闭收音增强")) {
        Text("恢复 0 dB", modifier = Modifier.clearAndSetSemantics {}, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun LockScreenPanel(state: LockScreenState, onInteraction: () -> Unit, onChange: (Boolean) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    DisclosureCard("锁屏控制",
        when { !state.available -> "当前不可用"; state.enabled -> "已开启"; else -> "已关闭" },
        expanded, onToggle = { onInteraction(); expanded = !expanded },
        icon = { ToolIcon(Glyph.Lock, MaterialTheme.colorScheme.primary, Modifier.size(20.dp)) }) {
        SettingSwitch("在锁屏上显示", state.enabled, state.available || state.enabled, onChange,
            hint = "在锁屏和灵动岛显示连接状态与快捷操作，关闭不影响录音")
        Text("仅展示连接状态与快捷操作，关闭不影响录音。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(state.message, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun EnhancementRow(label: String, detail: String, icon: Glyph, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    SettingSwitch(label, checked, enabled, onChange,
        detail = detail, icon = { ToolIcon(icon, MaterialTheme.colorScheme.onSurfaceVariant, Modifier.size(22.dp)) })
}

@Composable
private fun SettingSwitch(
    label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit,
    detail: String? = null, hint: String? = detail, icon: (@Composable () -> Unit)? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val tint by animateColorAsState(if (pressed) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent, tween(120))
    val haptic = LocalHapticFeedback.current
    val titleStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .5f))
    val detailContent: @Composable () -> Unit = {
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .5f))
    }
    val toggle: @Composable () -> Unit = {
        Switch(checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(
                uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                uncheckedBorderColor = Color.Transparent,
                checkedBorderColor = Color.Transparent,
            ), modifier = Modifier.clearAndSetSemantics {})
    }
    Box(Modifier.fillMaxWidth().pressFeedback(source).clip(RoundedCornerShape(12.dp)).background(tint)
        .toggleable(value = checked, enabled = enabled, interactionSource = source, indication = null,
            role = Role.Switch, onValueChange = {
                haptic.performHapticFeedback(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onChange(it)
            }).semantics {
            contentDescription = label
            stateDescription = if (checked) "已开启" else "已关闭"
            onClick(label = hint ?: if (checked) "关闭$label" else "开启$label", action = null)
        }.padding(vertical = 14.dp)) {
        if (LocalDensity.current.fontScale >= 1.5f) {
            Column(Modifier.clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ControlHeading(label, reserveLabel = if (detail != null) "回声抑制" else label, style = titleStyle, control = toggle)
                detailContent()
            }
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f).padding(end = 10.dp).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = titleStyle)
                detailContent()
            }
            toggle()
        }
    }
}

@Composable
private fun StatusNote(message: String, error: Boolean = false, progress: Boolean = false, announce: Boolean = true) {
    val color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { if (announce) liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.Top) {
        if (progress) CircularProgressIndicator(Modifier.padding(top = 2.dp).size(15.dp).clearAndSetSemantics {}, strokeWidth = 1.5.dp, color = color)
        else ToolIcon(Glyph.Info, color, Modifier.padding(top = 1.dp).size(16.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun HelpItem(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
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

private enum class Glyph { Mic, MicOff, Info, Arrow, Network, Lock, Noise, Echo, Settings }

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
            Glyph.Settings -> {
                line(3f, 6f, 21f, 6f); line(3f, 12f, 21f, 12f); line(3f, 18f, 21f, 18f)
                val background = color.copy(alpha = 1f)
                drawCircle(background, 2.5f * u, Offset(8*u, 6*u))
                drawCircle(background, 2.5f * u, Offset(16*u, 12*u))
                drawCircle(background, 2.5f * u, Offset(10*u, 18*u))
            }
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
