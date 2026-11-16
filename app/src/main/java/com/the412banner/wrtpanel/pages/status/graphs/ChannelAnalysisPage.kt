@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.the412banner.wrtpanel.pages.status.graphs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.UbusCall
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.double
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.int
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.EmptyState
import com.the412banner.wrtpanel.ui.components.ErrorBox
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.ResponsiveTable
import com.the412banner.wrtpanel.ui.components.SectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.floor

/** create_channel_graph(): column layout, vertical lines and channel labels in units of one column. */
private class ChannelGeometry(freq: List<Int>, band: Int) {
    class VLine(val pos: Double, val width: Double, val dash: Int)

    val columns: Int = if (band != 2) freq.size * 4 else freq.size + 3
    val offsets = HashMap<Int, Double>()
    val lines = ArrayList<VLine>()
    val texts = ArrayList<Pair<Double, String>>()

    init {
        var curr = 1.0
        lines.add(VLine(curr, 0.1, 1))
        for (i in freq.indices) {
            val channel = freq[i]
            offsets[channel] = curr + 1
            if (band != 2) {
                lines.add(VLine(curr + 1, 0.1, 3))
                texts.add((if (channel < 100) curr - 0.5 else curr - 1) to channel.toString())
            } else {
                lines.add(VLine(curr + 1, 0.1, 0))
                texts.add((curr + 1) to channel.toString())
            }
            curr += 1
            if (band != 2 && i + 1 < freq.size && freq[i + 1] != 0) {
                val next = freq[i + 1]
                if (next - channel == 4) {
                    for (j in 1 until 4) {
                        offsets[channel + j] = curr + 1
                        lines.add(VLine(curr + 1, 0.1, if (j == 2) 0 else 1))
                        curr += 1
                    }
                } else {
                    offsets[channel + 1] = curr + 1
                    lines.add(VLine(curr + 1, 0.1, 1))
                    curr += 1
                    offsets[next - 2] = curr + 1
                    lines.add(VLine(curr + 1, 0.5, 0))
                    curr += 1
                    offsets[next - 1] = curr + 1
                    lines.add(VLine(curr + 1, 0.1, 1))
                    curr += 1
                }
            }
        }
        lines.add(VLine(curr + 1, 0.1, 1))
    }
}

/** An iwinfo scan result (or the iwinfo info of the local radio) as handleScanRefresh() uses it. */
private class Bss(j: JsonObject) {
    val bssid: String = j["bssid"].str ?: "undefined"
    var ssid: String? = j["ssid"].str
    val channel: Int? = j["channel"].int
    var signal: Double = j["signal"].double ?: Double.NaN
    val quality: Double = j["quality"].double ?: 0.0
    val qualityMax: Double = j["quality_max"].double ?: 0.0
    val band: Int? = j["band"].int
    val mode: String = j["mode"].str ?: ""
    val ht: JsonElement? = j["ht_operation"]
    val vht: JsonElement? = j["vht_operation"]
    val he: JsonElement? = j["he_operation"]
    val eht: JsonElement? = j["eht_operation"]
    val centerChan1: Int? = j["center_chan1"].int
    val centerChan2: Int? = j["center_chan2"].int
    val htmode: String = j["htmode"].str ?: ""
    var stale = false
    var channelWidth = ""
}

/** The SVG group of one BSSID (polyline, centre marker, label); kept for the life of the page like the SVG nodes. */
private class NetShape(val bssid: String, val label: String) {
    var line: DoubleArray? = null
    var lineTop = 0.0
    var main: DoubleArray? = null
    var mainTop = 0.0
    var textX = Double.NaN
    var z = 0.0
    var stale = false
    var shownTop = 1.0
}

private class DrawShape(
    val color: Int, val label: String, val line: DoubleArray?, val lineTop: Double, val main: DoubleArray?, val mainTop: Double,
    val textX: Double, val fromTop: Double, val stale: Boolean, val z: Double,
)

private class CacheEntry(var data: Bss) {
    var color: Int? = null
    var shape: NetShape? = null
}

