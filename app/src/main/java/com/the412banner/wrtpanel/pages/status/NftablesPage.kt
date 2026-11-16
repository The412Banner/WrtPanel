package com.the412banner.wrtpanel.pages.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.JsonCodec
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.double
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.ErrorBox
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.SectionCard
import com.the412banner.wrtpanel.ui.components.htmlToAnnotated
import com.the412banner.wrtpanel.ui.theme.LocalStatusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val exprTr = mapOf(
    "meta.iifname" to "Ingress device name", "meta.oifname" to "Egress device name",
    "meta.iif" to "Ingress device id", "meta.oif" to "Egress device id",
    "meta.l4proto" to "IP protocol", "meta.l4proto.tcp" to "TCP", "meta.l4proto.udp" to "UDP", "meta.l4proto.icmp" to "ICMP",
    "meta.l4proto.icmpv6" to "ICMPv6", "meta.l4proto.ipv6-icmp" to "ICMPv6",
    "meta.nfproto" to "Address family", "meta.nfproto.ipv4" to "IPv4", "meta.nfproto.ipv6" to "IPv6",
    "meta.mark" to "Packet mark",
    "meta.time" to "Packet receive time", "meta.hour" to "Current time", "meta.day" to "Current weekday",
    "ct.state" to "Conntrack state",
    "ct.status" to "Conntrack status", "ct.status.dnat" to "DNAT",
    "ip.dscp" to "DSCP", "ip6.dscp" to "DSCP",
    "ether.saddr" to "Source MAC address", "ether.daddr" to "Destination MAC address",
    "ip.protocol" to "IP protocol", "ip.protocol.tcp" to "TCP", "ip.protocol.udp" to "UDP", "ip.protocol.icmp" to "ICMP",
    "ip.protocol.icmpv6" to "ICMPv6", "ip.protocol.ipv6-icmp" to "ICMPv6",
    "ip.saddr" to "Source IP", "ip.daddr" to "Destination IP", "ip.sport" to "Source port", "ip.dport" to "Destination port",
    "ip6.saddr" to "Source IPv6", "ip6.daddr" to "Destination IPv6",
    "icmp.code" to "ICMP code", "icmp.type" to "ICMP type", "icmpv6.code" to "ICMPv6 code", "icmpv6.type" to "ICMPv6 type",
    "tcp.sport" to "TCP source port", "tcp.dport" to "TCP destination port",
    "udp.sport" to "UDP source port", "udp.dport" to "UDP destination port",
    "tcp.flags" to "TCP flags",
    "th.sport" to "Transport header source port", "th.dport" to "Transport header destination port",
    "natflag.random" to "Randomize source port mapping", "natflag.fully-random" to "Full port randomization",
    "natflag.persistent" to "Use same source and destination for each connection",
    "rt.mtu" to "Effective route MTU",
    "tcpoption.maxseg.size" to "TCP MSS",
    "unit.packets" to "packets", "unit.mbytes" to "MiB", "unit.kbytes" to "KiB", "unit.week" to "week", "unit.day" to "day",
    "unit.hour" to "hour", "unit.minute" to "minute",
    "payload.ll" to "Link layer header bits %d-%d", "payload.nh" to "Network header bits %d-%d", "payload.th" to "Transport header bits %d-%d",
)

private val opTr = mapOf(
    "==" to "<var>%s</var> is <strong>%s</strong>",
    "!=" to "<var>%s</var> not <strong>%s</strong>",
    ">=" to "<var>%s</var> greater than or equal to <strong>%s</strong>",
    "<=" to "<var>%s</var> lower than or equal to <strong>%s</strong>",
    ">" to "<var>%s</var> greater than <strong>%s</strong>",
    "<" to "<var>%s</var> lower than <strong>%s</strong>",
    "in" to "<var>%s</var> is one of <strong>%s</strong>",
    "in_set" to "<var>%s</var> in set <strong>%s</strong>",
    "not_in_set" to "<var>%s</var> not in set <strong>%s</strong>",
)

