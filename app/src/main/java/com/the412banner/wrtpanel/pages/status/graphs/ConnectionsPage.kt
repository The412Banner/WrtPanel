package com.the412banner.wrtpanel.pages.status.graphs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.UbusCall
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.double
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.long
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.ContentMaxWidth
import com.the412banner.wrtpanel.ui.components.EmptyState
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.theme.LocalStatusColors
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One luci.getConntrackList entry. */
private class Conn(
    val layer3: String, val layer4: String, val src: String, val dst: String,
    val sport: Long?, val dport: Long?, val bytes: Double, val packets: Double,
) {
    companion object {
        fun of(e: JsonElement): Conn = Conn(
            e["layer3"].str ?: "", e["layer4"].str ?: "", e["src"].str ?: "", e["dst"].str ?: "",
            e["sport"].long, e["dport"].long, e["bytes"].double ?: 0.0, e["packets"].double ?: 0.0,
        )
    }
}

/** A row of the #connections table: Network, Protocol, Source, Destination, Transfer. */
private class ConnRow(val network: String, val protocol: String, val source: String, val destination: String, val transfer: String)

/** The page-wide lookup caches of connections.js (dns_cache, service_cache, ethers_cache, recheck_lookup_queue). */
private class LookupState {
    val dns = HashMap<String, String>()
    val services = HashMap<String, HashMap<String, String>>()
    val ethers = HashMap<String, JsonObject>()
    var ethersLoaded = false
    val recheck = HashMap<String, Int>()
    var version by mutableIntStateOf(0)
}

/** Status › Realtime Graphs › Connections — port of luci-mod-status view/status/connections.js. */
@Composable
fun ConnectionsPage(ctx: PageContext) {
    val width = remember { GraphWidth() }
    val graph = remember { RealtimeGraph(listOf(GraphLine(), GraphLine(), GraphLine())) }
    val graphs = remember(graph) { listOf(graph) }
    val lookups = remember { LookupState() }
    var conns by remember { mutableStateOf<List<Conn>?>(null) }
    var filterText by remember { mutableStateOf("") }
    var enableLookups by remember { mutableStateOf(false) }
    var lookupBusy by remember { mutableStateOf(false) }

    GraphPoll(ctx, width, graphs, Unit) {
        val res = ctx.client.batch(listOf(UbusCall("luci", "getConntrackList"), RealtimeGraph.call("conntrack")))
        val list = (res[0].getOrNull()["result"].arr ?: emptyList()).map { Conn.of(it) }.sortedByDescending { it.bytes }
        conns = list
        if (enableLookups) resolveNames(ctx, lookups, list)
        lookupBusy = false
        graph.update(RealtimeGraph.rows(res[1].getOrNull()))
    }

    val rows = remember(conns, filterText, enableLookups, lookups.version) {
        conns?.let { buildRows(it, filterText, enableLookups, lookups) }
    }

    val c = LocalStatusColors.current.series
    val udp = c[0]
    val tcp = c[4]
    val other = c[5]
    val count = { v: Double -> LuciFmt.d(v) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()) {
            val wide = maxWidth >= 640.dp
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 108.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "header") {
                    PageHeader("Connections", "This page displays the active connections via this device.")
                    Spacer(Modifier.height(8.dp))
                }
                item(key = "graph") {
                    GraphCard(
                        graph, listOf(ChartSeries(1, tcp), ChartSeries(0, udp), ChartSeries(2, other)), width,
                        labels = { info -> listOf(info.label75, info.label50, info.label25).map(count) },
                        rows = { info ->
                            listOf(
                                StatRow("UDP:", udp, count(info?.current.at(0)), count(info?.average.at(0)), count(info?.peak.at(0))),
                                StatRow("TCP:", tcp, count(info?.current.at(1)), count(info?.average.at(1)), count(info?.peak.at(1))),
                                StatRow("Other:", other, count(info?.current.at(2)), count(info?.average.at(2)), count(info?.peak.at(2))),
                            )
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                item(key = "settings") {
                    SettingsRow(
                        filterText, { filterText = it }, enableLookups, lookupBusy, wide,
                        onToggle = {
                            if (!enableLookups) { lookupBusy = true; enableLookups = true } else enableLookups = false
                        },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                when {
                    rows == null -> item(key = "collecting") { LoadingBox(text = "Collecting data...") }
                    rows.isEmpty() -> item(key = "empty") { EmptyState("No information available") }
                    else -> {
                        if (wide) item(key = "titles") { TitleRow() }
                        items(rows) { r -> ConnectionRow(r, wide) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(
    filter: String, onFilter: (String) -> Unit, enabled: Boolean, busy: Boolean, wide: Boolean, onToggle: () -> Unit,
) {
    val field = @Composable { m: Modifier ->
        OutlinedTextField(
            filter, onFilter, singleLine = true, modifier = m,
            placeholder = { Text("192.168.1.15 / UDP 1.15 / :443", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
        )
    }
    val button = @Composable {
        FilledTonalButton(onClick = onToggle, enabled = !busy) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (enabled) "Disable DNS lookups" else "Enable DNS lookups")
        }
    }
    if (wide) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            field(Modifier.weight(1f))
            button()
        }
    } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            field(Modifier.fillMaxWidth())
            button()
        }
    }
}

private val colWeights = floatArrayOf(2f, 2f, 7f, 7f, 4f)

@Composable
private fun TitleRow() {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        listOf("Network", "Protocol", "Source", "Destination", "Transfer").forEachIndexed { i, t ->
            Text(
                t, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(colWeights[i]).padding(end = 8.dp), maxLines = 1,
            )
        }
    }
}

@Composable
private fun ConnectionRow(r: ConnRow, wide: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val body = MaterialTheme.typography.bodyMedium
        val small = MaterialTheme.typography.bodySmall
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        if (wide) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(r.network, r.protocol, r.source, r.destination, r.transfer).forEachIndexed { i, t ->
                    Box(Modifier.weight(colWeights[i]).padding(end = 8.dp)) {
                        SelectionContainer { Text(t, style = if (i < 2) small else body, color = if (i < 2) muted else MaterialTheme.colorScheme.onSurface) }
                    }
                }
            }
        } else {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${r.network} · ${r.protocol}", style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.weight(1f))
                    Text(r.transfer, style = small, color = muted)
                }
                SelectionContainer {
                    Column(Modifier.padding(top = 2.dp)) {
                        Text(r.source, style = body)
                        Text("→ " + r.destination, style = body)
                    }
                }
            }
        }
    }
}

