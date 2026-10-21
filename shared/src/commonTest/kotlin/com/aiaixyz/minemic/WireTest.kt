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
}
