package com.aiaixyz.minemic.audio

import android.os.Process
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

internal actual fun createAudioProcessingExecution(): AudioProcessingExecution = object : AudioProcessingExecution {
    // The executor creates its thread only when the lazy DSP worker first runs.
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread({
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE) }
            task.run()
        }, "MineMic.audio-processing").apply { isDaemon = true }
    }
    override val dispatcher = executor.asCoroutineDispatcher()
    override fun close() { dispatcher.close() }
}
