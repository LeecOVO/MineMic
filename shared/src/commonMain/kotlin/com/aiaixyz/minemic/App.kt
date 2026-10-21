package com.aiaixyz.minemic

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aiaixyz.minemic.connection.*

@Composable
fun App(controller: MicController? = null, onConnect: ((String, String) -> Unit)? = null) {
    val state = controller?.state?.collectAsState()?.value ?: MicState(message = "手机端请运行 Android/iOS 应用；PC 端使用独立 MineMic-PC 客户端")
    var manual by rememberSaveable { mutableStateOf(false) }
    var code by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    val pending = state.phase in listOf(ConnectionPhase.SEARCHING, ConnectionPhase.CONNECTING, ConnectionPhase.DISCONNECTING)
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp).widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text("MineMic", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("${AppVersion.name} · ${AppVersion.stage}", style = MaterialTheme.typography.labelMedium)
                Text("把手机变成电脑麦克风", style = MaterialTheme.typography.titleMedium)
                if (!state.ownsPc) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(selected = !manual, onClick = { manual = false }, enabled = !pending, label = { Text("配对码连接") })
                        FilterChip(selected = manual, onClick = { manual = true }, enabled = !pending, label = { Text("手动连接") })
                    }
                    if (!manual) {
                        OutlinedTextField(value = code, onValueChange = { code = it.filter { c -> c in '0'..'9' }.take(6) },
                            modifier = Modifier.fillMaxWidth(), label = { Text("PC 端的 6 位配对码") },
                            placeholder = { Text("例如 038215") }, singleLine = true, enabled = !pending,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                        Text("手机和 PC 需连接同一局域网，PC 端需开启接收。")
                    } else {
                        OutlinedTextField(value = host, onValueChange = { host = it.filter { c -> c in '0'..'9' || c == '.' }.take(15) },
                            modifier = Modifier.fillMaxWidth(), label = { Text("PC 的 IP 地址") }, placeholder = { Text("192.168.1.100") },
                            singleLine = true, enabled = !pending, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        Text("填写 PC 端显示的 IPv4 地址；手动连接不需要配对码。")
                    }
                    Button(onClick = { (onConnect ?: { c, ip -> controller?.connect(c, ip) })(if (manual) "" else code, if (manual) host else "") },
                        enabled = controller != null && !pending && if (manual) Wire.validIpv4(host) else Wire.validCode(code),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (pending) "连接中…" else "连接 PC") }
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.pcName.isNotEmpty()) Text(state.pcName, style = MaterialTheme.typography.titleLarge)
                        Text(state.message)
                        if (pending || state.phase == ConnectionPhase.RECONNECTING) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (state.ownsPc) {
                            LinearProgressIndicator(progress = { state.level.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("麦克风静音", Modifier.weight(1f))
                                Switch(checked = state.muted, onCheckedChange = { controller?.setMuted(it) })
                            }
                            if (!state.recording && state.phase == ConnectionPhase.CONNECTED)
                                OutlinedButton(onClick = { controller?.retryMicrophone() }) { Text("启用麦克风") }
                            OutlinedButton(onClick = { controller?.disconnect() }, enabled = state.phase != ConnectionPhase.DISCONNECTING,
                                modifier = Modifier.fillMaxWidth()) { Text("断开连接") }
                            Text("连接会保持独占。断网不会让其他手机接入；手动断开后 PC 会更新配对码。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