private val actTr = mapOf(
    "accept" to "Accept packet", "notrack" to "Do not track", "drop" to "Drop packet",
    "jump" to "Continue in <strong><a href=\"#%q.%q\">%h</a></strong>",
    "goto" to "Goto chain <strong><a href=\"#%q.%q\">%h</a></strong>",
    "continue" to "Continue to next rule", "log" to "Log event", "log.prefix" to "Log event \"<strong>%h</strong>…\"",
    "reject.tcp reset" to "Reject packet with <strong>TCP reset</strong>",
    "reject.icmp" to "Reject IPv4 packet with <strong>ICMP type %h</strong>",
    "reject.icmpv6" to "Reject packet with <strong>ICMPv6 type %h</strong>",
    "reject.icmpx" to "Reject packet with <strong>ICMP type %h</strong>",
    "snat.ip.addr" to "Rewrite source to <strong>%h</strong>", "snat.ip.addr.port" to "Rewrite source to <strong>%h</strong>, port <strong>%h</strong>",
    "snat.ip6.addr" to "Rewrite source to <strong>%h</strong>", "snat.ip6.addr.port" to "Rewrite source to <strong>%h</strong>, port <strong>%h</strong>",
    "dnat.ip.addr" to "Rewrite destination to <strong>%h</strong>", "dnat.ip.addr.port" to "Rewrite destination to <strong>%h</strong>, port <strong>%h</strong>",
    "dnat.ip6.addr" to "Rewrite destination to <strong>%h</strong>", "dnat.ip6.addr.port" to "Rewrite destination to <strong>%h</strong>, port <strong>%h</strong>",
    "redirect" to "Redirect to local system", "redirect.port" to "Redirect to local port <strong>%h</strong>",
    "masquerade" to "Rewrite to egress device address",
    "mangle" to "Set header field <var>%s</var> to <strong>%s</strong>",
    "limit" to "At most <strong>%h</strong> per <strong>%h</strong>, burst of <strong>%h</strong>",
    "limit.burst" to "At most <strong>%h</strong> per <strong>%h</strong>, burst of <strong>%h</strong>",
    "limit.inv" to "At least <strong>%h</strong> per <strong>%h</strong>, burst of <strong>%h</strong>",
    "limit.inv.burst" to "At least <strong>%h</strong> per <strong>%h</strong>, burst of <strong>%h</strong>",
    "return" to "Continue in calling chain",
    "ct helper" to "Utilise <strong>%h</strong> conntrack helper",
    "flow" to "Utilize flow table <strong>%h</strong>",
)

private val actionKinds = setOf("accept", "notrack", "reject", "drop", "jump", "goto", "continue", "snat", "dnat", "redirect", "mangle", "masquerade", "return", "flow", "ct helper", "log")

/** One "ifacebadge": [html] content, tap [tooltip], [jump] = "table.chain" anchor, NAT [flags] with their tooltip. */
internal data class NBadge(val html: String, val tooltip: String? = null, val jump: String? = null, val flags: String? = null, val flagsTip: String? = null, val mono: Boolean = false)

private data class NRule(val left: List<NBadge>, val right: List<NBadge>)
private data class NChain(val id: String, val title: String, val hook: List<String>?, val rules: List<NRule>)
private data class NTable(val title: String, val chains: List<NChain>)
private data class NftModel(val legacy: Boolean, val tables: List<NTable>?)

private fun lastEntry(o: JsonElement?): Pair<String, JsonElement>? = (o as? JsonObject)?.entries?.lastOrNull()?.let { it.key to it.value }
private fun firstEntry(o: JsonElement?): Pair<String, JsonElement>? = (o as? JsonObject)?.entries?.firstOrNull()?.let { it.key to it.value }
private fun prim(e: JsonElement?): String = when (e) { null, is JsonNull -> "null"; is JsonPrimitive -> e.content; else -> e.toString() }

private fun isAction(e: JsonElement?): Boolean = (e as? JsonObject)?.keys?.any { it in actionKinds } == true

private fun exprToKey(e: JsonElement?): String? {
    if (e !is JsonObject) return null
    val (kind, spec) = firstEntry(e) ?: return null
    return when (kind) {
        "meta", "ct", "rt" -> "${LuciFmt.esc(kind)}.${LuciFmt.esc(prim(spec["key"]))}"
        "tcp option" -> "tcpoption.${LuciFmt.esc(prim(spec["name"]))}.${LuciFmt.esc(prim(spec["field"]))}"
        "reject" -> "reject.${LuciFmt.esc(prim(spec["type"]))}"
        else -> null
    }
}

