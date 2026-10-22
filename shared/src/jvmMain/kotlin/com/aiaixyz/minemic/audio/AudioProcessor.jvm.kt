package com.aiaixyz.minemic.audio

// Desktop previews do not ship the mobile DSP library.
internal actual fun createAudioProcessor(): AudioProcessor? = null
