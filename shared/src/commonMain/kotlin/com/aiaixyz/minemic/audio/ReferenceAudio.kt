package com.aiaixyz.minemic.audio

import kotlin.math.abs

internal const val REFERENCE_CAPABILITY = "ref-pcm-stereo-v1"

internal data class ReferenceChannel(val sequence: Long, val position: Long, val timeMicros: Long,
                                     val epoch: Long, val channel: Int, val pcm: ByteArray)

/** A separate authenticated format; never changes the existing microphone packets. */
internal object ReferenceAudio {
    fun decode(packet: ByteArray, session: Long, key: ByteArray, hmac: (ByteArray, ByteArray) -> ByteArray): ReferenceChannel? {
        if (packet.size != 1040 || !packet.copyOfRange(0, 4).contentEquals("MREF".encodeToByteArray())) return null
        if (packet[4] != 1.toByte() || packet[5] != 2.toByte() || packet[6].toInt() !in 0..1 || packet[7] != 0.toByte()) return null
        fun read(offset: Int, size: Int = 4): Long {
            var value = 0L; repeat(size) { value = (value shl 8) or (packet[offset + it].toLong() and 255) }; return value
        }
        if (read(8) != session || read(32) != 48000L || read(36, 2) != 480L || read(38, 2) != 960L || read(44) != 0L) return null
        val mac = hmac(packet.copyOfRange(0, 1008), key)
        if (mac.size != 32) return null
        var difference = 0
        repeat(32) { difference = difference or (mac[it].toInt() xor packet[1008 + it].toInt()) }
        if (difference != 0 || read(24, 8) < 0 || read(16, 8) < 0) return null
        return ReferenceChannel(read(12), read(16, 8), read(24, 8), read(40), packet[6].toInt(), packet.copyOfRange(48, 1008))
    }
}

/** NTP-style four timestamps. Offset is PC minus phone; RTT is not one-way delay. */
internal class ClockSync {
    data class Mapping(val pcMinusPhone: Long, val measuredAt: Long, val roundTripMicros: Long)
    private val samples = ArrayDeque<Mapping>()
    private val pending = mutableSetOf<Long>()
    var mapping: Mapping? = null; private set
    fun sent(t1: Long) { if (pending.size >= 8) pending.clear(); pending += t1 }
    fun receive(t1: Long, t2: Long, t3: Long, t4: Long): Boolean {
        if (!pending.remove(t1) || t4 < t1 || t3 < t2 || t2 < 0 || t3 < 0) return false
        val rtt = (t4 - t1) - (t3 - t2)
        if (rtt !in 0..80000) return false
        val offset = ((t2 - t1) + (t3 - t4)) / 2
        samples.addLast(Mapping(offset, t4, rtt))
        while (samples.isNotEmpty() && (samples.size > 12 || t4 - samples.first().measuredAt > 5000000)) samples.removeFirst()
        val best = samples.minBy { it.roundTripMicros }
        // Do not jump the timeline on an isolated congested sample.
        val previous = mapping
        val chosenOffset = if (previous == null || abs(best.pcMinusPhone - previous.pcMinusPhone) > 10000) best.pcMinusPhone
                           else (previous.pcMinusPhone * 3 + best.pcMinusPhone) / 4
        // Freshness means the last accepted exchange, not the age of the
        // lowest-RTT offset sample. Otherwise an active 1 Hz sync can expire
        // between replies whenever that older sample crosses five seconds.
        mapping = Mapping(chosenOffset, t4, best.roundTripMicros)
        return true
    }
    fun current(now: Long): Mapping? = mapping?.takeIf { now - it.measuredAt in 0..5000000 }
}
