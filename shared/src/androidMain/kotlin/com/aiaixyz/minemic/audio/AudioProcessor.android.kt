package com.aiaixyz.minemic.audio

object NativeApm {
    external fun create(): Long
    external fun destroy(handle: Long)
    external fun render(handle: Long, pcm: ByteArray): Boolean
    external fun process(handle: Long, pcm: ByteArray, noise: Boolean, echo: Boolean): ByteArray?
    external fun resetEcho(handle: Long)
    external fun setDeskNoise(handle: Long, enabled: Boolean)
    external fun bufferReference(handle: Long, pcm: ByteArray, valid: Boolean): Boolean
    external fun readReference(handle: Long, ratio: Double): ByteArray?
}

internal actual fun createAudioProcessor(): AudioProcessor? = try {
    System.loadLibrary("minemic_apm_jni")
    val handle = NativeApm.create()
    if (handle == 0L) null else object : AudioProcessor {
        private var ptr = handle
        override val supportsReferenceResampling = true
        override fun bufferReference(stereo: ByteArray, valid: Boolean) = ptr != 0L && stereo.size == 1920 && NativeApm.bufferReference(ptr, stereo, valid)
        override fun readReference(ratio: Double): ResampledReference? = if (ptr == 0L) null else NativeApm.readReference(ptr, ratio)?.let {
            if (it.size == 1921) ResampledReference(it.copyOf(1920), it[1920] == 1.toByte()) else null
        }
        override fun setDeskNoise(enabled: Boolean) { if (ptr != 0L) NativeApm.setDeskNoise(ptr, enabled) }
        override fun render(stereo: ByteArray) = ptr != 0L && stereo.size == 1920 && NativeApm.render(ptr, stereo)
        override fun process(mono: ByteArray, noise: Boolean, echo: Boolean) =
            if (ptr == 0L || mono.size != 960) null else NativeApm.process(ptr, mono, noise, echo)
        override fun resetEcho() { if (ptr != 0L) NativeApm.resetEcho(ptr) }
        override fun close() { if (ptr != 0L) { NativeApm.destroy(ptr); ptr = 0L } }
    }
} catch (_: LinkageError) { null }
