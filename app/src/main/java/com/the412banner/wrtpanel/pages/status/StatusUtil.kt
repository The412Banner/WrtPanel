package com.the412banner.wrtpanel.pages.status

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import com.the412banner.wrtpanel.core.UbusException
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.theme.CodeStyle
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import java.text.Collator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.floor

/** Status helpers — ports of LuCI's String.format, naturalCompare, tools/prng.js zone colours and Intl date output. */
internal object LuciFmt {
    /** String.prototype.format '%m': [mf] = min length field (1000 or 1024), [pr] = precision digits. */
    fun m(value: Number?, mf: Int = 1000, pr: Int = 2): String {
        var v = value?.toDouble()?.takeIf { !it.isNaN() } ?: 0.0
        val units = listOf(" ", " K", " M", " G", " T", " P", " E")
        var i = 0
        while (i < units.size && v > mf) { v /= mf; i++ }
        return if (i > 0) String.format(Locale.US, "%.${pr}f", v) + units[i.coerceAtMost(units.size - 1)] + (if (mf == 1024) "i" else "")
        else num(v) + " "
    }

    /** '%1024.2mB' */
    fun bytes(value: Number?): String = m(value, 1024, 2) + "B"

    /** String.prototype.format '%t'. */
    fun t(param: Number?): String {
        var ts = param?.toDouble() ?: 0.0
        var tm = 0.0; var th = 0.0; var td = 0.0
        if (ts > 59) { tm = floor(ts / 60); ts %= 60 }
        if (tm > 59) { th = floor(tm / 60); tm %= 60 }
        if (th > 23) { td = floor(th / 24); th %= 24 }
        fun d(x: Double) = floor(x).toLong().toString()
        return if (td > 0) "${d(td)}d ${d(th)}h ${d(tm)}m ${d(ts)}s" else "${d(th)}h ${d(tm)}m ${d(ts)}s"
    }

