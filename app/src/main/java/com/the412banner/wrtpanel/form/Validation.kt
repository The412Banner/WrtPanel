package com.the412banner.wrtpanel.form

/**
 * Port of LuCI's validation.js: datatype expressions like `or(ip4addr,"auto")`,
 * `range(1,65535)` or `list(macaddr)`. [validate] returns null when valid, otherwise the
 * message LuCI shows ("Expecting: …").
 */
object Datatypes {
    sealed interface Item
    data class Num(val v: Double) : Item
    data class Str(val v: String) : Item
    data class Fn(val name: String, val args: List<Item>?) : Item

    private val cache = HashMap<String, List<Item>>()

    fun compile(code0: String): List<Item> = cache.getOrPut(code0) { compileRaw(code0) }

    private fun compileRaw(code0: String): List<Item> {
        val code = "$code0,"
        val stack = ArrayList<Item>()
        var pos = 0
        var esc = false
        var depth = 0
        var i = 0
        var pendingFn: String? = null
        while (i < code.length) {
            val c = code[i]
            if (esc) { esc = false; i++; continue }
            when (c) {
                '\\' -> esc = true
                '"', '\'' -> {
                    var j = i + 1
                    while (j < code.length) {
                        if (code[j] == '\\') { j += 2; continue }
                        if (code[j] == c) { i = j; break }
                        j++
                    }
                }
                '(', ',' -> {
                    if (depth <= 0) {
                        if (pos < i) {
                            val label = code.substring(pos, i).replace(Regex("\\\\(.)"), "$1").trim(' ', '\t')
                            val num = label.toDoubleOrNull()
                            when {
                                label.isNotEmpty() && num != null -> stack.add(Num(num))
                                Regex("^(['\"]).*\\1$").matches(label) -> stack.add(Str(label.substring(1, label.length - 1)))
                                label in types -> { stack.add(Fn(label, null)); pendingFn = label }
                                else -> throw IllegalArgumentException("Unhandled token \"$label\"")
                            }
                        }
                        pos = i + 1
                    }
                    if (c == '(') depth++
                }
                ')' -> {
                    depth--
                    if (depth <= 0) {
                        val last = stack.lastOrNull() as? Fn ?: throw IllegalArgumentException("Argument list follows non-function")
                        stack[stack.size - 1] = Fn(last.name, compileRaw(code.substring(pos, i)))
                        pos = i + 1
                    }
                }
            }
            i++
        }
        return stack
    }

    /** Validates [value] (already known non-empty) against [datatype]; null = valid. */
    fun validate(datatype: String, value: String): String? {
        val items = runCatching { compile(datatype) }.getOrNull() ?: return null
        val fn = items.firstOrNull() as? Fn ?: return null
        val ctx = Ctx(value)
        return if (ctx.runFn(fn)) null else "Expecting: ${ctx.error ?: "valid value"}"
    }

    private class Ctx(var value: String) {
        var error: String? = null
        fun assert(cond: Boolean, msg: String?): Boolean {
            return if (!cond) { error = msg; false } else { error = null; true }
        }
        fun runFn(fn: Fn): Boolean = types[fn.name]?.invoke(this, fn.args ?: emptyList()) ?: false
        /** validation.js apply(): optionally replace value, then run a named type with literal args. */
        fun apply(name: String, v: String?, vararg args: Any?): Boolean {
            if (v != null) value = v
            val items = args.map { a ->
                when (a) {
                    is Item -> a
                    is Number -> Num(a.toDouble())
                    is Boolean -> if (a) Num(1.0) else Num(0.0)
                    null -> Str("")
                    else -> Str(a.toString())
                }
            }.filter { !(it is Str && it.v.isEmpty()) }
            return types[name]?.invoke(this, items) ?: false
        }
        fun applyItem(item: Item, v: String?): Boolean {
            if (v != null) value = v
            return when (item) {
                is Fn -> runFn(item)
                else -> false
            }
        }
    }