private fun exprToString(e: JsonElement?, hint: String? = null): String {
    if (e !is JsonObject && e !is JsonArray) {
        val s = if (hint != null) exprTr["$hint.${LuciFmt.esc(prim(e))}"] else null
        return s ?: LuciFmt.esc(prim(e))
    }
    val kind: String
    val spec: JsonElement
    if (e is JsonArray) { kind = "list"; spec = e } else { val l = lastEntry(e) ?: return ""; kind = l.first; spec = l.second }
    return when (kind) {
        "prefix" -> "${LuciFmt.esc(prim(spec["addr"]))}/${spec["len"].double?.toLong() ?: 0}"
        "set", "list" -> {
            val items = (spec as? JsonArray)?.map { exprToString(it) } ?: emptyList()
            if (kind == "set") "{ ${items.joinToString(", ")} }" else items.joinToString(", ")
        }
        "concat" -> (spec as? JsonArray)?.joinToString("+") { exprToString(it) } ?: ""
        "range" -> "${exprToString(spec[0], hint)}-${exprToString(spec[1], hint)}"
        "payload" -> {
            val proto = spec["protocol"].str; val field = spec["field"].str
            val base = spec["base"].str; val off = spec["offset"].double; val len = spec["len"].double
            if (!proto.isNullOrEmpty() && !field.isNullOrEmpty()) {
                val k = "${LuciFmt.esc(proto)}.${LuciFmt.esc(field)}"
                exprTr[k] ?: "<em>$k</em>"
            } else if (!base.isNullOrEmpty() && off != null && len != null) {
                val k = "payload.${LuciFmt.esc(base)}"
                jsFormat(exprTr[k] ?: jsFormat("<em>@%s,%%d,%%d</em>", base), off + 1, off + len + 1)
            } else "payload: payload"
        }
        "&", "|", "^" -> {
            val b = spec[1]
            "${exprToString(spec[0], hint)} ${LuciFmt.esc(kind)} " +
                (if (b is JsonArray) "(${LuciFmt.esc(b.joinToString("|") { prim(it) })})" else exprToString(b, hint))
        }
        else -> {
            val k = exprToKey(e)
            if (k != null) exprTr[k] ?: "<em>$k</em>" else "$kind: $spec"
        }
    }
}

private fun natFlags(spec: JsonElement?): Pair<String?, String?> {
    val f = (spec["flags"] as? JsonArray)?.map { exprTr["natflag.${LuciFmt.esc(prim(it))}"] ?: prim(it) } ?: emptyList()
    if (f.isEmpty()) return null to null
    return " (${if (f.size == 1) "1 flag" else "${f.size} flags"})" to f.joinToString("\n")
}

private fun rateUnit(value: JsonElement?, unit: String?): String {
    val u = unit ?: "packets"
    return jsFormat("%d %s", value?.double, exprTr["unit.${LuciFmt.esc(u)}"] ?: u)
}

private fun renderMatch(spec: JsonElement?): NBadge {
    var op = spec["op"].str ?: ""
    val right = spec["right"]
    when (op) {
        "==", "!=" -> if ((right is JsonObject && right["set"] != null) || (right is JsonPrimitive && right.isString && right.content.startsWith("@")))
            op = if (op == "==") "in_set" else "not_in_set"
        "in" -> if (right !is JsonObject && right !is JsonArray) op = "=="
    }
    val tpl = opTr[op] ?: jsFormat("<var>%%s</var> %h <strong>%%s</strong>", op)
    return NBadge(jsFormat(tpl, exprToString(spec["left"]), exprToString(right, exprToKey(spec["left"]))))
}