private class ScanRow(
    val q: Int?, val signal: Double, val color: Int, val ssid: String?, val channel: String,
    val width: String, val mode: String, val bssid: String, val stale: Boolean,
)

/** this.radios[ifname + band] */
private class RadioTab(val radio: String, val band: Int, val title: String, channels: List<Int>, val uciBand: String?) {
    val geo = ChannelGeometry(channels, band)
    val cache = LinkedHashMap<String, CacheEntry>()
    var rows by mutableStateOf<List<ScanRow>?>(null)
    var shapes by mutableStateOf<List<DrawShape>>(emptyList())
    var serial by mutableIntStateOf(0)
    var loadedOnce = false
    var lastError: String? = null
}

private val bandTitles = linkedMapOf(2 to "2.4GHz", 5 to "5GHz", 6 to "6GHz")

/** parseInt() of a decimal prefix. */
private fun parseIntJs(s: String): Double = Regex("^\\s*([+-]?\\d+)").find(s)?.groupValues?.get(1)?.toDoubleOrNull() ?: Double.NaN

/** add_wifi_to_graph() */
private fun addToGraph(tab: RadioTab, res: Bss, channels: List<Int?>, channelWidth: Double) {
    val entry = tab.cache.getOrPut(res.bssid) { CacheEntry(res) }
    val top = res.signal * -4 / 400
    if (entry.color == null) entry.color = LuciFmt.deriveColor(res.bssid)
    val shape = entry.shape ?: NetShape(res.bssid, res.ssid?.ifEmpty { null } ?: res.bssid).also { entry.shape = it }
    val off = tab.geo.offsets
    for (channel in channels) {
        if (channelWidth > 2) {
            val mo = res.channel?.let { off[it] }
            shape.main = mo?.let { doubleArrayOf(it - 2, it - 1, it + 1, it + 2) }
            shape.mainTop = top
        }
        val co = channel?.let { off[it] }
        shape.line = if (co == null || channelWidth.isNaN()) null
        else doubleArrayOf(co - channelWidth, co - (channelWidth - 1), co + (channelWidth - 1), co + channelWidth)
        shape.lineTop = top
        shape.textX = (res.channel?.let { off[it] } ?: Double.NaN) - 1
        shape.z = -res.signal
        shape.stale = res.stale
    }
}