/** joinAddressWithPortOrServiceName() */
private fun joinAddress(address: String, port: Long?, protocol: String, lookups: Boolean, st: LookupState): String {
    if (port == null || port == 0L) return address
    if (lookups) st.services[port.toString()]?.get(protocol)?.let { return "$address:$it" }
    return "$address:$port"
}

/** The row building part of updateConntrack(). */
private fun buildRows(conn: List<Conn>, filterText: String, lookups: Boolean, st: LookupState): List<ConnRow> {
    val rows = ArrayList<ConnRow>()
    val exprs = if (filterText.isNotEmpty()) filterText.split(" ") else emptyList()
    for (c in conn) {
        if ((c.src == "127.0.0.1" && c.dst == "127.0.0.1") || (c.src == "::1" && c.dst == "::1")) continue
        val src = if (lookups && st.dns.containsKey(c.src)) st.dns.getValue(c.src) else if (c.layer3 == "ipv6") "[${c.src}]" else c.src
        val dst = if (lookups && st.dns.containsKey(c.dst)) st.dns.getValue(c.dst) else if (c.layer3 == "ipv6") "[${c.dst}]" else c.dst
        val network = c.layer3.uppercase()
        val protocol = c.layer4.uppercase()
        val source = joinAddress(src, c.sport, protocol, lookups, st)
        val destination = joinAddress(dst, c.dport, protocol, lookups, st)
        if (exprs.isNotEmpty() && exprs.any { el ->
                el.uppercase() != network && el.uppercase() != protocol &&
                    !c.src.contains(el) && !source.contains(el) && !c.dst.contains(el) && !destination.contains(el)
            }
        ) continue
        val transfer = "%sB (%s %s)".format(LuciFmt.m(c.bytes, 1024), LuciFmt.d(c.packets), "Pkts.")
        rows.add(ConnRow(network, protocol, source, destination, transfer))
    }
    return rows
}

