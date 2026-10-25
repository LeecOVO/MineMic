package com.aiaixyz.minemic.audio

import kotlin.math.roundToInt

/** PCM16 / 48 kHz delays of the pinned WebRTC 2.1 and RNNoise 0.2 backend.
 * These are DSP sample alignment values, not device or acoustic latency.
 * The native impulse regression must be updated when replacing that backend. */
internal data class EnhancementTiming(
    val echoDelaySamples: Int = 430,
    val noiseDelaySamples: Int = 334,
    // RNNoise 0.2: analysis overlap plus delayed_X lookahead, two 10 ms frames.
    val deskNoiseDelaySamples: Int = 960,
    val playoutDelaySamples: Int = 1920,
)

internal class RawAudioHistory {
    private val samples = ShortArray(8192)
    var position = 0L; private set
    private var first = 0L
    fun clear() { first = position }
    fun push(pcm: ByteArray): Long {
        val at = position
        repeat(480) { samples[((position+it) % samples.size).toInt()] = pcmSample(pcm, it).toShort() }
        position += 480
        return at
    }
    fun read(at: Long): ByteArray = ByteArray(960).also { pcm ->
        repeat(480) { i ->
            val p = at+i
            val value = if (p >= first && p < position && position-p <= samples.size)
                samples[(p % samples.size).toInt()].toInt() else 0
            putPcmSample(pcm, i, value)
        }
    }
}

/** A fixed playout horizon lets late AEC results replace dry samples before
 * they are sent. Tags prohibit replay or rewriting already emitted audio. */
internal class AudioOutputTimeline {
    private val samples = ShortArray(8192)
    private val tags = LongArray(8192) { Long.MIN_VALUE }
    private var lastRead = -1L
    private var mix = 0.0
    private var lastWet = false
    private var lastOutput = 0
    private var lastDry = 0
    private var releaseCorrection = 0.0
    private var releaseSamples = 0
    fun clear() {
        tags.fill(Long.MIN_VALUE); lastRead = -1; mix = 0.0; lastWet = false
        lastOutput = 0; lastDry = 0; releaseSamples = 0
    }
    fun offer(at: Long, pcm: ByteArray, minimumPosition: Long = 0) {
        if (pcm.size != 960) return
        repeat(480) { i ->
            val p = at+i
            if (p >= minimumPosition && p >= 0 && p > lastRead && (lastRead < 0 || p-lastRead < samples.size)) {
                val slot = (p % samples.size).toInt()
                samples[slot] = pcmSample(pcm, i).toShort(); tags[slot] = p
            }
        }
    }
    data class Output(val pcm: ByteArray, val echoSamples: Int)
    fun read(at: Long, dry: ByteArray): Output {
        val pcm = ByteArray(960); var covered = 0
        repeat(480) { i ->
            val p = at+i; val raw = pcmSample(dry, i)
            val slot = if (p >= 0) (p % samples.size).toInt() else 0
            val wet = p >= 0 && tags[slot] == p
            val value: Double
            if (wet) {
                covered++; mix = (mix + 1.0/240).coerceAtMost(1.0)
                value = raw*(1-mix) + samples[slot]*mix
                releaseSamples = 0
            } else {
                if (lastWet) { releaseCorrection = (lastOutput-lastDry).toDouble(); releaseSamples = 240 }
                value = raw + releaseCorrection * releaseSamples / 240
                if (releaseSamples > 0) releaseSamples--
                mix = 0.0
            }
            val chosen = value.roundToInt().coerceIn(-32768, 32767)
            putPcmSample(pcm, i, chosen)
            lastOutput = chosen; lastDry = raw; lastWet = wet
        }
        lastRead = maxOf(lastRead, at+479)
        return Output(pcm, covered)
    }
}

private fun pcmSample(pcm: ByteArray, i: Int): Int =
    ((pcm[2*i].toInt() and 255) or (pcm[2*i+1].toInt() shl 8)).toShort().toInt()
private fun putPcmSample(pcm: ByteArray, i: Int, value: Int) {
    pcm[2*i] = value.toByte(); pcm[2*i+1] = (value shr 8).toByte()
}
