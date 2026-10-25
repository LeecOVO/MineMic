package com.aiaixyz.minemic.connection

import com.aiaixyz.minemic.audio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.abs

enum class ConnectionPhase { IDLE, SEARCHING, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTING, ERROR }
data class MicState(
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val message: String = "输入 PC 端的 6 位配对码，或手动输入 PC 地址",
    val pcName: String = "", val ownsPc: Boolean = false,
    val noiseSuppression: Boolean = false, val echoCancellation: Boolean = false,
    val deskNoiseSuppression: Boolean = false, val inputGainDb: Float = 0f,
    val enhancementMessage: String = "增强已关闭",
    val processingStatistics: ProcessingStatistics = ProcessingStatistics(),
    val recording: Boolean = false, val muted: Boolean = false, val level: Float = 0f,
)

class MicController(private val platform: PhonePlatform) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val mutableState = MutableStateFlow(MicState())
    val state = mutableState.asStateFlow()
    private var connectionJob: Job? = null
    private var audioJob: Job? = null
    private var captureWatchdog: Job? = null
    private var releaseJob: Job? = null
    private var activeEvents: Channel<Event>? = null
    private var intentionalRelease = false
    private var captureFrames: Channel<Pair<Long, CapturedAudio>>? = null
    private data class AbandonedOwner(val pc: LanComputer, val clientId: String, val token: String)
    private val pendingReleases = ArrayDeque<AbandonedOwner>()
    private val leases = ConnectionLeases(platform)
    private var session: Session? = null
    private var muted = false
    private var inputGainDb = 0f
    private var captureEpoch = 0L
    private var captureSequence = 0L
    private var options = EnhancementOptions()
    private var enhancer: AudioEnhancer? = null
    private var clock = ClockSync()
    private var referenceRequested = false
    private var referencePaused = false
    private var referenceFailure = ""
    private var referenceWaitDetail = ""
    private var referenceRetryAt = 0L
    private data class Session(val pc: LanComputer, val clientId: String, var token: String, var id: Long, var key: ByteArray, val supportsReference: Boolean, val instance: String)
    private sealed interface Event {
        data class Line(val fields: Map<String, String>) : Event
        data class Closed(val reason: String) : Event
    }
    private class PairError(val reason: String, val instance: String = "") : Exception(reason)
    private fun update(block: (MicState) -> MicState) { mutableState.value = block(mutableState.value) }

    fun observeState(observer: (MicState) -> Unit): MicStateSubscription {
        val job = scope.launch { state.collect { observer(it) } }
        return object : MicStateSubscription { override fun cancel() { job.cancel() } }
    }

    /** Lock-screen observers do not need microphone meter updates. */
    fun observeSessionState(observer: (MicState) -> Unit): MicStateSubscription {
        val job = scope.launch { state.map { it.copy(level = 0f) }.distinctUntilChanged().collect { observer(it) } }
        return object : MicStateSubscription { override fun cancel() { job.cancel() } }
    }

    fun connect(code: String, host: String) {
        scope.launch {
            if (session != null || connectionJob?.isActive == true) return@launch
            releaseJob?.cancelAndJoin(); releaseJob = null
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
                    val saved = leases.find(pc)
                    val abandoned = pendingReleases.firstOrNull { it.pc.host == pc.host && it.pc.port == pc.port }
                        ?: saved?.takeIf { it.releasePending }?.let { AbandonedOwner(pc, it.clientId, it.token) }
                    abandoned?.let {
                        if (notifyRelease(it)) { pendingReleases.remove(it); leases.forget(pc) }
                    }
                    val lease = leases.find(pc)?.takeUnless { it.releasePending }
                    val identity = lease?.clientId ?: clientId
                    val (channel, welcome) = open(pc, mapOf("type" to "connect", "mode" to if (host.isEmpty()) "code" else "manual",
                        "code" to code, "clientId" to identity, "token" to lease?.token.orEmpty(), "nonce" to nonce, "name" to platform.deviceName()))
                    require(welcome["nonce"] == nonce)
                    session = parseSession(pc, identity, welcome)
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
                if (reply["type"] == "error") throw PairError(reply["reason"] ?: "unreachable", reply["instance"].orEmpty())
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
        return Session(pc.copy(port = port, name = m["pcName"] ?: pc.name), clientId, token, id, Wire.unhex(m["key"] ?: ""), m["caps"]?.split(",")?.contains(REFERENCE_CAPABILITY) == true, m["instance"].orEmpty()).also {
            leases.remember(ConnectionLeases.Lease(it.pc.host, it.pc.port, it.clientId, it.token, it.instance))
        }
    }
    private suspend fun next(channel: Channel<Event>): Map<String, String> = when (val e = channel.receive()) {
        is Event.Line -> e.fields
        is Event.Closed -> throw PairError(e.reason)
    }
    private suspend fun maintain(initial: Channel<Event>) {
        var channel = initial
        while (currentCoroutineContext().isActive && session != null) {
            val s = session ?: return
            activeEvents = channel
            update { it.copy(phase = ConnectionPhase.CONNECTED, message = "已连接，PC 已为本手机保留", pcName = s.pc.name, ownsPc = true) }
            var ping: Job? = null
            var audioTransportFailed = false
            try {
                clock = ClockSync(); referenceRequested = false; referencePaused = true; captureSequence = 0L
                referenceRetryAt = 0L; referenceFailure = ""; referenceWaitDetail = ""
                beginAudio()
                val processing = enhancer
                // Bind callbacks to this attempt, never the mutable channel used by a later resume.
                val activeChannel = channel
                platform.openAudio(s.pc.host, s.pc.port, { processing?.offerReference(it) }, {
                    if (activeChannel.trySend(Event.Closed("audio_transport")).isFailure)
                        activeChannel.close(PairError("audio_transport"))
                })
                requestReference()
                ping = scope.launch {
                    var samples = 0
                    while (isActive) {
                        requestReference()
                        platform.sendControl(ControlJson.encode(mapOf("type" to "ping")))
                        if (s.supportsReference && options.echo) {
                            val t1 = platform.monotonicMicros(); clock.sent(t1)
                            platform.sendControl(ControlJson.encode(mapOf("type" to "clock", "t1" to t1.toString())))
                        }
                        delay(if (samples++ < 5 && options.echo) 200 else 1000)
                    }
                }
                while (true) {
                    val m = withTimeout(4000) { next(channel) }
                    if (m["type"] == "clock") {
                        val t1 = m["t1"]?.toLongOrNull(); val t2 = m["t2"]?.toLongOrNull(); val t3 = m["t3"]?.toLongOrNull()
                        if (t1 != null && t2 != null && t3 != null) clock.receive(t1, t2, t3, platform.monotonicMicros())
                    }
                    if (m["type"] == "reference") {
                        referenceWaitDetail = if (m["state"] == "waiting") m["detail"]?.take(200).orEmpty() else ""
                    }
                    if (m["type"] == "reference" && m["state"] == "unavailable") {
                        referenceFailure = m["detail"]?.take(200).orEmpty()
                        pauseReference()
                        update { it.copy(enhancementMessage = "PC 外放参考暂不可用，麦克风继续传输") }
                    }
                    if (m["type"] == "disconnected") {
                        m["closeId"]?.let { platform.finishControl(ControlJson.encode(mapOf("type" to "disconnect_ack", "closeId" to it))) }
                        clearLocal(if (m["reason"] == "stopped") "PC 已停止接收，配对码已更新" else "PC 已断开连接，可重新连接"); return
                    }
                }
            } catch (e: CancellationException) {
                // A heartbeat timeout is recoverable; an explicit job cancellation is not.
                if (e !is TimeoutCancellationException) throw e
            } catch (e: PairError) {
                audioTransportFailed = e.reason == "audio_transport"
            } catch (_: Exception) {
                // Network loss never transfers PC ownership to a different phone.
            } finally {
                ping?.cancel(); stopCapture(); platform.closeAudio()
                if (!intentionalRelease) { channel.close(); platform.closeControl(); if (activeEvents === channel) activeEvents = null }
            }
            update { it.copy(phase = ConnectionPhase.RECONNECTING, recording = false, level = 0f,
                message = if (audioTransportFailed) "音频通道异常，正在恢复原 PC 连接…" else "连接中断，正在重连原 PC…") }
            while (currentCoroutineContext().isActive) {
                delay(1200)
                try {
                    val nonce = Wire.hex(platform.randomBytes(16))
                    val (newChannel, welcome) = open(s.pc, mapOf("type" to "resume", "clientId" to s.clientId, "token" to s.token, "nonce" to nonce))
                    s.key.fill(0); session = parseSession(s.pc, s.clientId, welcome); channel = newChannel; break
                } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e }
                catch (e: Exception) {
                    platform.closeControl()
                    if (e is PairError && e.reason == "not_owner") {
                        if (s.instance.isNotEmpty() && e.instance.isNotEmpty() && s.instance != e.instance) {
                            // A different process has no old in-memory owner.
                            // Reclaim through normal first-owner arbitration.
                            try {
                                val nonce = Wire.hex(platform.randomBytes(16))
                                val (fresh, welcome) = open(s.pc, mapOf("type" to "connect", "mode" to "manual", "code" to "",
                                    "clientId" to s.clientId, "nonce" to nonce, "name" to platform.deviceName()))
                                s.key.fill(0); session = parseSession(s.pc, s.clientId, welcome); channel = fresh; break
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { platform.closeControl() }
                        } else { clearLocal("PC 已释放原连接，可重新连接"); return }
                    }
                }
            }
        }
    }
    private fun beginAudio() {
        stopCapture(keepEnhancer = true)
        val s = session ?: return
        val epoch = captureEpoch
        if (enhancer == null) enhancer = AudioEnhancer(s.id, s.key.copyOf(), platform::monotonicMicros, platform::hmac,
            diagnostics = platform::reportAudioDiagnostics,
            statistics = { value -> scope.launch {
                if (session === s) update { it.copy(processingStatistics = value) }
            } }, diagnosticFrame = platform::recordAudioDiagnosticFrame, timing = EnhancementTiming())
        // Native capture may deliver several 10 ms frames in one callback. A 40 ms
        // channel discarded valid iOS batches before this coroutine could run.
        // Keep at most 160 ms; on sustained overload prefer recent speech.
        val frames = Channel<Pair<Long, CapturedAudio>>(16, BufferOverflow.DROP_OLDEST)
        captureFrames = frames
        var capturePaused = false
        var frameTimedOut = false
        var lastFrameAt = platform.monotonicMicros()
        val gain = CaptureGain()
        val packets = AudioPacketEncoder(s.id) { platform.hmac(it, s.key) }
        captureWatchdog = scope.launch {
            while (isActive && epoch == captureEpoch) {
                delay(1000)
                if (!capturePaused && !frameTimedOut && platform.monotonicMicros() - lastFrameAt > 2000000) {
                    frameTimedOut = true
                    referencePaused = true; requestReference(); enhancer?.releaseProcessing()
                    update { it.copy(recording = false, level = 0f, message = "录音数据已暂停，请返回应用检查；连接保留") }
                }
            }
        }
        audioJob = scope.launch {
            for ((sequence, frame) in frames) {
                if (frame.pcm.size != 960 || capturePaused) continue
                lastFrameAt = platform.monotonicMicros()
                if (frameTimedOut) {
                    frameTimedOut = false; referencePaused = false; requestReference()
                    update { it.copy(recording = true, message = "手机麦克风正在传输") }
                }
                val chosen = options
                platform.recordAudioDiagnosticFrame("input", frame.capturedAtMicros, frame.pcm)
                // One bounded lookahead at a fixed capture age lets a remote
                // render packet arrive before DSP. Do not independently wait
                // for a new UDP reference inside every 10 ms processing call.
                val referenceWait = referenceLookaheadMicros(frame.capturedAtMicros,
                    platform.monotonicMicros(), chosen.echo && s.supportsReference)
                if (referenceWait > 0) delay((referenceWait + 999) / 1000)
                val result = if (chosen.noise || chosen.echo) enhancer?.enhance(frame,
                    chosen.copy(echo = chosen.echo && s.supportsReference && referenceRequested),
                    clock.current(platform.monotonicMicros())?.takeIf { platform.monotonicMicros() - frame.capturedAtMicros in -20000L..200000L },
                    referenceWaitDetail) else null
                val cleaned = if (options == chosen) result?.pcm ?: frame.pcm else frame.pcm
                platform.recordAudioDiagnosticFrame(if (options == chosen && result?.echo == true) "output-echo" else "output-bypass",
                    frame.capturedAtMicros, cleaned)
                val pcm = gain.process(cleaned, inputGainDb)
                if (capturePaused) continue
                val now = platform.monotonicMicros()
                // Missing loopback packets can mean silence, network jitter or native APM
                // initialization. Stop/retry only on an explicit PC capture failure.
                platform.sendAudio(packets.encode(sequence, pcm, muted))
                if (sequence % 10 == 1L) {
                    var peak = 0
                    for (i in 0 until 480) {
                        val n = (pcm[i * 2].toInt() and 255) or (pcm[i * 2 + 1].toInt() shl 8)
                        peak = maxOf(peak, abs(n))
                    }
                    val dspMessage = when {
                        chosen.echo && !s.supportsReference -> (if (chosen.noise) result?.message?.plus("；") ?: "" else "") + "回声抑制需要新版 Windows PC"
                        chosen.echo && now < referenceRetryAt -> (if (chosen.noise) result?.message?.plus("；") ?: "" else "") + "PC 外放参考不可用，稍后重试" + if (referenceFailure.isEmpty()) "" else "：$referenceFailure"
                        else -> result?.message ?: "增强已关闭"
                    }
                    val gainMessage = if (inputGainDb > 0f) "收音增强 +${inputGainDb.toInt()} dB" else ""
                    val enhancementMessage = if (gainMessage.isEmpty()) dspMessage else
                        (if (dspMessage == "增强已关闭") "" else "$dspMessage；") + gainMessage
                    update { it.copy(recording = true, muted = muted, level = if (muted) 0f else peak / 32768f,
                        message = "手机麦克风正在传输", enhancementMessage = enhancementMessage) }
                }
            }
        }
        platform.startAudio({ if (epoch == captureEpoch) frames.trySend(captureSequence++ to it) }, { error -> scope.launch {
            if (epoch == captureEpoch) {
                capturePaused = true; captureWatchdog?.cancel()
                frames.cancel(); audioJob?.cancel()
                referencePaused = true; requestReference(); enhancer?.releaseProcessing()
                update { it.copy(recording = false, level = 0f, message = "已连接，但无法录音：$error") }
            }
        } }, { active, message -> scope.launch {
            if (epoch == captureEpoch && session != null) {
                capturePaused = !active
                if (active) { frameTimedOut = false; lastFrameAt = platform.monotonicMicros() }
                referencePaused = !active; requestReference()
                if (!active) enhancer?.releaseProcessing()
                update { it.copy(recording = active, level = if (active) it.level else 0f, message = message) }
            }
        } })
    }
    private fun stopCapture(keepEnhancer: Boolean = false) {
        captureEpoch++; platform.stopAudio()
        captureFrames?.cancel(); captureFrames = null
        captureWatchdog?.cancel(); captureWatchdog = null
        audioJob?.cancel(); audioJob = null
        if (!keepEnhancer) { enhancer?.close(); enhancer = null }
    }
    private fun requestReference() {
        val s = session ?: return
        val wanted = options.echo && s.supportsReference && !referencePaused && platform.monotonicMicros() >= referenceRetryAt
        if (referenceRequested == wanted) return
        referenceRequested = wanted
        platform.sendControl(ControlJson.encode(mapOf("type" to "reference", "enabled" to if (wanted) "1" else "0")))
    }
    private fun pauseReference() {
        referenceRetryAt = platform.monotonicMicros() + 5000000
        requestReference(); enhancer?.releaseProcessing()
    }
    fun setNoiseSuppression(value: Boolean) { scope.launch {
        options = options.copy(noise = value)
        update { it.copy(noiseSuppression = value, enhancementMessage = if (value || options.echo) "等待音频增强" else "增强已关闭") }
        if (!options.noise && !options.echo) enhancer?.releaseProcessing()
    } }
    fun setDeskNoiseSuppression(value: Boolean) { scope.launch {
        options = options.copy(desk = value)
        update { it.copy(deskNoiseSuppression = value, enhancementMessage = if (options.noise) "等待应用降噪场景" else it.enhancementMessage) }
    } }
    fun setInputGainDb(value: Float) { scope.launch {
        inputGainDb = if (value.isFinite()) value.coerceIn(0f, 18f) else 0f
        update { it.copy(inputGainDb = inputGainDb) }
    } }
    fun setEchoCancellation(value: Boolean) { scope.launch {
        options = options.copy(echo = value); referenceRetryAt = 0L; requestReference()
        update { it.copy(echoCancellation = value, enhancementMessage = if (value || options.noise) "等待音频增强" else "增强已关闭") }
        if (!options.noise && !options.echo) enhancer?.releaseProcessing()
    } }
    fun setMuted(value: Boolean) { scope.launch { muted = value; update { it.copy(muted = value) } } }
    fun retryMicrophone() { scope.launch { if (session != null && state.value.phase == ConnectionPhase.CONNECTED) beginAudio() } }
    fun disconnect() {
        scope.launch {
            if (intentionalRelease || releaseJob?.isActive == true) return@launch
            val s = session
            val events = activeEvents
            val previousConnection = connectionJob; connectionJob = null
            intentionalRelease = true
            clearLocal("已断开连接", closeControl = false, forgetLease = false)
            val abandoned = s?.let { AbandonedOwner(it.pc, it.clientId, it.token) }
            if (abandoned != null) {
                pendingReleases.removeAll { it.pc.host == abandoned.pc.host && it.pc.port == abandoned.pc.port }
                if (pendingReleases.size >= 4) pendingReleases.removeFirst()
                pendingReleases.addLast(abandoned)
                leases.remember(ConnectionLeases.Lease(abandoned.pc.host, abandoned.pc.port, abandoned.clientId, abandoned.token, s?.instance.orEmpty(), releasePending = true))
            }
            releaseJob = scope.launch {
                try {
                    previousConnection?.cancelAndJoin()
                    if (abandoned != null && notifyRelease(abandoned, events)) {
                        pendingReleases.remove(abandoned); leases.forget(abandoned.pc)
                    }
                } finally {
                    withContext(NonCancellable) { previousConnection?.cancelAndJoin() }
                    events?.close(); platform.closeControl(); intentionalRelease = false
                }
            }
        }
    }
    private suspend fun notifyRelease(owner: AbandonedOwner, existing: Channel<Event>? = null): Boolean {
        val lines = existing ?: Channel(8)
        val deadline = platform.monotonicMicros() + 200000
        return try {
            withTimeoutOrNull(200) {
                if (existing == null) {
                    val ready = CompletableDeferred<Unit>()
                    platform.openControl(owner.pc.host, owner.pc.port, { ready.complete(Unit) }, { line ->
                        runCatching { ControlJson.decode(line) }.onSuccess { lines.trySend(Event.Line(it)) }
                    }, { ready.completeExceptionally(PairError("unreachable")); lines.trySend(Event.Closed("unreachable")) },
                        { ready.completeExceptionally(PairError("unreachable")); lines.trySend(Event.Closed("unreachable")) })
                    ready.await()
                }
                platform.sendControl(ControlJson.encode(mapOf("type" to "disconnect", "clientId" to owner.clientId, "token" to owner.token)))
                while (true) {
                    val reply = next(lines)
                    if (reply["type"] == "disconnected") {
                        reply["closeId"]?.let { platform.finishControl(ControlJson.encode(mapOf("type" to "disconnect_ack", "closeId" to it)),
                            ((deadline - platform.monotonicMicros()) / 1000).toInt().coerceIn(1, 200)) }
                        return@withTimeoutOrNull true
                    }
                    if (reply["type"] == "error" && reply["reason"] == "not_owner") return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE") false
            } ?: false
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
        finally { lines.close(); platform.closeControl() }
    }
    private fun clearLocal(message: String, closeControl: Boolean = true, forgetLease: Boolean = true) {
        val old = session; session = null; activeEvents = null
        mutableState.value = MicState(message = message, muted = muted, noiseSuppression = options.noise,
            echoCancellation = options.echo, deskNoiseSuppression = options.desk, inputGainDb = inputGainDb)
        stopCapture(); platform.stopDiscovery(); platform.closeAudio(); if (closeControl) platform.closeControl()
        old?.key?.fill(0); clock = ClockSync(); referenceRequested = false; referencePaused = false
        referenceFailure = ""; referenceWaitDetail = ""; referenceRetryAt = 0L
        if (forgetLease) old?.let { leases.forget(it.pc) }
    }
    fun close() {
        enhancer?.close(); enhancer = null; scope.cancel(); platform.stopDiscovery(); platform.stopAudio(); platform.closeAudio(); platform.closeControl()
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

internal fun referenceLookaheadMicros(capturedAt: Long, now: Long, enabled: Boolean): Long =
    if (!enabled || capturedAt <= 0 || capturedAt > now + 20000) 0
    else (100000 - (now - capturedAt)).coerceIn(0, 100000)
