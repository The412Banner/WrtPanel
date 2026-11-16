package com.the412banner.wrtpanel.pages.status

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.JsonCodec
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.double
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.theme.LocalStatusColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private class VBridge(val vlanFiltering0: Boolean) {
    val ports = mutableListOf<String>()
    val vlans = mutableMapOf<String, MutableList<String>>()
    var vlanFiltering = vlanFiltering0
}

private fun isStr(v: Any?) = v is String && v.isNotEmpty()

private fun resolveVLANChain(ifname0: String, bridges: Map<String, VBridge>, mapping: MutableMap<String, List<String>?>) {
    var ifname = ifname0
    while (mapping[ifname] == null) {
        val m = Regex("^(.+)\\.([^.]+)$").find(ifname) ?: break
        val base = m.groupValues[1]; val vid = m.groupValues[2]
        val br = bridges[base]
        if (br != null) mapping[ifname] = if (br.vlanFiltering) br.vlans[vid] else br.ports
        else if (Regex("^[0-9]{1,4}$").matches(vid) && vid.toInt() <= 4095) mapping[ifname] = listOf(base)
        else break
        ifname = base
    }
}

private fun buildVLANMappings(uci: UciStore, mapping: MutableMap<String, List<String>?>) {
    val bridges = LinkedHashMap<String, VBridge>()
    for (s in uci.sections("network", "bridge-vlan")) {
        val vlan = s["vlan"] as? String
        if (!isStr(s["device"]) || vlan == null || !Regex("^[0-9]{1,4}$").matches(vlan) || vlan.toInt() > 4095) continue
        val dev = s["device"] as String
        val br = bridges.getOrPut(dev) { VBridge(true) }
        val list = mutableListOf<String>()
        br.vlans[vlan] = list
        for (p in UciStore.toArray(s["ports"])) {
            val port = p.replace(Regex(":[ut*]+$"), "")
            if (!br.ports.contains(port)) br.ports.add(port)
            list.add(port)
        }
        for (a in UciStore.toArray(s["alias"])) if (a != vlan) br.vlans[a] = list
    }
    for (s in uci.sections("network", "device")) {
        val type = s["type"]
        if (type == "bridge") {
            if (!isStr(s["name"])) continue
            val name = s["name"] as String
            val br = bridges.getOrPut(name) { VBridge(false) }
            if (s["vlan_filtering"] == "0") br.vlanFiltering = false else if (s["vlan_filtering"] == "1") br.vlanFiltering = true
            for (p in UciStore.toArray(s["ports"])) if (!br.ports.contains(p)) br.ports.add(p)
            mapping[name] = br.ports
        } else if (type == "8021q" || type == "8021ad") {
            if (!isStr(s["name"]) || !isStr(s["vid"]) || !isStr(s["ifname"])) continue
            val name = s["name"] as String; val vid = s["vid"] as String; val ifn = s["ifname"] as String
            val pb = bridges[ifn]
            mapping[name] = if (pb != null) (if (pb.vlanFiltering) pb.vlans[vid] else pb.ports) else listOf(ifn)
            resolveVLANChain(ifn, bridges, mapping)
        }
    }
    for (br in bridges.values) {
        for (bp in br.ports.toList()) resolveVLANChain(bp, bridges, mapping)
        for (v in br.vlans.values.toList()) for (p in v.toList()) resolveVLANChain(p, bridges, mapping)
    }
    for (s in uci.sections("network", "interface")) {
        val d = s["device"]
        if (isStr(d)) resolveVLANChain(d as String, bridges, mapping)
    }
}

private fun resolveVLANPorts(ifname: String, mapping: Map<String, List<String>?>, seen: MutableSet<String> = mutableSetOf()): List<String> {
    val ports = mutableListOf<String>()
    val mp = mapping[ifname]
    if (mp != null) {
        for (m in mp) if (seen.add(m)) ports.addAll(resolveVLANPorts(m, mapping, seen))
    } else ports.add(ifname)
    return ports.sortedWith(LuciFmt.naturalCompare)
}

