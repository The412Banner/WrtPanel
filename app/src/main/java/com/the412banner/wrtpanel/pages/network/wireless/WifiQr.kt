/*
 * QR encoder: Kotlin port of LuCI's luci-lib-uqr (uqr.js).
 *
 * MIT License
 *
 * Copyright (c) Project Nayuki
 * Copyright (c) 2023 Anthony Fu <https://github.com/antfu>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.the412banner.wrtpanel.pages.network.wireless

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal object Qr {
    private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
        intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
    )
    private val NUM_ERROR_CORRECTION_BLOCKS = arrayOf(
        intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
        intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
        intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
    )
    /** [table index, format bits] per level, as uqr's LOW / MEDIUM / QUARTILE / HIGH. */
    private val ECC = mapOf("L" to intArrayOf(0, 1), "M" to intArrayOf(1, 0), "Q" to intArrayOf(2, 3), "H" to intArrayOf(3, 2))
    private const val ALNUM = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ $%*+-./:"

    private class Segment(val mode: IntArray, val numChars: Int, val bits: List<Int>)
    private val MODE_NUMERIC = intArrayOf(1, 10, 12, 14)
    private val MODE_ALPHANUMERIC = intArrayOf(2, 9, 11, 13)
    private val MODE_BYTE = intArrayOf(4, 8, 16, 16)

    private fun appendBits(v: Int, len: Int, out: MutableList<Int>) { for (i in len - 1 downTo 0) out += (v ushr i) and 1 }
    private fun bit(x: Int, i: Int) = ((x ushr i) and 1) != 0
    private fun ccBits(mode: IntArray, ver: Int) = mode[(ver + 7) / 17 + 1]

    private fun makeSegments(t: String): List<Segment> {
        if (t.isEmpty()) return emptyList()
        if (Regex("^[0-9]*$").matches(t)) {
            val b = ArrayList<Int>()
            var i = 0
            while (i < t.length) { val n = minOf(t.length - i, 3); appendBits(t.substring(i, i + n).toInt(), n * 3 + 1, b); i += n }
            return listOf(Segment(MODE_NUMERIC, t.length, b))
        }
        if (Regex("^[A-Z0-9 $%*+./:-]*$").matches(t)) {
            val b = ArrayList<Int>()
            var i = 0
            while (i + 2 <= t.length) { appendBits(ALNUM.indexOf(t[i]) * 45 + ALNUM.indexOf(t[i + 1]), 11, b); i += 2 }
            if (i < t.length) appendBits(ALNUM.indexOf(t[i]), 6, b)
            return listOf(Segment(MODE_ALPHANUMERIC, t.length, b))
        }
        val bytes = t.toByteArray(Charsets.UTF_8)
        val b = ArrayList<Int>()
        bytes.forEach { appendBits(it.toInt() and 0xff, 8, b) }
        return listOf(Segment(MODE_BYTE, bytes.size, b))
    }

    private fun rawDataModules(ver: Int): Int {
        var r = (16 * ver + 128) * ver + 64
        if (ver >= 2) {
            val n = ver / 7 + 2
            r -= (25 * n - 10) * n - 55
            if (ver >= 7) r -= 36
        }
        return r
    }

    private fun dataCodewords(ver: Int, ecc: IntArray) =
        rawDataModules(ver) / 8 - ECC_CODEWORDS_PER_BLOCK[ecc[0]][ver] * NUM_ERROR_CORRECTION_BLOCKS[ecc[0]][ver]

    private fun totalBits(segs: List<Segment>, ver: Int): Int {
        var n = 0
        for (s in segs) {
            val cc = ccBits(s.mode, ver)
            if (s.numChars >= (1 shl cc)) return Int.MAX_VALUE
            n += 4 + cc + s.bits.size
        }
        return n
    }

    private fun rsMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) { z = (z shl 1) xor ((z ushr 7) * 0x11d); z = z xor (((y ushr i) and 1) * x) }
        return z
    }

    private fun rsDivisor(degree: Int): IntArray {
        val r = IntArray(degree); r[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in r.indices) { r[j] = rsMultiply(r[j], root); if (j + 1 < r.size) r[j] = r[j] xor r[j + 1] }
            root = rsMultiply(root, 0x02)
        }
        return r
    }

    private fun rsRemainder(data: List<Int>, div: IntArray): List<Int> {
        val r = ArrayList<Int>(List(div.size) { 0 })
        for (b in data) {
            val f = b xor r.removeAt(0)
            r.add(0)
            div.forEachIndexed { i, c -> r[i] = r[i] xor rsMultiply(c, f) }
        }
        return r
    }

    /** encode(): the module matrix (true = dark) including a 1-module border. */
    fun encode(text: String, eccName: String = "L"): Array<BooleanArray> {
        val ecc = ECC[eccName] ?: ECC.getValue("L")
        val segs = makeSegments(text)
        var ver = 1
        var used: Int
        while (true) {
            used = totalBits(segs, ver)
            if (used <= dataCodewords(ver, ecc) * 8) break
            if (ver >= 40) throw IllegalArgumentException("Data too long")
            ver++
        }
        val bb = ArrayList<Int>()
        for (s in segs) { appendBits(s.mode[0], 4, bb); appendBits(s.numChars, ccBits(s.mode, ver), bb); bb.addAll(s.bits) }
        val cap = dataCodewords(ver, ecc) * 8
        appendBits(0, minOf(4, cap - bb.size), bb)
        appendBits(0, (8 - bb.size % 8) % 8, bb)
        var pad = 0xEC
        while (bb.size < cap) { appendBits(pad, 8, bb); pad = pad xor 0xEC xor 0x11 }
        val data = IntArray((bb.size + 7) / 8)
        bb.forEachIndexed { i, v -> data[i ushr 3] = data[i ushr 3] or (v shl (7 - (i and 7))) }
        val m = Matrix(ver, ecc, data.toList())
        val border = 1
        val n = m.size + 2 * border
        return Array(n) { y -> BooleanArray(n) { x -> m.get(x - border, y - border) } }
    }

    private class Matrix(val ver: Int, val ecc: IntArray, data: List<Int>) {
        val size = ver * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        init {
            drawFunctionPatterns()
            drawCodewords(addEccAndInterleave(data))
            var best = 0
            var min = Int.MAX_VALUE
            for (i in 0 until 8) {
                applyMask(i); drawFormatBits(i)
                val p = penalty()
                if (p < min) { best = i; min = p }
                applyMask(i)
            }
            applyMask(best); drawFormatBits(best)
        }

        fun get(x: Int, y: Int) = x in 0 until size && y in 0 until size && modules[y][x]

        private fun setF(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; isFunction[y][x] = true }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) { setF(6, i, i % 2 == 0); setF(i, 6, i % 2 == 0) }
            finder(3, 3); finder(size - 4, 3); finder(3, size - 4)
            val pos = alignmentPositions()
            val n = pos.size
            for (i in 0 until n) for (j in 0 until n) {
                if (!(i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0)) alignment(pos[i], pos[j])
            }
            drawFormatBits(0)
            drawVersion()
        }

        fun drawFormatBits(mask: Int) {
            val d = (ecc[1] shl 3) or mask
            var rem = d
            for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            val bits = ((d shl 10) or rem) xor 0x5412
            for (i in 0..5) setF(8, i, bit(bits, i))
            setF(8, 7, bit(bits, 6)); setF(8, 8, bit(bits, 7)); setF(7, 8, bit(bits, 8))
            for (i in 9 until 15) setF(14 - i, 8, bit(bits, i))
            for (i in 0 until 8) setF(size - 1 - i, 8, bit(bits, i))
            for (i in 8 until 15) setF(8, size - 15 + i, bit(bits, i))
            setF(8, size - 8, true)
        }

        private fun drawVersion() {
            if (ver < 7) return
            var rem = ver
            for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
            val bits = (ver shl 12) or rem
            for (i in 0 until 18) {
                val b = bit(bits, i)
                val a = size - 11 + i % 3
                val c = i / 3
                setF(a, c, b); setF(c, a, b)
            }
        }

        private fun finder(x: Int, y: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val dist = maxOf(Math.abs(dx), Math.abs(dy))
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) setF(xx, yy, dist != 2 && dist != 4)
            }
        }

        private fun alignment(x: Int, y: Int) {
            for (dy in -2..2) for (dx in -2..2) setF(x + dx, y + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }

        private fun alignmentPositions(): List<Int> {
            if (ver == 1) return emptyList()
            val num = ver / 7 + 2
            val step = if (ver == 32) 26 else 2 * Math.ceil((ver * 4 + 4).toDouble() / (num * 2 - 2)).toInt()
            val r = mutableListOf(6)
            var p = size - 7
            while (r.size < num) { r.add(1, p); p -= step }
            return r
        }

        private fun addEccAndInterleave(data: List<Int>): List<Int> {
            val numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecc[0]][ver]
            val blockEcc = ECC_CODEWORDS_PER_BLOCK[ecc[0]][ver]
            val raw = rawDataModules(ver) / 8
            val numShort = numBlocks - raw % numBlocks
            val shortLen = raw / numBlocks
            val div = rsDivisor(blockEcc)
            val blocks = ArrayList<List<Int>>()
            var k = 0
            for (i in 0 until numBlocks) {
                val dat = data.subList(k, k + shortLen - blockEcc + (if (i < numShort) 0 else 1)).toMutableList()
                k += dat.size
                val e = rsRemainder(dat, div)
                if (i < numShort) dat.add(0)
                blocks += dat + e
            }
            val out = ArrayList<Int>()
            for (i in blocks[0].indices) blocks.forEachIndexed { j, b -> if (i != shortLen - blockEcc || j >= numShort) out += b[i] }
            return out
        }

        private fun drawCodewords(data: List<Int>) {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0 until 2) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x] && i < data.size * 8) { modules[y][x] = bit(data[i ushr 3], 7 - (i and 7)); i++ }
                }
                right -= 2
            }
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                val inv = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    else -> ((x + y) % 2 + x * y % 3) % 2 == 0
                }
                if (!isFunction[y][x] && inv) modules[y][x] = !modules[y][x]
            }
        }

        private fun penalty(): Int {
            var result = 0
            for (pass in 0 until 2) {
                for (a in 0 until size) {
                    var color = false
                    var run = 0
                    val hist = IntArray(7)
                    for (b in 0 until size) {
                        val m = if (pass == 0) modules[a][b] else modules[b][a]
                        if (m == color) {
                            run++
                            if (run == 5) result += 3 else if (run > 5) result++
                        } else {
                            addHistory(run, hist)
                            if (!color) result += countPatterns(hist) * 40
                            color = m
                            run = 1
                        }
                    }
                    result += terminate(color, run, hist) * 40
                }
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
            }
            var dark = 0
            for (row in modules) for (m in row) if (m) dark++
            val total = size * size
            result += (Math.ceil(Math.abs(dark * 20 - total * 10).toDouble() / total).toInt() - 1) * 10
            return result
        }

        private fun countPatterns(h: IntArray): Int {
            val n = h[1]
            val core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
            return (if (core && h[0] >= n * 4 && h[6] >= n) 1 else 0) + (if (core && h[6] >= n * 4 && h[0] >= n) 1 else 0)
        }

        private fun terminate(color: Boolean, run0: Int, h: IntArray): Int {
            var run = run0
            if (color) { addHistory(run, h); run = 0 }
            run += size
            addHistory(run, h)
            return countPatterns(h)
        }

        private fun addHistory(run0: Int, h: IntArray) {
            var run = run0
            if (h[0] == 0) run += size
            System.arraycopy(h, 0, h, 1, h.size - 1)
            h[0] = run
        }
    }
}

