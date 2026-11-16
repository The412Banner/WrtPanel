package com.the412banner.wrtpanel.pages.status

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.double
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.ResponsiveTable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class WifiBadge(
    val level: Int,
    val title: String,
    val items: List<Pair<String?, String?>>,
    val em: String?,
    val wps: String?,
    val wpsButton: Int,
    val ifname: String?,
)

internal data class RadioBox(val name: String, val up: Boolean, val items: List<Pair<String?, String?>>, val badges: List<WifiBadge>)

internal data class AssocRow(
    val key: String,
    val network: String,
    val ifname: String?,
    val netTitle: String,
    val vlan: String?,
    val vlanZone: String?,
    val mac: String,
    val host: String,
    val sigLevel: Int,
    val sigValue: String,
    val sigTitle: String,
    val rx: String,
    val tx: String,
    val canDisconnect: Boolean,
    val addLabel: String?,
    val sid: String,
    val maclist: List<String>,
)

internal data class WifiModel(val radios: List<RadioBox>, val canRead: Boolean, val rows: List<AssocRow>)

/** Status › Overview include 60_wifi — port of luci-mod-status view/status/include/60_wifi.js. */
internal class WifiInclude : StatusInclude("Wireless") {
    private val wpsTbl = mapOf("Disabled" to "Disabled", "Active" to "Active", "Timed-out" to "Timed-out", "Overlap" to "Overlap", "Unknown" to "Unknown")

    private fun truthy(e: JsonElement?): Boolean = when (e) {
        null, is JsonNull -> false
        is JsonPrimitive -> if (e.isString) e.content.isNotEmpty() else when (e.content) {
            "true" -> true
            "false" -> false
            else -> e.double?.let { it != 0.0 && !it.isNaN() } ?: false
        }
        else -> true
    }

    private fun wifirate(r: JsonElement?): String {
        if (r !is JsonObject) return ""
        fun v(k: String) = r[k]?.let { if (it is JsonPrimitive && !it.isString) LuciFmt.num(it.double) else it.str } ?: "undefined"
        val rate = r["rate"].double?.let { LuciFmt.num(it / 1000) } ?: "NaN"
        var s = "$rate Mbit/s, ${v("mhz")} MHz"
        if (truthy(r["ht"]) || truthy(r["vht"])) s += listOfNotNull(
            if (truthy(r["vht"])) ", VHT-MCS ${v("mcs")}" else null,
            if (truthy(r["nss"])) ", VHT-NSS ${v("nss")}" else null,
            if (truthy(r["ht"])) ", MCS ${v("mcs")}" else null,
            if (truthy(r["short_gi"])) ", Short GI" else null,
        ).joinToString("")
        if (truthy(r["he"])) s += listOfNotNull(
            ", HE-MCS ${v("mcs")}",
            if (truthy(r["nss"])) ", HE-NSS ${v("nss")}" else null,
            if (truthy(r["he_gi"])) ", HE-GI ${v("he_gi")}" else null,
            if (truthy(r["he_dcm"])) ", HE-DCM ${v("he_dcm")}" else null,
        ).joinToString("")
        if (truthy(r["eht"])) s += listOfNotNull(
            ", EHT-MCS ${v("mcs")}",
            if (truthy(r["nss"])) ", EHT-NSS ${v("nss")}" else null,
            if (truthy(r["eht_gi"])) ", EHT-GI ${v("eht_gi")}" else null,
            if (truthy(r["eht_dcm"])) ", EHT-DCM ${v("eht_dcm")}" else null,
        ).joinToString("")
        return s
    }

    private suspend fun assocList(ctx: PageContext, n: WifiNetwork): List<Pair<JsonObject, WifiVlan?>> {
        val out = mutableListOf<Pair<JsonObject, WifiVlan?>>()
        for (vlan in n.getVlans()) {
            ctx.rpc("iwinfo", "assoclist", jsonOf("device" to vlan.ifname))["results"].arr?.forEach { (it as? JsonObject)?.let { s -> out += s to vlan } }
        }
        ctx.rpc("iwinfo", "assoclist", jsonOf("device" to n.getIfname()))["results"].arr?.forEach { (it as? JsonObject)?.let { s -> out += s to null } }
        return out
    }