private class PortMap { val networks = mutableListOf<Protocol>(); val zones = mutableListOf<FwZone>() }

private fun buildInterfaceMapping(uci: UciStore, zones: List<FwZone>, networks: List<Protocol>): Map<String, PortMap> {
    val vlanmap = mutableMapOf<String, List<String>?>()
    val portmap = mutableMapOf<String, PortMap>()
    val netmap = mutableMapOf<String, Protocol>()
    buildVLANMappings(uci, vlanmap)
    for (net in networks) {
        val l3dev = net.getDevice() ?: continue
        for (p in resolveVLANPorts(l3dev.getName(), vlanmap)) portmap.getOrPut(p) { PortMap() }.networks.add(net)
        netmap[net.getName()] = net
    }
    for (z in zones) {
        for (nn in z.networks) {
            val n = netmap[nn] ?: continue
            val l3dev = n.getDevice() ?: continue
            for (p in resolveVLANPorts(l3dev.getName(), vlanmap)) {
                val pm = portmap.getOrPut(p) { PortMap() }
                if (!pm.zones.contains(z)) pm.zones.add(z)
            }
        }
    }
    return portmap
}

/** One network badge of the "Part of networks" tooltip. */
internal data class NetBadge(val network: String, val zone: String?, val deviceI18n: String?, val deviceUp: Boolean)

internal data class PortBox(
    val name: String,
    val carrier: Boolean,
    val speed: String,
    val speedTitle: String?,
    val pseDelivering: Boolean,
    val tx: String,
    val rx: String,
    val psePower: String?,
    val pseKind: Int,
    val stats: List<Pair<String, String?>>,
    val zones: List<String?>,
    val networks: List<NetBadge>,
)

