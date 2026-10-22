package com.aiaixyz.minemic.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/** Optional digital gain after denoising, owned by the single audio sender. */
internal class CaptureGain {
    private var gain = 1.0
    private val smoothing = 1.0 - exp(-1.0 / (48000.0 * 0.020))

    fun process(pcm: ByteArray, decibels: Float): ByteArray {
        val db = if (decibels.isFinite()) decibels.coerceIn(0f, 18f) else 0f
        // Turning enhancement off is an immediate, bit-exact bypass.
        if (db == 0f) { gain = 1.0; return pcm }
        if (pcm.size != 960) return pcm
        val target = 10.0.pow(db / 20.0)
        val output = ByteArray(pcm.size)
        for (i in 0 until 480) {
            val input = (pcm[2 * i].toInt() and 255) or (pcm[2 * i + 1].toInt() shl 8)
            gain += (target - gain) * smoothing
            val amplified = input * gain
            val magnitude = abs(amplified)
            // Unity slope at the knee, smoothly approaching full scale. No PCM
            // wraparound and no extra buffering/look-ahead or automatic noise lift.
            val limited = if (magnitude <= 26000.0) amplified else {
                val value = 26000.0 + 6500.0 * tanh((magnitude - 26000.0) / 6500.0)
                if (amplified < 0) -value else value
            }
            val sample = limited.roundToInt().coerceIn(-32768, 32767)
            output[2 * i] = sample.toByte()
            output[2 * i + 1] = (sample shr 8).toByte()
        }
        return output
    }
}
