package com.aiaixyz.minemic.audio

import kotlinx.coroutines.CoroutineDispatcher

/** Serial DSP execution, independent of UI, monitoring and general worker load. */
internal interface AudioProcessingExecution {
    val dispatcher: CoroutineDispatcher
    fun close()
}

internal expect fun createAudioProcessingExecution(): AudioProcessingExecution