    /** JavaScript Number#toString for the values LuCI prints. */
    fun num(v: Double): String =
        if (v == floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()

    fun num(v: Number?): String = if (v == null) "" else num(v.toDouble())

    /** '%h' */
    fun esc(s: Any?): String {
        if (s == null || s is JsonObject) return ""
        return s.toString().replace("&", "&#38;").replace("\"", "&#34;").replace("'", "&#39;").replace("<", "&#60;").replace(">", "&#62;")
    }

    private val collator: Collator = Collator.getInstance().apply { strength = Collator.TERTIARY }

    /** L.naturalCompare (Intl.Collator with numeric: true). */
    val naturalCompare: Comparator<String?> = Comparator { a0, b0 ->
        val a = a0 ?: "undefined"; val b = b0 ?: "undefined"
        val ra = Regex("\\d+|\\D+").findAll(a).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0'); val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length.compareTo(ny.length) else nx.compareTo(ny)
            } else collator.compare(x, y)
            if (c != 0) return@Comparator c
        }
        ra.size.compareTo(rb.size)
    }

    /** Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle, hourCycle, timeZone }) as used by LuCI. */
    fun date(epochMs: Long, uci: UciStore): String {
        val zn = uci.getString("system", "@system[0]", "zonename")?.replace(' ', '_')?.ifEmpty { null } ?: "UTC"
        val ts = uci.getString("system", "@system[0]", "clock_timestyle") ?: "0"
        val hc = uci.getString("system", "@system[0]", "clock_hourcycle") ?: "0"
        val zone = runCatching { ZoneId.of(zn) }.getOrDefault(ZoneId.of("UTC"))
        val full = ts != "0"
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        val fmt = when (hc) {
            "h12" -> DateTimeFormatter.ofPattern(if (full) "MMM d, yyyy, h:mm:ss a zzzz" else "MMM d, yyyy, h:mm:ss a z", Locale.getDefault())
            "h11" -> DateTimeFormatter.ofPattern(if (full) "MMM d, yyyy, K:mm:ss a zzzz" else "MMM d, yyyy, K:mm:ss a z", Locale.getDefault())
            "h23" -> DateTimeFormatter.ofPattern(if (full) "MMM d, yyyy, HH:mm:ss zzzz" else "MMM d, yyyy, HH:mm:ss z", Locale.getDefault())
            "h24" -> DateTimeFormatter.ofPattern(if (full) "MMM d, yyyy, kk:mm:ss zzzz" else "MMM d, yyyy, kk:mm:ss z", Locale.getDefault())
            else -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, if (full) FormatStyle.FULL else FormatStyle.LONG).withLocale(Locale.getDefault())
        }
        return runCatching { t.format(fmt) }.getOrElse { t.toString() }
    }

    // cbi.js sfh() and tools/prng.js derive_color(), used by firewall.getColorForName().
    private fun i32(x: Long): Int = x.toInt()
    private fun u32(x: Long): Long = x and 0xFFFFFFFFL

    private fun sfh(s: String): String? {
        val bytes = s.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff }
        if (bytes.isEmpty()) return null
        fun u16(o: Int): Long = (((bytes.getOrElse(o + 1) { 0 }) shl 8) + bytes.getOrElse(o) { 0 }).toLong()
        fun s8(o: Int): Long { val n = bytes.getOrElse(o) { 0 }; return if (n > 0x7f) u32((n - 256).toLong()) else n.toLong() }
        var hash = u32(bytes.size.toLong())
        var len = bytes.size ushr 2
        var off = 0
        while (len-- > 0) {
            hash += u16(off)
            val tmp = u32((i32(u16(off + 2)) shl 11 xor i32(hash)).toLong())
            hash = u32((i32(hash) shl 16 xor i32(tmp)).toLong())
            hash += u32(hash) ushr 11
            off += 4
        }
        when (bytes.size and 3) {
            3 -> {
                hash += u16(off)
                hash = u32((i32(hash) xor (i32(hash) shl 16)).toLong())
                hash = u32((i32(hash) xor (i32(s8(off + 2)) shl 18)).toLong())
                hash += u32(hash) ushr 11
            }
            2 -> {
                hash += u16(off)
                hash = u32((i32(hash) xor (i32(hash) shl 11)).toLong())
                hash += u32(hash) ushr 17
            }
            1 -> {
                hash += s8(off)
                hash = u32((i32(hash) xor (i32(hash) shl 10)).toLong())
                hash += u32(hash) ushr 1
            }
        }
        hash = u32((i32(hash) xor (i32(hash) shl 3)).toLong())
        hash += u32(hash) ushr 5
        hash = u32((i32(hash) xor (i32(hash) shl 4)).toLong())
        hash += u32(hash) ushr 17
        hash = u32((i32(hash) xor (i32(hash) shl 25)).toLong())
        hash += u32(hash) ushr 6
        return java.lang.Long.toHexString(0x100000000L + hash).substring(1)
    }

    private class Prng(seed: Long) {
        private var s = LongArray(4)

        init {
            val n = i32(seed - 1)
            s[0] = (n and 0xffff).toLong()
            s[1] = (n ushr 16).toLong()
        }

        private fun mul(a: LongArray, b: LongArray): LongArray {
            val r = LongArray(8)
            for (j in 0 until 4) {
                var k = 0L
                for (i in 0 until 4) {
                    val t = a[i] * b[j] + r[i + j] + k
                    r[i + j] = t and 0xffff
                    k = t ushr 16
                }
                r[j + 4] = k
            }
            return r.copyOf(4)
        }

        private fun add(a: LongArray, n: Long): LongArray {
            val r = LongArray(4)
            var k = n
            for (i in 0 until 4) {
                val t = a[i] + k
                r[i] = t and 0xffff
                k = t ushr 16
            }
            return r
        }

        private fun shr(a: LongArray, n0: Int): LongArray {
            val r = longArrayOf(a[0], a[1], a[2], a[3], 0)
            var n = n0; var i = 4; var k = 0L
            while (n > 16) { for (j in 0 until 4) r[j] = r[j + 1]; n -= 16; i-- }
            while (i > 0) {
                val v = r[i - 1]
                r[i - 1] = (v ushr n) or k
                k = (v and ((1L shl n) - 1)) shl (16 - n)
                i--
            }
            return r.copyOf(4)
        }

        fun int(): Long {
            s = mul(s, longArrayOf(0x7f2d, 0x4c95, 0xf42d, 0x5851))
            s = add(s, 1)
            val r = shr(s, 33)
            return (r[1] shl 16) or r[0]
        }

        fun get(): Double = (int() % 0x7fffffff).toDouble() / 0x7fffffff
        fun get(u: Int): Int = floor(get() * u).toInt() + 1
    }

    /** firewall.getColorForName(). */
    fun zoneColor(name: String?): Color {
        val hex = when (name) {
            null -> 0xeeeeee
            "lan" -> 0x90f090
            "wan" -> 0xf09090
            else -> {
                val h = sfh(name)?.toLongOrNull(16) ?: 0L
                val p = Prng(h)
                val r = p.get(128); val g = p.get(128)
                var min = 0; var max = 128
                if (r + g < 128) min = 128 - r - g else max = 255 - r - g
                val b = min + floor(p.get() * (max - min)).toInt()
                ((0xff - r) shl 16) or ((0xff - g) shl 8) or (0xff - b)
            }
        }
        return Color(0xff000000.toInt() or hex)
    }
}

