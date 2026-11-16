package com.the412banner.wrtpanel.pages.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.ResponsiveTable
import com.the412banner.wrtpanel.ui.components.SectionCard
import com.the412banner.wrtpanel.ui.components.htmlToAnnotated
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val tableNames = listOf("Filter", "NAT", "Mangle", "Raw")

private data class IRule(val pkts: Long, val bytes: Long, val target: String, val proto: String, val indev: String, val outdev: String, val src: String, val dst: String, val options: String, val comment: String)

private data class IChain(val name: String, val policy: String?, val packets: Long, val bytes: Long, val references: Long, val rules: List<IRule>)

/** One parsed `iptables -nvxL -t <table>` dump; [refs] = chain -> (calling chain, rule number). */
private data class IDump(val chains: List<IChain>, val refs: Map<String, List<Pair<String, Int>>>)

private val ruleRe = Regex("^(\\d+) +(\\d+) +(\\d+) +(.*?) +(\\S+) +(\\S*) +(\\S+) +(\\S+) +(!?[a-f0-9:.]+(?:/[a-f0-9:.]+)?) +(!?[a-f0-9:.]+(?:/[a-f0-9:.]+)?) +(.+)$")

private fun parseIptablesDump(s: String): IDump {
    val chains = mutableListOf<IChain>()
    val refs = mutableMapOf<String, MutableList<Pair<String, Int>>>()
    var cur: IChain? = null
    var rules = mutableListOf<IRule>()
    fun flush() { cur?.let { chains += it.copy(rules = rules) } }
    for (line in Regex("([^\\n]*)\\n").findAll(s).map { it.groupValues[1] }) {
        val p = Regex("^Chain (.+) \\(policy (\\w+) (\\d+) packets, (\\d+) bytes\\)$").find(line)
        val r = if (p == null) Regex("^Chain (.+) \\((\\d+) references\\)$").find(line) else null
        when {
            p != null -> {
                flush()
                cur = IChain(p.groupValues[1], p.groupValues[2], p.groupValues[3].toLong(), p.groupValues[4].toLong(), 0, emptyList()); rules = mutableListOf()
            }
            r != null -> {
                flush()
                cur = IChain(r.groupValues[1], null, 0, 0, r.groupValues[2].toLong(), emptyList()); rules = mutableListOf()
            }
            line.startsWith("num ") -> {}
            else -> {
                val m = ruleRe.find(line) ?: continue
                var comment = "-"
                val options = m.groupValues[11].trim().replace(Regex("(?:^| )/\\* (.+) \\*/")) { mm ->
                    comment = mm.groupValues[1].replace(Regex("^!fw3(: |$)"), "").trim().ifEmpty { "-" }
                    ""
                }.ifEmpty { "-" }
                val target = m.groupValues[4]
                rules += IRule(m.groupValues[2].toLong(), m.groupValues[3].toLong(), target, m.groupValues[5], m.groupValues[7], m.groupValues[8], m.groupValues[9], m.groupValues[10], options, comment)
                val c = cur
                if (target.isNotEmpty() && c != null) refs.getOrPut(target) { mutableListOf() } += c.name to m.groupValues[1].toInt()
            }
        }
    }
    flush()
    return IDump(chains, refs)
}

