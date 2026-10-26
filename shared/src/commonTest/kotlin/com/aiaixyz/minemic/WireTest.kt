package com.aiaixyz.minemic
import com.aiaixyz.minemic.connection.*
import kotlin.test.*
class WireTest {
    @Test fun sixDigitCodesAndIpv4() {
        assertTrue(Wire.validCode("000007")); assertTrue(Wire.validCode("938205"))
        for (bad in listOf("12345", "1234567", "１２３４５６", "12a456", " 12345")) assertFalse(Wire.validCode(bad))
        assertTrue(Wire.validIpv4("192.168.1.2")); assertFalse(Wire.validIpv4("192.168.1.999"))
        assertFalse(Wire.validIpv4("localhost")); assertFalse(Wire.validIpv4("1.2.3"))
    }
    @Test fun jsonEscapesAndRejectsAmbiguity() {
        val fields = mapOf("name" to "手机\"a\\b\n\u0001", "type" to "connect")
        val decoded = ControlJson.decode(ControlJson.encode(fields))
        assertEquals(fields["name"], decoded["name"])
        assertFails { ControlJson.decode("""{"app":"MineMic","v":"2","app":"Other"}""") }
        assertFails { ControlJson.decode("""{"app":"MineMic","v":"1"}""") }
        assertFails { ControlJson.decode("""{"app":"MineMic","v":"2","n":123}""") }
    }
    @Test fun ipv6LiteralsAreValidatedWithoutDnsAndCanonicalizedForRecovery() {
        for (host in listOf("::", "::1", "2001:db8::1234", "FD12:3456:0:0:0:0:0:A", "fe80::1%en0",
                "fe80::1%12", "[fd12::abcd]", "::ffff:192.168.1.2", "1:2:3:4:5:6:7:8")) {
            assertTrue(Wire.validIpv6(host), host)
            assertTrue(IpVersion.IPV6.accepts(assertNotNull(Wire.normalizeIpAddress(host))))
            assertFalse(IpVersion.IPV4.accepts(host))
        }
        for (host in listOf("localhost", "192.168.1.2", ":1", "1:", "1::2::3", "1:::2", "1:2:3:4:5:6:7",
                "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7::8", "12345::", "gggg::", "[::1]:48721", "::1%",
                "::1%en0%1", "::1%bad|zone", "::1/64", "::ffff:999.1.1.1", "https://[::1]", " ::1"))
            assertFalse(Wire.validIpv6(host), host)
        assertEquals("fd12:0:0:0:0:0:0:a", Wire.normalizeIpAddress(" [FD12::000a] "))
        assertEquals("fe80:0:0:0:0:0:0:1%en0", Wire.normalizeIpAddress("fe80::1%en0"))
        assertEquals("192.168.1.2", Wire.normalizeIpAddress("192.168.001.002"))
        assertNull(Wire.normalizeIpAddress("[::1]:48721"))
    }
    @Test fun audioHeaderAndWrap() {
        val pcm = ByteArray(960) { it.toByte() }
        val b = Wire.audioBody(0x12345678, 0xffffffffL, pcm)
        assertEquals(992, b.size); assertEquals("MMIC", b.copyOfRange(0, 4).decodeToString())
        assertEquals(2, b[4].toInt()); assertEquals(3, b[5].toInt())
        assertContentEquals(byteArrayOf(0x12, 0x34, 0x56, 0x78), b.copyOfRange(8, 12))
        assertContentEquals(ByteArray(4) { -1 }, b.copyOfRange(12, 16))
        assertContentEquals(pcm, b.copyOfRange(32, 992))
        assertFails { Wire.audioBody(1, 0, ByteArray(959)) }
    }

    @Test fun reusableEncoderPreservesWireBytesAndDoesNotOverwriteQueuedPackets() {
        val signatures = mutableListOf<ByteArray>()
        val encoder = AudioPacketEncoder(0x12345678) { body ->
            signatures += body.copyOf()
            ByteArray(32) { (body[12].toInt() xor it).toByte() }
        }
        val pcm = ByteArray(960) { (it * 7).toByte() }
        val packets = mutableListOf<ByteArray>()
        val expected = mutableListOf<ByteArray>()
        for ((index, sequence) in listOf(0L, 1L, 0xffffffffL, 0x100000000L, 0x100000001L).withIndex()) {
            val muted = index % 2 == 1
            val body = Wire.audioBody(0x12345678, sequence, if (muted) ByteArray(960) else pcm)
            expected += body + ByteArray(32) { (body[12].toInt() xor it).toByte() }
            packets += encoder.encode(sequence, pcm, muted)
            assertContentEquals(body, signatures.last())
        }
        for (i in packets.indices) assertContentEquals(expected[i], packets[i])
        assertContentEquals(ByteArray(960) { (it * 7).toByte() }, pcm)
        assertFails { encoder.encode(0, ByteArray(959), false) }
    }
}
