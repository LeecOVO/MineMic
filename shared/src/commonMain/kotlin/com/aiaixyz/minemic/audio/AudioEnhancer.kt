package com.aiaixyz.minemic.audio

import com.aiaixyz.minemic.connection.CapturedAudio
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

internal data class EnhancementOptions(val noise: Boolean = false, val echo: Boolean = false, val desk: Boolean = false)
internal data class EnhancementResult(val pcm: ByteArray, val message: String, val echo: Boolean = false)

/** Native APM cannot block the sender: a separate worker and bounded deadlines. */
internal class AudioEnhancer(
    private val session: Long, private val key: ByteArray,
    private val now: () -> Long, private val hmac: (ByteArray, ByteArray) -> ByteArray,
    private val factory: () -> AudioProcessor? = ::createAudioProcessor,
) {
    private data class Input(val frame: CapturedAudio, val options: EnhancementOptions,
                             val mapping: ClockSync.Mapping?, val deadline: Long,
                             val result: CompletableDeferred<EnhancementResult?>,
                             val referenceDetail: String = "")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val references = Channel<ByteArray>(32, BufferOverflow.DROP_OLDEST)
    private val work = Channel<Input>(1, BufferOverflow.DROP_OLDEST, onUndeliveredElement = { it.result.complete(null) })
    private var processor: AudioProcessor? = null // Worker only.
    private var nextFactoryAt = 0L
    private val partial = mutableMapOf<Long, ReferenceChannel>()
    private data class Stereo(val sequence: Long, val position: Long, val time: Long, val epoch: Long, val pcm: ByteArray)
    private val ready = mutableListOf<Stereo>()
    private var lastReference: Stereo? = null
    private var echoRunning = false
    private var concealedReferenceFrames = 0
    private var referenceRate = ReferenceRateTracker()
    private var adaptiveNextTime: Double? = null
    private var adaptiveCaptureTime: Long? = null
    private val deferredEchoCaptures = ArrayDeque<Pair<Long, ByteArray>>()
    private var lastValidReferenceAt: Long? = null
    private val lastAuthenticatedReferenceAt = MutableStateFlow<Long?>(null)
    private var clockOffset: Long? = null
    private var referenceEpoch: Long? = null
    private val retiredEpochs = ArrayDeque<Long>()
    init {
        scope.launch {
            try {
                for (input in work) {
                    if (!input.result.isActive || now() >= input.deadline) { input.result.complete(null); continue }
                    val output = try { process(input) } catch (_: Exception) { null }
                    input.result.complete(if (now() <= input.deadline) output else null)
                }
            } finally { processor?.close(); processor = null; key.fill(0) }
        }
    }
    fun hasRecentReference(at: Long): Boolean = lastAuthenticatedReferenceAt.value?.let { at - it in 0..1500000 } == true
    fun offerReference(bytes: ByteArray) { if (bytes.size == 1040) references.trySend(bytes) }
    suspend fun enhance(frame: CapturedAudio, options: EnhancementOptions, mapping: ClockSync.Mapping?, referenceDetail: String = ""): EnhancementResult {
        if (!options.noise && !options.echo) return EnhancementResult(frame.pcm, "增强已关闭")
        val started = now()
        // AEC may wait for a remote reference, but never grow microphone latency indefinitely.
        // Hardware may hand off several frames at once. Their capture timestamps stay
        // historical; they must not expire a processing deadline before work even starts.
        val deadline = started + if (options.echo && mapping != null && started - frame.capturedAtMicros in -20000..40000) 20000 else 8000
        val result = CompletableDeferred<EnhancementResult?>()
        work.trySend(Input(frame, options, mapping, deadline, result, referenceDetail))
        val output = withTimeoutOrNull(maxOf(1, (deadline - started + 999) / 1000)) { result.await() }
        result.cancel()
        return output ?: EnhancementResult(frame.pcm, "增强暂不可用，已回退原始音频")
    }
    private fun ingest(bytes: ByteArray) {
        val channel = ReferenceAudio.decode(bytes, session, key, hmac) ?: return
        lastAuthenticatedReferenceAt.value = now()
        val offset = clockOffset ?: return
        val pcNow = now() + offset
        if (channel.timeMicros > pcNow + 20000 || pcNow - channel.timeMicros > 200000 || channel.epoch in retiredEpochs) return
        if (referenceEpoch != channel.epoch) {
            referenceEpoch?.let { retiredEpochs.addLast(it); if (retiredEpochs.size > 8) retiredEpochs.removeFirst() }
            referenceEpoch = channel.epoch; partial.clear(); ready.clear()
            processor?.resetEcho(); echoRunning = false; lastReference = null; concealedReferenceFrames = 0
            resetAdaptiveClock()
        }
        val last = lastReference
        if (last != null && channel.epoch == last.epoch && channel.position <= last.position) return
        val other = partial.remove(channel.sequence)
        if (other == null) {
            if (partial.size >= 16) partial.remove(partial.keys.first())
            partial[channel.sequence] = channel; return
        }
        if (other.channel == channel.channel || other.position != channel.position || other.timeMicros != channel.timeMicros || other.epoch != channel.epoch) return
        val stereo = ByteArray(1920)
        for (part in listOf(channel, other)) for (i in 0 until 480) {
            stereo[4*i + 2*part.channel] = part.pcm[2*i]
            stereo[4*i + 2*part.channel + 1] = part.pcm[2*i + 1]
        }
        if (ready.none { it.epoch == channel.epoch && it.position == channel.position }) {
            // A single channel or replay is not usable AEC input and must not
            // keep an old filter alive indefinitely when its partner is lost.
            lastValidReferenceAt = now()
            ready += Stereo(channel.sequence, channel.position, channel.timeMicros, channel.epoch, stereo)
            ready.sortBy { it.time }; if (ready.size > 24) ready.removeAt(0)
        }
    }
    private fun drain() { repeat(32) { ingest(references.tryReceive().getOrNull() ?: return) } }
    private suspend fun process(input: Input): EnhancementResult? {
        clockOffset = input.mapping?.pcMinusPhone
        if (!input.options.noise && !input.options.echo) {
            processor?.close(); processor = null; lastReference = null; echoRunning = false; concealedReferenceFrames = 0
            resetAdaptiveClock()
            drain(); partial.clear(); ready.clear(); lastValidReferenceAt = null; lastAuthenticatedReferenceAt.value = null
            return EnhancementResult(input.frame.pcm, "增强已关闭")
        }
        drain()
        val options = input.options
        val offset = input.mapping?.pcMinusPhone
        if (options.echo && offset != null) {
            val apm = processor ?: if (ready.isNotEmpty() && now() >= nextFactoryAt) {
                factory()?.also { processor = it } ?: run { nextFactoryAt = now() + 1000000; null }
            } else null
            if (apm?.supportsReferenceResampling == true) return processAdaptive(input, apm, offset)
        }
        var reference: Stereo? = null
        if (options.echo && offset != null) {
            val target = input.frame.capturedAtMicros + offset
            fun select(): Stereo? = ready.minByOrNull { abs(it.time - target) }?.takeIf { abs(it.time - target) <= 8000 }
            reference = select()
            // No retransmission. Spend at most this frame's remaining budget waiting for UDP.
            val reserve = if (options.noise) 8000 else 3000
            while (reference == null && lastValidReferenceAt?.let { now() - it < 75000 } == true && now() + reserve < input.deadline) {
                val bytes = withTimeoutOrNull(maxOf(1, (input.deadline - now() - reserve) / 1000)) { references.receive() } ?: break
                ingest(bytes); reference = select()
            }
        }
        if (reference == null && echoRunning && lastValidReferenceAt?.let { now() - it > 250000 } == true) {
            processor?.resetEcho(); echoRunning = false; lastReference = null; concealedReferenceFrames = 0
        }
        if (reference == null && options.echo && offset != null && echoRunning && concealedReferenceFrames < 3) {
            val last = lastReference
            val step = last?.let { input.frame.capturedAtMicros + offset - it.time }
            if (last != null && step != null && step in 2000..18000 &&
                lastValidReferenceAt?.let { now() - it in 0..75000 } == true) {
                // Up to 30 ms of render packet loss advances the internal AEC
                // clock with silence. Discard its output: the caller still gets
                // raw/NS audio, never an unverified echo-cancelled frame.
                if (processor?.advanceEcho(input.frame.pcm) == true) {
                    lastReference = last.copy(sequence = last.sequence + 1, position = last.position + 480,
                        time = last.time + 10000, pcm = missingRenderFrame)
                    concealedReferenceFrames++
                } else {
                    processor?.resetEcho(); echoRunning = false; lastReference = null; concealedReferenceFrames = 0
                }
            }
        }
        val referenceMessage = when {
            input.frame.capturedAtMicros <= 0 -> "录音时间戳暂不可用，回声抑制等待同步"
            input.mapping == null -> "正在同步 PC 与手机音频时钟"
            hasRecentReference(now()) -> "已收到 PC 外放参考，正在对齐音频"
            input.referenceDetail.isNotBlank() -> "PC 外放参考：${input.referenceDetail}"
            else -> "等待 PC 外放参考；请确认电脑正在播放声音及防火墙允许回传"
        }
        val needsProcessor = options.noise || reference != null
        if (!needsProcessor) return EnhancementResult(input.frame.pcm, "$referenceMessage；原始音频继续传输")
        val apm = processor ?: run {
            if (now() < nextFactoryAt) return null
            factory()?.also { processor = it } ?: run { nextFactoryAt = now() + 1000000; return null }
        }
        var echo = false
        if (reference != null) {
            val previous = lastReference
            // A capture frame may have bypassed AEC while UDP was delayed. Feed
            // intervening complete render frames in order instead of discarding
            // them and resetting adaptation on every small network hiccup.
            val pending = if (previous == null) listOf(reference) else ready.filter {
                it.epoch == reference.epoch && it.position > previous.position && it.position <= reference.position
            }.sortedBy { it.position }
            for (render in pending) {
                val last = lastReference
                if (last != null && (last.epoch != render.epoch || render.position != last.position + 480)) apm.resetEcho()
                echo = apm.render(render.pcm)
                lastReference = render
            }
            echoRunning = echo
            concealedReferenceFrames = 0
            ready.removeAll { it.epoch == reference.epoch && it.position <= reference.position }
        }
        apm.setDeskNoise(options.noise && options.desk)
        val pcm = apm.process(input.frame.pcm, options.noise, echo) ?: run {
            apm.close(); processor = null; echoRunning = false; lastReference = null; concealedReferenceFrames = 0
            nextFactoryAt = now() + 1000000; return null
        }
        val noiseName = if (options.desk) "桌面游戏降噪" else "降噪"
        val echoProblem = if (reference != null && !echo) "回声处理器暂不可用" else referenceMessage
        val message = when {
            echo && options.noise -> "回声抑制 + ${noiseName}处理中"
            echo -> "回声抑制处理中"
            options.echo && options.noise -> "${noiseName}处理中；$echoProblem"
            options.echo -> "$echoProblem；原始音频继续传输"
            else -> "${noiseName}处理中"
        }
        return EnhancementResult(pcm, message, echo)
    }
    private fun resetAdaptiveClock() {
        referenceRate = ReferenceRateTracker(); adaptiveNextTime = null; adaptiveCaptureTime = null
        deferredEchoCaptures.clear()
    }
    private suspend fun processAdaptive(input: Input, apm: AudioProcessor, offset: Long): EnhancementResult {
        val options = input.options
        val target = input.frame.capturedAtMicros + offset
        val previousCapture = adaptiveCaptureTime
        if ((previousCapture != null && target - previousCapture !in 5000..15000) ||
            (echoRunning && lastValidReferenceAt?.let { now() - it > 250000 } == true)) {
            apm.resetEcho(); lastReference = null; echoRunning = false; resetAdaptiveClock()
        }
        adaptiveCaptureTime = target
        val ratio = referenceRate.capture(target)
        fun feed() {
            if (adaptiveNextTime == null) {
                val first = ready.minByOrNull { abs(it.time - target) }?.takeIf { abs(it.time - target) <= 8000 } ?: return
                ready.removeAll { it.position < first.position }
            }
            val available = ready.filter { it.time <= target + 20000 }.sortedBy { it.position }
            for (render in available) {
                val last = lastReference
                if (last != null) {
                    val distance = render.position - last.position
                    if (distance <= 0) continue
                    if (last.epoch != render.epoch || distance % 480 != 0L || distance > 1920 ||
                        abs(render.time - last.time - distance * 1000000 / 48000) > 3000) {
                        apm.resetEcho(); lastReference = null; echoRunning = false; resetAdaptiveClock()
                    } else {
                        for (missing in 0 until (distance / 480 - 1).toInt()) {
                            // Only advance the internal filter over a short known
                            // gap. Sinc marks every affected output unpublishable.
                            if (!apm.bufferReference(missingRenderFrame, valid = false)) {
                                apm.resetEcho(); lastReference = null; echoRunning = false; resetAdaptiveClock()
                                break
                            }
                        }
                    }
                }
                if (adaptiveNextTime == null) adaptiveNextTime = render.time.toDouble()
                if (!apm.bufferReference(render.pcm)) {
                    apm.resetEcho(); lastReference = null; echoRunning = false; resetAdaptiveClock(); break
                }
                referenceRate.render(render.position, render.time)
                lastReference = render
            }
            lastReference?.let { last -> ready.removeAll { it.epoch == last.epoch && it.position <= last.position } }
        }
        fun aligned(): ResampledReference? {
            repeat(4) {
                val next = adaptiveNextTime ?: return null
                if (next > target + 20000) return null
                val reference = apm.readReference(ratio) ?: return null
                adaptiveNextTime = next + referenceRate.outputDurationMicros
                if (next >= target - 8000) return reference
                // A raw frame may already have been uploaded before its reference
                // arrived. Adapt using that actual capture, without delaying or
                // retransmitting it. Only unavailable history uses a clock bridge.
                val deferred = deferredEchoCaptures.minByOrNull { abs(it.first - next.toLong()) }
                    ?.takeIf { abs(it.first - next.toLong()) <= 8000 }
                val capture = deferred?.second ?: missingCaptureFrame
                deferred?.let { used -> while (deferredEchoCaptures.firstOrNull()?.first?.let { it <= used.first } == true) deferredEchoCaptures.removeFirst() }
                if (!apm.render(reference.pcm) || apm.process(capture, false, true) == null) {
                    apm.resetEcho(); lastReference = null; echoRunning = false; resetAdaptiveClock(); return null
                }
            }
            return null
        }
        feed()
        var reference = aligned()
        val reserve = if (options.noise) 8000 else 3000
        var received = 0
        while (reference == null && received++ < 32 && lastValidReferenceAt?.let { now() - it < 75000 } == true && now() + reserve < input.deadline) {
            val bytes = withTimeoutOrNull(maxOf(1, (input.deadline - now() - reserve) / 1000)) { references.receive() } ?: break
            ingest(bytes); feed(); reference = aligned()
        }
        apm.setDeskNoise(options.noise && options.desk)
        if (reference != null && apm.render(reference.pcm)) {
            val pcm = apm.process(input.frame.pcm, noise = options.noise && reference.valid, echo = true)
            if (pcm != null) {
                echoRunning = true
                if (reference.valid) return EnhancementResult(pcm,
                    if (options.noise) "回声抑制 + ${if (options.desk) "桌面游戏降噪" else "降噪"}处理中" else "回声抑制处理中", true)
            } else {
                apm.resetEcho(); lastReference = null; echoRunning = false; resetAdaptiveClock()
            }
        }
        if (reference == null && adaptiveNextTime != null) {
            if (deferredEchoCaptures.size >= 3) deferredEchoCaptures.removeFirst()
            deferredEchoCaptures.addLast(target to input.frame.pcm.copyOf())
        }
        val fallback = if (options.noise) apm.process(input.frame.pcm, noise = true, echo = false) ?: input.frame.pcm else input.frame.pcm
        val detail = when {
            reference != null && !reference.valid -> "参考短暂丢失，正在恢复对齐"
            hasRecentReference(now()) -> "已收到 PC 外放参考，正在对齐音频"
            input.referenceDetail.isNotBlank() -> "PC 外放参考：${input.referenceDetail}"
            else -> "等待 PC 外放参考"
        }
        return EnhancementResult(fallback, "$detail；${if (options.noise) "降噪" else "原始音频"}继续传输")
    }
    fun releaseProcessing() {
        work.trySend(Input(CapturedAudio(ByteArray(0), 0), EnhancementOptions(), null, Long.MAX_VALUE, CompletableDeferred()))
    }
    fun close() { work.close(); references.close(); scope.cancel() }
}

private val missingRenderFrame = ByteArray(1920)
private val missingCaptureFrame = ByteArray(960)
