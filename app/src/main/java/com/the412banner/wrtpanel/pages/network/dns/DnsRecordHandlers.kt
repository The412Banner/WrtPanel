package com.the412banner.wrtpanel.pages.network.dns

import java.util.Base64

/** Port of luci-mod-network tools/dnsrecordhandlers.js (SVCB/HTTPS type 65 wire format). */
internal object DnsRecordHandlers {
    private val svcParamKeyMap = linkedMapOf(
        "mandatory" to 0, "alpn" to 1, "no-default-alpn" to 2, "port" to 3, "ipv4hint" to 4, "ech" to 5, "ipv6hint" to 6,
    )

    data class Svcb(val priority: Int, val target: String, val params: List<String>)

    /** JavaScript parseInt(s, radix): leading digits only, null for NaN. */
    private fun jsParseInt(s: String, radix: Int = 10): Int? {
        val t = s.trim()
        val digits = if (radix == 16) "0-9a-fA-F" else "0-9"
        val m = Regex("^([+-]?)([$digits]+)").find(t) ?: return null
        val v = m.groupValues[2].toLongOrNull(radix)?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt() ?: return null
        return if (m.groupValues[1] == "-") -v else v
    }

    private fun hex(buf: List<Int>): String = buf.joinToString("") { it.toString(16).padStart(2, '0') }

    private fun stripQuotes(s: String): String = Regex("^\"(.*)\"$").replace(s, "$1")

    fun buildSvcbHex(priority0: Int?, target0: String, params: List<String>): String? {
        val buf = ArrayList<Int>()
        val priority = priority0 ?: 1
        var target = target0

        buf.add((priority shr 8) and 0xff); buf.add(priority and 0xff)

        if (target != ".") {
            if (target.endsWith(".")) target = target.dropLast(1)
            target.split('.').forEach { part ->
                buf.add(part.length)
                part.forEach { buf.add(it.code) }
            }
        }
        buf.add(0)

        if (priority == 0) {
            if (target == ".") return null
            return hex(buf)
        }

        val seenKeys = HashSet<String>()
        data class P(val keyNum: Int, val keyName: String, val valueBytes: List<Int>)
        val paramList = ArrayList<P>()
        var mandatoryKeys: Set<String> = emptySet()
        var definedAlpn: Set<String> = emptySet()
        var noDefaultAlpn = false

        for (line in params) {
            if (line.isBlank()) continue
            val split = line.split('=')
            val keyName = stripQuotes(split[0].trim())
            val v = stripQuotes((split.getOrNull(1) ?: "").trim())

            val keyNum = svcParamKeyToNumber(keyName) ?: continue
            if (keyName in seenKeys) continue
            seenKeys += keyName
            if (keyNum != 2 && v == "") continue
            if (keyNum == 0) mandatoryKeys = v.split(',').filter { it != "" }.toSet()
            if (keyNum == 1) definedAlpn = v.split(',').filter { it != "" }.toSet()
            if (keyNum == 2) noDefaultAlpn = true

            paramList += P(keyNum, keyName, encodeSvcParamValue(keyName, v))
        }

        if (noDefaultAlpn && definedAlpn.isEmpty()) return null
        for (key in mandatoryKeys) if (key !in seenKeys) return null

        paramList.sortBy { it.keyNum }
        for (p in paramList) {
            buf.add((p.keyNum shr 8) and 0xff); buf.add(p.keyNum and 0xff)
            buf.add((p.valueBytes.size shr 8) and 0xff); buf.add(p.valueBytes.size and 0xff)
            buf.addAll(p.valueBytes)
        }
        return hex(buf)
    }

    fun svcParamKeyToNumber(name0: String): Int? {
        val name = name0.lowercase()
        svcParamKeyMap[name]?.let { return it }
        val m = Regex("^key(\\d{1,5})$").find(name) ?: return null
        val n = m.groupValues[1].toInt()
        return if (n in 0..65535) n else null
    }

    private fun encodeSvcParamValue(key: String, value: String): List<Int> = when (key) {
        "mandatory" -> value.split(',').map { it.trim() }.distinct()
            .mapNotNull { svcParamKeyToNumber(it) }.filter { it != 0 }.sorted()
            .flatMap { listOf((it shr 8) and 0xff, it and 0xff) }
        "ech" -> Base64.getDecoder().decode(value.replace(Regex("[\\t\\n\\u000c\\r ]"), "")).map { it.toInt() and 0xff }
        "alpn" -> value.split(',').flatMap { v -> listOf(v.length) + v.map { it.code } }
        "no-default-alpn" -> emptyList()
        "port" -> (jsParseInt(value) ?: 0).let { listOf((it shr 8) and 0xff, it and 0xff) }
        "ipv4hint" -> value.split(',').flatMap { ip -> ip.trim().split('.').map { jsParseInt(it) ?: 0 } }
        "ipv6hint" -> value.split(',').flatMap { ip0 -> encodeIPv6(ip0.trim()) }
        else -> if (Regex("^key\\d{1,5}$", RegexOption.IGNORE_CASE).matches(key)) value.split(',').flatMap { v -> v.map { it.code } } else emptyList()
    }

