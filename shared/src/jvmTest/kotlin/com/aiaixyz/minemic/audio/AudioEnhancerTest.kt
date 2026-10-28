package com.aiaixyz.minemic.audio

import com.aiaixyz.minemic.connection.CapturedAudio
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class AudioEnhancerTest {
    @Test fun blockedMonitoringCannotDelayAudioDeliveryOrFollowingDspFrames() = runBlocking {
        val time = AtomicLong(1000000)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val enhancer = AudioEnhancer(7, key.copyOf(), time::get, ::hmac,
            statistics = { entered.countDown(); release.await(2, TimeUnit.SECONDS) }) {
            object : AudioProcessor {
                override fun render(stereo: ByteArray) = false
                override fun process(mono: ByteArray, noise: Boolean, echo: Boolean) = ByteArray(960) { 77 }
                override fun resetEcho() {}
                override fun close() {}
            }
        }
        suspend fun frame() = enhancer.enhance(CapturedAudio(ByteArray(960), time.get()), EnhancementOptions(noise = true), null)
        try {
            assertContentEquals(ByteArray(960) { 77 }, frame().pcm)
            time.addAndGet(1100000)
            assertContentEquals(ByteArray(960) { 77 }, frame().pcm)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(8) {
                time.addAndGet(10000)
                assertContentEquals(ByteArray(960) { 77 }, frame().pcm,
                    "a blocked statistics callback must not occupy the DSP worker")
            }
        } finally { release.countDown(); enhancer.close() }
    }
    @Test fun timedOutputBackfillsLateEchoAndRunsNoiseOnceInPlayoutOrder() = timedOutputClockScenario(0)
    @Test fun positiveReferencePhaseCannotSkipAnOlderUnprocessedVoiceFrame() = timedOutputClockScenario(7000)
    private fun timedOutputClockScenario(referencePhase: Long) = runBlocking {
        val time = AtomicLong(1000000)
        val noiseInputs = mutableListOf<Byte>()
        val echoInputs = mutableListOf<Byte>()
        var buffered = 0
        val enhancer = AudioEnhancer(7, key.copyOf(), time::get, ::hmac,
            timing = EnhancementTiming(echoDelaySamples = 0, noiseDelaySamples = 0, deskNoiseDelaySamples = 0),
            execution = object : AudioProcessingExecution {
                // This scenario tests frame order and the logical audio clock,
                // not a CI runner's ability to schedule a thread within 8 ms.
                override val dispatcher = Dispatchers.Unconfined
                override fun close() {}
            }) {
            object : AudioProcessor {
                override val supportsReferenceResampling = true
                override fun bufferReference(stereo: ByteArray, valid: Boolean): Boolean { buffered++; return true }
                override fun readReference(ratio: Double) = if (buffered > 0) {
                    buffered--; ResampledReference(ByteArray(1920), true)
                } else null
                override fun render(stereo: ByteArray) = true
                override fun process(mono: ByteArray, noise: Boolean, echo: Boolean): ByteArray {
                    if (echo) echoInputs += mono[0]
                    if (noise) noiseInputs += mono[0]
                    return mono
                }
                override fun resetEcho() { buffered = 0 }
                override fun close() {}
            }
        }
        try {
            var nextReference = 0
            repeat(40) { index ->
                val capture = 1000000+index*10000L; time.set(capture+100000)
                if (index !in 20..22) {
                    for (render in nextReference..index) for (channel in 0..1)
                        enhancer.offerReference(reference(channel, 1000000+render*10000L+referencePhase, render.toLong()))
                    nextReference = index+1
                }
                val result = enhancer.enhance(CapturedAudio(ByteArray(960) { (index+1).toByte() }, capture),
                    EnhancementOptions(noise = true, echo = true), ClockSync.Mapping(0, time.get(), 1000))
                assertContentEquals(ByteArray(960) { if (index < 4) 0 else (index-3).toByte() }, result.pcm)
                if (index >= 4) assertTrue(result.echo, "late references must not switch published audio to dry PCM")
            }
            assertEquals((1..40).map { it.toByte() }, echoInputs)
            assertEquals(List(4) { 0.toByte() }+(1..36).map { it.toByte() }, noiseInputs,
                "NS must consume the chronological playout stream once, not retrain on retrospective AEC captures")
        } finally { enhancer.close() }
    }
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
        val logicalNow = 1000000L
        val enhancer = AudioEnhancer(7, key, { logicalNow }, ::hmac) { object : AudioProcessor {
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
            val captureTime = logicalNow - 10000
            enhancer.offerReference(reference(1, captureTime)); enhancer.offerReference(reference(0, captureTime))
            enhancer.offerReference(reference(0, captureTime)) // Duplicate must never become a stereo pair.
            val out = enhancer.enhance(CapturedAudio(ByteArray(960), captureTime), EnhancementOptions(echo = true), ClockSync.Mapping(0, logicalNow, 1000))
            assertEquals(1, rendered.get()); assertContentEquals(ByteArray(960) { 77 }, out.pcm)
            val raw = ByteArray(960) { 42 }
            val missing = enhancer.enhance(CapturedAudio(raw, logicalNow + 90000), EnhancementOptions(echo = true), ClockSync.Mapping(0, logicalNow, 1000))
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
        val processingStarted = CountDownLatch(1); val releaseProcessing = CountDownLatch(1)
        val time = AtomicLong(1000000)
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        withContext(dispatcher) { /* Warm the worker independently of the sender deadline. */ }
        val enhancer = AudioEnhancer(7, key, time::get, ::hmac,
            execution = object : AudioProcessingExecution {
                override val dispatcher = dispatcher
                override fun close() = dispatcher.close()
            }) { object : AudioProcessor {
            override fun render(stereo: ByteArray) = false
            override fun process(mono: ByteArray, noise: Boolean, echo: Boolean): ByteArray {
                entered.incrementAndGet(); processingStarted.countDown()
                check(releaseProcessing.await(5, TimeUnit.SECONDS))
                return ByteArray(960) { 99 }
            }
            override fun resetEcho() {}
            override fun close() { destroyed.incrementAndGet() }
        } }
        try {
            val raw = ByteArray(960) { 15 }
            // Keep native processing blocked, while every sender call must still
            // return the dry frame. A test watchdog allows scheduler noise; the
            // application's real 8 ms deadline remains unchanged.
            val first = async { enhancer.enhance(CapturedAudio(raw, time.get()), EnhancementOptions(noise = true), null) }
            withContext(Dispatchers.IO) { assertTrue(processingStarted.await(2, TimeUnit.SECONDS)) }
            withTimeout(2000) {
                assertSame(raw, first.await().pcm)
                repeat(12) {
                    val out = enhancer.enhance(CapturedAudio(raw, time.get()), EnhancementOptions(noise = true), null)
                    assertSame(raw, out.pcm)
                }
            }
            assertEquals(1, entered.get(), "sender calls must not enter the blocked native processor")
            time.addAndGet(9000) // Both in-flight and queued work are now past their deadlines.
        } finally { releaseProcessing.countDown(); enhancer.close() }
        withTimeout(1000) { while (destroyed.get() == 0) delay(5) }
        assertEquals(1, entered.get(), "cancelled or expired queued frames must never reach native processing")
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
    @Test fun freshClockRepliesKeepTheLowestRttMappingAliveAcrossItsSampleAgeBoundary() {
        val clock = ClockSync()
        fun sample(sent: Long, roundTrip: Long) {
            clock.sent(sent)
            assertTrue(clock.receive(sent, sent+100000+roundTrip/2, sent+100000+roundTrip/2, sent+roundTrip))
        }
        sample(1000000, 100)
        for (second in 2L..5L) sample(second*1000000, 2000)
        assertNotNull(clock.current(6100500), "recent accepted replies must keep synchronization valid even while the best RTT sample ages out")
        assertNull(clock.current(10002001), "synchronization must still expire after five seconds without an accepted reply")
    }
    @Test fun sessionElapsedClockRetainsExactAudioMappingWithoutExposingPhoneUptime() {
        val origin = 9_000_000_000_000L
        val legacy = ClockSync(); val session = ClockSync(origin)
        val pcOffset = -4_000_000_000_000L
        for (i in 0..20) {
            val sent = origin + i * 500000L
            val rtt = if (i == 0) 100L else 2000L
            val wire = assertNotNull(session.sent(sent))
            assertEquals(i * 500000L, wire)
            assertTrue(wire < origin, "wire contains only time elapsed after the session began")
            legacy.sent(sent)
            val received = sent + rtt
            assertTrue(legacy.receive(sent, sent + pcOffset + rtt / 2, sent + pcOffset + rtt / 2, received))
            assertFalse(session.receive(sent, sent + pcOffset, sent + pcOffset, received), "raw uptime cannot match the pending request")
            assertTrue(session.receive(wire, sent + pcOffset + rtt / 2, sent + pcOffset + rtt / 2, received))
            assertEquals(legacy.current(received), session.current(received), "AEC must retain the original host-time offset and freshness")
            assertFalse(session.receive(wire, sent + pcOffset, sent + pcOffset, received), "replayed replies are rejected")
        }
        assertNull(session.sent(origin - 1))
    }
    @Test fun elapsedClockMatchesOutOfOrderRepliesAndBoundsOutstandingRequests() {
        val origin = 1_000_000_000L
        val clock = ClockSync(origin)
        val first = assertNotNull(clock.sent(origin + 1000))
        val second = assertNotNull(clock.sent(origin + 2000))
        assertTrue(clock.receive(second, origin + 102050, origin + 102100, origin + 2200))
        assertTrue(clock.receive(first, origin + 101050, origin + 101100, origin + 2300))
        assertNull(clock.current(origin + 6000000))
        repeat(9) { clock.sent(origin + 10000 + it) }
        assertFalse(clock.receive(10000, origin + 110050, origin + 110100, origin + 12000), "old outstanding requests stay bounded")
        assertTrue(clock.receive(10008, origin + 110058, origin + 110108, origin + 10208))
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