private fun renderExpr(e: JsonElement?, table: String?): NBadge {
    val (kind, spec) = lastEntry(e) ?: return NBadge("")
    return when (kind) {
        "match" -> renderMatch(spec)
        "ct helper" -> NBadge(jsFormat(actTr.getValue("ct helper"), prim(spec)))
        "reject" -> {
            val k = "reject.${if (spec is JsonObject) prim(spec["type"]) else "undefined"}"
            NBadge(jsFormat(actTr[k] ?: k, exprToString(spec["expr"])))
        }
        "accept", "notrack", "drop" -> NBadge(actTr[kind] ?: "<em>${LuciFmt.esc(kind)}</em>")
        "jump", "goto" -> {
            val target = prim(spec["target"])
            NBadge(jsFormat(actTr.getValue(kind), table, target, target), jump = "$table.$target")
        }
        "continue", "return" -> NBadge(actTr.getValue(kind))
        "snat", "dnat" -> {
            var k = "${LuciFmt.esc(kind)}.${LuciFmt.esc(prim(spec["family"]))}"
            val a = mutableListOf<Any?>()
            if (spec["addr"].truthyStr() != null || spec["addr"] is JsonObject) { k += ".addr"; a += exprToString(spec["addr"]) }
            if (spec["port"].truthyStr() != null || spec["port"] is JsonObject) { k += ".port"; a += exprToString(spec["port"]) }
            val (f, ft) = natFlags(spec)
            NBadge(jsFormat(actTr[k] ?: k, *a.toTypedArray()), flags = f, flagsTip = ft)
        }
        "redirect" -> {
            var k = "redirect"
            val a = mutableListOf<Any?>()
            if (spec is JsonObject && (spec["port"].truthyStr() != null || spec["port"] is JsonObject)) { k += ".port"; a += exprToString(spec["port"]) }
            val (f, ft) = natFlags(spec)
            NBadge(jsFormat(actTr[k] ?: k, *a.toTypedArray()), flags = f, flagsTip = ft)
        }
        "masquerade" -> { val (f, ft) = natFlags(spec); NBadge(actTr.getValue("masquerade"), flags = f, flagsTip = ft) }
        "mangle" -> NBadge(jsFormat(actTr.getValue("mangle"), exprToString(spec["key"]), exprToString(spec["value"])))
        "limit" -> {
            var k = "limit"
            val per = prim(spec["per"])
            val a = mutableListOf<Any?>(rateUnit(spec["rate"], spec["rate_unit"].str), exprTr["unit.${LuciFmt.esc(per)}"] ?: per)
            if (spec["inv"].truthyStr() != null) k += ".inv"
            if (spec["burst"].truthyStr() != null) { k += ".burst"; a += rateUnit(spec["burst"], spec["burst_unit"].str) }
            NBadge(jsFormat(actTr[k] ?: k, *a.toTypedArray()))
        }
        "flow" -> NBadge(jsFormat(actTr.getValue("flow"), prim(spec["flowtable"]).replace(Regex("^@"), "")))
        "log" -> {
            val prefix = spec["prefix"].truthyStr()
            NBadge(if (prefix != null) jsFormat(actTr.getValue("log.prefix"), prefix) else actTr.getValue("log"))
        }
        else -> NBadge("{ <strong>${LuciFmt.esc(kind)}</strong> }", tooltip = spec.toString(), mono = true)
    }
}

private fun counterBadge(c: JsonElement?): NBadge {
    val packets = c["packets"].double; val bytes = c["bytes"].double
    return NBadge(
        "<var>${LuciFmt.esc(LuciFmt.m(bytes, 1000, 1) + "B")}</var>",
        tooltip = "Traffic matched by rule: " + LuciFmt.m(packets, 1000, 1) + "Packets, " + LuciFmt.m(bytes, 1000, 1) + "Bytes",
    )
}

private fun renderRule(spec: JsonElement): NRule {
    val left = mutableListOf<NBadge>()
    val right = mutableListOf<NBadge>()
    var empty = true
    val table = spec["table"].str
    (spec["expr"] as? JsonArray)?.forEach { se0 ->
        val flowAdd = if (se0 is JsonPrimitive && se0.isString) Regex("^flow add (@\\S+)$").find(se0.content) else null
        val se: JsonElement = if (flowAdd != null)
            JsonObject(mapOf("flow" to JsonObject(mapOf("op" to JsonPrimitive("add"), "flowtable" to JsonPrimitive(flowAdd.groupValues[1])))))
        else se0
        val vmap = (se as? JsonObject)?.get("vmap")
        if (vmap != null) {
            val keys = mutableListOf<String>()
            val actions = mutableListOf<NBadge>()
            (vmap["data"]["set"] as? JsonArray)?.forEach { entry ->
                val mapKey = entry[0]; val act = entry[1]
                if (act is JsonObject && isAction(act)) {
                    val r = renderExpr(act, table)
                    actions += r.copy(html = LuciFmt.esc(exprToString(mapKey) + ":") + " " + r.html)
                } else {
                    actions += NBadge(exprToString(mapKey) + ":" + exprToString(act), tooltip = act.toString(), mono = true)
                }
                keys += "<strong>${exprToString(mapKey)}</strong>"
            }
            var match = jsFormat("Verdict map: <var>%h</var> is", exprToString(vmap["key"]))
            if (vmap["data"]["set"] is JsonArray) match += " " + keys.joinToString(" or ")
            left += NBadge(match)
            empty = false
            right += actions
            return@forEach
        }
        if (se is JsonPrimitive) {
            left += NBadge("{ <strong>${LuciFmt.esc(prim(se))}</strong> }")
            empty = false
            return@forEach
        }
        val res = renderExpr(se, table)
        when {
            (se as? JsonObject)?.get("counter") != null -> left.add(0, counterBadge(se["counter"]))
            isAction(se) -> right += res
            else -> { left += res; empty = false }
        }
    }
    spec["comment"].str?.takeIf { it.isNotEmpty() }?.let { left.add(0, NBadge("<var>#</var>", tooltip = "Rule comment: " + it.replace(Regex("^!fw4: "), ""))) }
    if (empty) left += NBadge("<em>${LuciFmt.esc("Any packet")}</em>")
    return NRule(left, right)
}

