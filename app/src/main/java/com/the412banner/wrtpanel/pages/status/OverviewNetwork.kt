package com.the412banner.wrtpanel.pages.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.double
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.InfoRow
import com.the412banner.wrtpanel.ui.components.UsageBar
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal data class UpstreamBox(
    val title: String,
    val active: Boolean,
    val items: List<Pair<String?, String?>>,
    val dhcp6Stats: List<Pair<String?, String?>>?,
    val devType: String?,
    val devUp: Boolean,
    val devI18n: String,
    val mac: String?,
)

internal data class NetworkModel(val boxes: List<UpstreamBox>, val ctCount: String, val ctMax: String)

/** Icon for a network.js device type (icons/<type>.svg). */
internal fun deviceIcon(type: String?): ImageVector = when (type) {
    "wifi" -> Icons.Outlined.Wifi
    "bridge", "switch", "vlan", "vrf" -> Icons.Outlined.DeviceHub
    "tunnel", "wireguard" -> Icons.Outlined.VpnKey
    else -> Icons.Outlined.SettingsEthernet
}

/** Status › Overview include 30_network — port of luci-mod-status view/status/include/30_network.js. */
internal class NetworkInclude : StatusInclude("Network") {
    override suspend fun fetch(ctx: PageContext, net: NetState): Any = coroutineScope {
        val count = async { resolveDefault("") { ctx.fs.read("/proc/sys/net/netfilter/nf_conntrack_count") }.trim() }
        val max = async { resolveDefault("") { ctx.fs.read("/proc/sys/net/netfilter/nf_conntrack_max") }.trim() }
        val stats = async { ctx.rpc("luci", "getOdhcp6cStats").obj ?: JsonObject(emptyMap()) }
        val wan = net.getWANNetworks()
        val wan6 = net.getWAN6Networks()
        val dh6 = stats.await()["result"].obj
        NetworkModel(
            boxes = wan.map { box(it, false, null) } + wan6.map { box(it, true, dh6) },
            ctCount = count.await(), ctMax = max.await(),
        )
    }

    private fun box(ifc: Protocol, ipv6: Boolean, dh6: JsonObject?): UpstreamBox {
        val dev = ifc.getL3Device()
        val active = dev != null && ifc.getProtocol() != "none"
        val addrs = if (ipv6) ifc.getIP6Addrs() else ifc.getIPAddrs()
        val dns = if (ipv6) ifc.getDNS6Addrs() else ifc.getDNSAddrs()
        val expires = ifc.getExpiry()
        val uptime = ifc.getUptime()
        val items = mutableListOf<Pair<String?, String?>>("Protocol" to ifc.getI18n())
        if (ipv6) {
            val p = ifc.getIP6Prefixes()
            if (p != null) p.forEach { items += "Prefix Delegated" to it } else items += "Prefix Delegated" to null
        }
        addrs.forEach { items += "Address" to it }
        items += "Gateway" to (if (ipv6) (ifc.getGateway6Addr() ?: "::") else (ifc.getGatewayAddr() ?: "0.0.0.0"))
        dns.forEach { items += "DNS" to it }
        items += "Expires" to (if (expires > -1) LuciFmt.t(expires) else null)
        items += "Connected" to (if (uptime > 0) LuciFmt.t(uptime) else null)
        var stats: List<Pair<String?, String?>>? = null
        if (ipv6 && ifc.getProtocol() == "dhcpv6" && dh6 != null && dev != null) {
            val s = dh6[dev.name].obj
            if (s != null) stats = s.entries.map { (k, v) -> k.replace("dhcp_", "DHCPv6 ") to "${LuciFmt.num(v.double ?: 0.0)} pkts" }
        }
        return UpstreamBox(
            title = if (ipv6) "IPv6 Upstream" else "IPv4 Upstream", active = active, items = items, dhcp6Stats = stats,
            devType = dev?.getType(), devUp = dev?.isUp() == true, devI18n = dev?.getI18n() ?: "-", mac = dev?.getMAC(),
        )
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content(model: Any, env: OverviewEnv) {
        val m = model as NetworkModel
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (m.boxes.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 2) {
                    m.boxes.forEach { b ->
                        IfaceBox(b.title, b.active, Modifier.weight(1f).widthIn(min = 260.dp)) {
                            ItemList(b.items)
                            if (b.dhcp6Stats != null) {
                                Text(
                                    itemText("DHCPv6 Statistics", "📊"), style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.clickable { env.tooltip.show { ItemList(b.dhcp6Stats) } },
                                )
                            }
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    deviceIcon(b.devType), null, Modifier.size(26.dp),
                                    tint = if (b.devType != null && b.devUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                )
                                Spacer(Modifier.width(8.dp))
                                ItemList(listOf("Device" to b.devI18n, "MAC address" to b.mac), small = true)
                            }
                        }
                    }
                }
            }
            val max = m.ctMax.toDoubleOrNull()
            if (m.ctMax.isNotEmpty()) {
                val (f, t) = progress(m.ctCount.toDoubleOrNull() ?: Double.NaN, max ?: Double.NaN, false, m.ctCount, m.ctMax)
                UsageBar("Active Connections", f, t)
            } else InfoRow("Active Connections", "?")
        }
    }
}

internal data class DslModel(val up: Boolean, val left: List<Pair<String?, String?>>, val right: List<Pair<String?, String?>>)

/** Status › Overview include 50_dsl — port of luci-mod-status view/status/include/50_dsl.js. */
internal class DslInclude : StatusInclude("DSL") {
    override suspend fun fetch(ctx: PageContext, net: NetState): Any? {
        if (net.hasDSLModem?.get("type").str.isNullOrEmpty()) throw IllegalStateException("no DSL modem")
        val dsl = resolveDefault<JsonElement?>(null) { ctx.rpc("dsl", "metrics") }.obj ?: JsonObject(emptyMap())
        if (dsl["state"].str.isNullOrEmpty()) return null
        fun fv(fmt: (Double) -> String, a: JsonElement?, b: JsonElement?) =
            (a.double?.let(fmt) ?: "-") + " / " + (b.double?.let(fmt) ?: "-")
        val rate = { v: Double -> LuciFmt.m(v, 1000, 3) + "b/s" }
        val snr = { v: Double -> String.format(java.util.Locale.US, "%.1f dB", v) }
        return DslModel(
            up = dsl["up"].bool == true,
            left = listOf(
                "Line State" to (dsl["state"].str?.ifEmpty { null } ?: "-"),
                "Line Mode" to (dsl["mode"].str?.ifEmpty { null } ?: "-"),
                "Line Uptime" to LuciFmt.t(dsl["uptime"].double),
            ),
            right = listOf(
                "Data Rate" to fv(rate, dsl["downstream"]["data_rate"], dsl["upstream"]["data_rate"]),
                "Noise Margin" to fv(snr, dsl["downstream"]["snr"], dsl["upstream"]["snr"]),
            ),
        )
    }

    @Composable
    override fun Content(model: Any, env: OverviewEnv) {
        val m = model as DslModel
        IfaceBox("DSL Status", m.up, Modifier.fillMaxWidth()) {
            ItemList(m.left)
            Spacer(Modifier.size(6.dp))
            ItemList(m.right)
        }
    }
}
