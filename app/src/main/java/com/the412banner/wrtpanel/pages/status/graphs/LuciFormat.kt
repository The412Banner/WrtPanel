package com.the412banner.wrtpanel.pages.status.graphs

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor

/** The String.prototype.format conversions (cbi.js) and tools/prng.js colours used by the graph views. */
internal object LuciFmt {
    /** JS `+x || 0`. */
    fun num(x: Double?): Double = if (x == null || x.isNaN()) 0.0 else x

    /** Number.prototype.toFixed. */
    fun toFixed(x: Double, digits: Int): String = when {
        x.isNaN() -> "NaN"
        x.isInfinite() -> if (x > 0) "Infinity" else "-Infinity"
        else -> BigDecimal(x).setScale(digits, RoundingMode.HALF_UP).toPlainString()
    }

    /** Number.prototype.toString for plain numbers. */
    fun jsString(x: Double): String = when {
        x.isNaN() -> "NaN"
        x.isInfinite() -> if (x > 0) "Infinity" else "-Infinity"
        x == 0.0 -> "0"
        x == floor(x) && abs(x) < 1e15 -> x.toLong().toString()
        else -> BigDecimal(x.toString()).stripTrailingZeros().toPlainString()
    }

    /** '%d' */
    fun d(x: Double?): String = toFixed(floor(num(x)), 0)

    /** '%.<digits>f' */
    fun f(x: Double?, digits: Int): String = toFixed(num(x), digits)

    /** '%<mf>.<pr>m', e.g. '%1024.2m'. */
    fun m(x: Double?, mf: Int = 1000, pr: Int = 2): String {
        val units = arrayOf(" ", " K", " M", " G", " T", " P", " E")
        var v = num(x)
        var i = 0
        while (i < units.size - 1 && v > mf) { v /= mf; i++ }
        return if (i > 0) toFixed(v, pr) + units[i] + (if (mf == 1024) "i" else "") else jsString(v) + " "
    }

    /** bandwidth.js rate(n, br). */
    fun rate(n: Double?, br: Boolean): String {
        val v = toFixed(num(n), 2).toDoubleOrNull() ?: 0.0
        return m(v * 8, 1024) + "bit/s" + (if (br) "\n" else " ") + "(" + m(v, 1024) + "B/s)"
    }

    /** cbi.js sfh() (SuperFastHash as 8 hex digits). */
    fun sfh(s: String?): String? {
        if (s.isNullOrEmpty()) return null
        val b = s.toByteArray(Charsets.UTF_8)
        if (b.isEmpty()) return null
        fun u16(off: Int) = ((b[off + 1].toInt() and 0xff) shl 8) + (b[off].toInt() and 0xff)
        fun s8(off: Int) = b[off].toInt()
        var hash = b.size
        var len = b.size ushr 2
        var off = 0
        while (len-- > 0) {
            hash += u16(off)
            val tmp = (u16(off + 2) shl 11) xor hash
            hash = (hash shl 16) xor tmp
            hash += hash ushr 11
            off += 4
        }
        when (b.size and 3) {
            3 -> { hash += u16(off); hash = hash xor (hash shl 16); hash = hash xor (s8(off + 2) shl 18); hash += hash ushr 11 }
            2 -> { hash += u16(off); hash = hash xor (hash shl 11); hash += hash ushr 17 }
            1 -> { hash += s8(off); hash = hash xor (hash shl 10); hash += hash ushr 1 }
        }
        hash = hash xor (hash shl 3); hash += hash ushr 5
        hash = hash xor (hash shl 4); hash += hash ushr 17
        hash = hash xor (hash shl 25); hash += hash ushr 6
        return "%08x".format(hash)
    }

    private class Prng(seed: Int) {
        private var s: Long = seed.toLong() and 0xffffffffL
        fun int(): Int { s = s * 0x5851f42d4c957f2dL + 1; return (s ushr 33).toInt() }
        fun get(): Double = (int() % 0x7fffffff).toDouble() / 0x7fffffff
        fun get(upper: Int): Int = floor(get() * upper).toInt() + 1
    }

    /** tools/prng.js derive_color(): 0xRRGGBB. */
    fun deriveColor(s: String): Int {
        val h = sfh(s)?.toLongOrNull(16)
        val p = Prng(if (h == null) 0 else (h - 1).toInt())
        val r = p.get(128)
        val g = p.get(128)
        var min = 0
        var max = 128
        if (r + g < 128) min = 128 - r - g else max = 255 - r - g
        val b = min + floor(p.get() * (max - min)).toInt()
        return ((0xff - r) shl 16) or ((0xff - g) shl 8) or (0xff - b)
    }
}
