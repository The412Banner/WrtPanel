package com.the412banner.wrtpanel.pages.firewall

/**
 * Non-UI parts of luci-app-firewall tools/firewall.js (protocol table, lookupProto, fmt,
 * map_invert, host hints) and the zone colours of luci-base firewall.js / tools/prng.js.
 */
object FwTool {
    data class Proto(val name: String, val num: Int, val label: String)

    /** tools/firewall.js `protocols`. */
    val protocols = listOf(
        Proto("ip", 0, "IP"), Proto("hopopt", 0, "HOPOPT"), Proto("icmp", 1, "ICMP"), Proto("igmp", 2, "IGMP"),
        Proto("ggp", 3, "GGP"), Proto("ipencap", 4, "IP-ENCAP"), Proto("st", 5, "ST"), Proto("tcp", 6, "TCP"),
        Proto("egp", 8, "EGP"), Proto("igp", 9, "IGP"), Proto("pup", 12, "PUP"), Proto("udp", 17, "UDP"),
        Proto("hmp", 20, "HMP"), Proto("xns-idp", 22, "XNS-IDP"), Proto("rdp", 27, "RDP"), Proto("iso-tp4", 29, "ISO-TP4"),
        Proto("dccp", 33, "DCCP"), Proto("xtp", 36, "XTP"), Proto("ddp", 37, "DDP"), Proto("idpr-cmtp", 38, "IDPR-CMTP"),
        Proto("ipv6", 41, "IPv6"), Proto("ipv6-route", 43, "IPv6-Route"), Proto("ipv6-frag", 44, "IPv6-Frag"),
        Proto("idrp", 45, "IDRP"), Proto("rsvp", 46, "RSVP"), Proto("gre", 47, "GRE"), Proto("esp", 50, "IPSEC-ESP"),
        Proto("ah", 51, "IPSEC-AH"), Proto("skip", 57, "SKIP"), Proto("icmpv6", 58, "IPv6-ICMP"), Proto("ipv6-icmp", 58, "IPv6-ICMP"),
        Proto("ipv6-nonxt", 59, "IPv6-NoNxt"), Proto("ipv6-opts", 60, "IPv6-Opts"), Proto("rspf", 73, "RSPF"), Proto("rspf", 73, "CPHB"),
        Proto("vmtp", 81, "VMTP"), Proto("eigrp", 88, "EIGRP"), Proto("ospf", 89, "OSPFIGP"), Proto("ax.25", 93, "AX.25"),
        Proto("ipip", 94, "IPIP"), Proto("etherip", 97, "ETHERIP"), Proto("encap", 98, "ENCAP"), Proto("pim", 103, "PIM"),
        Proto("ipcomp", 108, "IPCOMP"), Proto("vrrp", 112, "VRRP"), Proto("l2tp", 115, "L2TP"), Proto("isis", 124, "ISIS"),
        Proto("sctp", 132, "SCTP"), Proto("fc", 133, "FC"), Proto("mh", 135, "Mobility-Header"), Proto("ipv6-mh", 135, "Mobility-Header"),
        Proto("mobility-header", 135, "Mobility-Header"), Proto("udplite", 136, "UDPLite"), Proto("mpls-in-ip", 137, "MPLS-in-IP"),
        Proto("manet", 138, "MANET"), Proto("hip", 139, "HIP"), Proto("shim6", 140, "Shim6"), Proto("wesp", 141, "WESP"),
        Proto("rohc", 142, "ROHC"),
    )

    /** lookupProto() result `[ num, label, name ]`; num is -1 for unknown protocols. */
    data class ProtoInfo(val num: Int, val label: String, val name: String)

    fun lookupProto(x: Any?): ProtoInfo? {
        if (x == null || x == "") return null
        val s = jsString(x).lowercase()
        val n = jsNumber(s)
        for (p in protocols) if (s == p.name || n == p.num.toDouble()) return ProtoInfo(p.num, p.label, p.name)
        return ProtoInfo(-1, jsString(x), jsString(x))
    }

