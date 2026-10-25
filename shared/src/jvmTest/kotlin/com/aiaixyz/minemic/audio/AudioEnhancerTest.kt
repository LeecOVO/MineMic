package com.aiaixyz.minemic.audio

import com.aiaixyz.minemic.connection.CapturedAudio
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class AudioEnhancerTest {
    private fun now() = System.nanoTime() / 1000
    private val key = ByteArray(16) { it.toByte() }
    private fun hmac(body: ByteArray, secret: ByteArray) = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(secret, "HmacSHA256")); doFinal(body)
    }
    private fun reference(channel: Int, time: Long, sequence: Long = 0, position: Long = sequence * 480): ByteArray {
        val body = ByteArray(1008)
        "MREF".encodeToByteArray().copyInto(body); body[4] = 1; body[5] = 2; body[6] = channel.toByte()
        fun put(offset: Int, value: Long, size: Int = 4) { repeat(size) { body[offset + it] = (value ushr ((size - it - 1)*8)).toByte() } }
        put(8, 7); put(12, sequence); put(16, position, 8); put(24, time, 8)
        put(32, 48000); put(36, 480, 2); put(38, 960, 2); put(40, 3)
        repeat(480) { body[48 + 2*it] = if (channel == 0) 11 else 22 }
        return body + hmac(body, key)
    }
    @Test fun disabledIsBitExactAndDoesNotConstructNativeProcessor() = runBlocking {
        var factories = 0
        val enhancer = AudioEnhancer(7, key, ::now, ::hmac) { factories++; null }
        try {
            val pcm = ByteArray(960) { it.toByte() }
            repeat(100) { assertSame(pcm, enhancer.enhance(CapturedAudio(pcm, now()), EnhancementOptions(), null).pcm) }
            assertSame(pcm, enhancer.enhance(CapturedAudio(pcm, now()), EnhancementOptions(desk = true), null).pcm)
            assertEquals(0, factories)
        } finally { enhancer.close() }
    }
    @Test fun codecRejectsWrongSessionTamperingAndOversize() {
        val valid = reference(1, 100000)
        val decoded = assertNotNull(ReferenceAudio.decode(valid, 7, key, ::hmac))
        assertEquals(1, decoded.channel); assertEquals(100000L, decoded.timeMicros)
        assertNull(ReferenceAudio.decode(valid, 8, key, ::hmac))
        assertNull(ReferenceAudio.decode(valid + byteArrayOf(0), 7, key, ::hmac))
        valid[400] = (valid[400].toInt() xor 1).toByte()
        assertNull(ReferenceAudio.decode(valid, 7, key, ::hmac))
    }
    @Test fun captureFailureDetailReplacesGenericPlaybackAdviceWithoutChangingMicrophoneAudio() = runBlocking {
        val time = 1000000L
        val enhancer = AudioEnhancer(7, key, { time }, ::hmac) { error("Missing references must not start AEC") }
        try {
            val raw = ByteArray(960) { 35 }
            val detail = "USB speakers · 已采集，但驱动未提供有效的参考时间"
            val result = enhancer.enhance(CapturedAudio(raw, time), EnhancementOptions(echo = true),
                ClockSync.Mapping(0, time, 1000), detail)
            assertSame(raw, result.pcm); assertFalse(result.echo)
            assertTrue(result.message.contains(detail))
            assertFalse(result.message.contains("请确认电脑正在播放声音"))
        } finally { enhancer.close() }
    }
    @Test fun stereoPairsReorderWithoutMixingAndMissingReferenceBypassesAec() = runBlocking {
        val rendered = AtomicInteger(); val resets = AtomicInteger()
        val enhancer = AudioEnhancer(7, key, ::now, ::hmac) { object : AudioProcessor {
            override fun render(stereo: ByteArray): Boolean {
                repeat(480) { assertEquals(11, stereo[4*it].toInt()); assertEquals(22, stereo[4*it+2].toInt()) }
                rendered.incrementAndGet(); return true
            }
            override fun process(mono: ByteArray, noise: Boolean, echo: Boolean): ByteArray {
                assertTrue(echo); return ByteArray(960) { 77 }
            }
            override fun resetEcho() { resets.incrementAndGet() }
            override fun close() {}
        } }
        try {
            val captureTime = now() - 10000
            enhancer.offerReference(reference(1, captureTime)); enhancer.offerReference(reference(0, captureTime))
            enhancer.offerReference(reference(0, captureTime)) // Duplicate must never become a stereo pair.
            val out = enhancer.enhance(CapturedAudio(ByteArray(960), captureTime), EnhancementOptions(echo = true), ClockSync.Mapping(0, now(), 1000))
            assertEquals(1, rendered.get()); assertContentEquals(ByteArray(960) { 77 }, out.pcm)
            val raw = ByteArray(960) { 42 }
            val missing = enhancer.enhance(CapturedAudio(raw, now() + 90000), EnhancementOptions(echo = true), ClockSync.Mapping(0, now(), 1000))
            assertSame(raw, missing.pcm); assertEquals(0, resets.get(), "one missing reference must not discard AEC adaptation")
        } finally { enhancer.close() }
    }
    @Test fun timestampedHardwareBatchCanUseAlreadyReceivedReference() = runBlocking {
        val rendered = AtomicInteger()
        val logicalNow = 1000000L
        val enhancer = AudioEnhancer(7, key, { logicalNow }, ::hmac) { object : AudioProcessor {
            override fun render(stereo: ByteArray): Boolean { rendered.incrementAndGet(); return true }
            override fun process(mono: ByteArray, noise: Boolean, echo: Boolean) = if (echo) ByteArray(960) { 77 } else mono
            override fun resetEcho() {}
            override fun close() {}
        } }
        try {
            repeat(3) { enhancer.enhance(CapturedAudio(ByteArray(960), logicalNow), EnhancementOptions(noise = true), null) }
            val captured = logicalNow - 90000
            enhancer.offerReference(reference(0, captured)); enhancer.offerReference(reference(1, captured))
            val result = enhancer.enhance(CapturedAudio(ByteArray(960), captured), EnhancementOptions(echo = true), ClockSync.Mapping(0, logicalNow, 1000))
            assertTrue(result.echo, "valid historical hardware frames must not expire before processing starts")
            assertEquals(1, rendered.get())
            assertContentEquals(ByteArray(960) { 77 }, result.pcm)
        } finally { enhancer.close() }
    }
    @Test fun delayedReferenceCatchupDoesNotResetContinuousRenderStream() = runBlocking {
        val rendered = AtomicInteger(); val resets = AtomicInteger()
        val logicalNow = 1000000L
        val enhancer = AudioEnhancer(7, key, { logicalNow }, ::hmac) { object : AudioProcessor {
            override fun render(stereo: ByteArray): Boolean { rendered.incrementAndGet(); return true }
            override fun process(mono: ByteArray, noise: Boolean, echo: Boolean) = mono
            override fun resetEcho() { resets.incrementAndGet() }
            override fun close() {}
        } }
        try {
            repeat(3) { enhancer.enhance(CapturedAudio(ByteArray(960), logicalNow), EnhancementOptions(noise = true), null) }
            val first = logicalNow - 60000
            for (channel in 0..1) enhancer.offerReference(reference(channel, first, 0))
            assertTrue(enhancer.enhance(CapturedAudio(ByteArray(960), first), EnhancementOptions(echo = true), ClockSync.Mapping(0, logicalNow, 1000)).echo)
            val initialResets = resets.get()
            // Reference for the intervening capture arrived late but is complete.
            for (sequence in 1L..2L) for (channel in 0..1) enhancer.offerReference(reference(channel, first + sequence*10000, sequence))
            assertTrue(enhancer.enhance(CapturedAudio(ByteArray(960), first + 20000), EnhancementOptions(echo = true), ClockSync.Mapping(0, logicalNow, 1000)).echo)
            assertEquals(3, rendered.get(), "all continuous render frames reach native AEC")
            assertEquals(initialResets, resets.get(), "a delayed reference must not reset an otherwise continuous stream")
        } finally { enhancer.close() }
    }
    @Test fun slowNativeProcessingCannotHoldSenderOrPublishLateOutput() = runBlocking {
        val entered = AtomicInteger(); val destroyed = AtomicInteger()
        val enhancer = AudioEnhancer(7, key, ::now, ::hmac) { object : AudioProcessor {
            override fun render(stereo: ByteArray) = false
            override fun process(mono: ByteArray, noise: Boolean, echo: Boolean): ByteArray {
                entered.incrementAndGet(); Thread.sleep(100); return ByteArray(960) { 99 }
            }
            override fun resetEcho() {}
            override fun close() { destroyed.incrementAndGet() }
        } }
        try {
            val raw = ByteArray(960) { 15 }; val started = now()
            repeat(12) {
                val out = enhancer.enhance(CapturedAudio(raw, now()), EnhancementOptions(noise = true), null)
                assertSame(raw, out.pcm)
            }
            assertTrue(now() - started < 400000, "sender was blocked by native processing")
            assertTrue(entered.get() <= 3, "unbounded processing backlog")
        } finally { enhancer.close() }
        withTimeout(1000) { while (destroyed.get() == 0) delay(5) }
    }
    @Test fun unpairedReferenceCannotKeepStaleEchoAdaptationAlive() = runBlocking {
        val time = AtomicLong(1000000)
        val resets = AtomicInteger()
        val enhancer = AudioEnhancer(7, key, time::get, ::hmac) { object : AudioProcessor {
            override fun render(stereo: ByteArray) = true
            override fun process(mono: ByteArray, noise: Boolean, echo: Boolean) = mono
            override fun resetEcho() { resets.incrementAndGet() }
            override fun close() {}
        } }
        try {
            for (channel in 0..1) enhancer.offerReference(reference(channel, time.get()))
            assertTrue(enhancer.enhance(CapturedAudio(ByteArray(960), time.get()), EnhancementOptions(echo = true), ClockSync.Mapping(0, time.get(), 1000)).echo)
            val previous = resets.get()
            time.addAndGet(300000)
            // Valid HMAC and timestamp, but only one channel: unusable as AEC input.
            enhancer.offerReference(reference(0, time.get(), 30))
            val result = enhancer.enhance(CapturedAudio(ByteArray(960), time.get()), EnhancementOptions(echo = true), ClockSync.Mapping(0, time.get(), 1000))
            assertFalse(result.echo)
            assertEquals(previous + 1, resets.get(), "only complete stereo references may refresh the missing-reference grace period")
        } finally { enhancer.close() }
    }
    @Test fun failedEchoRenderDoesNotClaimNoiseSuppressionWhenNoiseIsDisabled() = runBlocking {
        val time = 1000000L
        val enhancer = AudioEnhancer(7, key, { time }, ::hmac) { object : AudioProcessor {
            override fun render(stereo: ByteArray) = false
            override fun process(mono: ByteArray, noise: Boolean, echo: Boolean) = mono
            override fun resetEcho() {}
            override fun close() {}
        } }
        try {
            for (channel in 0..1) enhancer.offerReference(reference(channel, time))
            val raw = ByteArray(960) { 17 }
            val result = enhancer.enhance(CapturedAudio(raw, time), EnhancementOptions(echo = true), ClockSync.Mapping(0, time, 1000))
            assertSame(raw, result.pcm); assertFalse(result.echo)
            assertFalse(result.message.contains("降噪"), "echo-only failure must not report an inactive noise processor")
        } finally { enhancer.close() }
    }
    @Test fun isolatedReferenceLossBypassesOutputWithoutResettingFilterClock() = runBlocking {
        val time = AtomicLong(1000000); val advances = AtomicInteger(); val resets = AtomicInteger()
        val enhancer = AudioEnhancer(7, key, time::get, ::hmac) { object : AudioProcessor {
            override fun render(stereo: ByteArray) = true
            override fun process(mono: ByteArray, noise: Boolean, echo: Boolean) = if (echo) ByteArray(960) { 77 } else mono
            override fun advanceEcho(mono: ByteArray): Boolean { advances.incrementAndGet(); return true }
            override fun resetEcho() { resets.incrementAndGet() }
            override fun close() {}
        } }
        try {
            for (channel in 0..1) enhancer.offerReference(reference(channel, time.get()))
            assertTrue(enhancer.enhance(CapturedAudio(ByteArray(960), time.get()), EnhancementOptions(echo = true), ClockSync.Mapping(0, time.get(), 1000)).echo)
            val previous = resets.get()
            time.addAndGet(10000)
            val raw = ByteArray(960) { 15 }
            val missed = enhancer.enhance(CapturedAudio(raw, time.get()), EnhancementOptions(echo = true), ClockSync.Mapping(0, time.get(), 1000))
            assertSame(raw, missed.pcm); assertFalse(missed.echo); assertEquals(1, advances.get())
            time.addAndGet(10000)
            for (channel in 0..1) enhancer.offerReference(reference(channel, time.get(), 2))
            assertTrue(enhancer.enhance(CapturedAudio(raw, time.get()), EnhancementOptions(echo = true), ClockSync.Mapping(0, time.get(), 1000)).echo)
            assertEquals(previous, resets.get(), "one lost UDP reference channel must not rebuild the entire echo filter")
        } finally { enhancer.close() }
    }
    @Test fun sustainedReferenceLossKeepsRawAudioAndBoundsConcealment() = runBlocking {
        val time = AtomicLong(1000000); val advances = AtomicInteger(); val resets = AtomicInteger()
        val enhancer = AudioEnhancer(7, key, time::get, ::hmac) { object : AudioProcessor {
            override fun render(stereo: ByteArray) = true
            override fun process(mono: ByteArray, noise: Boolean, echo: Boolean) = mono
            override fun advanceEcho(mono: ByteArray): Boolean { advances.incrementAndGet(); return true }
            override fun resetEcho() { resets.incrementAndGet() }
            override fun close() {}
        } }
        try {
            for (channel in 0..1) enhancer.offerReference(reference(channel, time.get()))
            assertTrue(enhancer.enhance(CapturedAudio(ByteArray(960), time.get()), EnhancementOptions(echo = true), ClockSync.Mapping(0, time.get(), 1000)).echo)
            val before = resets.get()
            repeat(5) {
                time.addAndGet(10000)
                val raw = ByteArray(960) { 23 }
                val result = enhancer.enhance(CapturedAudio(raw, time.get()), EnhancementOptions(echo = true), ClockSync.Mapping(0, time.get(), 1000))
                assertSame(raw, result.pcm); assertFalse(result.echo)
            }
            assertEquals(3, advances.get(), "silence is only an internal 30 ms clock bridge, never an unlimited substitute")
            time.addAndGet(10000)
            for (channel in 0..1) enhancer.offerReference(reference(channel, time.get(), 6))
            assertTrue(enhancer.enhance(CapturedAudio(ByteArray(960), time.get()), EnhancementOptions(echo = true), ClockSync.Mapping(0, time.get(), 1000)).echo)
            assertEquals(before + 1, resets.get(), "a real sustained render gap requires a clean echo timeline")
        } finally { enhancer.close() }
    }
    @Test fun clockRejectsUnsolicitedSlowAndReversedSamplesAndExpires() {
        val clock = ClockSync()
        assertFalse(clock.receive(1000, 1200, 1300, 1500))
        clock.sent(1000); assertTrue(clock.receive(1000, 101100, 101200, 1300))
        assertEquals(100000L, assertNotNull(clock.current(1400)).pcMinusPhone)
        clock.sent(2000); assertFalse(clock.receive(2000, 102100, 102000, 2400))
        clock.sent(3000); assertFalse(clock.receive(3000, 104000, 104001, 1000000))
        assertNull(clock.current(6000000))
    }
    @Test fun concealedResamplerOutputNeverReplacesMicrophoneAndNoiseRemainsIndependent() = runBlocking {
        for (noise in listOf(false, true)) {
            val time = 1000000L
            var available = false
            val enhancer = AudioEnhancer(7, key.copyOf(), { time }, ::hmac) { object : AudioProcessor {
                override val supportsReferenceResampling = true
                override fun bufferReference(stereo: ByteArray, valid: Boolean): Boolean { available = true; return true }
                override fun readReference(ratio: Double) = if (available) {
                    available = false; ResampledReference(ByteArray(1920), valid = false)
                } else null
                override fun render(stereo: ByteArray) = true
                override fun process(mono: ByteArray, noise: Boolean, echo: Boolean) = when {
                    echo -> ByteArray(960) { 99 }
                    noise -> ByteArray(960) { 55 }
                    else -> mono
                }
                override fun resetEcho() {}
                override fun close() {}
            } }
            try {
                for (channel in 0..1) enhancer.offerReference(reference(channel, time))
                val raw = ByteArray(960) { 12 }
                val result = enhancer.enhance(CapturedAudio(raw, time), EnhancementOptions(echo = true, noise = noise), ClockSync.Mapping(0, time, 1000))
                assertFalse(result.echo)
                if (noise) assertContentEquals(ByteArray(960) { 55 }, result.pcm) else assertSame(raw, result.pcm)
            } finally { enhancer.close() }
        }
    }
    @Test fun lateReferenceAdaptsWithActualAlreadyUploadedCapture() = runBlocking {
        val time = AtomicLong(1000000)
        val adapted = mutableListOf<Byte>()
        var buffered = 0
        val enhancer = AudioEnhancer(7, key.copyOf(), time::get, ::hmac) { object : AudioProcessor {
            override val supportsReferenceResampling = true
            override fun bufferReference(stereo: ByteArray, valid: Boolean): Boolean { buffered++; return true }
            override fun readReference(ratio: Double) = if (buffered >= 2) {
                buffered--; ResampledReference(ByteArray(1920), true)
            } else null
            override fun render(stereo: ByteArray) = true
            override fun process(mono: ByteArray, noise: Boolean, echo: Boolean): ByteArray {
                if (echo) adapted += mono[0]
                return ByteArray(960) { 77 }
            }
            override fun resetEcho() { buffered = 0 }
            override fun close() {}
        } }
        try {
            for (channel in 0..1) enhancer.offerReference(reference(channel, time.get()))
            val first = ByteArray(960) { 12 }
            assertSame(first, enhancer.enhance(CapturedAudio(first, time.get()), EnhancementOptions(echo = true), ClockSync.Mapping(0, time.get(), 1000)).pcm)
            time.addAndGet(10000)
            for (sequence in 1L..2L) for (channel in 0..1) enhancer.offerReference(reference(channel, 1000000 + sequence*10000, sequence))
            assertTrue(enhancer.enhance(CapturedAudio(ByteArray(960) { 13 }, time.get()), EnhancementOptions(echo = true), ClockSync.Mapping(0, time.get(), 1000)).echo)
            assertEquals(listOf(12.toByte(), 13.toByte()), adapted, "late references train on real capture history without retransmitting microphone audio")
        } finally { enhancer.close() }
    }
}