/** handleScanRefresh() for one tab. */
private suspend fun scanTab(ctx: PageContext, tab: RadioTab) {
    val (scan, info) = coroutineScope {
        val a = async { ctx.client.call("iwinfo", "scan", jsonOf("device" to tab.radio), slow = true)["results"].arr ?: emptyList() }
        val b = async { ctx.client.call("iwinfo", "info", jsonOf("device" to tab.radio)).obj ?: JsonObject(emptyMap()) }
        a.await() to b.await()
    }
    val results = ArrayList<Bss>()
    for (j in scan) {
        val res = Bss(j.obj ?: continue)
        tab.cache.getOrPut(res.bssid) { CacheEntry(res) }.data = res
        res.stale = false
        results.add(res)
    }
    val off = tab.geo.offsets
    val rows = ArrayList<ScanRow>()

    if ("${tab.band}g" == tab.uciBand) {
        val local = Bss(info)
        tab.cache.getOrPut(local.bssid) { CacheEntry(local) }.data = local
        if (local.channel != null && off[local.channel] != null && (local.centerChan1 ?: 0) != 0) {
            val centers = mutableListOf<Int?>(local.centerChan1)
            val widthText = Regex("[EV]*H[TE]").replaceFirst(local.htmode, "")
            var width = parseIntJs(widthText) / 10
            if ((local.centerChan2 ?: 0) != 0) {
                centers.add(local.centerChan2)
                width = 8.0
            }
            local.signal = -10.0
            local.ssid = "Local Interface"
            addToGraph(tab, local, centers, width)
            rows.add(ScanRow(null, local.signal, tab.cache[local.bssid]?.color ?: 0, local.ssid, LuciFmt.d(local.channel.toDouble()), "$widthText MHz", local.mode, local.bssid, false))
        }
    }

    for (e in tab.cache.values) if (e.data.stale) results.add(e.data)
    results.sortWith(compareBy<Bss>({ it.channel ?: 0 }, { it.ssid ?: "" }, { it.bssid }))

    for (res in results) {
        val q = if (res.quality > 0 && res.qualityMax > 0) floor((100 / res.qualityMax) * res.quality).toInt() else 0
        val centers = mutableListOf<Int?>(res.channel)
        var width = 2.0
        if (tab.band != res.band) continue
        if (res.channel == null || off[res.channel] == null) continue

        res.channelWidth = "20 MHz"
        if (res.ht != null) {
            when (res.ht["secondary_channel_offset"].str) {
                "below" -> { res.channelWidth = "40 MHz"; width = 4.0; centers[0] = res.channel - 2 }
                "above" -> { res.channelWidth = "40 MHz"; width = 4.0; centers[0] = res.channel + 2 }
                else -> if (res.ht["channel_width"].int == 2040) res.channelWidth = "20 MHz (40 MHz Intolerant)"
            }
        }
        val vw = res.vht["channel_width"].double
        if (vw != null && vw > 40) {
            val c1 = res.vht["center_freq_1"].int
            val c2 = res.vht["center_freq_2"].int
            centers[0] = c1
            when (vw) {
                80.0 -> {
                    width = 8.0
                    res.channelWidth = "80 MHz"
                    if (c2 != null && c2 != 0) {
                        val diff = abs(c2 - (c1 ?: 0))
                        if (diff == 8) { width = 16.0; res.channelWidth = "160 MHz"; centers.add(c2) }
                        else if (diff > 8) { width = 8.0; res.channelWidth = "80+80 MHz"; centers.add(c2) }
                    }
                }
                8080.0 -> { res.channelWidth = "80+80 MHz"; width = 8.0; centers.add(c2) }
                160.0 -> { res.channelWidth = "160 MHz"; width = 16.0 }
            }
        }
        val hw = res.he["channel_width"].double
        if (hw != null && hw > 20) {
            centers[0] = res.he["center_freq_1"].int
            width = hw / 10
            when (hw) {
                40.0 -> res.channelWidth = "40 MHz"
                80.0 -> res.channelWidth = "80 MHz"
                160.0 -> { res.channelWidth = "160 MHz"; centers.add(res.he["center_freq_2"].int) }
            }
        }
        if (res.eht["channel_width"].double == 320.0) {
            width = 32.0
            res.channelWidth = "320 MHz"
            centers.add(res.eht["center_freq_2"].int)
        }

        addToGraph(tab, res, centers, width)
        rows.add(ScanRow(q, res.signal, tab.cache[res.bssid]?.color ?: 0, res.ssid, LuciFmt.d(res.channel.toDouble()), res.channelWidth, res.mode, res.bssid, res.stale))
        tab.cache[res.bssid]?.data?.stale = true
    }

    tab.rows = rows
    tab.shapes = tab.cache.values.mapNotNull { e ->
        val s = e.shape ?: return@mapNotNull null
        val from = s.shownTop
        s.shownTop = s.lineTop
        DrawShape(e.color ?: 0, s.label, s.line, s.lineTop, s.main, s.mainTop, s.textX, from, s.stale, s.z)
    }.sortedBy { it.z }
    tab.serial++
    tab.loadedOnce = true
}

@Composable
private fun SignalBars(level: Int, modifier: Modifier = Modifier) {
    val on = MaterialTheme.colorScheme.onSurface
    val off = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.size(width = 18.dp, height = 14.dp)) {
        val gap = 1.5.dp.toPx()
        val bw = (size.width - gap * 3) / 4
        for (i in 0 until 4) {
            val bh = size.height * (i + 1) / 4
            drawRoundRect(
                if (level > i) on else off, topLeft = Offset(i * (bw + gap), size.height - bh),
                size = androidx.compose.ui.geometry.Size(bw, bh), cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()),
            )
        }
        if (level < 0) drawLine(on, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = 1.5.dp.toPx())
    }
}

