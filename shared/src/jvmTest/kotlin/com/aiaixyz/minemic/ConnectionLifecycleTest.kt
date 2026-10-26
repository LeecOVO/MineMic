package com.aiaixyz.minemic

import com.aiaixyz.minemic.connection.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ConnectionLifecycleTest {
    @Test fun ipv6RecoveryUsesCanonicalAddressAndKeepsInterfaceScope() {
        val platform = Platform()
        val leases = ConnectionLeases(platform)
        leases.remember(ConnectionLeases.Lease("[FD12::000a]", 48721, "a".repeat(32), "b".repeat(32), "c".repeat(32)))
        val restored = ConnectionLeases(platform)
        assertNotNull(restored.find(LanComputer("fd12:0:0:0:0:0:0:a", 48721, "PC")))
        restored.remember(ConnectionLeases.Lease("fe80::1%en0", 48721, "a".repeat(32), "b".repeat(32), "c".repeat(32)))
        assertNotNull(ConnectionLeases(platform).find(LanComputer("fe80::1%en0", 48721, "PC")))
        assertNull(ConnectionLeases(platform).find(LanComputer("fe80::1%en1", 48721, "other link")))
        restored.forget(LanComputer("FD12::A", 48721, "PC"))
        assertNull(ConnectionLeases(platform).find(LanComputer("fd12::a", 48721, "PC")))
    }
    private class Platform : PhonePlatform {
        private var savedConnection: String? = null
        override fun loadConnectionState() = savedConnection
        override fun saveConnectionState(state: String?) { savedConnection = state }
        private val randomSequence = AtomicInteger(1)
        private var ownerClient = ""
        @Volatile var instance = "a".repeat(32)
        @Volatile var owner = false
        @Volatile var dropRelease = false
        @Volatile var recording = false
        @Volatile var connected = false
        @Volatile var audio = false
        @Volatile var controlHost = ""
        @Volatile var audioHost = ""
        var discoveredHosts = listOf("127.0.0.1")
        var unavailableHosts = emptySet<String>()
        @Volatile var line: ((String) -> Unit)? = null
        @Volatile var closed: (() -> Unit)? = null
        private var ownerInstance = ""
        val opens = AtomicInteger()
        val requests = CopyOnWriteArrayList<Map<String, String>>()
        override fun monotonicMicros() = System.nanoTime() / 1000
        override fun deviceName() = "Test phone"
        override fun randomBytes(count: Int) = randomSequence.getAndIncrement().let { value -> ByteArray(count) { value.toByte() } }
        override fun hmac(body: ByteArray, key: ByteArray) = ByteArray(32)
        override fun discover(onFound: (LanComputer) -> Unit, onError: (String) -> Unit) {
            discoveredHosts.forEach { onFound(LanComputer(it, 48721, "Test PC")) }
        }
        override fun stopDiscovery() {}
        override fun openControl(host: String, port: Int, onReady: () -> Unit, onLine: (String) -> Unit, onClosed: () -> Unit, onError: (String) -> Unit) {
            opens.incrementAndGet(); controlHost = host
            if (host in unavailableHosts) { onError("unavailable"); return }
            connected = true; line = onLine; closed = onClosed; onReady()
        }
        fun reply(fields: Map<String, String>) { line?.invoke(ControlJson.encode(fields)) }
        override fun sendControl(line: String) {
            val request = ControlJson.decode(line); requests += request
            when (request["type"]) {
                "connect", "resume" -> {
                    if (request["type"] == "resume" && (!owner || ownerInstance != instance)) reply(mapOf("type" to "error", "reason" to "not_owner", "instance" to instance))
                    else if (request["type"] == "connect" && owner &&
                        (request["clientId"] != ownerClient || request["token"] != "b".repeat(32)))
                        reply(mapOf("type" to "error", "reason" to "busy", "instance" to instance))
                    else {
                        owner = true; ownerInstance = instance; ownerClient = request["clientId"].orEmpty()
                        reply(mapOf("type" to "welcome", "nonce" to request.getValue("nonce"), "session" to opens.get().toString(),
                            "token" to "b".repeat(32), "key" to "c".repeat(32), "pcName" to "Test PC", "port" to "48721", "instance" to instance))
                    }
                }
                "ping" -> reply(mapOf("type" to "pong"))
                "disconnect" -> if (!dropRelease) { owner = false; reply(mapOf("type" to "disconnected", "reason" to "phone", "closeId" to "close-test")) }
            }
        }
        override fun closeControl() { connected = false }
        override fun openAudio(host: String, port: Int, onReference: (ByteArray) -> Unit, onError: (String) -> Unit) { audioHost = host; audio = true }
        override fun sendAudio(bytes: ByteArray) {}
        override fun closeAudio() { audio = false }
        override fun startAudio(onFrame: (CapturedAudio) -> Unit, onError: (String) -> Unit, onState: (Boolean, String) -> Unit) { recording = true; onState(true, "capturing") }
        override fun stopAudio() { recording = false }
    }
    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(6000) { while (!condition()) delay(5) }
    private suspend fun connect(phone: MicController) { phone.connect("", "127.0.0.1"); waitFor { phone.state.value.phase == ConnectionPhase.CONNECTED && phone.state.value.recording } }

    @Test fun manualIpv6UsesCanonicalHostForControlAudioAndRelease() = runBlocking {
        val platform = Platform(); val phone = MicController(platform)
        try {
            phone.connect("", "[FD12::000a]", IpVersion.IPV6)
            waitFor { phone.state.value.recording && platform.audio }
            assertEquals("fd12:0:0:0:0:0:0:a", platform.controlHost)
            assertEquals(platform.controlHost, platform.audioHost)
            assertEquals("manual", platform.requests.first()["mode"])
            phone.disconnect(); waitFor { phone.state.value.phase == ConnectionPhase.IDLE && !platform.owner }
            assertNull(platform.loadConnectionState())
        } finally { phone.close() }
    }
    @Test fun wrongAddressFamilyIsRejectedBeforeOpeningTransport() = runBlocking {
        val platform = Platform(); val phone = MicController(platform)
        try {
            phone.connect("", "::1")
            waitFor { phone.state.value.phase == ConnectionPhase.ERROR }
            assertTrue(phone.state.value.message.contains("IPv4")); assertEquals(0, platform.opens.get())
            phone.connect("", "192.168.1.2", IpVersion.IPV6)
            waitFor { phone.state.value.message.contains("IPv6") }
            assertEquals(0, platform.opens.get())
        } finally { phone.close() }
    }
    @Test fun codePairingAutomaticallyTriesBothAddressFamilies() = runBlocking {
        for ((first, second) in listOf("127.0.0.1" to "0:0:0:0:0:0:0:1", "0:0:0:0:0:0:0:1" to "127.0.0.1")) {
            val platform = Platform().apply {
                discoveredHosts = listOf("not-an-ip", first, second)
                unavailableHosts = setOf(first)
            }
            val phone = MicController(platform)
            try {
                phone.connect("123456", "")
                waitFor { phone.state.value.recording }
                assertEquals(second, platform.controlHost)
                assertEquals(2, platform.opens.get(), "unreachable family must not prevent trying the other")
                assertEquals("code", platform.requests.first()["mode"])
            } finally { phone.close() }
        }
    }

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
    @Test fun processRestartRecoversOnlyAfterUserConnectsAndExplicitDisconnectErasesLease() = runBlocking {
        val platform = Platform(); var phone = MicController(platform)
        try {
            connect(phone)
            val first = platform.requests.first { it["type"] == "connect" }
            assertNotNull(platform.loadConnectionState())
            phone.close(); val opens = platform.opens.get()
            phone = MicController(platform); delay(50)
            assertEquals(opens, platform.opens.get(), "reopening the app must not start a connection or microphone automatically")
            assertFalse(platform.recording)
            connect(phone)
            val restarted = platform.requests.last { it["type"] == "connect" }
            assertEquals(first["clientId"], restarted["clientId"])
            assertEquals("b".repeat(32), restarted["token"])
            assertNotEquals(first["nonce"], restarted["nonce"])
            phone.disconnect(); waitFor { phone.state.value.phase == ConnectionPhase.IDLE && !platform.connected && platform.loadConnectionState() == null }
            assertNull(platform.loadConnectionState(), "an acknowledged explicit disconnect removes the protected recovery record")
        } finally { phone.close() }
    }
    @Test fun missedReleaseSurvivesProcessRestartAndIsRetriedBeforeFreshPairing() = runBlocking {
        val platform = Platform(); var phone = MicController(platform)
        try {
            connect(phone); platform.dropRelease = true
            phone.disconnect(); waitFor { phone.state.value.phase == ConnectionPhase.IDLE && !platform.connected }
            assertNotNull(platform.loadConnectionState())
            phone.close(); platform.dropRelease = false
            val before = platform.requests.size; phone = MicController(platform)
            connect(phone)
            val operations = platform.requests.drop(before).mapNotNull { it["type"] }.filter { it == "disconnect" || it == "connect" }
            assertEquals(listOf("disconnect", "connect"), operations)
        } finally { phone.close() }
    }
    @Test fun recoveryRecordsAreBoundedAndMalformedStorageCannotIntroduceAnOwner() {
        val platform = Platform(); val leases = ConnectionLeases(platform)
        repeat(6) { i ->
            leases.remember(ConnectionLeases.Lease("192.168.1.${i+1}", 48721, "a".repeat(32), "b".repeat(32), "c".repeat(32)))
        }
        val restored = ConnectionLeases(platform)
        assertNull(restored.find(LanComputer("192.168.1.1", 48721, "old")))
        assertNotNull(restored.find(LanComputer("192.168.1.6", 48721, "current")))
        assertEquals(4, platform.loadConnectionState()!!.lines().size)
        val before = platform.loadConnectionState()
        leases.remember(ConnectionLeases.Lease("192.168.1.7", 48721, "a".repeat(32), "b".repeat(32), "bad|instance"))
        assertEquals(before, platform.loadConnectionState(), "untrusted fields must not become additional serialized leases")
        platform.saveConnectionState("192.168.1.1|48721|invalid|invalid|invalid|0")
        assertNull(ConnectionLeases(platform).find(LanComputer("192.168.1.1", 48721, "malformed")))
        platform.saveConnectionState("x".repeat(2049))
        assertNull(ConnectionLeases(platform).find(LanComputer("192.168.1.1", 48721, "oversized")))
    }
}
