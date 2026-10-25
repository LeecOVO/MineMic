@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package com.aiaixyz.minemic.audio

import com.aiaixyz.minemic.audio.nativeapm.*
import kotlinx.cinterop.*

internal actual fun createAudioProcessor(): AudioProcessor? {
    val handle = mm_apm_create() ?: return null
    return object : AudioProcessor {
        private var ptr = handle
        private var closed = false
        override val supportsReferenceResampling = true
        override fun bufferReference(stereo: ByteArray, valid: Boolean): Boolean = !closed && stereo.size == 1920 && stereo.usePinned {
            mm_apm_buffer_reference(ptr, it.addressOf(0).reinterpret(), if (valid) 1 else 0) == 0
        }
        override fun readReference(ratio: Double): ResampledReference? {
            if (closed) return null
            val pcm = ByteArray(1920)
            val status = pcm.usePinned { mm_apm_read_reference(ptr, ratio, it.addressOf(0).reinterpret()) }
            return if (status == 0 || status == 2) ResampledReference(pcm, status == 0) else null
        }
        override fun setDeskNoise(enabled: Boolean) { if (!closed) mm_apm_set_desk_noise(ptr, if (enabled) 1 else 0) }
        override fun render(stereo: ByteArray): Boolean = !closed && stereo.size == 1920 && stereo.usePinned {
            mm_apm_render(ptr, it.addressOf(0).reinterpret()) == 0
        }
        override fun process(mono: ByteArray, noise: Boolean, echo: Boolean): ByteArray? {
            if (closed || mono.size != 960) return null
            val output = ByteArray(960)
            val status = mono.usePinned { input -> output.usePinned { out ->
                mm_apm_capture(ptr, input.addressOf(0).reinterpret(), out.addressOf(0).reinterpret(), if (noise) 1 else 0, if (echo) 1 else 0)
            } }
            return if (status == 0) output else null
        }
        override fun resetEcho() { if (!closed) mm_apm_reset_echo(ptr) }
        override fun close() { if (!closed) { mm_apm_destroy(ptr); closed = true } }
    }
}