    /** JavaScript's String(x) for the values used here. */
    fun jsString(x: Any?): String = when (x) {
        null -> "undefined"
        is String -> x
        is Boolean -> x.toString()
        is Double -> if (x == Math.floor(x) && !x.isInfinite() && kotlin.math.abs(x) < 1e21) x.toLong().toString() else x.toString()
        is Float -> jsString(x.toDouble())
        is Number -> x.toString()
        is List<*> -> x.joinToString(",") { if (it == null) "" else jsString(it) }
        is Map<*, *> -> "[object Object]"
        else -> x.toString()
    }

    /** JavaScript's Number(string) (loose equality with numbers). */
    fun jsNumber(s0: String): Double {
        val s = s0.trim()
        if (s.isEmpty()) return 0.0
        Regex("^0[xX]([0-9a-fA-F]+)$").find(s)?.let { return it.groupValues[1].toBigInteger(16).toDouble() }
        Regex("^0[oO]([0-7]+)$").find(s)?.let { return it.groupValues[1].toBigInteger(8).toDouble() }
        Regex("^0[bB]([01]+)$").find(s)?.let { return it.groupValues[1].toBigInteger(2).toDouble() }
        if (Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$").matches(s)) return s.toDouble()
        if (s == "Infinity" || s == "+Infinity") return Double.POSITIVE_INFINITY
        if (s == "-Infinity") return Double.NEGATIVE_INFINITY
        return Double.NaN
    }

    /** L.toArray */
    fun toArray(v: Any?): List<Any?> = when (v) {
        null -> emptyList()
        is List<*> -> v
        is Map<*, *> -> listOf(v)
        is Element -> listOf(v)
        else -> jsString(v).trim().let { s -> if (s.isEmpty()) emptyList() else s.split(Regex("\\s+")) }
    }

    /** '%02X'.format(n) */
    fun hex2(n: Long): String = java.lang.Long.toHexString(n).uppercase().padStart(2, '0')

    /** An inline element passed to [fmt] (LuCI passes DOM nodes, e.g. zone badges). */
    sealed interface Element

    /** `E('span', { class: 'zonebadge', style }, [ E('strong'|'em', text) ])` */
    data class ZoneBadge(val text: String, val color: String, val strong: Boolean) : Element

    /** Result of [fmt]: HTML with `<span data-fmt-placeholder="N"></span>` markers for [values]. */
    data class Fmt(val html: String, val values: List<Element>) {
        val isEmpty: Boolean get() = html.isEmpty()
    }

    val EmptyFmt = Fmt("", emptyList())

    fun plain(text: String) = Fmt(text, emptyList())

    /** fwtool.fmt(fmtstr, args) */
    fun fmt(fmtstr: String, args: Map<String, Any?> = emptyMap()): Fmt {
        val values = ArrayList<Element>()
        val tokens = fmtTokens(fmtstr, args, values)
        return Fmt(tokens?.joinToString("") ?: fmtstr, values)
    }

    private fun get(args: Map<String, Any?>, key: String): Any? {
        var obj: Any? = args
        for (n in key.trim().split('.')) {
            if (obj !is Map<*, *>) return null
            obj = obj[n]
        }
        return obj
    }

    private fun isset(v: Any?): Boolean = when (v) {
        is Map<*, *> -> v.isNotEmpty()
        is List<*> -> v.isNotEmpty()
        else -> v != null && v != "" && v != false
    }

    private fun parse(tokens: MutableList<String>, text: Any?, values: MutableList<Element>) {
        if (text is Element) {
            tokens.add("<span data-fmt-placeholder=\"${values.size}\"></span>")
            values.add(text)
        } else {
            tokens.add(jsString(text).replace(Regex("\\\\(.)"), "$1"))
        }
    }

    /** Returns null for an invalid expression (fmt() then returns the format string itself). */
    private fun fmtTokens(fmtstr: String, args: Map<String, Any?>, values: MutableList<Element>): List<String>? {
        val tokens = ArrayList<String>()
        val len = fmtstr.length
        fun charAt(k: Int): Char? = if (k in 0 until len) fmtstr[k] else null
        var i = 0
        var last = 0
        while (i <= len) {
            if (charAt(i) == '%' && charAt(i + 1) == '{') {
                if (i > last) parse(tokens, fmtstr.substring(last, i), values)
                var j = i + 1
                var nest = 0
                val subexpr = ArrayList<String>()
                var off = j + 1
                var esc = false
                while (j <= len) {
                    val ch = charAt(j)
                    if (esc) {
                        esc = false
                    } else if (ch == '\\') {
                        esc = true
                    } else if (ch == '{') {
                        nest++
                    } else if (ch == '}') {
                        if (--nest == 0) {
                            subexpr.add(fmtstr.substring(off, j))
                            break
                        }
                    } else if (ch == '?' || ch == ':' || ch == '#') {
                        if (nest == 1) {
                            subexpr.add(fmtstr.substring(off, j))
                            subexpr.add(ch.toString())
                            off = j + 1
                        }
                    }
                    j++
                }
                val varname = subexpr.getOrNull(0)?.trim() ?: return null
                val op1 = subexpr.getOrNull(1) ?: "?"
                val ifSet = subexpr.getOrNull(2)?.takeIf { it != "" } ?: "%{$varname}"
                val op2 = subexpr.getOrNull(3) ?: ":"
                val ifUnset = subexpr.getOrNull(4) ?: ""
                if (nest != 0 || subexpr.size > 5 || varname == "") return null
                if (op1 == "#" && subexpr.size == 3) {
                    val items = toArray(get(args, varname))
                    for (k in items.indices) {
                        val sub = HashMap(args)
                        sub["first"] = k == 0
                        sub["next"] = k > 0
                        sub["last"] = (k + 1) == items.size
                        sub["item"] = items[k]
                        tokens.addAll(fmtTokens(ifSet, sub, values) ?: listOf(ifSet))
                    }
                } else if (op1 == "?" && op2 == ":" && (subexpr.size == 1 || subexpr.size == 3 || subexpr.size == 5)) {
                    val v = get(args, varname)
                    if (subexpr.size == 1) parse(tokens, if (isset(v)) v else "", values)
                    else if (isset(v)) tokens.addAll(fmtTokens(ifSet, args, values) ?: listOf(ifSet))
                    else tokens.addAll(fmtTokens(ifUnset, args, values) ?: listOf(ifUnset))
                } else {
                    return null
                }
                last = j + 1
                i = j
            } else if (i >= len) {
                if (i > last) parse(tokens, fmtstr.substring(last, i), values)
            }
            i++
        }
        return tokens
    }

    /** fwtool.map_invert(v, fn) */
    fun mapInvert(v: Any?, fn: String? = null): List<Map<String, Any?>> = toArray(v).map { x ->
        var s = jsString(x)
        if (fn == "toLowerCase") s = s.lowercase() else if (fn == "toUpperCase") s = s.uppercase()
        mapOf("ival" to s, "inv" to (s.firstOrNull() == '!'), "val" to s.replace(Regex("^!\\s*"), ""))
    }

    // ---- host hints (luci-rpc getHostHints) ----

    data class Host(val name: String?, val ipaddrs: List<String>, val ip6addrs: List<String>)

    /** fwtool.transformHostHints(family, hosts): choices as (value, label). */
    fun transformHostHints(family: String?, hosts: Map<String, Host>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val ip4 = LinkedHashMap<String, String>()
        val ip6 = LinkedHashMap<String, String>()
        for ((mac, h) in hosts) {
            h.ipaddrs.forEach { ip4[it] = mac }
            h.ip6addrs.forEach { ip6[it] = mac }
        }
        if (family.isNullOrEmpty() || family == "ipv4") {
            sortedAddr(ip4.keys).forEach { ip -> out += ip to "$ip (${hosts[ip4[ip]]?.name?.takeIf { it.isNotEmpty() } ?: ip4[ip]})" }
        }
        if (family.isNullOrEmpty() || family == "ipv6") {
            sortedAddr(ip6.keys).forEach { ip -> out += ip to "$ip (${hosts[ip6[ip]]?.name?.takeIf { it.isNotEmpty() } ?: ip6[ip]})" }
        }
        return out
    }

    /** L.sortedKeys(obj, null, 'addr') */
    fun sortedAddr(keys: Collection<String>): List<String> = keys.map { k ->
        k to Regex("(?:^|[.:])([0-9a-fA-F]{1,4})").replace(k) { m -> ("000" + m.groupValues[1].lowercase()).takeLast(4) }
    }.sortedWith { a, b -> naturalCompare(a.second, b.second) }.map { it.first }

    /** L.naturalCompare (Intl.Collator numeric) for ASCII strings. */
    fun naturalCompare(a: String, b: String): Int {
        val re = Regex("\\d+|\\D+")
        val ta = re.findAll(a).map { it.value }.toList()
        val tb = re.findAll(b).map { it.value }.toList()
        for (k in 0 until minOf(ta.size, tb.size)) {
            val x = ta[k]; val y = tb[k]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val bx = x.trimStart('0'); val by = y.trimStart('0')
                if (bx.length != by.length) bx.length.compareTo(by.length) else bx.compareTo(by)
            } else {
                val ci = x.lowercase().compareTo(y.lowercase())
                if (ci != 0) ci else -x.compareTo(y)
            }
            if (c != 0) return c
        }
        return ta.size.compareTo(tb.size)
    }