/** render_signal_badge() */
@Composable
private fun SignalBadge(q: Int?, signal: Double) {
    val level = when {
        q == null -> 4
        q < 0 -> -1
        q == 0 -> 0
        q < 25 -> 1
        q < 50 -> 2
        q < 75 -> 3
        else -> 4
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        SignalBars(level)
        Spacer(Modifier.width(6.dp))
        Text("%s %s".format(LuciFmt.d(signal), "dBm"), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
    }
}

/** Colour of a BSSID: derive_color(), darkened on light backgrounds so lines and labels keep their contrast. */
@Composable
private fun bssColor(rgb: Int): Color {
    val c = Color(0xff000000.toInt() or rgb)
    return if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) lerp(c, Color.Black, 0.38f) else c
}

@Composable
private fun ChannelChart(tab: RadioTab) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val strong = MaterialTheme.colorScheme.outline
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val backdrop = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.82f)
    val centre = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val dark = MaterialTheme.colorScheme.surface.luminance() <= 0.5f
    val colors = tab.shapes.map { bssColor(it.color) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(tab.serial) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }

    Canvas(Modifier.fillMaxWidth().height(320.dp)) {
        val w = size.width
        val h = size.height
        val dp1 = 1.dp.toPx()
        val step = w / tab.geo.columns
        val t = progress.value

        for (f in floatArrayOf(0.25f, 0.5f, 0.75f, 0.9f)) drawLine(grid, Offset(0f, h * f), Offset(w, h * f), strokeWidth = 0.8f * dp1)
        for (l in tab.geo.lines) {
            val x = (l.pos * step).toFloat()
            drawLine(
                if (l.width > 0.2) strong else grid, Offset(x, 0f), Offset(x, h),
                strokeWidth = if (l.width > 0.2) 1.2f * dp1 else 0.8f * dp1,
                pathEffect = when (l.dash) { 1 -> PathEffect.dashPathEffect(floatArrayOf(2 * dp1, 2 * dp1)); 3 -> PathEffect.dashPathEffect(floatArrayOf(4 * dp1, 4 * dp1)); else -> null },
            )
        }

        clipRect(0f, 0f, w, h) {
            tab.shapes.forEachIndexed { i, s ->
                val color = colors[i]
                val a = if (s.stale) 0.5f else 1f
                val top = (s.fromTop + (s.lineTop - s.fromTop) * t).coerceIn(0.0, 1.0)
                s.main?.let { m ->
                    val y = (h * (s.fromTop + (s.mainTop - s.fromTop) * t).coerceIn(0.0, 1.0)).toFloat()
                    val xs = m.map { (it * step).toFloat() }
                    val p = Path().apply { moveTo(xs[0], h); lineTo(xs[1], y); lineTo(xs[2], y); lineTo(xs[3], h); close() }
                    drawPath(p, Brush.horizontalGradient(listOf(Color.Transparent, centre, Color.Transparent), startX = xs[0], endX = xs[3]), alpha = a)
                }
                s.line?.let { l ->
                    val y = (h * top).toFloat()
                    val xs = l.map { (it * step).toFloat() }
                    val p = Path().apply { moveTo(xs[0], h); lineTo(xs[1], y); lineTo(xs[2], y); lineTo(xs[3], h) }
                    drawPath(p, color.copy(alpha = 0x4f / 255f * a))
                    drawPath(p, color.copy(alpha = a), style = Stroke(width = 1.2f * dp1))
                }
                if (!s.textX.isNaN()) {
                    val style = labelStyle.copy(color = color.copy(alpha = a), shadow = if (dark) Shadow(Color.Black, Offset(dp1, dp1), dp1) else null)
                    val layout = measurer.line(s.label, style)
                    drawText(layout, topLeft = Offset((s.textX * step).toFloat(), (h * top).toFloat() - 2 * dp1 - layout.size.height))
                }
            }
        }

        for ((pos, text) in tab.geo.texts) chartLabel(measurer.line(text, labelStyle), (pos * step).toFloat() + 5 * dp1, 4 * dp1, backdrop)
        listOf(0.25f to "-25 dbm", 0.5f to "-50 dbm", 0.75f to "-75 dbm", 0.9f to "-90 dbm").forEach { (f, text) ->
            val layout = measurer.line(text, labelStyle)
            chartLabel(layout, 10 * dp1, h * f - layout.size.height - 3 * dp1, backdrop)
        }
    }
}