    override suspend fun fetch(ctx: PageContext, net: NetState): Any? = coroutineScope {
        fun access(fn: String) = async {
            ctx.rpc("session", "access", jsonOf("scope" to "access-group", "object" to "luci-mod-status-index-wifi", "function" to fn))["access"].bool ?: false
        }
        val canRead = access("read")
        val canWrite = access("write")
        val zones = loadZones(ctx)
        if (ctx.sysFeature("wifi")) resolveDefault(Unit) { ctx.uci.load("wireless") }
        val radios = net.getWifiDevices()
        val networks = net.getWifiNetworks()
        val hints = net.hostHints()
        val hasWPS = ctx.sysFeature("hostapd", "wps")

        val assoc = networks.map { n ->
            async { resolveDefault(emptyList()) { assocList(ctx, n) }.sortedBy { it.first["mac"].str ?: "" } }
        }.awaitAll()
        val wps = networks.map { n ->
            async {
                if (hasWPS && ctx.uci.getString("wireless", n.sid, "wps_pushbutton") == "1") {
                    val d = resolveDefault<JsonElement?>(null) { ctx.call("hostapd.${n.getIfname()}", "wps_status") }
                    true to (if (d != null) d["pbc_status"].str else "No Data")
                } else false to null
            }
        }.awaitAll()

        val boxes = radios.map { radio ->
            var chan: String? = null; var coco: String? = null; var freq: String? = null
            var rate: Double? = null; var noise: Double? = null; var txp: Double? = null
            var chanSet = false; var cocoSet = false; var freqSet = false; var rateSet = false; var noiseSet = false; var txpSet = false
            val badges = networks.indices.filter { networks[it].getWifiDeviceName() == radio.getName() }.map { i ->
                val n = networks[i]
                val isAssoc = n.getBSSID() != "00:00:00:00:00:00" && n.getChannel() != null && !n.isDisabled()
                val q = n.getSignalPercent()
                val level = when { n.isDisabled() -> -1; q <= 0 -> 0; q < 25 -> 1; q < 50 -> 2; q < 75 -> 3; else -> 4 }
                val (wpsOn, wpsStatus) = wps[i]
                val badge = WifiBadge(
                    level = level,
                    title = "Signal: ${kotlin.math.floor(n.getSignal()).toLong()} dBm / Quality: $q%",
                    items = listOf(
                        "SSID" to (n.getActiveSSID() ?: "?"),
                        "Mode" to n.getActiveMode(),
                        "BSSID" to (if (isAssoc) (n.getActiveBSSID() ?: "-") else null),
                        "Encryption" to (if (isAssoc) n.getActiveEncryption() else null),
                        "Associations" to (if (isAssoc) assoc[i].size.takeIf { it > 0 }?.toString() ?: "-" else null),
                    ),
                    em = if (isAssoc) null else if (n.isDisabled()) "Wireless is disabled" else "Wireless is not associated",
                    wps = wpsStatus?.let { wpsTbl[it] },
                    wpsButton = if (!wpsOn) 0 else if (wpsStatus == "Active") 2 else 1,
                    ifname = n.getIfname(),
                )
                if (!chanSet) { chan = n.getChannel(); chanSet = chan != null }
                if (!cocoSet) { coco = n.getCountryCode(); cocoSet = true }
                if (!freqSet) { freq = n.getFrequency(); freqSet = freq != null }
                if (!rateSet) { rate = n.getBitRate(); rateSet = rate != null }
                if (!noiseSet) { noise = n.getNoise(); noiseSet = true }
                if (!txpSet) { txp = n.getTXPower(); txpSet = txp != null }
                badge
            }
            val chanNum = chan?.toDoubleOrNull()
            RadioBox(
                name = radio.getName(), up = radio.isUp(),
                items = listOf(
                    "Type" to radio.getI18n().replace(Regex("^Generic | Wireless Controller .+$"), ""),
                    "Bitrate" to rate?.takeIf { it != 0.0 }?.let { "${it.toLong()} Mbit/s" },
                    "Channel" to (if (chan != null && chanNum != 0.0) String.format(java.util.Locale.US, "%d (%.3f %s)", chanNum?.toLong() ?: 0L, freq?.toDoubleOrNull() ?: 0.0, "GHz") else null),
                    "Country Code" to coco?.takeIf { it.isNotEmpty() },
                    "Noise" to noise?.takeIf { it != 0.0 }?.let { String.format(java.util.Locale.US, "%.2f %s", it, "dBm") },
                    "TX Power" to txp?.takeIf { it != 0.0 }?.let { String.format(java.util.Locale.US, "%.2f %s", it, "dBm") },
                ),
                badges = badges,
            )
        }
        if (boxes.isEmpty()) return@coroutineScope null

        val write = canWrite.await()
        val rows = mutableListOf<AssocRow>()
        networks.forEachIndexed { i, n ->
            val macfilter = ctx.uci.getString("wireless", n.sid, "macfilter")
            val filterOn = macfilter != null && macfilter != "disable"
            val maclist = if (filterOn) UciStore.toArray(ctx.uci.get("wireless", n.sid, "maclist")) else emptyList()
            val macset = maclist.map { it.uppercase() }.toSet()
            assoc[i].forEachIndexed { k, (bss, vlan) ->
                val mac = bss["mac"].str ?: ""
                val name = hints.hostnameByMAC(mac)
                val ipv4 = hints.ipByMAC(mac)
                val ipv6 = hints.ip6ByMAC(mac)
                val signal = bss["signal"].double ?: 0.0
                val noiseV = bss["noise"].double
                fun d(x: Double) = kotlin.math.floor(x).toLong()
                val q = minOf((signal + 110) / 70 * 100, 100.0)
                val lvl = when { q == 0.0 -> 0; q < 25 -> 1; q < 50 -> 2; q < 75 -> 3; else -> 4 }
                val (sv, st) = if (noiseV != null && noiseV != 0.0)
                    "${d(signal)}/${d(noiseV)} dBm" to "Signal: ${d(signal)} dBm / Noise: ${d(noiseV)} dBm / SNR ${d(signal - noiseV)}"
                else "${d(signal)} dBm" to "Signal: ${d(signal)} dBm"
                val hint = when {
                    name != null && ipv4 != null && ipv6 != null -> "$name ($ipv4, $ipv6)"
                    name != null && (ipv4 != null || ipv6 != null) -> "$name (${ipv4 ?: ipv6})"
                    else -> name ?: ipv4 ?: ipv6 ?: "?"
                }
                var vlanZone: String? = null
                if (vlan != null) vlan.network?.let { vn -> zones.forEach { z -> if (z.networks.contains(vn)) vlanZone = z.name } }
                val can = n.isClientDisconnectSupported() && write
                rows += AssocRow(
                    key = "${n.sid}-$mac-$k", network = n.getShortName(), ifname = n.getIfname(), netTitle = n.getI18n(),
                    vlan = vlan?.getI18n(), vlanZone = vlanZone, mac = mac, host = hint, sigLevel = lvl, sigValue = sv, sigTitle = st,
                    rx = wifirate(bss["rx"]), tx = wifirate(bss["tx"]), canDisconnect = can,
                    addLabel = if (can && filterOn && !macset.contains(mac)) (if (macfilter == "allow") "Add to Whitelist" else "Add to Blacklist") else null,
                    sid = n.sid, maclist = maclist,
                )
            }
        }
        WifiModel(boxes, canRead.await(), rows)
    }