/** Status › Firewall (iptables) — port of luci-mod-status view/status/iptables.js. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IptablesPage(ctx: PageContext) {
    var hasIp6 by remember { mutableStateOf<Boolean?>(null) }
    val dumps = remember { mutableStateMapOf<String, IDump>() }
    var hideEmpty by remember { mutableStateOf(false) }
    var raw by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val state = rememberScrollState()
    val anchors = remember { ScrollAnchors() }
    val chainY = remember { mutableMapOf<String, Float>() }
    val scope = rememberCoroutineScope()
    val tooltip = rememberTooltip()

    LaunchedEffect(Unit) {
        val v6 = resolveDefault(null) { ctx.fs.stat("/usr/sbin/ip6tables") } != null
        hasIp6 = v6
        val cmds = listOf("/usr/sbin/iptables") + (if (v6) listOf("/usr/sbin/ip6tables") else emptyList())
        val interval = ((ctx.client.env?.pollInterval ?: 5).coerceAtLeast(1)) * 1000L
        while (true) {
            coroutineScope {
                cmds.flatMapIndexed { i, cmd ->
                    tableNames.map { tn ->
                        async {
                            val out = resolveDefault<String?>(null) { ctx.fs.execDirect(cmd, listOf("--line-numbers", "-w", "-nvxL", "-t", tn.lowercase())) }
                            if (out != null) dumps["${if (i > 0) "ipv6" else "ipv4"}-$tn"] = parseIptablesDump(out)
                        }
                    }
                }.awaitAll()
            }
            delay(interval)
        }
    }

    fun jump(family: String, table: String, chain: String) {
        val y = chainY["rule_${family}_${table.lowercase()}_$chain"] ?: return
        scope.launch { state.animateScrollTo((y - anchors.content).toInt().coerceAtLeast(0)) }
    }

    ScrollPageColumn(state, onContentY = { anchors.content = it }) {
        PageHeader("Firewall Status", null)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { hideEmpty = !hideEmpty }) { Text(if (hideEmpty) "Show empty chains" else "Hide empty chains") }
            OutlinedButton(onClick = { raw = !raw }) { Text(if (raw) "Human-readable counters" else "Show raw counters") }
            OutlinedButton(onClick = {
                val v6 = hasIp6 == true
                scope.launch {
                    try { ctx.fs.exec("/usr/sbin/iptables", listOf("-Z")) } catch (e: Exception) { ctx.toast(jsFormat("Unable to reset iptables counters: %s", e.message)) }
                    if (v6) try { ctx.fs.exec("/usr/sbin/ip6tables", listOf("-Z")) } catch (e: Exception) { ctx.toast(jsFormat("Unable to reset ip6tables counters: %s", e.message)) }
                }
            }) { Text("Reset Counters") }
            OutlinedButton(onClick = {
                scope.launch {
                    try { ctx.fs.execDirect("/etc/init.d/firewall", listOf("restart")) } catch (e: Exception) { ctx.toast(jsFormat("Unable to restart firewall: %s", e.message)) }
                }
            }) { Text("Restart Firewall") }
        }
        val v6 = hasIp6
        if (v6 == null) {
            LoadingBox()
        } else {
            if (v6) {
                PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("IPv4 Firewall") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("IPv6 Firewall") })
                }
            }
            val family = if (v6 && tab == 1) "ipv6" else "ipv4"
            val tables = tableNames.filter { dumps.containsKey("$family-$it") }
            if (tables.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Collecting data...", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                }
            }
            tables.forEach { tn ->
                val dump = dumps.getValue("$family-$tn")
                val seen = dump.chains.map { it.name }.toSet()
                SectionCard("Table: $tn") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        dump.chains.forEach { c ->
                            if (hideEmpty && c.rules.isEmpty()) return@forEach
                            val id = "rule_${family}_${tn.lowercase()}_${c.name}"
                            val title = if (c.policy != null)
                                jsFormat("%s <em>%s</em> (%s: <em>%s</em>, %d %s, ", "Chain", c.name, "Policy", c.policy, c.packets, "Packets") +
                                    LuciFmt.m(c.bytes, 1000, 2) + "B Traffic)"
                            else jsFormat("%s <em>%s</em> (%d %s)", "Chain", c.name, c.references, "References")
                            val refs = dump.refs[c.name]
                            Text(
                                htmlToAnnotated(title), style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier
                                    .onGloballyPositioned { chainY[id] = it.positionInRoot().y }
                                    .let { mod ->
                                        if (c.policy == null && !refs.isNullOrEmpty()) mod.clickable {
                                            tooltip.show {
                                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    refs.forEach { (ch, num) ->
                                                        Text(
                                                            "Chain $ch, Rule #$num", style = MaterialTheme.typography.bodyMedium,
                                                            textDecoration = TextDecoration.Underline,
                                                            modifier = Modifier.clickable { tooltip.content = null; jump(family, tn, ch) },
                                                        )
                                                    }
                                                }
                                            }
                                        } else mod
                                    },
                            )
                            ResponsiveTable(
                                headers = listOf("Pkts.", "Traffic", "Target", "Prot.", "In", "Out", "Source", "Destination", "Options", "Comment"),
                                rows = c.rules.map { r ->
                                    listOf(
                                        if (raw) r.pkts.toString() else LuciFmt.m(r.pkts, 1000, 2),
                                        if (raw) r.bytes.toString() else LuciFmt.m(r.bytes, 1000, 2) + "B",
                                        r.target.ifEmpty { "-" }, r.proto, r.indev, r.outdev, r.src, r.dst, r.options, r.comment,
                                    )
                                },
                                weights = listOf(0.7f, 0.9f, 1.1f, 0.6f, 0.7f, 0.7f, 1.2f, 1.2f, 1.6f, 1.2f),
                                minWideWidth = 900.dp,
                                primaryColumn = 2,
                                empty = "No rules in this chain.",
                                cell = { ri, col, t ->
                                    val r = c.rules[ri]
                                    when {
                                        col == 2 && seen.contains(r.target) -> Text(
                                            t, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                                            textDecoration = TextDecoration.Underline, modifier = Modifier.clickable { jump(family, tn, r.target) },
                                        )
                                        (col == 4 || col == 5) && t != "*" -> IfBadge { Text(t, style = MaterialTheme.typography.bodySmall) }
                                        else -> SelectionContainer { Text(t.ifEmpty { "-" }, style = MaterialTheme.typography.bodyMedium) }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    TooltipHost(tooltip)
}
