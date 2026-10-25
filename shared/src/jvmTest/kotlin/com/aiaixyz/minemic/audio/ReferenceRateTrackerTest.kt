package com.aiaixyz.minemic.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ReferenceRateTrackerTest {
    @Test fun independentClocksConvergeWithoutUsingPacketArrivalTimes() {
        for (ppm in listOf(-200, 200)) {
            val tracker = ReferenceRateTracker()
            val rate = 1.0 + ppm / 1000000.0
            var previous = 1.0
            repeat(12000) { block ->
                tracker.render(block * 480L, 1000000 + (block * 10000 / rate).toLong())
                val ratio = tracker.capture(1000000 + block * 10000L)
                assertTrue(abs(ratio - previous) <= .00000101, "ratio must slew smoothly")
                previous = ratio
            }
            assertTrue(abs(tracker.ratio - rate) < .000002, "independent sample clocks should converge: $ppm ppm, ${tracker.ratio}")
        }
    }
    @Test fun stalledCaptureDoesNotBecomeAnExtremeResampleRatio() {
        val tracker = ReferenceRateTracker()
        repeat(1000) { tracker.render(it * 480L, it * 10000L); tracker.capture(it * 10000L) }
        tracker.capture(100000000)
        repeat(1000) { tracker.render((it + 1000) * 480L, (it + 1000) * 10000L); tracker.capture(100010000 + it * 10000L) }
        assertTrue(abs(tracker.ratio - 1.0) < .000002)
    }
}