    private fun truthy(args: List<Item>, i: Int): Boolean = when (val a = args.getOrNull(i)) {
        null -> false
        is Num -> a.v != 0.0
        is Str -> a.v.isNotEmpty()
        is Fn -> true
    }

    private fun lit(args: List<Item>, i: Int): String? = when (val a = args.getOrNull(i)) {
        is Num -> if (a.v == Math.floor(a.v)) a.v.toLong().toString() else a.v.toString()
        is Str -> a.v
        else -> null
    }

    fun parseInteger(x: String): Double = if (Regex("^-?\\d+$").matches(x)) x.toDouble() else Double.NaN
    fun parseDecimal(x: String): Double = if (Regex("^-?\\d+(?:\\.\\d+)?$").matches(x)) x.toDouble() else Double.NaN

    fun parseIPv4(x: String): IntArray? {
        val m = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(x) ?: return null
        val p = m.groupValues.drop(1).map { it.toInt() }
        if (p.any { it > 255 }) return null
        return p.toIntArray()
    }

    fun parseIPv6(x0: String): IntArray? {
        var x = x0
        Regex("^([a-fA-F0-9:]+):(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})$").find(x)?.let { m ->
            val v4 = parseIPv4(m.groupValues[2]) ?: return null
            x = "${m.groupValues[1]}:${(v4[0] * 256 + v4[1]).toString(16)}:${(v4[2] * 256 + v4[3]).toString(16)}"
        }
        if (!Regex("^[a-fA-F0-9:]+$").matches(x)) return null
        val ps = x.split("::")
        if (ps.size > 2) return null
        val prefix = ps[0].ifEmpty { "0" }.split(':')
        val suffix = if (ps.size > 1) ps[1].ifEmpty { "0" }.split(':') else emptyList()
        if (if (suffix.isNotEmpty()) prefix.size + suffix.size > 7 else ((ps.size < 2 && prefix.size < 8) || prefix.size > 8)) return null
        val words = ArrayList<Int>()
        for (p in prefix) {
            val w = p.toIntOrNull(16)
            if (p.length <= 4 && w != null && w <= 0xffff) words.add(w) else return null
        }
        repeat(8 - prefix.size - suffix.size) { words.add(0) }
        for (p in suffix) {
            val w = p.toIntOrNull(16)
            if (p.length <= 4 && w != null && w <= 0xffff) words.add(w) else return null
        }
        return words.toIntArray()
    }

    private fun arrayle(a: IntArray?, b: IntArray?): Boolean {
        if (a == null || b == null) return false
        for (i in a.indices) { if (a[i] > b[i]) return false else if (a[i] < b[i]) return true }
        return true
    }