/** L.hasSystemFeature(name[, sub]). */
internal fun PageContext.sysFeature(name: String, sub: String? = null): Boolean {
    val ft: JsonElement? = session.features[name]
    if (sub != null) return (ft as? JsonObject)?.get(sub)?.bool == true
    return ft != null && ft !is JsonNull && ft.bool != false
}

/** L.hasViewPermission() == false. */
internal val PageContext.isReadonlyView: Boolean get() = readonly || node?.readonly == true

/** rpc.declare without `reject`: a ubus status error resolves to null (the `expect` default). */
internal suspend fun PageContext.rpc(obj: String, method: String, args: JsonObject = JsonObject(emptyMap())): JsonElement? =
    try { call(obj, method, args) } catch (e: UbusException) { null }

/** L.resolveDefault(promise, default). */
internal suspend fun <T> resolveDefault(default: T, block: suspend () -> T): T =
    try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { default }

/** A LuCI "ifacebox": a bordered box with a centered bold head ("active" tinted) and a body. */
@Composable
internal fun IfaceBox(title: String, active: Boolean, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth()
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title, style = MaterialTheme.typography.titleSmall,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        }
    }
}

/** L.itemlist(): "Label: value" lines, entries with a null value are left out. */
@Composable
internal fun ItemList(items: List<Pair<String?, String?>>, small: Boolean = false) {
    val style = if (small) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    SelectionContainer {
        Column {
            items.forEach { (label, value) ->
                if (value == null) return@forEach
                Text(itemText(label, value), style = style)
            }
        }
    }
}

internal fun itemText(label: String?, value: String): AnnotatedString = buildAnnotatedString {
    if (!label.isNullOrEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$label: ") }
    append(value)
}

/** A small coloured zone marker (firewall.getZoneColorStyle). */
@Composable
internal fun ZoneDot(zone: String?, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(LuciFmt.zoneColor(zone?.takeIf { it != "*" })))
}

/** Rounded label like LuCI's "ifacebadge". */
@Composable
internal fun IfBadge(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.let { if (onClick != null) it.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick) else it },
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) { content() }
    }
}

/** State holder for a LuCI hover tooltip, shown on tap. */
@Composable
internal fun rememberTooltip(): MutableTooltip = remember { MutableTooltip() }

internal class MutableTooltip {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    fun show(t: String, monospace: Boolean = false) {
        content = { SelectionContainer { Text(t, style = if (monospace) CodeStyle else MaterialTheme.typography.bodyMedium) } }
    }
    fun show(c: @Composable () -> Unit) { content = c }
}

@Composable
internal fun TooltipHost(t: MutableTooltip) {
    val c = t.content ?: return
    AlertDialog(
        onDismissRequest = { t.content = null },
        confirmButton = { TextButton(onClick = { t.content = null }) { Text("Dismiss") } },
        text = { Box(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) { c() } },
    )
}

/** The text of a JSON value JavaScript treats as truthy, else null. */
internal fun JsonElement?.truthyStr(): String? = when (this) {
    null, is JsonNull -> null
    is kotlinx.serialization.json.JsonPrimitive ->
        if (isString) content.ifEmpty { null }
        else if (content == "false" || content.toDoubleOrNull()?.let { it == 0.0 || it.isNaN() } == true) null
        else content
    else -> toString()
}

/** String.prototype.format for %s %h %q %d %%, keeping LuCI's reuse of the last substitution when arguments run out. */
internal fun jsFormat(tpl: String, vararg args: Any?): String {
    val sb = StringBuilder()
    var i = 0
    var n = 0
    var subst: String? = null
    while (i < tpl.length) {
        val c = tpl[i]
        if (c == '%' && i + 1 < tpl.length && tpl[i + 1] in "%shqd") {
            val t = tpl[i + 1]
            if (t == '%') subst = "%"
            else if (n < args.size) {
                val p = args[n++]
                subst = when (t) {
                    'h' -> LuciFmt.esc(p)
                    'q' -> if (p == null) "" else p.toString().replace("\"", "&#34;").replace("'", "&#39;")
                    'd' -> {
                        val d = when (p) { is Number -> p.toDouble(); is String -> p.trim().toDoubleOrNull(); else -> null }
                        kotlin.math.floor(d?.takeIf { !it.isNaN() } ?: 0.0).toLong().toString()
                    }
                    else -> p?.toString() ?: "undefined"
                }
            }
            sb.append(subst ?: "undefined")
            i += 2
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}
