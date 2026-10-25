package com.aiaixyz.minemic.audio

import kotlinx.coroutines.Dispatchers

internal actual fun createAudioProcessingExecution(): AudioProcessingExecution = object : AudioProcessingExecution {
    override val dispatcher = Dispatchers.Default.limitedParallelism(1)
    override fun close() {}
}
