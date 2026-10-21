package com.aiaixyz.minemic

import com.aiaixyz.minemic.connection.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class AudioBurstTest {
    private class BurstPlatform : PhonePlatform {
        val sent = CopyOnWriteArrayList<ByteArray>()
        @Volatile var captured: ((ByteArray) -> Unit)? = null
        private var reply: ((String) -> Unit)? = null
        override fun deviceName() = "Burst microphone"
        override fun randomBytes(count: Int) = ByteArray(count)
        override fun hmac(body: ByteArray, key: ByteArray) = ByteArray(32)
        override fun discover(onFound: (LanComputer) -> Unit, onError: (String) -> Unit) {}
        override fun stopDiscovery() {}
        override fun openControl(host: String, port: Int, onReady: () -> Unit, onLine: (String) -> Unit,
                                 onClosed: () -> Unit, onError: (String) -> Unit) { reply = onLine; onReady() }
        override fun sendControl(line: String) {
            val request = ControlJson.decode(line)
            if (request["type"] == "connect") reply?.invoke(ControlJson.encode(mapOf(
                "type" to "welcome", "nonce" to request.getValue("nonce"), "session" to "1",
                "token" to "11".repeat(16), "key" to "22".repeat(16), "port" to "48721", "pcName" to "PC"
            )))
        }
        override fun closeControl() {}
        override fun openAudio(host: String, port: Int) {}
        override fun sendAudio(bytes: ByteArray) { sent += bytes }
        override fun closeAudio() {}
        override fun startAudio(onFrame: (ByteArray) -> Unit, onError: (String) -> Unit) {
            captured = onFrame
            // Synchronous native callback before startAudio returns: the sender
            // cannot drain the channel yet. This used to discard most of a batch.
            repeat(12) { index -> onFrame(ByteArray(960) { index.toByte() }) }
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
        } finally { controller.close() }
    }
}