/** The lookup part of updateConntrack(): /etc/services, DHCP leases, rrdns, host hints, network devices. */
private suspend fun resolveNames(ctx: PageContext, st: LookupState, conn: List<Conn>) {
    if (st.services.isEmpty()) {
        try {
            for (line in ctx.fs.read("/etc/services").split("\n")) {
                val m = Regex("^([\\w-]+)\\s+(\\d+)/(\\w+)").find(line) ?: continue
                val port = m.groupValues[2]
                st.services.getOrPut(port) { HashMap() }.putIfAbsent(m.groupValues[3].uppercase(), m.groupValues[1].uppercase())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
        }
        st.version++
    }

    val queue = LinkedHashSet<String>()
    for (c in conn) {
        if ((c.src == "127.0.0.1" && c.dst == "127.0.0.1") || (c.src == "::1" && c.dst == "::1")) continue
        if (!st.dns.containsKey(c.src)) queue.add(c.src)
        if (!st.dns.containsKey(c.dst)) queue.add(c.dst)
    }
    if (queue.isEmpty()) return
    val reduced = LinkedHashSet(queue.take(100))

    suspend fun soft(obj: String, method: String, args: JsonObject = jsonOf()): JsonElement? =
        try { ctx.call(obj, method, args) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }

    fun update(addr: String?, name: String?) {
        if (addr.isNullOrEmpty() || name.isNullOrEmpty()) return
        if (!st.dns.containsKey(addr)) st.dns[addr] = name
        queue.remove(addr)
        reduced.remove(addr)
    }

    val leases = soft("luci-rpc", "getDHCPLeases").obj
    if (!leases.isNullOrEmpty()) {
        for (lease in (leases["dhcp_leases"].arr ?: emptyList()) + (leases["dhcp6_leases"].arr ?: emptyList())) {
            update(lease["ipaddr"].str?.ifEmpty { null } ?: lease["ip6addr"].str, lease["hostname"].str)
        }
    }

    if (reduced.isNotEmpty()) {
        val replies = soft("network.rrdns", "lookup", jsonOf("addrs" to reduced.toList(), "timeout" to 5000, "limit" to 1000)).obj
        replies?.forEach { (addr, name) -> update(addr, name.str) }
    }

    if (reduced.isNotEmpty()) {
        val hints: Map<String, JsonElement>? = if (st.ethersLoaded) st.ethers else soft("luci-rpc", "getHostHints").obj
        if (!hints.isNullOrEmpty()) {
            for ((ether, obj) in hints) {
                val o = obj.obj ?: continue
                val name = o["name"].str
                if (ether.isEmpty() || name.isNullOrEmpty()) continue
                if (!st.ethers.containsKey(ether) && o.isNotEmpty()) st.ethers[ether] = o
                for (addr in (o["ipaddrs"].arr ?: emptyList()) + (o["ip6addrs"].arr ?: emptyList())) update(addr.str, name)
            }
            if (st.ethers.isNotEmpty()) st.ethersLoaded = true
        }
    }

    if (reduced.isNotEmpty()) {
        val devices = soft("luci-rpc", "getNetworkDevices").obj
        devices?.values?.forEach { device ->
            val mac = device["mac"].str
            if (mac.isNullOrEmpty()) return@forEach
            val name = st.ethers[mac]?.get("name").str ?: return@forEach
            for (item in (device["ipaddrs"].arr ?: emptyList()) + (device["ip6addrs"].arr ?: emptyList())) update(item["address"].str, name)
        }
    }

    for (address in reduced) {
        val n = st.recheck[address] ?: 0
        if (n > 2) st.dns[address] = if (address.contains(':')) "[$address]" else address
        else st.recheck[address] = n + 1
    }
    st.version++
}
