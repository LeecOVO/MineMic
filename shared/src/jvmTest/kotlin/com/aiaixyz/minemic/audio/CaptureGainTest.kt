package com.aiaixyz.minemic.audio

import kotlin.math.abs
import kotlin.test.*

class CaptureGainTest {
    private fun pcm(sample: (Int) -> Int) = ByteArray(960).also { out ->
        repeat(480) { i -> val n = sample(i); out[2*i] = n.toByte(); out[2*i+1] = (n shr 8).toByte() }
    }
    private fun sample(pcm: ByteArray, i: Int) = (pcm[2*i].toInt() and 255) or (pcm[2*i+1].toInt() shl 8)

    @Test fun zeroAndInvalidGainBypassBitExactlyEvenAfterBoost() {
        val processor = CaptureGain(); val raw = pcm { if (it % 2 == 0) 32767 else -32768 }
        repeat(10) { processor.process(raw, 18f) }
        assertSame(raw, processor.process(raw, 0f))
        assertSame(raw, processor.process(raw, Float.NaN))
        assertSame(raw, processor.process(raw, Float.POSITIVE_INFINITY))
    }
    @Test fun quietSpeechGetsRequestedGainWithSmoothChanges() {
        val processor = CaptureGain(); val raw = pcm { 1000 }
        val first = processor.process(raw, 6f)
        assertTrue(sample(first, 0) in 1000..1010)
        assertTrue(sample(first, 479) > sample(first, 0))
        var last = first
        repeat(30) { last = processor.process(raw, 6f) }
        assertTrue(abs(sample(last, 479) - 1995) <= 3)
        assertContentEquals(raw, processor.process(raw, 0f))
    }
    @Test fun maximumBoostNeverWrapsOrInvertsPolarity() {
        val processor = CaptureGain(); val raw = pcm { if (it % 2 == 0) 20000 else -20000 }
        repeat(50) {
            val output = processor.process(raw, 100f)
            repeat(480) { i ->
                val n = sample(output, i)
                assertTrue(abs(n) <= 32500)
                assertEquals(i % 2 == 0, n > 0)
            }
        }
    }
}
