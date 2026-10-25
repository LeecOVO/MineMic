package com.aiaixyz.minemic.audio

import com.aiaixyz.minemic.connection.CapturedAudio
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertTrue

// Reuse the mobile JNI bridge in an opt-in host fixture. Desktop previews still
// have no native processor; this class belongs only to JVM tests.
private object NativeApm {
    external fun create(): Long
    external fun destroy(handle: Long)
    external fun render(handle: Long, pcm: ByteArray): Boolean
    external fun process(handle: Long, pcm: ByteArray, noise: Boolean, echo: Boolean): ByteArray?
    external fun resetEcho(handle: Long)
    external fun setDeskNoise(handle: Long, enabled: Boolean)
    external fun bufferReference(handle: Long, pcm: ByteArray, valid: Boolean): Boolean
    external fun readReference(handle: Long, ratio: Double): ByteArray?
}

class NativeEchoPipelineTest {
    private val key = ByteArray(16) { (it + 1).toByte() }
    private fun hmac(body: ByteArray, secret: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256")); doFinal(body)
    }
    private fun put(pcm: ByteArray, offset: Int, value: Int) {
        pcm[offset] = value.toByte(); pcm[offset + 1] = (value shr 8).toByte()
    }
    private fun reference(channel: Int, block: Int, time: Long, samples: IntArray): ByteArray {
        val bytes = ByteArray(1008)
        "MREF".encodeToByteArray().copyInto(bytes); bytes[4] = 1; bytes[5] = 2; bytes[6] = channel.toByte()
        fun field(offset: Int, value: Long, count: Int = 4) {
            repeat(count) { bytes[offset + it] = (value ushr (8 * (count - it - 1))).toByte() }
        }
        field(8, 7); field(12, block.toLong()); field(16, block * 480L, 8); field(24, time, 8)
        field(32, 48000); field(36, 480, 2); field(38, 960, 2); field(40, 1)
        repeat(480) { put(bytes, 48 + it * 2, samples[block * 480 + it]) }
        return bytes + hmac(bytes, key)
    }
    @Test fun timestampedHardwareBatchesReachRealWebRtcAndSuppressEcho() = exercise(dropEvery = 0)
    @Test fun isolatedLostReferenceChannelsDoNotContinuouslyResetRealWebRtc() = exercise(dropEvery = 43)
    @Test fun adaptiveReferenceHandlesFastIndependentClockAndChannelLoss() = exercise(dropEvery = 43, adaptive = true, ppm = 200, blocks = 6000)
    @Test fun adaptiveReferenceHandlesSlowIndependentClock() = exercise(dropEvery = 0, adaptive = true, ppm = -200, blocks = 6000)
    @Test fun adaptiveReferencePreservesSyntheticNearEndDuringDoubleTalk() = exercise(dropEvery = 43, adaptive = true, ppm = 200, blocks = 3000, doubleTalk = true)
    @Test fun baselinePreservesSyntheticNearEndDuringDoubleTalk() = exercise(dropEvery = 43, blocks = 3000, doubleTalk = true)

