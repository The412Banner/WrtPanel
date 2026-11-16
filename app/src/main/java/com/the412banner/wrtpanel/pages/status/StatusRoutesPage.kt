package com.the412banner.wrtpanel.pages.status

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.ExecResult
import com.the412banner.wrtpanel.core.JsonCodec
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.int
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.form.Datatypes
import com.the412banner.wrtpanel.pages.common.NetTools
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.ErrorBox
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.ResponsiveTable
import com.the412banner.wrtpanel.ui.components.SectionCard
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** A table cell: [text], shown as an interface badge when [badge], with an optional tap [tooltip]. */
internal data class RCell(val text: String, val badge: Boolean = false, val tooltip: String? = null)

private data class RTable(val title: String, val headers: List<String>, val rows: List<List<RCell>>, val primary: Int)

private data class RoutesData(val v4: List<RTable>, val v6: List<RTable>)

private fun applyMask(addr: String?, mask0: Int, v6: Boolean): String? {
    if (addr == null) return null
    val words = (if (v6) Datatypes.parseIPv6(addr) else Datatypes.parseIPv4(addr))?.copyOf() ?: return null
    val bword = if (v6) 0xffff else 0xff
    val bwlen = if (v6) 16 else 8
    var mask = mask0
    if (mask < 0 || mask > (if (v6) 128 else 32)) return null
    for (i in words.indices) {
        val b = minOf(mask, bwlen)
        words[i] = words[i] and ((bword shl (bwlen - b)) and bword)
        mask -= b
    }
    return if (v6) words.joinToString(":") { Integer.toHexString(it) } else words.joinToString(".")
}

private fun networkByDevice(networks: List<JsonElement>, dev: String?, addr: String?, mask: Int, v6: Boolean): String? {
    var match: String? = null
    var prefix = -1
    for (net in networks) {
        if (net !is JsonObject || (net["l3_device"].str != dev && net["device"].str != dev)) continue
        for (key in listOf("ipv4-address", "ipv6-address", "ipv6-prefix", "ipv6-prefix-assignment", "route")) {
            val list = net[key].arr ?: continue
            for (e in list) {
                val cmp = e["address"].str?.ifEmpty { null } ?: e["target"].str?.ifEmpty { null } ?: continue
                val cm = e["mask"].int ?: continue
                if (applyMask(cmp, cm, v6) != applyMask(addr, cm, v6) || mask < cm) continue
                if (cm > prefix) { match = net["interface"].str; prefix = cm }
            }
        }
    }
    return match
}

/** `flags[flags[j]] = flags[j + 1]` of the views. */
private fun pairFlags(s: String): Map<String, String> {
    val f = s.trim().split(Regex("\\s+"))
    val m = HashMap<String, String>()
    var j = 0
    while (j < f.size) { f.getOrNull(j + 1)?.let { m[f[j]] = it }; j += 2 }
    return m
}

private fun vendorOf(macs: JsonObject?, lladdr: String?): String? {
    var v: String? = null
    macs?.forEach { (mac, d) -> if (lladdr == mac) v = d["vendor"].str }
    return v
}

private val routeHeaders = listOf("Device", "Target", "Gateway", "Source", "Metric", "Table", "Protocol")

private suspend fun loadCommon(ctx: PageContext, json: Boolean): Triple<List<JsonElement>, List<String>, JsonObject?> = coroutineScope {
    val dump = async { ctx.rpc("network.interface", "dump")["interface"].arr ?: JsonArray(emptyList()) }
    val cmds = listOf(
        listOf("-4", "neigh", "show"), listOf("-4", "route", "show", "table", "all"), listOf("-4", "rule", "show"),
        listOf("-6", "neigh", "show"), listOf("-6", "route", "show", "table", "all"), listOf("-6", "rule", "show"),
    ).map { a ->
        val args = if (json) listOf(a[0], "-j") + a.drop(1) else a
        async { resolveDefault(ExecResult(0, "", "")) { ctx.fs.exec("/sbin/ip", args) }.stdout }
    }
    val ufp = async { if (ctx.sysFeature("ufpd")) ctx.rpc("fingerprint", "fingerprint").obj else null }
    Triple(dump.await(), cmds.map { it.await() }, ufp.await())
}

