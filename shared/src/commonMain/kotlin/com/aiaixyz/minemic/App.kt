package com.aiaixyz.minemic

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aiaixyz.minemic.connection.MicController
import com.aiaixyz.minemic.connection.MicState
import com.aiaixyz.minemic.connection.LockScreenControls
import com.aiaixyz.minemic.ui.MineMicScreen
import com.aiaixyz.minemic.ui.MineMicTheme
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
) {
    val state = controller?.state?.collectAsState()?.value ?: if (onConnect != null) MicState() else
        MicState(message = "手机端请运行 Android/iOS 应用；PC 端使用独立 MineMic-PC 客户端")
    val lockScreenState = lockScreenControls?.state?.collectAsState()?.value
    var licenses by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    MineMicTheme {
        MineMicScreen(
            state = state,
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
                    licenses = runCatching { Res.readBytes("files/audio-processing-licenses.txt").decodeToString() }
                        .getOrDefault("许可文件暂时无法读取，请在 MineMic 项目页面查看第三方许可。")
                }
            },
        )
        licenses?.let { text ->
            AlertDialog(
                onDismissRequest = { licenses = null },
                title = { Text("第三方许可") },
                text = {
                    Text(text, Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall)
                },
                confirmButton = { TextButton(onClick = { licenses = null }) { Text("关闭") } },
            )
        }
    }
}