    private fun exercise(dropEvery: Int, adaptive: Boolean = false, ppm: Int = 0, blocks: Int = 1200, doubleTalk: Boolean = false) = runBlocking {
        val library = System.getenv("MINEMIC_APM_TEST_LIBRARY")
        assumeTrue("Run tests/native-audio/run-pipeline.sh for real WebRTC pipeline checks", !library.isNullOrEmpty())
        System.load(library)
        val time = AtomicLong(1000000)
        val resets = AtomicInteger()
        val enhancer = AudioEnhancer(7, key.copyOf(), time::get, ::hmac) {
            val handle = NativeApm.create()
            if (handle == 0L) null else object : AudioProcessor {
                override val supportsReferenceResampling = adaptive
                override fun bufferReference(stereo: ByteArray, valid: Boolean) = NativeApm.bufferReference(handle, stereo, valid)
                override fun readReference(ratio: Double) = NativeApm.readReference(handle, ratio)?.let {
                    ResampledReference(it.copyOf(1920), it[1920] == 1.toByte())
                }
                override fun setDeskNoise(enabled: Boolean) = NativeApm.setDeskNoise(handle, enabled)
                override fun render(stereo: ByteArray) = NativeApm.render(handle, stereo)
                override fun process(mono: ByteArray, noise: Boolean, echo: Boolean) = NativeApm.process(handle, mono, noise, echo)
                override fun resetEcho() { resets.incrementAndGet(); NativeApm.resetEcho(handle) }
                override fun close() = NativeApm.destroy(handle)
            }
        }
        val rate = 1.0 + ppm / 1000000.0
        val left = IntArray(((blocks + 20) * rate).toInt() * 480); val right = IntArray(left.size)
        var seed = 7
        fun noise(): Double { seed = seed * 1664525 + 1013904223; return (seed shr 16) / 32768.0 }
        var smoothL = 0.0; var smoothR = 0.0
        for (i in left.indices) {
            smoothL = .6 * smoothL + .4 * noise(); smoothR = .6 * smoothR + .4 * noise()
            left[i] = (smoothL * 11000).toInt(); right[i] = (smoothR * 11000).toInt()
        }
        val start = time.get(); val offset = 200000000L
        var inputPower = 0.0; var outputPower = 0.0; var processed = 0
        val near = if (doubleTalk) IntArray(blocks * 480) else IntArray(0)
        val observed = if (doubleTalk) IntArray(blocks * 480) else IntArray(0)
        val enhanced = BooleanArray(blocks)
        var smoothNear = 0.0
        for (i in near.indices) {
            smoothNear = .85 * smoothNear + .15 * noise()
            if (i >= blocks * 3 / 4 * 480) {
                // Same voiced/enveloped double-talk fixture as the native APM
                // regression, with independent broadband content added.
                val seconds = i / 48000.0
                near[i] = ((.55 + .45 * sin(seconds * 7.1)) * 4500 *
                    (sin(seconds * 2 * PI * 173) + .35 * sin(seconds * 2 * PI * 346)) + smoothNear * 800).toInt()
            }
        }
        fun nearEnd(sample: Int) = if (doubleTalk) near[sample] else 0
        var nextReference = 0
        try {
            for (batch in 0 until blocks step 10) {
                // Both hardware and UDP may arrive in batches. Keep the actual
                // sample timestamps, and reorder stereo channels in transit.
                time.set(start + (batch + 9) * 10000L)
                val due = if (adaptive) (((batch + 9) * rate).toInt() + 1) else batch + 10
                for (block in nextReference until due)
                    enhancer.offerReference(reference(0, block, start + (block * 10000 / rate).toLong() + offset, left))
                for (block in due - 1 downTo nextReference) if (dropEvery == 0 || block == 0 || block % dropEvery != 0)
                    enhancer.offerReference(reference(1, block, start + (block * 10000 / rate).toLong() + offset, right))
                nextReference = due
                for (block in batch until batch + 10) {
                    val pcm = ByteArray(960)
                    repeat(480) { i ->
                        val t = block * 480 + i
                        fun at(samples: IntArray, position: Double): Double {
                            val index = position.toInt(); val fraction = position - index
                            return samples[index] * (1 - fraction) + samples[index + 1] * fraction
                        }
                        val echo = if (t >= 2500) (.5 * at(left, (t - 2400) * rate) + .3 * at(right, (t - 2400) * rate) + .08 * at(left, (t - 2500) * rate)).toInt() else 0
                        put(pcm, i * 2, echo + nearEnd(t))
                    }
                    val result = enhancer.enhance(CapturedAudio(pcm, start + block * 10000L),
                        EnhancementOptions(echo = true), ClockSync.Mapping(offset, time.get(), 1000))
                    if (result.echo) processed++
                    enhanced[block] = result.echo
                    if (block > blocks * 3 / 4) repeat(480) { i ->
                        fun sample(bytes: ByteArray) = ((bytes[i * 2].toInt() and 255) or (bytes[i * 2 + 1].toInt() shl 8)).toShort().toInt()
                        val input = sample(pcm).toDouble(); val output = sample(result.pcm).toDouble()
                        inputPower += input * input; outputPower += output * output
                        if (doubleTalk) observed[block * 480 + i] = output.toInt()
                    }
                }
            }
            val attenuation = 10 * log10(inputPower / maxOf(1.0, outputPower))
            println("Real WebRTC through authenticated Kotlin pipeline: adaptive=$adaptive, ppm=$ppm, dropEvery=$dropEvery, processed=$processed/$blocks, resets=${resets.get()}, attenuation=$attenuation dB")
            assertTrue(processed > blocks * if (adaptive) .70 else .83, "reference should reach real AEC for most capture frames")
            assertTrue(resets.get() <= 2, "isolated lost UDP channels must not repeatedly destroy the native echo filter")
            if (doubleTalk) {
                // Account for DSP algorithm delay; raw bypass is checked
                // separately and must not be correlated at an AEC output lag.
                var correlation = 0.0; var gain = 0.0; var latencySamples = 0
                for (delay in 0..960) {
                    var cross = 0.0; var nearPower = 0.0; var outPower = 0.0
                    for (sample in blocks * 3 / 4 * 480 + 48000 until observed.size step 4) if (enhanced[sample / 480]) {
                        val a = near[sample - delay].toDouble(); val b = observed[sample].toDouble()
                        cross += a*b; nearPower += a*a; outPower += b*b
                    }
                    val candidate = cross / sqrt(maxOf(1.0, nearPower * outPower))
                    if (candidate > correlation) { correlation = candidate; gain = cross / maxOf(1.0, nearPower); latencySamples = delay }
                }
                println("Synthetic voiced double-talk: correlation=$correlation, gain=$gain, alignmentSearchLag=$latencySamples samples (not device latency)")
                assertTrue(correlation > .60 && gain in .5..1.4, "near-end signal must survive adaptive echo processing")
            } else assertTrue(attenuation > if (dropEvery == 0 && !adaptive) 10.0 else 6.0,
                "timestamped capture and network jitter must still suppress synthetic echo; attenuation=$attenuation dB")
        } finally { enhancer.close() }
    }
}
