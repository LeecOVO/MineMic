package com.aiaixyz.minemic.audio

/** Current one-second window, plus counters for this microphone session. No PCM. */
data class ProcessingStatistics(
    val referenceFramesPerSecond: Int = 0,
    val echoPercent: Int = 0,
    val fallbackFramesPerSecond: Int = 0,
    val realignments: Long = 0,
    val timeouts: Long = 0,
    val available: Boolean = false,
)