private fun loadRoutesText(networks: List<JsonElement>, out: List<String>, macs: JsonObject?): RoutesData {
    fun neighs(s: String, v6: Boolean): List<List<RCell>> {
        if (s.isEmpty()) return emptyList()
        val res = mutableListOf<List<RCell>>()
        for (line in s.trim().split('\n')) {
            val m = Regex("^([0-9a-f:.]+) (.+) (\\S+) *$").find(line) ?: continue
            val addr = m.groupValues[1]
            if (Regex("^fe[89a-f][0-9a-f]:").containsMatchIn(addr)) continue
            val flags = pairFlags(m.groupValues[2])
            val ll = flags["lladdr"] ?: continue
            val vendor = vendorOf(macs, ll)
            val net = networkByDevice(networks, flags["dev"], addr, if (v6) 128 else 32, v6)
            res += listOf(
                RCell(addr),
                RCell(if (vendor != null) "${ll.uppercase()} ($vendor)" else ll.uppercase()),
                RCell(net ?: "(${flags["dev"]})", badge = true),
            )
        }
        return res
    }

    fun routes(s: String, v6: Boolean): List<List<RCell>> {
        if (s.isEmpty()) return emptyList()
        val res = mutableListOf<List<RCell>>()
        for (line in s.trim().split('\n')) {
            val m = Regex("^(?:([a-z_]+|\\d+) )?(default|[0-9a-f:./]+) (.+)$").find(line) ?: continue
            val type = m.groupValues[1].ifEmpty { "unicast" }
            val d = m.groupValues[2]
            val dest = if (d == "default") (if (v6) "::/0" else "0.0.0.0/0") else d
            if (dest.isEmpty() || type != "unicast" || dest == "fe80::/64" || dest == "ff00::/8") continue
            val flags = pairFlags(m.groupValues[3])
            val addr = dest.substringBefore('/')
            val bits = if (dest.contains('/')) dest.substringAfter('/').substringBefore('/').toIntOrNull() ?: 0 else if (v6) 128 else 32
            val net = networkByDevice(networks, flags["dev"], addr, bits, v6)
            res += listOf(
                RCell(net ?: "(${flags["dev"]})", badge = true),
                RCell(dest),
                RCell(flags["via"] ?: "-"),
                RCell(flags["src"] ?: flags["from"] ?: "-"),
                RCell(flags["metric"]?.ifEmpty { null } ?: "0"),
                RCell(flags["table"] ?: "main"),
                RCell(flags["proto"] ?: ""),
            )
        }
        return res
    }

    fun rules(s: String): List<List<RCell>> =
        if (s.trim().isEmpty()) emptyList() else s.trim().split('\n').map { l ->
            val m = Regex("^(\\d+):\\s+(.+)$").find(l)
            listOf(RCell(m?.groupValues?.get(1) ?: ""), RCell(m?.groupValues?.get(2) ?: ""))
        }

    val rh = listOf("Priority", "Rule")
    val nh = listOf("IP address", "MAC address", "Interface")
    return RoutesData(
        v4 = listOf(
            RTable("IPv4 Neighbours", nh, neighs(out[0], false), 0),
            RTable("Active IPv4 Routes", routeHeaders, routes(out[1], false), 1),
            RTable("Active IPv4 Rules", rh, rules(out[2]), 1),
        ),
        v6 = listOf(
            RTable("IPv6 Neighbours", nh, neighs(out[3], true), 0),
            RTable("Active IPv6 Routes", routeHeaders, routes(out[4], true), 1),
            RTable("Active IPv6 Rules", rh, rules(out[5]), 1),
        ),
    )
}

