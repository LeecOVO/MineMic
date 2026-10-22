package com.aiaixyz.minemic.audio

/** A single worker owns this object. PCM is 48 kHz, signed little-endian, 10 ms. */
interface AudioProcessor {
    fun setDeskNoise(enabled: Boolean) {}
    fun render(stereo: ByteArray): Boolean
    fun process(mono: ByteArray, noise: Boolean, echo: Boolean): ByteArray?
    fun resetEcho()
    fun close()
}

internal expect fun createAudioProcessor(): AudioProcessor?
