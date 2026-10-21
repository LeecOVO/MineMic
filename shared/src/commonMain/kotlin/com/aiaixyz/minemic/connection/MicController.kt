package com.aiaixyz.minemic.connection

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

enum class ConnectionPhase { IDLE, SEARCHING, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTING, ERROR }
data class MicState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val message: String = "输入 PC 端的 6 位配对码，或手动输入 PC 地址",
    val pcName: String = "", val ownsPc: Boolean = false,
    val recording: Boolean = false, val muted: Boolean = false, val level: Float = 0f,
)

class MicController(private val platform: PhonePlatform) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val mutableState = MutableStateFlow(MicState())
    val state = mutableState.asStateFlow()
    private var connectionJob: Job? = null
    private var audioJob: Job? = null
    private var releaseJob: Job? = null
    private var session: Session? = null
    private var muted = false
    private data class Session(val pc: LanComputer, val clientId: String, var token: String, var id: Long, var key: ByteArray)
    private sealed interface Event {
        data class Line(val fields: Map<String, String>) : Event
        data class Closed(val reason: String) : Event
    }
    private class PairError(val reason: String) : Exception(reason)
    private fun update(block: (MicState) -> MicState) { mutableState.value = block(mutableState.value) }

    fun connect(code: String, host: String) {
        scope.launch {
            if (session != null || connectionJob?.isActive == true || releaseJob?.isActive == true) return@launch
            if (host.isBlank() && !Wire.validCode(code) || host.isNotBlank() && !Wire.validIpv4(host.trim())) {
                update { it.copy(phase = ConnectionPhase.ERROR, message = if (host.isBlank()) "请输入完整的 6 位数字配对码" else "请输入有效的 PC IPv4 地址") }
                return@launch
            }
            connectionJob = scope.launch { establish(code, host.trim()) }
        }
    }
    private suspend fun establish(code: String, host: String) {
        try {
            val candidates = if (host.isNotEmpty()) listOf(LanComputer(host, Wire.PORT, host)) else discover()
            val clientId = Wire.hex(platform.randomBytes(16))
            val nonce = Wire.hex(platform.randomBytes(16))
            var lastReason = "wrong_code"
            for (pc in candidates) {
                update { it.copy(phase = ConnectionPhase.CONNECTING, message = "正在连接 ${pc.name}…") }
                try {
                    val (channel, welcome) = open(pc, mapOf("type" to "connect", "mode" to if (host.isEmpty()) "code" else "manual",
                        "code" to code, "clientId" to clientId, "nonce" to nonce, "name" to platform.deviceName()))
                    require(welcome["nonce"] == nonce)
                    session = parseSession(pc, clientId, welcome)
                    maintain(channel)
                    return
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { lastReason = (e as? PairError)?.reason ?: "unreachable"; platform.closeControl() }
            }
            throw PairError(lastReason)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            update { it.copy(phase = ConnectionPhase.ERROR, message = friendly((e as? PairError)?.reason ?: "discovery")) }
        } finally { platform.stopDiscovery() }
    }
    private suspend fun discover(): List<LanComputer> {
        update { it.copy(phase = ConnectionPhase.SEARCHING, message = "正在查找同一局域网内的 PC…") }
        val found = Channel<LanComputer>(32)
        platform.discover({ found.trySend(it) }, { found.close(PairError("discovery")) })
        try {
            val first = try { withTimeout(8000) { found.receive() } } catch (_: TimeoutCancellationException) { throw PairError("discovery") }
            delay(1000)
            val all = mutableListOf(first)
            while (all.size < 16) all += found.tryReceive().getOrNull() ?: break
            return all.distinctBy { "${it.host}:${it.port}" }
        } finally { platform.stopDiscovery(); found.close() }
    }
    private suspend fun open(pc: LanComputer, request: Map<String, String>): Pair<Channel<Event>, Map<String, String>> {
        val ready = CompletableDeferred<Unit>()
        val channel = Channel<Event>(32)
        platform.openControl(pc.host, pc.port, { ready.complete(Unit) }, { line ->
            try { if (channel.trySend(Event.Line(ControlJson.decode(line))).isFailure) channel.close(PairError("unreachable")) }
            catch (_: Exception) { channel.close(PairError("invalid_response")) }
        }, { channel.trySend(Event.Closed("unreachable")); ready.completeExceptionally(PairError("unreachable")) },
            { reason -> channel.trySend(Event.Closed(reason)); ready.completeExceptionally(PairError("unreachable")) })
        return try {
            withTimeout(3500) {
                ready.await(); platform.sendControl(ControlJson.encode(request))
                val reply = next(channel)
                if (reply["type"] == "error") throw PairError(reply["reason"] ?: "unreachable")
                require(reply["type"] == "welcome" && reply["nonce"] == request["nonce"])
                channel to reply
            }
        } catch (e: TimeoutCancellationException) { channel.close(); throw PairError("unreachable") }
        catch (e: Exception) { channel.close(); throw e }
    }
    private fun parseSession(pc: LanComputer, clientId: String, m: Map<String, String>): Session {
        val id = m["session"]?.toLongOrNull() ?: error("Invalid session")
        require(id in 1..0xffffffffL)
        val token = m["token"] ?: error("Missing token"); Wire.unhex(token)
        val port = m["port"]?.toIntOrNull() ?: error("Missing port"); require(port in 1024..65535)
        return Session(pc.copy(port = port, name = m["pcName"] ?: pc.name), clientId, token, id, Wire.unhex(m["key"] ?: ""))
    }
    private suspend fun next(channel: Channel<Event>): Map<String, String> = when (val e = channel.receive()) {
        is Event.Line -> e.fields
        is Event.Closed -> throw PairError(e.reason)
    }
    private suspend fun maintain(initial: Channel<Event>) {
        var channel = initial
        while (currentCoroutineContext().isActive && session != null) {
            val s = session ?: return
            update { it.copy(phase = ConnectionPhase.CONNECTED, message = "已连接，PC 已为本手机保留", pcName = s.pc.name, ownsPc = true) }
            var ping: Job? = null
            try {
                platform.openAudio(s.pc.host, s.pc.port); beginAudio()
                ping = scope.launch { while (isActive) { platform.sendControl(ControlJson.encode(mapOf("type" to "ping"))); delay(1000) } }
                while (true) {
                    val m = withTimeout(4000) { next(channel) }
                    if (m["type"] == "disconnected") { clearLocal("PC 已断开连接，配对码已更新"); return }
                }
            } catch (e: CancellationException) {
                // A heartbeat timeout is recoverable; an explicit job cancellation is not.
                if (e !is TimeoutCancellationException) throw e
            } catch (_: Exception) {
                // Network loss never transfers PC ownership to a different phone.
            } finally { ping?.cancel(); stopCapture(); platform.closeAudio(); channel.close(); platform.closeControl() }
            update { it.copy(phase = ConnectionPhase.RECONNECTING, recording = false, level = 0f, message = "连接中断，正在重连原 PC…") }
            while (currentCoroutineContext().isActive) {
                delay(1200)
                try {
                    val nonce = Wire.hex(platform.randomBytes(16))
                    val (newChannel, welcome) = open(s.pc, mapOf("type" to "resume", "clientId" to s.clientId, "token" to s.token, "nonce" to nonce))
                    session = parseSession(s.pc, s.clientId, welcome); channel = newChannel; break
                } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e }
                catch (e: Exception) {
                    platform.closeControl()
                    if (e is PairError && e.reason == "not_owner") { clearLocal("PC 已释放原连接，请使用新的配对码连接"); return }
                }
            }
        }
    }
    private fun beginAudio() {
        stopCapture()
        val s = session ?: return
        // Native capture may deliver several 10 ms frames in one callback. A 40 ms
        // channel discarded valid iOS batches before this coroutine could run.
        // Keep at most 160 ms; on sustained overload prefer recent speech.
        val frames = Channel<Pair<Long, ByteArray>>(16, BufferOverflow.DROP_OLDEST)
        var captureSequence = 0L
        audioJob = scope.launch {
            for ((sequence, pcm) in frames) {
                if (pcm.size != 960) continue
                val body = Wire.audioBody(s.id, sequence, if (muted) ByteArray(960) else pcm)
                platform.sendAudio(body + platform.hmac(body, s.key))
                if (sequence % 10 == 1L) {
                    var peak = 0
                    for (i in 0 until 480) {
                        val n = (pcm[i * 2].toInt() and 255) or (pcm[i * 2 + 1].toInt() shl 8)
                        peak = maxOf(peak, abs(n))
                    }
                    update { it.copy(recording = true, muted = muted, level = if (muted) 0f else peak / 32768f, message = "手机麦克风正在传输") }
                }
            }
        }
        platform.startAudio({ frames.trySend(captureSequence++ to it) }, { error -> scope.launch {
            frames.close(); update { it.copy(recording = false, level = 0f, message = "已连接，但无法录音：$error") }
        } })
    }
    private fun stopCapture() { platform.stopAudio(); audioJob?.cancel(); audioJob = null }
    fun setMuted(value: Boolean) { scope.launch { muted = value; update { it.copy(muted = value) } } }
    fun retryMicrophone() { scope.launch { if (session != null && state.value.phase == ConnectionPhase.CONNECTED) beginAudio() } }
    fun disconnect() {
        scope.launch {
            if (releaseJob?.isActive == true) return@launch
            releaseJob = scope.launch {
                connectionJob?.cancelAndJoin(); stopCapture(); platform.closeAudio(); platform.closeControl()
                val s = session ?: run { clearLocal("已取消连接"); return@launch }
                update { it.copy(phase = ConnectionPhase.DISCONNECTING, recording = false, level = 0f, message = "正在通知 PC 断开连接…") }
                var released = false
                repeat(3) {
                    if (released) return@repeat
                    val ready = CompletableDeferred<Unit>(); val lines = Channel<Event>(8)
                    platform.openControl(s.pc.host, s.pc.port, { ready.complete(Unit) }, { line ->
                        runCatching { ControlJson.decode(line) }.onSuccess { lines.trySend(Event.Line(it)) }
                    }, { lines.trySend(Event.Closed("unreachable")); ready.completeExceptionally(PairError("unreachable")) },
                        { ready.completeExceptionally(PairError("unreachable")) })
                    try {
                        withTimeout(1800) {
                            ready.await(); platform.sendControl(ControlJson.encode(mapOf("type" to "disconnect", "clientId" to s.clientId, "token" to s.token)))
                            val reply = next(lines)
                            released = reply["type"] == "disconnected" || reply["reason"] == "not_owner"
                        }
                    } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e }
                    catch (_: Exception) { } finally { lines.close(); platform.closeControl() }
                }
                if (released) clearLocal("已断开，PC 配对码已更新")
                else update { it.copy(phase = ConnectionPhase.RECONNECTING, message = "未能通知 PC，原占用仍保留；请重试断开或在 PC 上手动断开") }
            }
        }
    }
    private fun clearLocal(message: String) {
        stopCapture(); platform.closeAudio(); platform.closeControl(); session?.key?.fill(0); session = null
        mutableState.value = MicState(message = message, muted = muted)
    }
    fun close() {
        scope.cancel(); platform.stopDiscovery(); platform.stopAudio(); platform.closeAudio(); platform.closeControl()
        // Process/lifecycle destruction is NOT an explicit user disconnect at the PC.
    }
    private fun friendly(reason: String) = when (reason) {
        "wrong_code" -> "未找到此配对码，请核对 PC 上的 6 位数字"
        "busy" -> "该 PC 已被另一台手机连接，请先在 PC 上手动断开"
        "rate_limited" -> "连接尝试过于频繁，请稍后重试"
        "discovery" -> "未发现 PC，请确认局域网权限、同一 Wi-Fi 和 PC 已开启接收，或手动输入 IP"
        else -> "连接失败，请检查 PC 地址、防火墙和局域网连接"
    }
}