/** Status › Overview include 29_ports — port of luci-mod-status view/status/include/29_ports.js. */
internal class PortsInclude : StatusInclude("Port status") {
    override suspend fun fetch(ctx: PageContext, net: NetState): Any? {
        val builtin = resolveDefault<JsonArray?>(null) { ctx.rpc("luci", "getBuiltinEthernetPorts")["result"].arr } ?: JsonArray(emptyList())
        val boardTxt = resolveDefault("{}") { ctx.fs.read("/etc/board.json") }
        val zones = loadZones(ctx)
        val networks = net.getNetworks()
        ctx.uci.load("network")
        val board = JsonCodec.parseToJsonElement(boardTxt.ifEmpty { "{}" })

        val allPorts = LinkedHashSet<String>()
        builtin.forEach { p -> p["device"].str?.takeIf { it.isNotEmpty() }?.let { allPorts += it } }
        if (allPorts.isEmpty() && board["network"] != null) {
            for (role in listOf("lan", "wan")) {
                val r = board["network"][role] ?: continue
                val ports = r["ports"]
                if (ports is JsonArray) ports.forEach { p -> p.str?.let { allPorts += it } }
                else r["device"].str?.takeIf { it.isNotEmpty() }?.let { allPorts += it }
            }
        }
        val pseMap = mutableMapOf<String, JsonObject>()
        for (dev in allPorts) {
            val st = resolveDefault<JsonElement?>(null) { ctx.rpc("network.device", "status", jsonOf("name" to dev)) }
            st["pse"].obj?.let { pseMap[dev] = it }
        }

        if (ctx.sysFeature("swconfig")) return null

        val portMap = buildInterfaceMapping(ctx.uci, zones, networks)
        val known = mutableListOf<String>()
        if (builtin.isNotEmpty()) builtin.forEach { known += it["device"].str ?: "undefined" }
        else if (board is JsonObject && board["network"] is JsonObject) {
            for (k in listOf("lan", "wan")) {
                val r = board["network"][k] as? JsonObject ?: continue
                val ports = r["ports"]
                if (ports is JsonArray) ports.forEach { known += it.str ?: "undefined" }
                else if ((r["device"] as? JsonPrimitive)?.isString == true) known += r["device"].str!!
            }
        }
        known.sortWith(LuciFmt.naturalCompare)

        return known.map { device ->
            val nd = net.instantiateDevice(device)
            val speed = nd.getSpeed()
            val duplex = nd.getDuplex()
            val carrier = nd.getCarrier()
            val pmap = portMap[nd.getName()]
            val pzones: List<FwZone?> = if (pmap != null && pmap.zones.isNotEmpty())
                pmap.zones.sortedWith { a, b -> LuciFmt.naturalCompare.compare(a.name, b.name) } else listOf(null)
            val pse = pseMap[device]
            val status = pse?.get("c33-power-status").str?.ifEmpty { null } ?: pse?.get("podl-power-status").str
            val power = pse?.get("c33-actual-power").double
            val delivering = pse != null && status == "delivering" && (power ?: 0.0) > 0
            var psePower: String? = null
            var pseKind = 0
            if (pse != null) {
                when {
                    status == "delivering" && power != null && power != 0.0 -> psePower = "⚡︎ ${String.format(java.util.Locale.US, "%.1f", power / 1000)} W"
                    status == "searching" -> psePower = "⚡︎ searching"
                    status == "fault" || status == "otherfault" || status == "error" -> { psePower = "⚡︎ fault"; pseKind = 2 }
                    status == "disabled" -> { psePower = "⚡︎ off"; pseKind = 1 }
                }
            }
            val (speedText, speedTitle) = formatSpeed(carrier, speed, duplex)
            val stats = nd.stats
            val items = mutableListOf<Pair<String, String?>>(
                "Received bytes" to LuciFmt.m(stats["rx_bytes"].double, 1024) + "B",
                "Received packets" to LuciFmt.m(stats["rx_packets"].double, 1000) + "Pkts.",
                "Received multicast" to LuciFmt.m(stats["multicast"].double, 1000) + "Pkts.",
                "Receive errors" to LuciFmt.m(stats["rx_errors"].double, 1000) + "Pkts.",
                "Receive dropped" to LuciFmt.m(stats["rx_dropped"].double, 1000) + "Pkts.",
                "Transmitted bytes" to LuciFmt.m(stats["tx_bytes"].double, 1024) + "B",
                "Transmitted packets" to LuciFmt.m(stats["tx_packets"].double, 1000) + "Pkts.",
                "Transmit errors" to LuciFmt.m(stats["tx_errors"].double, 1000) + "Pkts.",
                "Transmit dropped" to LuciFmt.m(stats["tx_dropped"].double, 1000) + "Pkts.",
                "Collisions seen" to stats["collisions"]?.let { LuciFmt.num(it.double) },
            )
            if (pse != null) {
                items += "PoE status" to (status ?: "unknown")
                if (power != null && power != 0.0) items += "PoE power" to String.format(java.util.Locale.US, "%.1f W", power / 1000)
                pse["c33-power-class"]?.str?.takeIf { it.isNotEmpty() && it != "0" }?.let { items += "PoE class" to it }
                pse["c33-available-power-limit"].double?.takeIf { it != 0.0 }?.let { items += "PoE limit" to String.format(java.util.Locale.US, "%.1f W", it / 1000) }
            }
            val zmap = mutableMapOf<String, String?>()
            pmap?.zones?.forEach { z -> z.networks.forEach { zmap[it] = z.name } }
            PortBox(
                name = nd.getName(), carrier = carrier, speed = speedText, speedTitle = speedTitle, pseDelivering = delivering,
                tx = "▲ " + LuciFmt.m(nd.getTXBytes(), 1024, 1) + "B",
                rx = "▼ " + LuciFmt.m(nd.getRXBytes(), 1024, 1) + "B",
                psePower = psePower, pseKind = pseKind, stats = items,
                zones = pzones.map { it?.name },
                networks = pmap?.networks?.map { n ->
                    val d = n.getDevice()
                    NetBadge(n.getName(), zmap[n.getName()], d?.getI18n(), d?.isUp() == true)
                } ?: emptyList(),
            )
        }
    }