private fun loadRoutesJson(networks: List<JsonElement>, out: List<String>, macs: JsonObject?): RoutesData {
    fun parse(s: String): List<JsonElement> = runCatching { JsonCodec.parseToJsonElement(s).arr?.toList() }.getOrNull() ?: emptyList()
    fun isNullFlag(o: JsonElement, k: String) = o is JsonObject && o.containsKey(k) && o[k] is JsonNull
    fun tip(o: JsonElement) = o.toString()

    fun neighs(s: String, v6: Boolean): List<List<RCell>> {
        val res = mutableListOf<List<RCell>>()
        for (n in parse(s)) {
            val dst = n["dst"].str ?: ""
            if (Regex("^fe[89a-f][0-9a-f]:").containsMatchIn(dst)) continue
            if (n["state"].arr?.any { it.str == "FAILED" } == true) continue
            val ll = n["lladdr"].str
            val vendor = vendorOf(macs, ll)
            val net = networkByDevice(networks, n["dev"].str, dst, if (v6) 128 else 32, v6)
            val entry = buildString {
                append('#')
                n["nud"].truthyStr()?.let { append("; NUD: $it") }
                if (isNullFlag(n, "proxy")) append("; Proxy: ✅")
                if (isNullFlag(n, "nomaster")) append(";  No master : ✅")
                n["vrf"].truthyStr()?.let { append("; VRF: $it") }
            }
            res += listOf(
                RCell(entry, tooltip = tip(n)),
                RCell(dst),
                RCell((ll?.uppercase() ?: "undefined") + (if (vendor != null) " ($vendor)" else "")),
                RCell(net ?: "(${n["dev"].str})", badge = true),
            )
        }
        return res
    }

    fun routes(s: String, v6: Boolean): List<List<RCell>> {
        val res = mutableListOf<List<RCell>>()
        for (rt in parse(s)) {
            val d = rt["dst"].str ?: ""
            val dest = if (d == "default") (if (v6) "::/0" else "0.0.0.0/0") else d
            if (dest == "fe80::/64" || dest == "ff00::/8") continue
            val addr = dest.substringBefore('/')
            val bits = if (dest.contains('/')) dest.substringAfter('/').substringBefore('/').toIntOrNull() ?: 0 else if (v6) 128 else 32
            val net = networkByDevice(networks, rt["dev"].str, addr, bits, v6)
            res += listOf(
                RCell(net ?: "(${rt["dev"].str})", badge = true),
                RCell(dest),
                RCell(rt["gateway"].truthyStr() ?: "-"),
                RCell(rt["prefsrc"].truthyStr() ?: rt["from"].truthyStr() ?: "-"),
                RCell(rt["metric"].truthyStr() ?: "-"),
                RCell(rt["table"].truthyStr() ?: "main"),
                RCell(rt["protocol"].str ?: ""),
            )
        }
        return res
    }

    fun rules(s: String): List<List<RCell>> = parse(s).map { rl ->
        val entry = buildString {
            append('#')
            if (isNullFlag(rl, "not")) append("; Not: ✅")
            if (isNullFlag(rl, "nop")) append("; No-op: ✅")
            if (isNullFlag(rl, "l3mdev")) append("; L3Mdev: ✅")
            listOf("fwmark" to "Fwmark", "from" to "From", "to" to "To", "tos" to "ToS", "dsfield" to "DSCP", "uidrange" to "UID-range", "goto" to "goto", "nat" to "NAT")
                .forEach { (k, label) -> rl[k].truthyStr()?.let { append("; $label:$it") } }
        }
        fun net(a: String, l: String) = rl[a].truthyStr()?.let { v -> rl[l].truthyStr()?.let { "$v/$it" } ?: v } ?: "any"
        val proto = rl["ipproto"].str?.split('-')?.getOrNull(1)
        listOf(
            RCell(entry, tooltip = tip(rl)),
            RCell(rl["priority"].str ?: ""),
            rl["iif"].truthyStr()?.let { RCell(it, badge = true) } ?: RCell("-"),
            RCell(net("src", "srclen")),
            RCell(rl["sport"].truthyStr() ?: "-"),
            RCell(rl["action"].truthyStr() ?: "-"),
            RCell(NetTools.protocols.firstOrNull { proto != null && it.i.toString() == proto }?.d ?: "-"),
            rl["oif"].truthyStr()?.let { RCell(it, badge = true) } ?: RCell("-"),
            RCell(net("dst", "dstlen")),
            RCell(rl["dport"].truthyStr() ?: "-"),
            RCell(rl["table"].truthyStr() ?: "-"),
        )
    }

    val rh = listOf("Rule", "Priority", "Ingress", "Source", "Src Port", "Action", "IP Protocol", "Egress", "Destination", "Dest Port", "Table")
    val nh = listOf("Entry", "IP address", "MAC address", "Interface")
    return RoutesData(
        v4 = listOf(
            RTable("IPv4 Neighbours", nh, neighs(out[0], false), 1),
            RTable("Active IPv4 Routes", routeHeaders, routes(out[1], false), 1),
            RTable("Active IPv4 Rules", rh, rules(out[2]), 1),
        ),
        v6 = listOf(
            RTable("IPv6 Neighbours", nh, neighs(out[3], true), 1),
            RTable("Active IPv6 Routes", routeHeaders, routes(out[4], true), 1),
            RTable("Active IPv6 Rules", rh, rules(out[5]), 1),
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoutingView(ctx: PageContext, json: Boolean) {
    var data by remember { mutableStateOf<RoutesData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tooltip = rememberTooltip()
    LaunchedEffect(tick) {
        error = null
        try {
            val (nets, out, macs) = loadCommon(ctx, json)
            data = if (json) loadRoutesJson(nets, out, macs) else loadRoutesText(nets, out, macs)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message ?: e.toString()
        }
    }
    PageColumn {
        PageHeader("Routing", "The following rules are currently active on this system.")
        val d = data
        when {
            d == null && error != null -> ErrorBox(error!!) { tick++ }
            d == null -> LoadingBox()
            else -> {
                PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("IPv4 Routing") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("IPv6 Routing") })
                }
                (if (tab == 0) d.v4 else d.v6).forEach { t -> RoutesTableCard(t.title, t.headers, t.rows, t.primary, tooltip) }
            }
        }
    }
    TooltipHost(tooltip)
}

@Composable
internal fun RoutesTableCard(title: String, headers: List<String>, rows: List<List<RCell>>, primary: Int, tooltip: MutableTooltip) {
    SectionCard(title) {
        ResponsiveTable(
            headers = headers,
            rows = rows.map { r -> r.map { it.text } },
            primaryColumn = primary,
            empty = "No entries available",
            minWideWidth = if (headers.size > 7) 900.dp else 600.dp,
            cell = { r, c, t ->
                val cell = rows[r][c]
                val tip = cell.tooltip
                when {
                    cell.badge -> IfBadge { Text(t, style = MaterialTheme.typography.bodySmall) }
                    tip != null -> IfBadge(onClick = { tooltip.show(tip, monospace = true) }) {
                        Text(t, style = MaterialTheme.typography.bodySmall)
                    }
                    else -> SelectionContainer { Text(t.ifEmpty { "-" }, style = MaterialTheme.typography.bodyMedium) }
                }
            },
        )
    }
}

/** Status › Routing (ip-full / ip-tiny) — port of luci-mod-status view/status/routesj.js. */
@Composable
fun StatusRoutesJsonPage(ctx: PageContext) = RoutingView(ctx, json = true)

/** Status › Routing — port of luci-mod-status view/status/routes.js. */
@Composable
fun StatusRoutesPage(ctx: PageContext) = RoutingView(ctx, json = false)
