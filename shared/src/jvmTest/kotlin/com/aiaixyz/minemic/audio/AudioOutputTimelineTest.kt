package com.aiaixyz.minemic.audio

import kotlin.test.*

class AudioOutputTimelineTest {
    private fun frame(at: Long): ByteArray = ByteArray(960).also { pcm ->
        repeat(480) { i ->
            val p = at+i
            val sample = if (p < 0) 0 else (((p*1103515245+12345) ushr 16) % 12000-6000).toInt()
            pcm[2*i] = sample.toByte(); pcm[2*i+1] = (sample shr 8).toByte()
        }
    }
    @Test fun lateProcessedFramesPreserveEverySampleWithoutAPathTimingJump() {
        val raw = RawAudioHistory(); val output = AudioOutputTimeline()
        val pending = mutableListOf<Triple<Int, Long, ByteArray>>()
        repeat(400) { index ->
            val position = raw.push(frame(index*480L))
            val processed = frame(position-430)
            val due = if (index % 52 < 3) index+3 else index
            pending += Triple(due, position-430, processed)
            pending.filter { it.first <= index }.forEach { output.offer(it.second, it.third) }
            pending.removeAll { it.first <= index }
            val actual = output.read(position-1920, raw.read(position-1920))
            assertContentEquals(frame(position-1920), actual.pcm, "frame $index skipped or replayed audio")
            if (index >= 4) assertEquals(480, actual.echoSamples, "late output should arrive before its fixed playout slot")
        }
    }
    @Test fun alreadyPlayedCorrectionsCannotReplayIntoNewAudioOrSurviveReset() {
        val output = AudioOutputTimeline()
        repeat(100) { index ->
            val at = index*480L
            assertContentEquals(frame(at), output.read(at, frame(at)).pcm)
            output.offer(at, ByteArray(960) { 127 })
        }
        output.clear()
        assertEquals(0, output.read(0, ByteArray(960)).echoSamples)
        assertContentEquals(ByteArray(960), output.read(480, ByteArray(960)).pcm)
    }
    @Test fun rawHistoryWrapsWithinItsFixedCapacityAndClearsCaptureHistory() {
        val history = RawAudioHistory()
        repeat(200) { index ->
            val at = history.push(frame(index*480L))
            assertContentEquals(frame(at-1920), history.read(at-1920))
        }
        history.clear()
        assertContentEquals(ByteArray(960), history.read(history.position-480))
    }
}