    private fun formatSpeed(carrier: Boolean, speed: Long?, duplex: String?): Pair<String, String?> {
        if (speed != null && speed > 0 && duplex != null) {
            val d = if (duplex == "half") " (H)" else ""
            val title = "Speed: $speed Mbit/s, Duplex: $duplex"
            val text = when {
                speed < 1000 -> "$speed M$d"
                speed == 1000L -> "1 GbE$d"
                speed >= 1_000_000 && speed < 1_000_000_000 -> LuciFmt.num(speed / 1e6) + " TbE"
                speed >= 1_000_000_000 -> LuciFmt.num(speed / 1e9) + " PbE"
                else -> LuciFmt.num(speed / 1000.0) + " GbE"
            }
            return text to title
        }
        return (if (carrier) "Connected" else "no link") to null
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    override fun Content(model: Any, env: OverviewEnv) {
        @Suppress("UNCHECKED_CAST")
        val ports = model as List<PortBox>
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ports.forEach { p -> PortCard(p, env.tooltip) }
        }
    }
}

@Composable
private fun PortCard(p: PortBox, tooltip: MutableTooltip) {
    val sc = LocalStatusColors.current
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.widthIn(min = 86.dp, max = 120.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                Text(p.name, style = MaterialTheme.typography.titleSmall)
            }
            Column(
                Modifier.fillMaxWidth().clickable(enabled = p.speedTitle != null) { p.speedTitle?.let { tooltip.show(it) } }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Icon(Icons.Outlined.SettingsEthernet, null, tint = if (p.carrier) sc.ok else MaterialTheme.colorScheme.outline, modifier = Modifier.size(30.dp))
                    if (p.pseDelivering) Text("⚡", style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopEnd))
                }
                Text(p.speed, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
            Row(
                Modifier.fillMaxWidth().height(10.dp).clickable { tooltip.show { NetworksTooltip(p.networks) } }.padding(vertical = 3.dp),
            ) {
                p.zones.forEach { z ->
                    Box(Modifier.weight(1f).height(4.dp).background(LuciFmt.zoneColor(z).copy(alpha = if (p.carrier) 1f else 0.25f)))
                }
            }
            Column(
                Modifier.fillMaxWidth().clickable { tooltip.show { ItemList(p.stats) } }.padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(p.tx, style = MaterialTheme.typography.labelSmall)
                Text(p.rx, style = MaterialTheme.typography.labelSmall)
                if (p.psePower != null) {
                    Text(
                        p.psePower, style = MaterialTheme.typography.labelSmall,
                        color = when (p.pseKind) { 2 -> sc.bad; 1 -> MaterialTheme.colorScheme.outline; else -> MaterialTheme.colorScheme.onSurface },
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworksTooltip(nets: List<NetBadge>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (nets.isEmpty()) "Port is not part of any network" else if (nets.size == 1) "Part of network:" else "Part of networks:",
            style = MaterialTheme.typography.bodyMedium,
        )
        nets.forEach { n ->
            IfBadge {
                ZoneDot(n.zone)
                Spacer(Modifier.width(6.dp))
                Column {
                    Text("${n.network}:", style = MaterialTheme.typography.bodyMedium)
                    Text(if (n.zone != null) "Part of zone \"${n.zone}\"" else "No zone assigned", style = MaterialTheme.typography.bodySmall)
                    if (n.deviceI18n != null) Text(n.deviceI18n, style = MaterialTheme.typography.bodySmall, color = if (n.deviceUp) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                    else Text("(no interfaces attached)", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
                }
            }
        }
    }
}
