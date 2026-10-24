package com.aiaixyz.minemic

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import com.aiaixyz.minemic.connection.MicController
import com.aiaixyz.minemic.connection.MicState
import com.aiaixyz.minemic.connection.LockScreenControls
import com.aiaixyz.minemic.ui.MineMicScreen
import com.aiaixyz.minemic.ui.MineMicTheme
import com.aiaixyz.minemic.ui.AppearanceSettings
import com.aiaixyz.minemic.ui.PlatformAppearance
import com.aiaixyz.minemic.ui.rememberAppearanceSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import minemic.shared.generated.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi

@Composable
fun App(controller: MicController? = null, onConnect: ((String, String) -> Unit)? = null) =
    App(controller = controller, onConnect = onConnect, lockScreenControls = null)

// The two-argument overload preserves existing trailing-lambda call sites.
@Composable
@OptIn(ExperimentalResourceApi::class)
fun App(
    controller: MicController? = null,
    onConnect: ((String, String) -> Unit)? = null,
    lockScreenControls: LockScreenControls?,
    appearanceSettings: AppearanceSettings? = null,
) {
    val appearance = appearanceSettings ?: rememberAppearanceSettings()
    val dark = appearance.mode.isDark(isSystemInDarkTheme())
    PlatformAppearance(appearance.mode, dark)
    val observedState = controller?.state?.collectAsState()
    // Meter changes invalidate only the meter, not the form, controls or dialogs.
    val preview = onConnect != null
    val state by remember(observedState, preview) {
        derivedStateOf(structuralEqualityPolicy()) {
            observedState?.value?.copy(level = 0f) ?: if (preview) MicState() else
                MicState(message = "手机端请运行 Android/iOS 应用；PC 端使用独立 MineMic-PC 客户端")
        }
    }
    val audioLevel = remember(observedState) { { observedState?.value?.level ?: 0f } }
    val lockScreenState = lockScreenControls?.state?.collectAsState()?.value
    var licenses by remember { mutableStateOf<String?>(null) }
    var licenseError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    MineMicTheme(darkTheme = dark) {
        MineMicScreen(
            state = state,
            licenseText = licenses,
            licenseError = licenseError,
            audioLevel = audioLevel,
            themeMode = appearance.mode,
            onThemeModeChange = appearance::select,
            controlsEnabled = controller != null,
            connectEnabled = controller != null || onConnect != null,
            onConnect = { code, host -> (onConnect ?: { c, ip -> controller?.connect(c, ip) })(code, host) },
            onMutedChange = { controller?.setMuted(it) },
            onNoiseSuppressionChange = { controller?.setNoiseSuppression(it) },
            onEchoCancellationChange = { controller?.setEchoCancellation(it) },
            onInputGainChange = { controller?.setInputGainDb(it) },
            onDeskNoiseSuppressionChange = { controller?.setDeskNoiseSuppression(it) },
            lockScreenState = lockScreenState,
            onLockScreenControlsChange = { lockScreenControls?.setEnabled(it) },
            onRetryMicrophone = { controller?.retryMicrophone() },
            onDisconnect = { controller?.disconnect() },
            onShowLicenses = {
                scope.launch {
                    if (licenses == null) {
                        licenseError = false
                        try {
                            licenses = Res.readBytes("files/audio-processing-licenses.txt").decodeToString()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            licenseError = true
                        }
                    }
                }
            },
        )
    }
}