@Composable
private fun ScanTable(tab: RadioTab) {
    val rows = tab.rows
    if (rows == null) {
        LoadingBox(text = "Starting wireless scan...")
        return
    }
    ResponsiveTable(
        headers = listOf("Signal", "SSID", "Channel", "Channel Width", "Mode", "BSSID"),
        rows = rows.map { listOf(LuciFmt.d(it.signal), it.ssid ?: "hidden", it.channel, it.width, it.mode, it.bssid) },
        weights = listOf(2f, 4f, 2f, 3f, 2f, 3f),
        primaryColumn = 1,
        empty = "No data",
        cell = { r, col, text ->
            val row = rows[r]
            Box(Modifier.alpha(if (row.stale) 0.5f else 1f)) {
                when (col) {
                    0 -> SignalBadge(row.q, row.signal)
                    1 -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(bssColor(row.color)))
                        Spacer(Modifier.width(6.dp))
                        if (row.ssid != null) Text(row.ssid, style = MaterialTheme.typography.titleSmall)
                        else Text("hidden", style = MaterialTheme.typography.titleSmall.copy(fontStyle = FontStyle.Italic))
                    }
                    else -> Text(text.ifEmpty { "-" }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
    )
}

/** Status › Channel Analysis — port of luci-mod-status view/status/channel_analysis.js. */
@Composable
fun ChannelAnalysisPage(ctx: PageContext) {
    var tabs by remember { mutableStateOf<List<RadioTab>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        try {
            val st = NetState.load(ctx)
            val radios = st.wifiDeviceNames()
            val freqs = ctx.client.batch(radios.map { UbusCall("iwinfo", "freqlist", jsonOf("device" to it)) }).map { it.getOrThrow()["results"].arr ?: emptyList() }
            val list = ArrayList<RadioTab>()
            radios.forEachIndexed { i, radio ->
                for ((band, title) in bandTitles) {
                    val channels = freqs[i].filter { it["band"].int == band }.mapNotNull { it["channel"].int }
                    if (channels.isEmpty()) continue
                    list.add(RadioTab(radio, band, "$radio ($title)", channels, st.wireless.get(radio, "band") as? String))
                }
            }
            tabs = list
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    var selected by rememberSaveable { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = tabs
    val active = if (list.isNullOrEmpty()) null else list[selected.coerceIn(0, list.size - 1)]

    suspend fun refresh(tab: RadioTab, manual: Boolean) {
        busy = true
        try {
            scanTab(ctx, tab)
            tab.lastError = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.toString()
            if (manual || msg != tab.lastError) ctx.toast(msg)
            tab.lastError = msg
        } finally {
            busy = false
        }
    }

    if (active != null) {
        LaunchedEffect(active) {
            while (!active.loadedOnce) {
                if (!busy) refresh(active, manual = false)
                if (!active.loadedOnce) delay((ctx.client.env?.pollInterval ?: 5) * 1000L)
            }
        }
    }

    PageColumn {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { PageHeader("Channel Analysis", null) }
            FilledTonalButton(onClick = { if (active != null) scope.launch { refresh(active, manual = true) } }, enabled = active != null && !busy) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("Refresh Channels")
            }
        }
        when {
            error != null && list == null -> ErrorBox(error!!) { reload++ }
            list == null -> LoadingBox()
            list.isEmpty() -> EmptyState("No information available")
            active != null -> {
                GraphTabs(list.map { it.title }, list.indexOf(active)) { selected = it }
                SectionCard(contentPadding = PaddingValues(vertical = 10.dp)) { ChannelChart(active) }
                SectionCard { ScanTable(active) }
            }
        }
    }
}