    private fun bytelen(s: String) = s.toByteArray(Charsets.UTF_8).size
    private fun fmt(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()

    private val types: Map<String, Ctx.(List<Item>) -> Boolean> = mapOf(
        "integer" to { _ -> assert(!parseInteger(value).isNaN(), "valid integer value") },
        "uinteger" to { _ -> assert(parseInteger(value) >= 0, "positive integer value") },
        "float" to { _ -> assert(!parseDecimal(value).isNaN(), "valid decimal value") },
        "ufloat" to { _ -> assert(parseDecimal(value) >= 0, "positive decimal value") },
        "ipaddr" to { a ->
            val nomask = truthy(a, 0)
            val v = value
            assert(apply("ip4addr", v, nomask) || apply("ip6addr", v, nomask), if (nomask) "valid IP address" else "valid IP address or prefix")
        },
        "ip4addr" to { a ->
            val nomask = truthy(a, 0)
            val re = if (nomask) Regex("^(\\d+\\.\\d+\\.\\d+\\.\\d+)$") else Regex("^(\\d+\\.\\d+\\.\\d+\\.\\d+)(?:/(\\d+\\.\\d+\\.\\d+\\.\\d+)|/(\\d{1,2}))?$")
            val m = re.find(value)
            val ok = m != null && parseIPv4(m.groupValues[1]) != null && run {
                val g2 = m.groupValues.getOrNull(2).orEmpty(); val g3 = m.groupValues.getOrNull(3).orEmpty()
                when {
                    g2.isNotEmpty() -> parseIPv4(g2) != null
                    g3.isNotEmpty() -> { val keep = value; val r = apply("ip4prefix", g3); value = keep; r }
                    else -> true
                }
            }
            assert(ok, if (nomask) "valid IPv4 address" else "valid IPv4 address or network")
        },
        "ip6addr" to { a ->
            val nomask = truthy(a, 0)
            val re = if (nomask) Regex("^([0-9a-fA-F:.]+)$") else Regex("^([0-9a-fA-F:.]+)(?:/(\\d{1,3}))?$")
            val m = re.find(value)
            val ok = m != null && parseIPv6(m.groupValues[1]) != null && run {
                val g2 = m.groupValues.getOrNull(2).orEmpty()
                if (g2.isNotEmpty()) { val keep = value; val r = apply("ip6prefix", g2); value = keep; r } else true
            }
            assert(ok, if (nomask) "valid IPv6 address" else "valid IPv6 address or prefix")
        },
        "ip6ll" to { a ->
            val x = value.takeWhile { it.isLetterOrDigit() }.toIntOrNull(16) ?: 0
            assert(((x and 0xffc0) xor 0xfe80) == 0 && apply("ip6addr", null, truthy(a, 0)), "valid IPv6 Link Local address")
        },
        "ip6ula" to { a ->
            val x = value.takeWhile { it.isLetterOrDigit() }.toIntOrNull(16) ?: 0
            assert(((x and 0xfe00) xor 0xfc00) == 0 && apply("ip6addr", null, truthy(a, 0)), "valid IPv6 ULA address")
        },
        "ip4prefix" to { _ -> val n = value.toDoubleOrNull(); assert(n != null && n >= 0 && n <= 32, "valid IPv4 prefix value (0-32)") },
        "ip6prefix" to { _ -> val n = value.toDoubleOrNull(); assert(n != null && n >= 0 && n <= 128, "valid IPv6 prefix value (0-128)") },
        "cidr" to { a -> val v = value; assert(apply("cidr4", v, truthy(a, 0)) || apply("cidr6", v, truthy(a, 0)), "valid IPv4 or IPv6 CIDR") },
        "cidr4" to { a ->
            val m = Regex("^(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})/(-)?(\\d{1,2})$").find(value)
            val ok = m != null && parseIPv4(m.groupValues[1]) != null && (truthy(a, 0) || m.groupValues[2].isEmpty()) &&
                run { val keep = value; val r = apply("ip4prefix", m.groupValues[3]); value = keep; r }
            assert(ok, "valid IPv4 CIDR")
        },
        "cidr6" to { a ->
            val m = Regex("^([0-9a-fA-F:.]+)/(-)?(\\d{1,3})$").find(value)
            val ok = m != null && parseIPv6(m.groupValues[1]) != null && (truthy(a, 0) || m.groupValues[2].isEmpty()) &&
                run { val keep = value; val r = apply("ip6prefix", m.groupValues[3]); value = keep; r }
            assert(ok, "valid IPv6 CIDR")
        },
        "ipnet4" to { _ ->
            val m = Regex("^(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})/(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})$").find(value)
            assert(m != null && parseIPv4(m.groupValues[1]) != null && parseIPv4(m.groupValues[2]) != null, "IPv4 network in address/netmask notation")
        },
        "ipnet6" to { _ ->
            val m = Regex("^([0-9a-fA-F:.]+)/([0-9a-fA-F:.]+)$").find(value)
            assert(m != null && parseIPv6(m.groupValues[1]) != null && parseIPv6(m.groupValues[2]) != null, "IPv6 network in address/netmask notation")
        },
        "ip6hostid" to { _ ->
            if (value == "eui64" || value == "random") true else {
                val v6 = parseIPv6(value)
                assert(!(v6 == null || v6[0] != 0 || v6[1] != 0 || v6[2] != 0 || v6[3] != 0), "valid IPv6 host id")
            }
        },
        "ipmask" to { a -> val v = value; assert(apply("ipmask4", v, truthy(a, 0)) || apply("ipmask6", v, truthy(a, 0)), "valid network in address/netmask notation") },
        "ipmask4" to { a -> val v = value; assert(apply("cidr4", v, truthy(a, 0)) || apply("ipnet4", v) || apply("ip4addr", v), "valid IPv4 network") },
        "ipmask6" to { a -> val v = value; assert(apply("cidr6", v, truthy(a, 0)) || apply("ipnet6", v) || apply("ip6addr", v), "valid IPv6 network") },
        "iprange" to { _ -> val v = value; assert(apply("iprange4", v) || apply("iprange6", v), "valid IP address range") },
        "iprange4" to { _ -> val m = value.split('-'); assert(m.size == 2 && arrayle(parseIPv4(m[0]), parseIPv4(m[1])), "valid IPv4 address range") },
        "iprange6" to { _ -> val m = value.split('-'); assert(m.size == 2 && arrayle(parseIPv6(m[0]), parseIPv6(m[1])), "valid IPv6 address range") },
        "port" to { _ -> val p = parseInteger(value); assert(p >= 0 && p <= 65535, "valid port value") },
        "portrange" to { _ ->
            val m = Regex("^(\\d+)-(\\d+)$").find(value)
            if (m != null) {
                val p1 = m.groupValues[1].toLong(); val p2 = m.groupValues[2].toLong()
                assert(p1 <= p2 && p2 <= 65535, "valid port or port range (port1-port2)")
            } else assert(apply("port", null), "valid port or port range (port1-port2)")
        },
        "macaddr" to { a ->
            val multicast = truthy(a, 0)
            val m = Regex("^([a-fA-F0-9]{2}):([a-fA-F0-9]{2}:){4}[a-fA-F0-9]{2}$").find(value)
            val first = m?.groupValues?.get(1)?.toIntOrNull(16) ?: 0
            assert(m != null && ((first and 1) == 0) == !multicast, if (multicast) "valid multicast MAC address" else "valid MAC address")
        },
        "host" to { a ->
            val v = value
            val ipv4only = lit(a, 0) == "1"
            assert(apply("hostname", v) || apply(if (ipv4only) "ip4addr" else "ipaddr", v, "nomask"), "valid hostname or IP address")
        },
        "hostname" to { a ->
            val strict = truthy(a, 0)
            if (value.length <= 253) assert(
                (Regex("^[a-zA-Z0-9_]+$").matches(value) ||
                    (Regex("^[a-zA-Z0-9_][a-zA-Z0-9_\\-.]*[a-zA-Z0-9]\\.?$").matches(value) && Regex("[^0-9.]").containsMatchIn(value))) &&
                    (!strict || !value.startsWith("_")),
                "valid hostname",
            ) else assert(false, "valid hostname")
        },
        "network" to { _ ->
            val v = value
            assert(apply("uciname", v) || apply("hostname", v) || apply("ip4addr", v) || apply("ip6addr", v), "valid UCI identifier, hostname or IP address range")
        },
        "hostport" to { a ->
            val hp = value.split(':')
            assert(hp.size == 2 && apply("host", hp[0], lit(a, 0) ?: "") && apply("port", hp[1]), "valid host:port")
        },
        "ip4addrport" to { _ ->
            val hp = value.split(':')
            assert(hp.size == 2 && apply("ip4addr", hp[0], true) && apply("port", hp[1]), "valid IPv4 address:port")
        },
        "ipaddrport" to { a ->
            val bracket = lit(a, 0) == "1"
            val m4 = Regex("^([^\\[\\]:]+):(\\d+)$").find(value)
            val m6 = (if (bracket) Regex("^\\[(.+)]:(\\d+)$") else Regex("^([^\\[\\]]+):(\\d+)$")).find(value)
            if (m4 != null) assert(apply("ip4addr", m4.groupValues[1], true) && apply("port", m4.groupValues[2]), "valid address:port")
            else assert(m6 != null && apply("ip6addr", m6.groupValues[1], true) && apply("port", m6.groupValues[2]), "valid address:port")
        },
        "sep" to { a -> val s = lit(a, 0); if (s == null) true else assert(value == s, "string: \"$s\"") },
        "tuple" to { a ->
            var sep: String? = null
            val ts = a.toMutableList()
            val sepIdx = ts.indexOfFirst { it is Fn && it.name == "sep" }
            if (sepIdx >= 0) { sep = (ts[sepIdx] as Fn).args?.let { lit(it, 0) }; ts.removeAt(sepIdx) }
            val raw = value
            val tokens = if (sep == null) raw.split(Regex("\\s+")) else raw.split(sep).map { it.trim() }
            if (tokens.size != ts.size) {
                val names = ts.joinToString(sep ?: " ") { (it as? Fn)?.name ?: "value" }
                assert(false, if (ts.size > 1) "$names; ${ts.size} tokens separated by ${if (sep == null) "whitespace" else "\"$sep\""}" else names)
            } else {
                var ok = true
                for (i in tokens.indices) {
                    if (!applyItem(ts[i], tokens[i])) { ok = false; break }
                }
                if (ok) assert(true, null) else assert(false, error)
            }
        },
        "wpakey" to { _ ->
            val v = value
            if (v.length == 64) assert(Regex("^[a-fA-F0-9]{64}$").matches(v), "valid hexadecimal WPA key")
            else assert(v.length in 8..63, "key between 8 and 63 characters")
        },
        "wepkey" to { _ ->
            var v = value
            if (v.startsWith("s:")) v = v.substring(2)
            if (v.length == 10 || v.length == 26) assert(Regex("^[a-fA-F0-9]{10,26}$").matches(v), "valid hexadecimal WEP key")
            else assert(v.length == 5 || v.length == 13, "key with either 5 or 13 characters")
        },
        "uciname" to { _ -> assert(Regex("^[a-zA-Z0-9_]+$").matches(value), "valid UCI identifier") },
        "ucifw4zonename" to { _ -> assert(Regex("^[a-zA-Z_][a-zA-Z0-9_]+$").matches(value), "valid fw4 zone name UCI identifier") },
        "netdevname" to { _ ->
            val v = value
            if (v == "." || v == "..") assert(false, "valid network device name, not \".\" or \"..\"")
            else assert(Regex("^[^:/%\\s]{1,15}$").matches(v), "valid network device name between 1 and 15 characters not containing \":\", \"/\", \"%\" or spaces")
        },
        "range" to { a ->
            val mn = lit(a, 0)?.toDoubleOrNull() ?: Double.NaN; val mx = lit(a, 1)?.toDoubleOrNull() ?: Double.NaN
            val v = parseDecimal(value)
            assert(v >= mn && v <= mx, "value between ${fmt(mn)} and ${fmt(mx)}")
        },
        "min" to { a -> val mn = lit(a, 0)?.toDoubleOrNull() ?: Double.NaN; assert(parseDecimal(value) >= mn, "value greater or equal to ${fmt(mn)}") },
        "max" to { a -> val mx = lit(a, 0)?.toDoubleOrNull() ?: Double.NaN; assert(parseDecimal(value) <= mx, "value smaller or equal to ${fmt(mx)}") },
        "length" to { a -> val n = lit(a, 0)?.toDoubleOrNull()?.toInt() ?: -1; assert(bytelen(value) == n, "value with $n characters") },
        "rangelength" to { a ->
            val mn = lit(a, 0)?.toDoubleOrNull()?.toInt() ?: 0; val mx = lit(a, 1)?.toDoubleOrNull()?.toInt() ?: Int.MAX_VALUE
            val l = bytelen(value); assert(l in mn..mx, "value between $mn and $mx characters")
        },
        "minlength" to { a -> val mn = lit(a, 0)?.toDoubleOrNull()?.toInt() ?: 0; assert(bytelen(value) >= mn, "value with at least $mn characters") },
        "maxlength" to { a -> val mx = lit(a, 0)?.toDoubleOrNull()?.toInt() ?: Int.MAX_VALUE; assert(bytelen(value) <= mx, "value with at most $mx characters") },
        "or" to { a ->
            val errors = ArrayList<String>()
            val v = value
            var ok = false
            for (it in a) {
                value = v
                if (it is Fn) {
                    if (runFn(it)) { ok = true; break } else errors.add(error ?: it.name)
                } else {
                    val l = when (it) { is Num -> fmt(it.v); is Str -> it.v; else -> "" }
                    if (l == v) { ok = true; break }
                    errors.add("\"$l\"")
                }
            }
            value = v
            if (ok) assert(true, null) else assert(false, "One of the following: \n - " + errors.joinToString("\n - "))
        },
        "and" to { a ->
            val v = value
            var ok = true
            var err: String? = null
            for (it in a) {
                value = v
                if (it is Fn) {
                    if (!runFn(it)) { ok = false; err = error; break }
                } else {
                    val l = when (it) { is Num -> fmt(it.v); is Str -> it.v; else -> "" }
                    if (l != v) { ok = false; err = "\"$l\""; break }
                }
            }
            value = v
            if (ok) assert(true, null) else assert(false, err)
        },
        "neg" to { a ->
            value = value.replace(Regex("^[ \\t]*![ \\t]*"), "")
            val f = a.firstOrNull() as? Fn
            if (f != null && runFn(f)) assert(true, null) else assert(false, "Potential negation of: ${error ?: ""}")
        },
        "list" to { a ->
            val f = a.firstOrNull() as? Fn
            val tokens = Regex("[^ \\t]+").findAll(value).map { it.value }.toList()
            var ok = true
            for (t in tokens) {
                value = t
                if (f != null && !runFn(f)) { ok = false; break }
            }
            if (ok) assert(true, null) else assert(false, error)
        },
        "phonedigit" to { _ -> assert(Regex("^[0-9*#!.]+$").matches(value), "valid phone digit (0-9, \"*\", \"#\", \"!\" or \".\")") },
        "timehhmmss" to { _ -> assert(Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d:(?:[0-5]\\d|60)$").matches(value), "valid time (HH:MM:SS)") },
        "dateyyyymmdd" to { _ ->
            val m = Regex("^(\\d\\d\\d\\d)-(\\d\\d)-(\\d\\d)").find(value)
            if (m != null) {
                val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); val d = m.groupValues[3].toInt()
                val dim = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
                val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
                val max = if (mo == 2 && leap) 29 else dim.getOrElse(mo - 1) { 0 }
                assert(y >= 2015 && mo in 1..12 && d in 1..max, "valid date (YYYY-MM-DD)")
            } else assert(false, "valid date (YYYY-MM-DD)")
        },
        // Uniqueness across sibling fields is checked by the form engine; here only the sub-type.
        "unique" to { a -> val f = a.firstOrNull() as? Fn; if (f != null) runFn(f) else assert(true, null) },
        "hexstring" to { _ -> assert(Regex("^([a-fA-F0-9]{2})+$").matches(value), "hexadecimal encoded value") },
        "string" to { a -> val s = lit(a, 0); if (s == null) true else assert(value == s, "string: \"$s\"") },
        "directory" to { _ -> true },
        "file" to { _ -> true },
        "device" to { _ -> true },
    )
}