    private fun delClient(env: OverviewEnv, r: AssocRow, addlist: Boolean) {
        val ctx = env.ctx
        ctx.scope.launch {
            try {
                ctx.call("hostapd.${r.ifname}", "del_client", jsonOf("addr" to r.mac, "deauth" to true, "reason" to 5, "ban_time" to 60000))
            } catch (e: Exception) { if (!addlist) ctx.toast(e.message ?: "Error") }
        }
        if (addlist) {
            ctx.scope.launch {
                try {
                    ctx.uci.set("wireless", r.sid, "maclist", r.maclist + r.mac)
                    ctx.uci.save()
                    ctx.session.changes.afterSave()
                } catch (e: Exception) { ctx.toast(e.message ?: "Error") }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content(model: Any, env: OverviewEnv) {
        val m = model as WifiModel
        val busy = remember { mutableStateListOf<String>() }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
                m.radios.forEach { r ->
                    IfaceBox(r.name, r.up, Modifier.weight(1f).widthIn(min = 260.dp)) {
                        ItemList(r.items)
                        r.badges.forEach { b -> WifiBadgeView(b, env, busy) }
                    }
                }
            }
            if (m.canRead) {
                Text("Associated Stations", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                if (m.rows.isEmpty()) {
                    Text("No information available", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    val rows = m.rows
                    val actions: @Composable RowScope.(Int) -> Unit = { i ->
                        val r = rows[i]
                        if (r.canDisconnect) DisconnectButton(r, r.key !in busy) { add -> busy += r.key; delClient(env, r, add) }
                    }
                    ResponsiveTable(
                        headers = listOf("Network", "MAC address", "Host", "Signal / Noise", "RX Rate / TX Rate"),
                        rows = rows.map { listOf(it.network, it.mac, it.host, it.sigValue, it.rx + "\n" + it.tx) },
                        weights = listOf(1.6f, 1.1f, 1.3f, 0.9f, 1.8f),
                        cell = { i, c, t ->
                            val r = rows[i]
                            Box(Modifier.alpha(if (r.key in busy) 0.5f else 1f)) {
                                when (c) {
                                    0 -> IfBadge(onClick = { env.tooltip.show(r.netTitle) }) {
                                        if (r.vlan != null) {
                                            ZoneDot(r.vlanZone); Spacer(Modifier.width(4.dp))
                                            Text(r.vlan, style = MaterialTheme.typography.labelSmall); Spacer(Modifier.width(6.dp))
                                        }
                                        Icon(Icons.Outlined.Wifi, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                                        Text("${r.network} (${r.ifname})", style = MaterialTheme.typography.bodySmall)
                                    }
                                    3 -> IfBadge(onClick = { env.tooltip.show(r.sigTitle) }) {
                                        SignalBars(r.sigLevel); Spacer(Modifier.width(4.dp))
                                        Text(r.sigValue, style = MaterialTheme.typography.bodySmall)
                                    }
                                    else -> SelectionContainer { Text(t, style = MaterialTheme.typography.bodySmall) }
                                }
                            }
                        },
                        rowActions = actions.takeIf { rows.any { it.canDisconnect } },
                    )
                }
            }
        }
    }
}

@Composable
private fun DisconnectButton(r: AssocRow, enabled: Boolean, onAction: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { onAction(false) }, enabled = enabled, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
            Text("Disconnect", style = MaterialTheme.typography.labelMedium)
        }
        if (r.addLabel != null) {
            Box {
                IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.ArrowDropDown, null) }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text("Disconnect") }, onClick = { open = false; onAction(false) })
                    DropdownMenuItem(text = { Text(r.addLabel) }, onClick = { open = false; onAction(true) })
                }
            }
        }
    }
}

