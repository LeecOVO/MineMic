package com.aiaixyz.minemic.audio

/** A single worker owns this object. PCM is 48 kHz, signed little-endian, 10 ms. */
interface AudioProcessor {
    val supportsReferenceResampling: Boolean get() = false
    fun bufferReference(stereo: ByteArray, valid: Boolean = true): Boolean = false
    fun readReference(ratio: Double): ResampledReference? = null
    fun setDeskNoise(enabled: Boolean) {}
    fun render(stereo: ByteArray): Boolean
    fun process(mono: ByteArray, noise: Boolean, echo: Boolean): ByteArray?
    /** Advance the filter clock for a lost render frame; never publish this output. */
    fun advanceEcho(mono: ByteArray): Boolean = render(missingRender) && process(mono, noise = false, echo = true) != null
    fun resetEcho()
    fun close()
}

data class ResampledReference(val pcm: ByteArray, val valid: Boolean)

private val missingRender = ByteArray(1920)

internal expect fun createAudioProcessor(): AudioProcessor?
