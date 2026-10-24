package com.aiaixyz.minemic

import com.aiaixyz.minemic.connection.*
import com.aiaixyz.minemic.audio.ReferenceAudio
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import kotlin.test.*
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PcInteropTest {
    private class Platform(private val port: Int) : PhonePlatform {
        @Volatile var tcp: Socket? = null
        @Volatile var udp: DatagramSocket? = null
        private var capture = AtomicBoolean(false)
        @Volatile private var epoch = 0
        @Volatile private var sessionId = 0L
        @Volatile private var key = ByteArray(16)
        val validReferences = AtomicInteger()
        val clockReplies = AtomicInteger()
        override fun monotonicMicros() = System.nanoTime() / 1000
        override fun deviceName() = "Kotlin integration test"
        override fun randomBytes(count: Int) = ByteArray(count).also { SecureRandom().nextBytes(it) }
        override fun hmac(body: ByteArray, key: ByteArray) = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(body) }
        override fun discover(onFound: (LanComputer) -> Unit, onError: (String) -> Unit) { onFound(LanComputer("127.0.0.1", port, "Test PC")) }
        override fun stopDiscovery() {}
        override fun openControl(host: String, port: Int, onReady: () -> Unit, onLine: (String) -> Unit, onClosed: () -> Unit, onError: (String) -> Unit) {
            closeControl(); val attempt = epoch
            Thread {
                try {
                    val socket = Socket(host, port); tcp = socket; onReady()
                    val reader = socket.getInputStream().bufferedReader()
                    while (epoch == attempt) {
                        val line = reader.readLine() ?: break
                        val fields = ControlJson.decode(line)
                        if (fields["type"] == "welcome") { sessionId = fields.getValue("session").toLong(); key = Wire.unhex(fields.getValue("key")) }
                        if (fields["type"] == "clock") clockReplies.incrementAndGet()
                        onLine(line)
                    }
                    if (epoch == attempt) onClosed()
                } catch (e: Exception) { if (epoch == attempt) onError(e.message ?: "closed") }
            }.apply { isDaemon = true; start() }
        }
        override fun sendControl(line: String) { tcp?.getOutputStream()?.write((line + "\n").toByteArray()) }
        override fun closeControl() { epoch++; runCatching { tcp?.close() }; tcp = null }
        override fun openAudio(host: String, port: Int, onReference: (ByteArray) -> Unit) {
            closeAudio(); val socket = DatagramSocket().apply { connect(InetAddress.getByName(host), port) }; udp = socket
            Thread {
                val storage = ByteArray(1201)
                try { while (!socket.isClosed) {
                    val packet = DatagramPacket(storage, storage.size); socket.receive(packet)
                    val bytes = storage.copyOf(packet.length)
                    val decoded = ReferenceAudio.decode(bytes, sessionId, key, ::hmac)
                    if (decoded != null) {
                        val sample = (decoded.pcm[0].toInt() and 255) or (decoded.pcm[1].toInt() shl 8)
                        check(sample == if (decoded.channel == 0) 1000 else -2000)
                        validReferences.incrementAndGet()
                    }
                    onReference(bytes)
                } } catch (_: SocketException) { }
            }.apply { isDaemon = true; start() }
        }
        override fun sendAudio(bytes: ByteArray) { udp?.send(DatagramPacket(bytes, bytes.size)) }
        override fun closeAudio() { udp?.close(); udp = null }
        override fun startAudio(onFrame: (CapturedAudio) -> Unit, onError: (String) -> Unit, onState: (Boolean, String) -> Unit) {
            val active = AtomicBoolean(true); capture.set(false); capture = active
            onState(true, "capturing")
            Thread { while (active.get()) { onFrame(CapturedAudio(ByteArray(960), monotonicMicros())); Thread.sleep(10) } }.apply { isDaemon = true; start() }
        }
        override fun stopAudio() { capture.set(false) }
    }
    private suspend fun waitFor(condition: () -> Boolean) { withTimeout(9000) { while (!condition()) delay(15) } }
    @Test fun realPcPairingOwnershipResumeAndDisconnect() = runBlocking {
        val path = System.getenv("MINEMIC_PC_TEST_SERVER")
        assumeTrue("Set MINEMIC_PC_TEST_SERVER to run the real C++ fixture", !path.isNullOrEmpty())
        val process = ProcessBuilder(path, "--serve").redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val firstLine = process.inputStream.bufferedReader().readLine().split(' ')
        val port = firstLine[0].toInt(); val code = firstLine[1]
        val nativeA = Platform(port); val nativeB = Platform(port)
        val phoneA = MicController(nativeA); val phoneB = MicController(nativeB)
        try {
            phoneA.connect(code, ""); waitFor { phoneA.state.value.recording }
            assertTrue(phoneA.state.value.ownsPc)
            phoneB.connect(code, ""); waitFor { phoneB.state.value.phase == ConnectionPhase.ERROR }
            assertTrue(phoneB.state.value.message.contains("另一台手机"))
            // Close the actual socket without a protocol disconnect. Ownership must survive.
            nativeA.tcp?.close()
            waitFor { phoneA.state.value.phase == ConnectionPhase.RECONNECTING }
            assertTrue(phoneA.state.value.ownsPc)
            waitFor { phoneA.state.value.phase == ConnectionPhase.CONNECTED && phoneA.state.value.recording }
            phoneA.disconnect(); waitFor { !phoneA.state.value.ownsPc && phoneA.state.value.phase == ConnectionPhase.IDLE }
            phoneB.connect(code, ""); waitFor { phoneB.state.value.recording }
            assertTrue(phoneB.state.value.ownsPc, "explicit disconnect keeps the same pairing code usable")
        } finally { phoneA.close(); phoneB.close(); process.destroy(); process.waitFor() }
    }
    @Test fun realPcReferenceExtensionAndUnsupportedNativeFallbackKeepMicrophoneWorking() = runBlocking {
        val path = System.getenv("MINEMIC_PC_TEST_SERVER")
        assumeTrue("Set MINEMIC_PC_TEST_SERVER to run the real C++ fixture", !path.isNullOrEmpty())
        val process = ProcessBuilder(path, "--serve-reference").redirectError(ProcessBuilder.Redirect.INHERIT).start()
        val address = process.inputStream.bufferedReader().readLine().split(' ')
        val native = Platform(address[0].toInt()); val phone = MicController(native)
        try {
            phone.setEchoCancellation(true); phone.setNoiseSuppression(true)
            phone.connect(address[1], "")
            waitFor { native.validReferences.get() >= 10 && native.clockReplies.get() > 0 && phone.state.value.recording }
            // JVM deliberately has no native APM: enhanced mode must still upload raw PCM.
            assertTrue(phone.state.value.ownsPc)
            // No native APM exists on JVM. Receiving valid reference must not be
            // confused with AEC success, silence, or a reason to stop loopback.
            delay(1800) // Past the former 1.5 second AEC-success timeout.
            val before = native.validReferences.get()
            delay(600)
            assertTrue(native.validReferences.get() > before + 30, "reference was repeatedly paused because APM could not process it")
            phone.setEchoCancellation(false); delay(100)
            val stoppedAt = native.validReferences.get(); delay(250)
            assertEquals(stoppedAt, native.validReferences.get())
            assertTrue(phone.state.value.recording)
            phone.disconnect(); waitFor { !phone.state.value.ownsPc }
        } finally { phone.close(); process.destroy(); process.waitFor() }
    }
}
