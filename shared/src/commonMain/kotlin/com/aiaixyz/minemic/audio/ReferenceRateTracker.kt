package com.aiaixyz.minemic.audio

import kotlin.math.abs

/** Sample-position slopes, not UDP arrival intervals, estimate the two rates. */
internal class ReferenceRateTracker {
    private class Rate {
        var rate = 1.0
        private var position: Long? = null
        private var time = 0L
        fun observe(nextPosition: Long, nextTime: Long) {
            val first = position
            if (first == null || nextPosition <= first || nextTime <= time) {
                position = nextPosition; time = nextTime; return
            }
            val elapsed = nextTime - time
            if (elapsed < 1000000) return
            val measured = (nextPosition - first).toDouble() * 1000000.0 / (48000.0 * elapsed)
            if (measured in .998..1.002) rate += (measured - rate) * .25
            position = nextPosition; time = nextTime
        }
    }
    private val render = Rate()
    private var capture = Rate()
    private var capturePosition = 0L
    private var previousCapture: Long? = null
    var ratio = 1.0; private set
    val outputDurationMicros: Double get() = 10000.0 * ratio / render.rate
    fun render(position: Long, time: Long) = render.observe(position, time)
    fun capture(time: Long): Double {
        val previous = previousCapture
        if (previous != null && time - previous !in 5000..15000) { capture = Rate(); capturePosition = 0 }
        capture.observe(capturePosition, time); capturePosition += 480; previousCapture = time
        val desired = (render.rate / capture.rate).coerceIn(.998, 1.002)
        if (abs(desired - ratio) > .0000001) ratio += (desired - ratio).coerceIn(-.000001, .000001)
        return ratio
    }
}