private fun renderChain(data: List<JsonElement>, spec: JsonElement): NChain {
    val name = prim(spec["name"])
    val title = when (spec["type"].str) {
        "filter" -> jsFormat("Traffic filter chain \"%h\"", name)
        "route" -> jsFormat("Route action chain \"%h\"", name)
        "nat" -> jsFormat("NAT action chain \"%h\"", name)
        else -> jsFormat("Rule container chain \"%h\"", name)
    }
    val policy = if (spec["policy"].str == "drop") "Drop unmatched packets" else "Continue processing unmatched packets"
    val hookName = spec["hook"].str
    val hook = when (hookName) {
        "ingress" -> "Capture packets directly after the NIC received them"
        "prerouting" -> "Capture incoming packets before any routing decision"
        "input" -> "Capture incoming packets routed to the local system"
        "forward" -> "Capture incoming packets addressed to other hosts"
        "output" -> "Capture outgoing packets originating from the local system"
        "postrouting" -> "Capture outgoing packets after any routing decision"
        else -> jsFormat("Chain hook \"%h\"", hookName)
    }
    val hookLines = if (spec["hook"].truthyStr() != null) listOf(
        jsFormat("Hook: <strong>%h</strong> (%h), Priority: <strong>%d</strong>", hookName, hook, spec["prio"].double),
        jsFormat("Policy: <strong>%h</strong> (%h)", spec["policy"].str, policy),
    ) else null
    val rules = data.mapNotNull { d ->
        val r = d["rule"] as? JsonObject ?: return@mapNotNull null
        if (r["table"] == spec["table"] && r["chain"] == spec["name"] && r["family"] == spec["family"]) renderRule(r) else null
    }
    return NChain("${prim(spec["table"])}.$name", title, hookLines, rules)
}

private fun renderTable(data: List<JsonElement>, spec: JsonElement): NTable {
    val name = prim(spec["name"])
    val title = when (spec["family"].str) {
        "ip" -> jsFormat("IPv4 traffic table \"%h\"", name)
        "ip6" -> jsFormat("IPv6 traffic table \"%h\"", name)
        "inet" -> jsFormat("IPv4/IPv6 traffic table \"%h\"", name)
        "arp" -> jsFormat("ARP traffic table \"%h\"", name)
        "bridge" -> jsFormat("Bridge traffic table \"%h\"", name)
        "netdev" -> jsFormat("Network device table \"%h\"", name)
        else -> jsFormat("\"%h\" table \"%h\"", spec["family"].str, name)
    }
    val chains = data.mapNotNull { d ->
        val c = d["chain"] as? JsonObject ?: return@mapNotNull null
        if (c["table"] == spec["name"] && c["family"] == spec["family"]) renderChain(data, c) else null
    }
    return NTable(title, chains)
}

private suspend fun saveOutput(ctx: PageContext, legacy: String, plain: String): String =
    if (ctx.fs.stat(legacy) != null) resolveDefault("") { ctx.fs.execDirect(legacy) }
    else resolveDefault("") { ctx.fs.execDirect(plain) }

