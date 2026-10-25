package com.aiaixyz.minemic.connection

data class CapturedAudio(val pcm: ByteArray, val capturedAtMicros: Long)

data class LanComputer(val host: String, val port: Int, val name: String)

/** Native adapters do OS discovery, sockets and capture; session policy stays in Kotlin. */
interface PhonePlatform {
    fun monotonicMicros(): Long
    fun deviceName(): String
    fun randomBytes(count: Int): ByteArray
    fun hmac(body: ByteArray, key: ByteArray): ByteArray
    fun discover(onFound: (LanComputer) -> Unit, onError: (String) -> Unit)
    fun stopDiscovery()
    fun openControl(host: String, port: Int, onReady: () -> Unit, onLine: (String) -> Unit, onClosed: () -> Unit, onError: (String) -> Unit)
    fun sendControl(line: String)
    fun closeControl()
    /** Detach the current socket and best-effort flush one final line within 200 ms. */
    fun finishControl(line: String, timeoutMillis: Int = 200) { sendControl(line); closeControl() }
    fun openAudio(host: String, port: Int, onReference: (ByteArray) -> Unit, onError: (String) -> Unit)
    fun sendAudio(bytes: ByteArray)
    fun closeAudio()
    fun startAudio(onFrame: (CapturedAudio) -> Unit, onError: (String) -> Unit, onState: (Boolean, String) -> Unit)
    fun stopAudio()
    /** Numeric, local diagnostics only. Production adapters may discard them. */
    fun reportAudioDiagnostics(message: String) {}
    fun recordAudioDiagnosticFrame(kind: String, capturedAtMicros: Long, pcm: ByteArray) {}
}