/** Draws [text] as a QR code (uqr renderSVG with white background). */
@Composable
internal fun QrImage(text: String, ecc: String, modifier: Modifier = Modifier) {
    val matrix = remember(text, ecc) { runCatching { Qr.encode(text, ecc) }.getOrNull() }
    if (matrix == null) return
    Canvas(modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f)) {
        val n = matrix.size
        val cell = size.width / n
        drawRect(Color.White)
        for (y in 0 until n) for (x in 0 until n) {
            if (matrix[y][x]) drawRect(Color.Black, topLeft = Offset(x * cell, y * cell), size = Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

/** createWiFiPassword(): the WIFI: URI of the network. */
internal fun wifiQrText(ssid: String, encr: String, key: String, hidden: Boolean): String {
    fun pct(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xff
            val printable = (v in 0x20..0x3A && v != 0x3B) || v in 0x3C..0x7E
            if (printable) append(v.toChar()) else append("%" + v.toString(16).uppercase().padStart(2, '0'))
        }
    }
    val trdisable = when {
        encr == "sae" -> "0"
        encr.startsWith("wpa3") -> "2"
        encr == "owe" -> "3"
        else -> ""
    }
    return listOfNotNull(
        "WIFI:",
        if (key.isNotEmpty()) "T:WPA;" else null,
        if (trdisable.isNotEmpty()) "R:$trdisable;" else null,
        "S:$ssid;",
        if (hidden) "H:true;" else null,
        if (key.isNotEmpty()) "P:${pct(key)};" else null,
    ).joinToString("") + ";"
}
