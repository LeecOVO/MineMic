package com.aiaixyz.minemic

import com.aiaixyz.minemic.connection.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ConnectionLifecycleTest {
    private class Platform : PhonePlatform {
        @Volatile var instance = "a".repeat(32)
        @Volatile var owner = false
        @Volatile var dropRelease = false
        @Volatile var recording = false
        @Volatile var connected = false
        @Volatile var audio = false
        @Volatile var line: ((String) -> Unit)? = null
        @Volatile var closed: (() -> Unit)? = null
        private var ownerInstance = ""
        val opens = AtomicInteger()
        val requests = CopyOnWriteArrayList<Map<String, String>>()
        override fun monotonicMicros() = System.nanoTime() / 1000
        override fun deviceName() = "Test phone"
        override fun randomBytes(count: Int) = ByteArray(count) { 7 }
        override fun hmac(body: ByteArray, key: ByteArray) = ByteArray(32)
        override fun discover(onFound: (LanComputer) -> Unit, onError: (String) -> Unit) = onFound(LanComputer("127.0.0.1", 48721, "Test PC"))
        override fun stopDiscovery() {}
        override fun openControl(host: String, port: Int, onReady: () -> Unit, onLine: (String) -> Unit, onClosed: () -> Unit, onError: (String) -> Unit) {
            opens.incrementAndGet(); connected = true; line = onLine; closed = onClosed; onReady()
        }
        fun reply(fields: Map<String, String>) { line?.invoke(ControlJson.encode(fields)) }
        override fun sendControl(line: String) {
            val request = ControlJson.decode(line); requests += request
            when (request["type"]) {
                "connect", "resume" -> {
                    if (request["type"] == "resume" && (!owner || ownerInstance != instance)) reply(mapOf("type" to "error", "reason" to "not_owner", "instance" to instance))
                    else {
                        owner = true; ownerInstance = instance
                        reply(mapOf("type" to "welcome", "nonce" to request.getValue("nonce"), "session" to opens.get().toString(),
                            "token" to "b".repeat(32), "key" to "c".repeat(32), "pcName" to "Test PC", "port" to "48721", "instance" to instance))
                    }
                }
                "ping" -> reply(mapOf("type" to "pong"))
                "disconnect" -> if (!dropRelease) { owner = false; reply(mapOf("type" to "disconnected", "reason" to "phone", "closeId" to "close-test")) }
            }
        }
        override fun closeControl() { connected = false }
        override fun openAudio(host: String, port: Int, onReference: (ByteArray) -> Unit, onError: (String) -> Unit) { audio = true }
        override fun sendAudio(bytes: ByteArray) {}
        override fun closeAudio() { audio = false }
        override fun startAudio(onFrame: (CapturedAudio) -> Unit, onError: (String) -> Unit, onState: (Boolean, String) -> Unit) { recording = true; onState(true, "capturing") }
        override fun stopAudio() { recording = false }
    }
    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(6000) { while (!condition()) delay(5) }
    private suspend fun connect(phone: MicController) { phone.connect("", "127.0.0.1"); waitFor { phone.state.value.phase == ConnectionPhase.CONNECTED && phone.state.value.recording } }

    @Test fun explicitDisconnectIsImmediateAndTimeoutDoesNotReturnToReconnecting() = runBlocking {
        val platform = Platform(); val phone = MicController(platform)
        try {
            connect(phone); platform.dropRelease = true
            val oldClosed = platform.closed
            val start = System.nanoTime()
            repeat(3) { phone.disconnect() }
            waitFor { phone.state.value.phase == ConnectionPhase.IDLE }
            assertTrue((System.nanoTime() - start) / 1000000 < 150, "local disconnect must not wait for FIN acknowledgment")
            assertFalse(phone.state.value.ownsPc || phone.state.value.recording)
            assertEquals("", phone.state.value.pcName)
            waitFor { !platform.connected && !platform.audio && !platform.recording }
            delay(1300)
            assertEquals(ConnectionPhase.IDLE, phone.state.value.phase)
            assertEquals(1, platform.opens.get(), "active connection is reused; no multi-second release reconnect loop")
            platform.dropRelease = false
            val beforeReconnect = platform.requests.size
            connect(phone)
            assertEquals(listOf("disconnect", "connect"), platform.requests.drop(beforeReconnect).mapNotNull { it["type"] }.filter { it == "disconnect" || it == "connect" },
                "a missed release must be cleared before fresh pairing, without reopening the old UI session")
            oldClosed?.invoke(); delay(50)
            assertEquals(ConnectionPhase.CONNECTED, phone.state.value.phase, "retired callbacks cannot tear down a fresh session")
        } finally { phone.close() }
    }
    @Test fun pcInitiatedDisconnectAcknowledgesAndClearsLocalResources() = runBlocking {
        for (reason in listOf("pc", "stopped")) {
            val platform = Platform(); val phone = MicController(platform)
            try {
                connect(phone); platform.owner = false
                platform.reply(mapOf("type" to "disconnected", "reason" to reason, "closeId" to "close-test"))
                waitFor { phone.state.value.phase == ConnectionPhase.IDLE }
                assertTrue(platform.requests.any { it["type"] == "disconnect_ack" && it["closeId"] == "close-test" })
                assertFalse(phone.state.value.ownsPc || phone.state.value.recording)
                withTimeout(250) { while (platform.audio || platform.recording) delay(1) }
                val opens = platform.opens.get(); delay(1300)
                assertEquals(opens, platform.opens.get(), "intentional PC release must not automatically reconnect")
            } finally { phone.close() }
        }
    }
    @Test fun unexpectedLossResumesAndUnexpectedPcRestartUsesNormalFirstOwnerPairing() = runBlocking {
        val platform = Platform(); val phone = MicController(platform)
        try {
            connect(phone)
            platform.closed?.invoke()
            waitFor { phone.state.value.phase == ConnectionPhase.RECONNECTING }
            assertTrue(phone.state.value.ownsPc)
            waitFor { phone.state.value.phase == ConnectionPhase.CONNECTED }
            assertTrue(platform.requests.any { it["type"] == "resume" })
            platform.instance = "d".repeat(32); platform.owner = false; platform.closed?.invoke()
            waitFor { phone.state.value.phase == ConnectionPhase.RECONNECTING }
            waitFor { phone.state.value.phase == ConnectionPhase.CONNECTED }
            assertEquals(2, platform.requests.count { it["type"] == "connect" })
        } finally { phone.close() }
    }
    @Test fun samePcProcessReleasingOwnershipDoesNotSilentlyPairAgain() = runBlocking {
        val platform = Platform(); val phone = MicController(platform)
        try {
            connect(phone); platform.owner = false; platform.closed?.invoke()
            waitFor { phone.state.value.phase == ConnectionPhase.RECONNECTING }
            waitFor { phone.state.value.phase == ConnectionPhase.IDLE }
            assertEquals(1, platform.requests.count { it["type"] == "connect" })
            assertFalse(platform.recording || platform.audio || phone.state.value.ownsPc)
        } finally { phone.close() }
    }
}
