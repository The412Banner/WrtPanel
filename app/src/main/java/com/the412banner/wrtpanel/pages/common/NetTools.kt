package com.the412banner.wrtpanel.pages.common

import com.the412banner.wrtpanel.form.Datatypes

/** Helpers from LuCI's network.js / tools/network.js. */
object NetTools {
    data class Proto(val n: String, val i: Int, val d: String)

    /** tools/network.js `protocols` (name, number, description). */
    val protocols = listOf(
        Proto("hopopt", 0, "HOPOPT"), Proto("icmp", 1, "ICMP"), Proto("igmp", 2, "IGMP"), Proto("ggp", 3, "GGP"),
        Proto("ipencap", 4, "IP-ENCAP"), Proto("st", 5, "ST"), Proto("tcp", 6, "TCP"), Proto("egp", 8, "EGP"),
        Proto("igp", 9, "IGP"), Proto("pup", 12, "PUP"), Proto("udp", 17, "UDP"), Proto("hmp", 20, "HMP"),
        Proto("xns-idp", 22, "XNS-IDP"), Proto("rdp", 27, "RDP"), Proto("iso-tp4", 29, "ISO-TP4"), Proto("dccp", 33, "DCCP"),
        Proto("xtp", 36, "XTP"), Proto("ddp", 37, "DDP"), Proto("idpr-cmtp", 38, "IDPR-CMTP"), Proto("ipv6", 41, "IPv6"),
        Proto("ipv6-route", 43, "IPv6-Route"), Proto("ipv6-frag", 44, "IPv6-Frag"), Proto("idrp", 45, "IDRP"),
        Proto("rsvp", 46, "RSVP"), Proto("gre", 47, "GRE"), Proto("esp", 50, "IPSEC-ESP"), Proto("ah", 51, "IPSEC-AH"),
        Proto("skip", 57, "SKIP"), Proto("icmpv6", 58, "IPv6-ICMP"), Proto("ipv6-nonxt", 59, "IPv6-NoNxt"),
        Proto("ipv6-opts", 60, "IPv6-Opts"), Proto("rspf", 73, "CPHB"), Proto("vmtp", 81, "VMTP"), Proto("eigrp", 88, "EIGRP"),
        Proto("ospf", 89, "OSPFIGP"), Proto("ax.25", 93, "AX.25"), Proto("ipip", 94, "IPIP"), Proto("etherip", 97, "ETHERIP"),
        Proto("encap", 98, "ENCAP"), Proto("pim", 103, "PIM"), Proto("ipcomp", 108, "IPCOMP"), Proto("vrrp", 112, "VRRP"),
        Proto("l2tp", 115, "L2TP"), Proto("isis", 124, "ISIS"), Proto("sctp", 132, "SCTP"), Proto("fc", 133, "FC"),
        Proto("mobility-header", 135, "Mobility-Header"), Proto("udplite", 136, "UDPLite"), Proto("mpls-in-ip", 137, "MPLS-in-IP"),
        Proto("manet", 138, "MANET"), Proto("hip", 139, "HIP"), Proto("shim6", 140, "Shim6"), Proto("wesp", 141, "WESP"),
        Proto("rohc", 142, "ROHC"),
    )

    /** network.maskToPrefix */
    fun maskToPrefix(mask: String, v6: Boolean): Int? {
        val m = (if (v6) Datatypes.parseIPv6(mask) else Datatypes.parseIPv4(mask))?.copyOf() ?: return null
        var bits = 0
        var z = false
        val top = if (v6) 0x8000 else 0x80
        val full = if (v6) 0xffff else 0xff
        for (i in m.indices) {
            z = z || m[i] == 0
            while (!z && (m[i] and top) != 0) { m[i] = (m[i] shl 1) and full; bits++ }
            if (m[i] != 0) return null
        }
        return bits
    }

    /** network.prefixToMask */
    fun prefixToMask(bits0: Int, v6: Boolean): String? {
        var bits = bits0
        val w = if (v6) 128 else 32
        if (bits < 0 || bits > w) return null
        val m = IntArray(if (v6) 8 else 4)
        val step = if (v6) 16 else 8
        val full = if (v6) 0xffff else 0xff
        for (i in m.indices) {
            val b = minOf(bits, step)
            m[i] = if (b == 0) 0 else (full shl (step - b)) and full
            bits -= b
        }
        return if (v6) m.joinToString(":") { it.toString(16) } else m.joinToString(".")
    }

    /** "/etc/iproute2/rt_tables" lines → (id, name), ids > 0. */
    fun parseRtTables(text: String): List<Pair<Int, String>> = text.lines().mapNotNull { l ->
        val m = Regex("^(\\d+)\\s+(\\S+)$").find(l.trim()) ?: return@mapNotNull null
        val id = m.groupValues[1].toInt()
        if (id > 0) id to m.groupValues[2] else null
    }
}
