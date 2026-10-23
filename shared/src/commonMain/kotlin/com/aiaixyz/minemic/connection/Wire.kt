package com.aiaixyz.minemic.connection

object Wire {
    const val PORT = 48721
    fun validCode(code: String) = code.length == 6 && code.all { it in '0'..'9' }
    fun validIpv4(host: String): Boolean {
        val parts = host.split('.')
        return parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } && (it.toIntOrNull() ?: -1) in 0..255 }
    }
    fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    fun unhex(value: String): ByteArray {
        require(value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(16) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
    fun audioBody(session: Long, sequence: Long, pcm: ByteArray): ByteArray {
        require(pcm.size == 960 && session in 1..0xffffffffL)
        val out = ByteArray(992)
        "MMIC".encodeToByteArray().copyInto(out)
        out[4] = 2; out[5] = 3
        fun put(offset: Int, value: Long, size: Int = 4) {
            repeat(size) { out[offset + it] = (value ushr ((size - it - 1) * 8)).toByte() }
        }
        put(8, session); put(12, sequence); put(16, sequence * 480); put(20, 48000)
        put(24, 480, 2); out[26] = 1; out[27] = 1; put(28, 960, 2)
        pcm.copyInto(out, 32)
        return out
    }
}

/** Single-sender scratch storage. Each returned packet owns its bytes. */
internal class AudioPacketEncoder(session: Long, private val authenticate: (ByteArray) -> ByteArray) {
    private val body = Wire.audioBody(session, 0, ByteArray(960))

    fun encode(sequence: Long, pcm: ByteArray, muted: Boolean): ByteArray {
        require(pcm.size == 960)
        for (i in 0 until 4) {
            body[12 + i] = (sequence ushr ((3 - i) * 8)).toByte()
            body[16 + i] = ((sequence * 480) ushr ((3 - i) * 8)).toByte()
        }
        if (muted) body.fill(0, 32, 992) else pcm.copyInto(body, 32)
        val mac = authenticate(body)
        check(mac.size == 32)
        return ByteArray(1024).also {
            body.copyInto(it)
            mac.copyInto(it, 992)
        }
    }
}

/** Deliberately small JSON string map. No unbounded input or ambiguous duplicate fields. */
object ControlJson {
    fun encode(fields: Map<String, String>): String {
        fun quote(value: String) = buildString {
            append('"')
            value.forEach { c -> when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\")
                '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c.code < 32) append("\\u${c.code.toString(16).padStart(4, '0')}") else append(c)
            } }
            append('"')
        }
        return (mapOf("app" to "MineMic", "v" to "2") + fields).entries.joinToString(",", "{", "}") { quote(it.key) + ":" + quote(it.value) }
    }
    fun decode(line: String): Map<String, String> {
        require(line.encodeToByteArray().size <= 1024)
        var p = 0
        fun space() { while (p < line.length && line[p].isWhitespace()) p++ }
        fun take(c: Char) { space(); require(p < line.length && line[p++] == c) }
        fun string(): String {
            take('"')
            return buildString {
                while (true) {
                    require(p < line.length)
                    val c = line[p++]
                    if (c == '"') break
                    require(c.code >= 32)
                    if (c != '\\') append(c) else {
                        require(p < line.length)
                        append(when (val escaped = line[p++]) {
                            '"', '\\', '/' -> escaped
                            'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; 'b' -> '\b'; 'f' -> '\u000c'
                            'u' -> { require(p + 4 <= line.length); val n = line.substring(p, p + 4).toInt(16); p += 4; n.toChar() }
                            else -> error("Invalid JSON escape")
                        })
                    }
                }
            }
        }
        val result = mutableMapOf<String, String>()
        take('{'); space()
        if (p < line.length && line[p] != '}') while (true) {
            val key = string(); take(':'); val value = string()
            require(result.put(key, value) == null)
            space(); if (p < line.length && line[p] == ',') p++ else break
        }
        take('}'); space(); require(p == line.length)
        require(result["app"] == "MineMic" && result["v"] == "2")
        return result
    }
}
