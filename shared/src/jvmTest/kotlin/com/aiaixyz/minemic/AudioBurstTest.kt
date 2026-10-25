package com.aiaixyz.minemic

import com.aiaixyz.minemic.connection.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class AudioBurstTest {
    private class BurstPlatform : PhonePlatform {
        val sent = CopyOnWriteArrayList<ByteArray>()
        val controls = CopyOnWriteArrayList<String>()
        val clockOffset = AtomicLong()
        @Volatile var captured: ((CapturedAudio) -> Unit)? = null
        @Volatile var status: ((Boolean, String) -> Unit)? = null
        @Volatile var failure: ((String) -> Unit)? = null
        private var reply: ((String) -> Unit)? = null
        override fun monotonicMicros() = System.nanoTime() / 1000 + clockOffset.get()
        override fun deviceName() = "Burst microphone"
        override fun randomBytes(count: Int) = ByteArray(count)
        override fun hmac(body: ByteArray, key: ByteArray) = ByteArray(32)
        override fun discover(onFound: (LanComputer) -> Unit, onError: (String) -> Unit) {}
        override fun stopDiscovery() {}
        override fun openControl(host: String, port: Int, onReady: () -> Unit, onLine: (String) -> Unit,
                                 onClosed: () -> Unit, onError: (String) -> Unit) { reply = onLine; onReady() }
        override fun sendControl(line: String) {
            val request = ControlJson.decode(line)
            controls += request.getValue("type")
            if (request["type"] == "connect") reply?.invoke(ControlJson.encode(mapOf(
                "type" to "welcome", "nonce" to request.getValue("nonce"), "session" to "1",
                "token" to "11".repeat(16), "key" to "22".repeat(16), "port" to "48721", "pcName" to "PC"
            )))
        }
        override fun closeControl() {}
        override fun openAudio(host: String, port: Int, onReference: (ByteArray) -> Unit, onError: (String) -> Unit) {}
        override fun sendAudio(bytes: ByteArray) { sent += bytes }
        override fun closeAudio() {}
        override fun startAudio(onFrame: (CapturedAudio) -> Unit, onError: (String) -> Unit, onState: (Boolean, String) -> Unit) {
            captured = onFrame
            status = onState; failure = onError
            onState(true, "手机麦克风正在传输")
            // Synchronous native callback before startAudio returns: the sender
            // cannot drain the channel yet. This used to discard most of a batch.
            repeat(12) { index -> onFrame(CapturedAudio(ByteArray(960) { index.toByte() }, monotonicMicros())) }
        }
        override fun stopAudio() { captured = null }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(3000) { while (!condition()) delay(5) }

    @Test fun nativeCaptureBatchPreservesEverySampleAndSequence() = runBlocking {
        val platform = BurstPlatform(); val controller = MicController(platform)
        try {
            controller.connect("", "127.0.0.1")
            await { platform.sent.size >= 12 }
            assertEquals(12, platform.sent.size)
            for ((sequence, packet) in platform.sent.withIndex()) {
                assertEquals(1024, packet.size)
                assertContentEquals(byteArrayOf(0, 0, 0, sequence.toByte()), packet.copyOfRange(12, 16))
                assertContentEquals(ByteArray(960) { sequence.toByte() }, packet.copyOfRange(32, 992))
            }
            assertFalse(platform.controls.any { it == "clock" || it == "reference" })
        } finally { controller.close() }
    }
    @Test fun retryKeepsSequenceAndInterruptionOrFailureNeverReleasesOwnership() = runBlocking {
        val platform = BurstPlatform(); val controller = MicController(platform)
        try {
            controller.connect("", "127.0.0.1"); await { platform.sent.size == 12 }
            val staleStatus = platform.status
            controller.retryMicrophone(); await { platform.sent.size == 24 }
            assertContentEquals(byteArrayOf(0, 0, 0, 12), platform.sent[12].copyOfRange(12, 16))
            platform.status?.invoke(false, "interrupted"); await { !controller.state.value.recording }
            platform.captured?.invoke(CapturedAudio(ByteArray(960), platform.monotonicMicros()))
            delay(50); assertEquals(24, platform.sent.size); assertTrue(controller.state.value.ownsPc)
            platform.status?.invoke(true, "resumed"); await { controller.state.value.recording }
            staleStatus?.invoke(false, "late old engine callback"); delay(20)
            assertTrue(controller.state.value.recording)
            platform.failure?.invoke("permission revoked"); await { !controller.state.value.recording }
            platform.captured?.invoke(CapturedAudio(ByteArray(960), platform.monotonicMicros()))
            delay(30); assertEquals(24, platform.sent.size); assertTrue(controller.state.value.ownsPc)
        } finally { controller.close() }
    }
    @Test fun gainWorksWhenNativeDenoiserIsUnavailableAndMuteStillSendsSilence() = runBlocking {
        val platform = BurstPlatform(); val controller = MicController(platform)
        val raw = ByteArray(960).also { bytes -> repeat(480) { bytes[2*it] = 0xE8.toByte(); bytes[2*it+1] = 3 } }
        suspend fun frame(): ByteArray {
            val count = platform.sent.size
            platform.captured?.invoke(CapturedAudio(raw, platform.monotonicMicros()))
            await { platform.sent.size > count }
            return platform.sent.last().copyOfRange(32, 992)
        }
        try {
            controller.connect("", "127.0.0.1"); await { platform.sent.size == 12 }
            controller.setInputGainDb(6f); controller.setNoiseSuppression(true); controller.setDeskNoiseSuppression(true)
            await { controller.state.value.inputGainDb == 6f && controller.state.value.deskNoiseSuppression }
            // JVM has no native library: fallback remains usable with manual gain.
            repeat(18) { frame() }
            val boosted = frame()
            val sample = (boosted[958].toInt() and 255) or (boosted[959].toInt() shl 8)
            assertTrue(sample in 1990..2000)
            controller.setMuted(true); await { controller.state.value.muted }
            assertContentEquals(ByteArray(960), frame())
            controller.setMuted(false); controller.setInputGainDb(0f); controller.setNoiseSuppression(false)
            await { !controller.state.value.muted && controller.state.value.inputGainDb == 0f && !controller.state.value.noiseSuppression }
            assertContentEquals(raw, frame())
            assertTrue(controller.state.value.ownsPc && controller.state.value.deskNoiseSuppression)
        } finally { controller.close() }
    }
    @Test fun stalledCaptureStopsReportingRecordingWithoutReleasingThePc() = runBlocking {
        val platform = BurstPlatform(); val controller = MicController(platform)
        try {
            controller.connect("", "127.0.0.1"); await { platform.sent.size == 12 }
            platform.clockOffset.set(4000000)
            await { !controller.state.value.recording }
            assertTrue(controller.state.value.ownsPc)
            assertFalse(platform.controls.contains("disconnect"))
            assertTrue(controller.state.value.message.contains("录音数据已暂停"))
            val count = platform.sent.size
            platform.captured?.invoke(CapturedAudio(ByteArray(960), platform.monotonicMicros()))
            await { platform.sent.size > count && controller.state.value.recording }
            assertTrue(controller.state.value.ownsPc)
        } finally { controller.close() }
    }

    @Test fun sessionObserversIgnoreMeterChangesButReceiveMuteAndCaptureTransitions() = runBlocking {
        val platform = BurstPlatform(); val controller = MicController(platform)
        val all = CopyOnWriteArrayList<MicState>()
        val sessions = CopyOnWriteArrayList<MicState>()
        val fullSubscription = controller.observeState { all += it }
        val sessionSubscription = controller.observeSessionState { sessions += it }
        try {
            controller.connect("", "127.0.0.1")
            await { platform.sent.size == 12 && sessions.lastOrNull()?.recording == true }
            val before = sessions.size
            val fullBefore = all.size
            repeat(6) { batch ->
                val count = platform.sent.size
                repeat(10) {
                    platform.captured?.invoke(CapturedAudio(ByteArray(960) { (batch * 11).toByte() }, platform.monotonicMicros()))
                }
                await { platform.sent.size == count + 10 }
            }
            await { all.size > fullBefore }
            assertEquals(before, sessions.size)
            assertTrue(sessions.all { it.level == 0f })
            controller.setMuted(true)
            await { sessions.last().muted }
            platform.status?.invoke(false, "interrupted")
            await { !sessions.last().recording }
            assertTrue(sessions.last().ownsPc)
        } finally { fullSubscription.cancel(); sessionSubscription.cancel(); controller.close() }
    }
}