    // ---- zone colours (firewall.js getColorForName, prng.js derive_color, cbi.js sfh) ----

    /** firewall.getColorForName */
    fun colorForName(name: String?): String = when (name) {
        null -> "#eeeeee"
        "lan" -> "#90f090"
        "wan" -> "#f09090"
        else -> deriveColor(name)
    }

    /** Colour of fwmodel.getZoneColorStyle(zone): '*' and unset use the neutral colour. */
    fun zoneColor(zone: String?): String = colorForName(if (zone != null && zone != "*" && zone != "") zone else null)

    /** cbi.js sfh(): string hash as 8 hex digits. */
    fun sfh(s: String?): String? {
        if (s.isNullOrEmpty()) return null
        val bytes = s.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff }
        if (bytes.isEmpty()) return null
        fun u16(off: Int): Int = (bytes[off + 1] shl 8) + bytes[off]
        fun s8(off: Int): Int = bytes[off].let { if (it > 0x7f) it - 256 else it }
        var hash = bytes.size
        var n = bytes.size ushr 2
        var off = 0
        while (n-- > 0) {
            hash += u16(off)
            val tmp = (u16(off + 2) shl 11) xor hash
            hash = (hash shl 16) xor tmp
            hash += hash ushr 11
            off += 4
        }
        when (bytes.size and 3) {
            3 -> {
                hash += u16(off)
                hash = hash xor (hash shl 16)
                hash = hash xor (s8(off + 2) shl 18)
                hash += hash ushr 11
            }
            2 -> {
                hash += u16(off)
                hash = hash xor (hash shl 11)
                hash += hash ushr 17
            }
            1 -> {
                hash += s8(off)
                hash = hash xor (hash shl 10)
                hash += hash ushr 1
            }
        }
        hash = hash xor (hash shl 3)
        hash += hash ushr 5
        hash = hash xor (hash shl 4)
        hash += hash ushr 17
        hash = hash xor (hash shl 25)
        hash += hash ushr 6
        return String.format("%08x", hash.toLong() and 0xffffffffL)
    }

    private class Prng(seed: Long) {
        private var s: Long = (seed - 1).toInt().toLong() and 0xffffffffL

        fun int(): Int {
            s = s * 0x5851f42d4c957f2dL + 1
            return (s ushr 33).toInt()
        }

        fun get(): Double = (int() % 0x7fffffff).toDouble() / 0x7fffffff

        fun get(upper: Int): Int = Math.floor(get() * upper).toInt() + 1
    }

    /** prng.derive_color(string) */
    fun deriveColor(string: String): String {
        val seed = sfh(string)?.toLong(16) ?: 0L
        val p = Prng(seed)
        val r = p.get(128)
        val g = p.get(128)
        var min = 0
        var max = 128
        if (r + g < 128) min = 128 - r - g else max = 255 - r - g
        val b = min + Math.floor(p.get() * (max - min)).toInt()
        return "#%02x%02x%02x".format(0xff - r, 0xff - g, 0xff - b)
    }

    /** RGB of a "#rrggbb" colour (getZoneColorStyle). */
    fun rgb(hex: String): Triple<Int, Int, Int> {
        fun part(a: Int, b: Int) = hex.substring(a.coerceAtMost(hex.length), b.coerceAtMost(hex.length)).toIntOrNull(16) ?: 0
        return Triple(part(1, 3), part(3, 5), part(5, 7))
    }
}