@Composable
private fun WifiBadgeView(b: WifiBadge, env: OverviewEnv, busy: MutableList<String>) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 2.dp)) {
                IfBadge(onClick = { env.tooltip.show(b.title) }) { SignalBars(b.level) }
            }
            Spacer(Modifier.width(8.dp))
            Column {
                ItemList(b.items, small = true)
                if (b.em != null) Text(b.em, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                if (b.wps != null) Text(itemText("WPS status", b.wps), style = MaterialTheme.typography.bodySmall)
                if (b.wpsButton != 0 && b.ifname != null) {
                    val key = "wps-${b.ifname}"
                    FilledTonalButton(
                        onClick = {
                            busy += key
                            env.ctx.scope.launch {
                                try { env.ctx.call("hostapd.${b.ifname}", if (b.wpsButton == 2) "wps_cancel" else "wps_start") }
                                catch (e: Exception) { env.ctx.toast(e.message ?: "Error") }
                                busy -= key
                            }
                        },
                        enabled = key !in busy,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text(if (b.wpsButton == 2) "Stop WPS" else "Start WPS", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

/** The signal-*.svg icons: [level] -1 = off, 0..4 bars. */
@Composable
internal fun SignalBars(level: Int, modifier: Modifier = Modifier) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.size(width = 18.dp, height = 14.dp)) {
        val bw = size.width / 5.5f
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            drawRoundRect(
                color = if (level > i) on else off,
                topLeft = Offset(i * bw * 1.45f, size.height - h),
                size = Size(bw, h),
                cornerRadius = CornerRadius(1.5f, 1.5f),
                alpha = if (level < 0) 0.4f else 1f,
            )
        }
    }
}
