package com.aiaixyz.minemic.connection

data class LanComputer(val host: String, val port: Int, val name: String)

/** Native adapters do OS discovery, sockets and capture; session policy stays in Kotlin. */
interface PhonePlatform {
    fun deviceName(): String
    fun randomBytes(count: Int): ByteArray
    fun hmac(body: ByteArray, key: ByteArray): ByteArray
    fun discover(onFound: (LanComputer) -> Unit, onError: (String) -> Unit)
    fun stopDiscovery()
    fun openControl(host: String, port: Int, onReady: () -> Unit, onLine: (String) -> Unit, onClosed: () -> Unit, onError: (String) -> Unit)
    fun sendControl(line: String)
    fun closeControl()
    fun openAudio(host: String, port: Int)
    fun sendAudio(bytes: ByteArray)
    fun closeAudio()
    fun startAudio(onFrame: (ByteArray) -> Unit, onError: (String) -> Unit)
    fun stopAudio()
}
