@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.the412banner.wrtpanel.pages.network.wireless

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.ui.theme.LocalStatusColors

/** render_signal_badge(): bars (-1 = none), the value text and the tooltip title. */
internal class SignalInfo(val bars: Int, val value: String, val title: String, val disabled: Boolean, val percent: Int)

internal fun signalInfo(percent: Int, signal: Double?, noise: Double?, mode: String? = null): SignalInfo {
    val bars = when {
        percent < 0 -> -1
        percent == 0 -> 0
        percent < 25 -> 1
        percent < 50 -> 2
        percent < 75 -> 3
        else -> 4
    }
    val s = signal?.takeIf { it != 0.0 }
    val n = noise?.takeIf { it != 0.0 }
    return when {
        s != null && n != null -> SignalInfo(
            bars, "${jsNum(s)}/${jsNum(n)} dBm",
            listOf("Signal: ${jsNum(s)} dBm", "Noise: ${jsNum(n)} dBm", "SNR: ${jsNum(s - n)} dBm").joinToString(" / "), false, percent,
        )
        s != null -> SignalInfo(bars, "${jsNum(s)} dBm", "Signal ${jsNum(s)} dBm", false, percent)
        percent > -1 -> {
            val t = when (mode) {
                "ap" -> "No client associated"
                "sta", "adhoc", "mesh" -> "Not associated"
                else -> "No RX signal"
            }
            if (n != null) SignalInfo(bars, "---/${jsNum(n)} dBm", "$t / Noise: ${jsNum(n)} dBm", false, percent)
            else SignalInfo(bars, "--- dBm", t, false, percent)
        }
        else -> SignalInfo(bars, "disabled", "Interface is disabled", true, percent)
    }
}

/** Four rising bars; [bars] of them filled, none when -1. */
@Composable
internal fun SignalBars(bars: Int, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    val sc = LocalStatusColors.current
    val on = when {
        bars >= 3 -> sc.ok
        bars == 2 -> sc.warn
        bars >= 0 -> sc.bad
        else -> MaterialTheme.colorScheme.outline
    }
    val off = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.size(size)) {
        val gap = this.size.width * 0.08f
        val w = (this.size.width - gap * 3) / 4
        for (i in 0 until 4) {
            val h = this.size.height * (0.3f + 0.7f * (i + 1) / 4f)
            drawRoundRect(
                color = if (i < bars) on else off,
                topLeft = Offset(i * (w + gap), this.size.height - h),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 3, w / 3),
            )
        }
        if (bars < 0) {
            drawLine(on, Offset(0f, 0f), Offset(this.size.width, this.size.height), strokeWidth = w / 2, cap = StrokeCap.Round)
        }
    }
}

/** A signal badge: bars with the value next to (or below, [wrap]) them; long-press shows the title. */
@Composable
internal fun SignalBadge(info: SignalInfo, wrap: Boolean = false, modifier: Modifier = Modifier) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(info.title) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        val text: @Composable () -> Unit = {
            Text(
                info.value,
                style = if (info.disabled) MaterialTheme.typography.labelSmall.copy(fontStyle = FontStyle.Italic) else MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (wrap) Column(horizontalAlignment = Alignment.CenterHorizontally) { SignalBars(info.bars, size = 30.dp); Spacer(Modifier.padding(top = 2.dp)); text() }
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { SignalBars(info.bars); text() }
    }
}

/** A value of [itemList]: text, optionally emphasised (LuCI's <em>). */
internal class Item(val label: String, val value: String?, val em: Boolean = false)

/** L.itemlist(): "Label: value" items with cycling separators; items without value are skipped. */
internal fun itemList(items: List<Item>, separators: List<String>): AnnotatedString = buildAnnotatedString {
    items.forEachIndexed { i, it ->
        val v = it.value ?: return@forEachIndexed
        if (it.label.isNotEmpty()) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("${it.label}: ") }
        if (it.em) withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(v) } else append(v)
        if (i + 1 < items.size) append(separators[i % separators.size])
    }
}

/** render_network_status() */
internal fun networkStatus(net: WifiNet, pending: Int): AnnotatedString {
    val mode = net.activeMode
    val bssid = net.activeBSSID
    val channel = net.channel
    val disabled = net.get("disabled") == "1" || net.wifiDeviceName?.let { net.st.uci.getString("wireless", it, "disabled") } == "1"
    val assoc = bssid != null && bssid != "00:00:00:00:00:00" && channel != null && mode != "Unknown" && !disabled
    val mesh = net.mode == "mesh"
    val status = when {
        pending > 0 -> Item("", "Interface has %d pending changes".format(pending))
        !assoc -> Item("", if (disabled) "Wireless is disabled" else "Wireless is not associated", em = true)
        else -> Item("", null)
    }
    return itemList(
        listOf(
            Item(if (mesh) "Mesh ID" else "SSID", (if (mesh) net.meshId else net.ssid) ?: "?"),
            Item("Mode", mode),
            Item("BSSID", if (pending == 0 && assoc) bssid else null),
            Item("Encryption", if (pending == 0 && assoc) net.activeEncryption else null),
            status,
        ),
        listOf(" | ", "\n"),
    )
}

/** render_modal_status() text part. */
internal fun modalStatus(net: WifiNet): Pair<SignalInfo, AnnotatedString> {
    val mode = net.activeMode
    val noise = net.noise
    val bssid = net.activeBSSID
    val channel = net.channel
    val disabled = net.get("disabled") == "1"
    val assoc = bssid != null && bssid != "00:00:00:00:00:00" && channel != null && mode != "Unknown" && !disabled
    val sig = signalInfo(if (disabled) -1 else net.signalPercent, net.signal, noise, net.mode)
    var text = itemList(
        listOf(
            Item("Mode", mode),
            Item("SSID", net.ssid ?: "?"),
            Item("BSSID", if (assoc) bssid else null),
            Item("Encryption", if (assoc) net.activeEncryption else null),
            Item("Channel", if (assoc) "${net.channel} (${net.frequency ?: 0} GHz)" else null),
            Item("Tx-Power", if (assoc) "${net.txPower?.let { jsNum(it) }} dBm" else null),
            Item("Signal", if (assoc) "${jsNum(net.signal)} dBm" else null),
            Item("Noise", if (assoc) "${jsNum(noise)} dBm" else null),
            Item("Bitrate", if (assoc) "${net.bitRate ?: 0} Mbit/s" else null),
            Item("Country", if (assoc) net.countryCode else null),
        ),
        listOf(" | ", "\n", "\n", "\n", "\n", "\n", " | ", "\n", " | "),
    )
    if (!assoc) text = buildAnnotatedString {
        append(text)
        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(if (disabled) "Wireless is disabled" else "Wireless is not associated") }
    }
    return sig to text
}

@Composable
internal fun ModalStatusView(net: WifiNet?) {
    if (net == null) return
    val (sig, text) = modalStatus(net)
    Column {
        Text("Status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SignalBadge(sig, wrap = true, modifier = Modifier.width(84.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
