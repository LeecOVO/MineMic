package com.aiaixyz.minemic.connection

/** At most four PC-specific recovery leases, never an audio key or a recording. */
internal class ConnectionLeases(private val platform: PhonePlatform) {
    data class Lease(val host: String, val port: Int, val clientId: String, val token: String,
                     val instance: String, val releasePending: Boolean = false)
    private val leases = ArrayDeque<Lease>()
    init {
        runCatching { platform.loadConnectionState() }.getOrNull()?.takeIf { it.length <= 2048 }
            ?.lineSequence()?.take(4)?.forEach { line ->
                val fields = line.split('|')
                if (fields.size == 6) {
                    val port = fields[1].toIntOrNull()
                    if (Wire.validIpv4(fields[0]) && port != null && port in 1024..65535 &&
                        hex(fields[2]) && hex(fields[3]) && (fields[4].isEmpty() || hex(fields[4])) && fields[5] in listOf("0", "1"))
                        leases.addLast(Lease(fields[0], port, fields[2], fields[3], fields[4], fields[5] == "1"))
                }
            }
    }
    fun find(pc: LanComputer): Lease? = leases.lastOrNull { it.host == pc.host && it.port == pc.port }
    fun remember(lease: Lease) {
        if (!Wire.validIpv4(lease.host) || lease.port !in 1024..65535 || !hex(lease.clientId) ||
            !hex(lease.token) || (lease.instance.isNotEmpty() && !hex(lease.instance))) return
        leases.removeAll { it.host == lease.host && it.port == lease.port }
        if (leases.size >= 4) leases.removeFirst()
        leases.addLast(lease); persist()
    }
    fun forget(pc: LanComputer) {
        if (leases.removeAll { it.host == pc.host && it.port == pc.port }) persist()
    }
    private fun persist() {
        val state = leases.takeIf { it.isNotEmpty() }?.joinToString("\n") {
            listOf(it.host, it.port, it.clientId, it.token, it.instance, if (it.releasePending) "1" else "0").joinToString("|")
        }
        // A storage failure must not break a working live microphone session.
        runCatching { platform.saveConnectionState(state) }
    }
    private fun hex(value: String) = value.length == 32 && value.all { it in '0'..'9' || it in 'a'..'f' }
}