    private fun encodeIPv6(ip0: String): List<Int> {
        var ip = ip0
        if (Regex("\\d+\\.\\d+\\.\\d+\\.\\d+$").containsMatchIn(ip)) {
            val parts = ip.split(':').toMutableList()
            val ipv4Tail = parts.removeAt(parts.size - 1)
            ip = parts.joinToString(":")
            val octets = ipv4Tail.split('.').map { jsParseInt(it) ?: 0 }
            if (octets.size != 4) return emptyList()
            val word1 = ((octets[0] shl 8) or octets[1]).toString(16).padStart(4, '0')
            val word2 = ((octets[2] shl 8) or octets[3]).toString(16).padStart(4, '0')
            ip += ":$word1:$word2"
        }
        var parts = ip.trim().split(':')
        if (parts.contains("")) {
            val missing = 8 - parts.count { it != "" }
            val expanded = ArrayList<String>()
            for (i in parts.indices) {
                if (parts[i] == "" && (i == 0 || parts[i - 1] != "")) repeat(missing) { expanded.add("0000") }
                else if (parts[i] != "") expanded.add(parts[i].padStart(4, '0'))
            }
            parts = expanded
        } else {
            parts = parts.map { it.padStart(4, '0') }
        }
        return parts.flatMap { p -> listOf(jsParseInt(p.take(2), 16) ?: 0, jsParseInt(p.drop(2).take(2), 16) ?: 0) }
    }

    fun parseSvcbHex(hex: String?): Svcb? {
        if (hex.isNullOrEmpty()) return null
        val data = hex.replace(Regex("[\\s:]"), "").lowercase()
        val pairs = Regex(".{2}").findAll(data).map { it.value }.toList()
        if (pairs.isEmpty()) return null
        val buf = pairs.map { jsParseInt(it, 16) ?: 0 }
        fun u16(o: Int) = (buf[o] shl 8) or buf[o + 1]

        var offset = 0
        if (buf.size < 2) return null
        val priority = u16(offset)
        offset += 2

        val labels = ArrayList<String>()
        while (offset < buf.size) {
            val len = buf[offset++]
            if (len == 0) break
            if (offset + len > buf.size) return null
            labels += String(CharArray(len) { buf[offset + it].toChar() })
            offset += len
        }
        val target = labels.joinToString(".") + "."

        val svcParams = ArrayList<String>()
        while (offset + 4 <= buf.size) {
            val key = u16(offset)
            val len = u16(offset + 2)
            offset += 4
            if (offset + len > buf.size) break
            val valBuf = buf.subList(offset, offset + len)
            offset += len
            val keyname = svcParamKeyFromNumber(key)
            if (keyname == "no-default-alpn" && valBuf.isEmpty()) svcParams += keyname
            else svcParams += "$keyname=${decodeSvcParamValue(keyname, valBuf)}"
        }
        return Svcb(priority, target, svcParams)
    }

    fun svcParamKeyFromNumber(num: Int): String = svcParamKeyMap.entries.firstOrNull { it.value == num }?.key ?: "key$num"

    private fun decodeSvcParamValue(key: String, buf: List<Int>): String = when (key) {
        "mandatory" -> (0 until buf.size - 1 step 2).joinToString(",") { svcParamKeyFromNumber((buf[it] shl 8) or buf[it + 1]) }
        "ech" -> Base64.getEncoder().encodeToString(ByteArray(buf.size) { buf[it].toByte() })
        "alpn" -> {
            var pos = 0
            val result = ArrayList<String>()
            while (pos < buf.size) {
                val len = buf[pos++]
                if (pos + len > buf.size) break
                result += String(CharArray(len) { buf[pos + it].toChar() })
                pos += len
            }
            result.joinToString(",")
        }
        "no-default-alpn" -> ""
        "port" -> ((buf.getOrElse(0) { 0 } shl 8) or buf.getOrElse(1) { 0 }).toString()
        "ipv4hint" -> buf.chunked(4).joinToString(",") { it.joinToString(".") }
        "ipv6hint" -> {
            val addrs = ArrayList<String>()
            var i = 0
            while (i + 15 <= buf.size) {
                val addr = (0 until 16 step 2).map { j ->
                    ((buf.getOrElse(i + j) { 0 } shl 8) or buf.getOrElse(i + j + 1) { 0 }).toString(16).padStart(4, '0')
                }
                addrs += compressIPv6(addr)
                i += 16
            }
            addrs.joinToString(",")
        }
        else -> if (Regex("^key\\d{1,5}$", RegexOption.IGNORE_CASE).matches(key)) String(CharArray(buf.size) { buf[it].toChar() })
        else buf.joinToString("") { it.toString(16).padStart(2, '0') }
    }

    fun compressIPv6(hextets: List<String>): String {
        val normalized = hextets.map { (it.toIntOrNull(16) ?: 0).toString(16) }
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < normalized.size) {
            if (normalized[i] != "0") { i++; continue }
            val start = i
            while (i < normalized.size && normalized[i] == "0") i++
            val len = i - start
            if (len > bestLen) { bestStart = start; bestLen = len }
        }
        if (bestLen < 2) return normalized.joinToString(":")
        val head = normalized.subList(0, bestStart).joinToString(":")
        val tail = normalized.subList(bestStart + bestLen, normalized.size).joinToString(":")
        return when {
            head.isNotEmpty() && tail.isNotEmpty() -> "$head::$tail"
            head.isNotEmpty() -> "$head::"
            tail.isNotEmpty() -> "::$tail"
            else -> "::"
        }
    }
}
