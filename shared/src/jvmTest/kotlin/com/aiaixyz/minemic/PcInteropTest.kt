package com.aiaixyz.minemic

import com.aiaixyz.minemic.connection.*
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import kotlin.test.*
import java.net.*
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PcInteropTest {
    private class Platform(private val port: Int) : PhonePlatform {
        @Volatile var tcp: Socket? = null
        @Volatile var udp: DatagramSocket? = null
        private var capture = AtomicBoolean(false)
        @Volatile private var epoch = 0
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
                    while (epoch == attempt) { val line = reader.readLine() ?: break; onLine(line) }
                    if (epoch == attempt) onClosed()
                } catch (e: Exception) { if (epoch == attempt) onError(e.message ?: "closed") }
            }.apply { isDaemon = true; start() }
        }
        override fun sendControl(line: String) { tcp?.getOutputStream()?.write((line + "\n").toByteArray()) }
        override fun closeControl() { epoch++; runCatching { tcp?.close() }; tcp = null }
        override fun openAudio(host: String, port: Int) { closeAudio(); udp = DatagramSocket().apply { connect(InetAddress.getByName(host), port) } }
        override fun sendAudio(bytes: ByteArray) { udp?.send(DatagramPacket(bytes, bytes.size)) }
        override fun closeAudio() { udp?.close(); udp = null }
        override fun startAudio(onFrame: (ByteArray) -> Unit, onError: (String) -> Unit) {
            val active = AtomicBoolean(true); capture.set(false); capture = active
            Thread { while (active.get()) { onFrame(ByteArray(960)); Thread.sleep(10) } }.apply { isDaemon = true; start() }
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
            phoneB.connect(code, ""); waitFor { phoneB.state.value.phase == ConnectionPhase.ERROR && phoneB.state.value.message.contains("配对码") }
        } finally { phoneA.close(); phoneB.close(); process.destroy(); process.waitFor() }
    }
}