/** Status › Firewall — port of luci-mod-status view/status/nftables.js. */
@Composable
fun NftablesPage(ctx: PageContext) {
    var model by remember { mutableStateOf<NftModel?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val state = rememberScrollState()
    val anchors = remember { ScrollAnchors() }
    val chainY = remember { mutableMapOf<String, Float>() }
    val scope = rememberCoroutineScope()
    val tooltip = rememberTooltip()

    LaunchedEffect(tick) {
        error = null
        try {
            model = coroutineScope {
                val nft = async { resolveDefault<JsonElement?>(null) { JsonCodec.parseToJsonElement(ctx.fs.execDirect("/usr/sbin/nft", listOf("--terse", "--json", "list", "ruleset"))) } }
                val ipt = async { saveOutput(ctx, "/usr/sbin/iptables-legacy-save", "/usr/sbin/iptables-save") }
                val ipt6 = async { saveOutput(ctx, "/usr/sbin/ip6tables-legacy-save", "/usr/sbin/ip6tables-save") }
                val legacy = Regex("\n-A ").containsMatchIn(ipt.await()) || Regex("\n-A ").containsMatchIn(ipt6.await())
                val list = nft.await()["nftables"] as? JsonArray
                NftModel(legacy, list?.let { l -> l.filter { (it as? JsonObject)?.containsKey("table") == true }.map { renderTable(l, it["table"]!!) } })
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = e.message ?: e.toString()
        }
    }

    fun jump(id: String) {
        val y = chainY[id] ?: return
        scope.launch { state.animateScrollTo((y - anchors.content).toInt().coerceAtLeast(0)) }
    }

    ScrollPageColumn(state, onContentY = { anchors.content = it }) {
        val m = model
        when {
            m == null && error != null -> ErrorBox(error!!) { tick++ }
            m == null -> LoadingBox()
            else -> {
                if (m.legacy) {
                    Surface(shape = MaterialTheme.shapes.large, color = LocalStatusColors.current.warn.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Legacy rules detected", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "There are legacy iptables rules present on the system. Mixing iptables and nftables rules is discouraged and may lead to incomplete traffic filtering.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            FilledTonalButton(onClick = { ctx.nav.open("admin/status/nftables/iptables") }) { Text("Open iptables rules overview…") }
                        }
                    }
                }
                val tables = m.tables
                if (tables == null) {
                    Text("No nftables ruleset loaded.", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                } else {
                    tables.forEach { t ->
                        SectionCard(t.title) {
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                t.chains.forEach { c -> ChainView(c, tooltip, { id, y -> chainY[id] = y }, ::jump) }
                            }
                        }
                    }
                }
            }
        }
    }
    TooltipHost(tooltip)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainView(c: NChain, tooltip: MutableTooltip, onY: (String, Float) -> Unit, onJump: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(c.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.onGloballyPositioned { onY(c.id, it.positionInRoot().y) })
        c.hook?.forEach { Text(htmlToAnnotated(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 560.dp
            Column(Modifier.fillMaxWidth()) {
                if (wide) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text("Rule matches", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.6f))
                        Text("Rule actions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
                    }
                    HorizontalDivider()
                }
                if (c.rules.isEmpty()) {
                    Text("No rules in this chain", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, modifier = Modifier.padding(vertical = 8.dp))
                }
                c.rules.forEachIndexed { i, r ->
                    if (wide) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            FlowRow(Modifier.weight(0.6f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                r.left.forEach { NftBadgeView(it, tooltip, onJump) }
                            }
                            FlowRow(Modifier.weight(0.4f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                r.right.forEach { NftBadgeView(it, tooltip, onJump) }
                            }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                r.left.forEach { NftBadgeView(it, tooltip, onJump) }
                            }
                            if (r.right.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("→", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                                r.right.forEach { NftBadgeView(it, tooltip, onJump) }
                            }
                        }
                    }
                    if (i < c.rules.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun NftBadgeView(b: NBadge, tooltip: MutableTooltip, onJump: (String) -> Unit) {
    val jump = b.jump
    val tip = b.tooltip
    val ftip = b.flagsTip
    val click: (() -> Unit)? = when {
        jump != null -> { { onJump(jump) } }
        tip != null -> { { tooltip.show(tip, monospace = b.mono) } }
        ftip != null -> { { tooltip.show(ftip) } }
        else -> null
    }
    IfBadge(onClick = click) {
        Text(htmlToAnnotated(b.html + (b.flags?.let { LuciFmt.esc(it) } ?: "")), style = MaterialTheme.typography.bodySmall)
    }
}
