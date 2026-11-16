@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.the412banner.wrtpanel.pages.network.interfaces

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.the412banner.wrtpanel.core.ChangeRecord
import com.the412banner.wrtpanel.core.JsonCodec
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormRuntime
import com.the412banner.wrtpanel.form.SaveBar
import com.the412banner.wrtpanel.form.SectionBody
import com.the412banner.wrtpanel.form.SectionView
import com.the412banner.wrtpanel.pages.common.NetTools
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.EmptyState
import com.the412banner.wrtpanel.ui.components.ErrorBox
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.SectionCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the view loaded: network state, routing tables and the page map (globals, DSL, ATM). */
class IfPage(
    val ctx: PageContext,
    val model: NetModel,
    val rtTables: List<Pair<Int, String>>,
    val map: FormMap,
    val rt: FormRuntime,
    val migration: Migration?,
) {
    fun networks(): List<NetIface> = model.getNetworks()

    /** m.save(cb): parse the page map, run [block], stage with uci save. */
    suspend fun saveMap(block: (suspend () -> Unit)? = null): String? {
        for (s in map.sections) rt.parseSection(s)?.let { return it }
        block?.invoke()
        ctx.uci.save()
        rt.clearEdits()
        ctx.session.changes.afterSave()
        return null
    }
}

enum class Migration { Bridge, Ifname }

private sealed interface PageLoad {
    data object Loading : PageLoad
    data class Ready(val page: IfPage) : PageLoad
    data class Failed(val message: String) : PageLoad
}

private suspend fun loadPage(ctx: PageContext): IfPage {
    ctx.uci.unloadAll()
    val model = NetModel(ctx)
    model.load(full = true)
    val rtTables = NetTools.parseRtTables(ctx.fs.readOrNull("/etc/iproute2/rt_tables") ?: "")
    ctx.uci.load(listOfNotNull("network", "dhcp", "firewall", "system", "luci", if (model.hasWifi) "wireless" else null))
    ctx.session.changes.refresh()

    val uci = ctx.uci
    val migration = when {
        uci.sections("network", "interface").any { it["type"] == "bridge" && it["ports"] == null && it["ifname"] != null } -> Migration.Bridge
        uci.sections("network", "device").any { it["type"] == "bridge" && it["ports"] == null && it["ifname"] != null } ||
            uci.sections("network", "interface").any { it["device"] == null && it["ifname"] != null } -> Migration.Ifname
        else -> null
    }

    val m = FormMap("network")
    m.tabbed = true
    m.chain("dhcp")

    m.typed("globals", "Global network options") {
        addremove = false
        anonymous = true
        value(
            "ula_prefix", "IPv6 ULA-Prefix",
            "Unique Local Address (<a href=\"https://datatracker.ietf.org/doc/html/rfc4193#section-3\" target=\"_blank\">RFC4193</a>) - prefix <code>fd00::/8</code> (the L bit is always 1). " +
                "ULA for IPv6 is analogous to IPv4 private network addressing. This prefix is randomly generated at first install.",
        ) { datatype = "cidr6" }
        value(
            "dhcp_default_duid", "Default DUID",
            "The default <abbr title=\"DHCP Unique Identifier\">DUID</abbr> for this device, used when acting as a DHCP server or client. The client identifier can be overridden on a per-interface basis.<br />" +
                "This identifier is randomly generated the first time the device is booted.",
        ) { datatype = "and(rangelength(6,260),hexstring)" }
        if (model.hasFeature("mptcp")) flag("multipath", "Multi-Path TCP", "For packets originating from this device, e.g. VPN.") { optional = true }
        val l3help1 = "%s services running on this device in the default VRF context (ie., not bound to any VRF device) shall work across all VRF domains."
        val l3help2 = "Off means VRF traffic will be handled exclusively by sockets bound to VRFs."
        flag("tcp_l3mdev", "TCP Layer 3 Master Device (tcp_l3mdev) accept", l3help1.format("TCP") + "<br/>" + l3help2)
        flag("udp_l3mdev", "UDP Layer 3 Master Device (udp_l3mdev) accept", l3help1.format("UDP") + "<br/>" + l3help2)
        list("packet_steering", "Packet Steering", "Enable packet steering across CPUs. May help or hinder network speed.") {
            value("0", "Disabled"); value("1", "Enabled"); value("2", "Enabled (all CPUs)")
            default = "1"; optional = true
        }
        val steerFlow = uci.getString("network", "globals", "steering_flows")
        value(
            "steering_flows", "Steering flows (<abbr title=\"Receive Packet Steering\">RPS</abbr>)",
            "Directs packet flows to specific CPUs where the local socket owner listens (the local service). Note: this setting is for local services on the device only (not for forwarding).",
        ) {
            value("", "Standard: none"); value("128", "Suggested: 128"); value("256", "256")
            depends("packet_steering", "1"); depends("packet_steering", "2")
            datatype = "uinteger"
            default = steerFlow
        }
    }

    val dsl = model.dslModemType
    if (dsl != null) {
        m.typed("dsl", "DSL") {
            anonymous = true
            list("annex", "Annex") {
                if (dsl == "vdsl") {
                    value("a", "ADSL (all variants) Annex A/L/M + VDSL2 Annex A/B/C")
                    value("b", "ADSL (all variants) Annex B + VDSL2 Annex A/B/C")
                    value("j", "ADSL (all variants) Annex B/J + VDSL2 Annex A/B/C")
                } else {
                    value("a", "ADSL (all variants) Annex A/L/M")
                    value("b", "ADSL (all variants) Annex B")
                    value("j", "ADSL (all variants) Annex B/J")
                }
                value("m", "ADSL (all variants) Annex M"); value("at1", "ANSI T1.413"); value("admt", "ADSL (G.992.1) Annex A")
                value("bdmt", "ADSL (G.992.1) Annex B"); value("alite", "Splitterless ADSL (G.992.2) Annex A"); value("a2", "ADSL2 (G.992.3) Annex A")
                value("b2", "ADSL2 (G.992.3) Annex B"); value("l", "ADSL2 (G.992.3) Annex L"); value("m2", "ADSL2 (G.992.3) Annex M")
                value("a2p", "ADSL2+ (G.992.5) Annex A"); value("b2p", "ADSL2+ (G.992.5) Annex B"); value("m2p", "ADSL2+ (G.992.5) Annex M")
            }
            list("tone", "Tone") {
                value("", "auto"); value("a", "A43C + J43 + A43"); value("av", "A43C + J43 + A43 + V43"); value("b", "B43 + B43C"); value("bv", "B43 + B43C + V43")
            }
            if (dsl == "vdsl") {
                list("xfer_mode", "Encapsulation mode") { value("", "auto"); value("atm", "ATM (Asynchronous Transfer Mode)"); value("ptm", "PTM/EFM (Packet Transfer Mode)") }
                list("line_mode", "DSL line mode") { value("", "auto"); value("adsl", "ADSL"); value("vdsl", "VDSL") }
                list("ds_snr_offset", "Downstream SNR offset") {
                    default = "0"
                    for (i in -100..100 step 5) value(i.toString(), "%.1f dB".format(java.util.Locale.ROOT, i / 10.0))
                }
            }
            value("firmware", "Firmware File")
        }
    }

    if (model.hasFeature("br2684ctl")) {
        m.typed("atm-bridge", "ATM Bridges", "ATM bridges expose encapsulated ethernet in AAL5 connections as virtual Linux network interfaces which can be used in conjunction with DHCP or PPP to dial into the provider network.") {
            addremove = true
            anonymous = true
            addbtntitle = "Add ATM Bridge"
            onAdd = { r, sid ->
                var maxUnit = -1
                r.uci.sections("network", "atm-bridge").forEach { s -> (s["unit"] as? String)?.toIntOrNull()?.let { if (it > maxUnit) maxUnit = it } }
                r.uci.set("network", sid, "unit", (maxUnit + 1).toString())
                r.uci.set("network", sid, "atmdev", "0")
                r.uci.set("network", sid, "encaps", "llc")
                r.uci.set("network", sid, "payload", "bridged")
                r.uci.set("network", sid, "vci", "35")
                r.uci.set("network", sid, "vpi", "8")
            }
            tab("general", "General Setup")
            tab("advanced", "Advanced Settings")
            value("vci", "ATM Virtual Channel Identifier (VCI)", tab = "general")
            value("vpi", "ATM Virtual Path Identifier (VPI)", tab = "general")
            list("encaps", "Encapsulation mode", tab = "general") { value("llc", "LLC"); value("vc", "VC-Mux") }
            value("atmdev", "ATM device number", tab = "advanced")
            value("unit", "Bridge unit number", tab = "advanced")
            list("payload", "Forwarding mode", tab = "advanced") { value("bridged", "bridged"); value("routed", "routed") }
        }
    }

    if (ctx.readonly) m.readonly = true
    val rt = FormRuntime(m, ctx)
    for (o in m.sections.flatMap { it.allOptions() }) o.loadFn?.invoke(ctx, rt)
    return IfPage(ctx, model, rtTables, m, rt, migration)
}

/** Network › Interfaces — port of luci-mod-network view/network/interfaces.js. */
@Composable
fun InterfacesPage(ctx: PageContext) {
    val generation by ctx.session.changes.generation.collectAsState()
    var reloadTick by remember { mutableIntStateOf(0) }
    var state by remember(generation, reloadTick) { mutableStateOf<PageLoad>(PageLoad.Loading) }
    LaunchedEffect(generation, reloadTick) {
        state = PageLoad.Loading
        state = try {
            PageLoad.Ready(loadPage(ctx))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            PageLoad.Failed(e.message ?: e.toString())
        }
    }
    when (val s = state) {
        is PageLoad.Loading -> PageColumn { LoadingBox() }
        is PageLoad.Failed -> PageColumn { ErrorBox(s.message) { reloadTick++ } }
        is PageLoad.Ready -> InterfacesView(s.page, onReload = { reloadTick++ })
    }
}

/** Restart / Stop progress of a row (the reconnect / disconnect attributes of the view). */
private data class Phase(val up: Boolean, val running: Boolean)

@Composable
private fun InterfacesView(page: IfPage, onReload: () -> Unit) {
    val ctx = page.ctx
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val phases = remember { mutableStateMapOf<String, Phase>() }
    var editIface by remember { mutableStateOf<String?>(null) }
    var editDevice by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(page) {
        val interval = (ctx.client.env?.pollInterval ?: 5) * 1000L
        while (true) {
            delay(interval)
            runCatching { page.model.load(full = false) }
        }
    }

    if (page.migration != null) {
        MigrationView(page, page.migration)
        return
    }

    val tabs = listOf("Interfaces", "Devices") + page.map.sections.map { it.title ?: it.sectionType }
    Box(Modifier.fillMaxSize()) {
        PageColumn {
            PrimaryScrollableTabRow(selectedTabIndex = tab.coerceIn(0, tabs.size - 1), edgePadding = 0.dp) {
                tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
            }
            when (val t = tab.coerceIn(0, tabs.size - 1)) {
                0 -> InterfacesTab(page, phases, onEdit = { editIface = it }, onAdd = { adding = true })
                1 -> DevicesTab(page, onEdit = { sid, added -> editDevice = sid to added })
                else -> SectionView(page.rt, page.map.sections[t - 2])
            }
        }
        if (!(page.map.readonly || ctx.readonly)) {
            SaveBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                busy = busy,
                onReset = { page.rt.reset(); onReload() },
                onSave = { apply, unchecked ->
                    scope.launch {
                        busy = true
                        val err = try { page.saveMap() } catch (e: Exception) { e.message ?: e.toString() }
                        busy = false
                        if (err != null) ctx.toast(err)
                        else if (apply) ctx.session.changes.apply(checked = !unchecked)
                        else ctx.toast("Changes saved")
                    }
                },
            )
        }
    }

    editIface?.let { sid -> InterfaceEditor(page, sid, onClose = { editIface = null }) }
    editDevice?.let { (sid, added) -> DeviceEditor(page, sid, added, onClose = { editDevice = null }) }
    if (adding) AddInterfaceDialog(page, onDismiss = { adding = false }, onCreated = { adding = false; editIface = it })
}

// ---------------------------------------------------------------- interface rows

private data class Item(val label: String?, val value: String, val em: Boolean = false)

/** count_changes() */
private fun countChanges(changes: Map<String, List<ChangeRecord>>, sid: String): Int =
    (changes["network"]?.count { it.getOrNull(1) == sid } ?: 0) + (changes["dhcp"]?.count { it.getOrNull(1) == sid } ?: 0)

/** render_status(); null = "Interface is marked for deletion". */
private fun statusItems(page: IfPage, ifc: NetIface, withDevice: Boolean, changes: Map<String, List<ChangeRecord>>): List<Item>? {
    var desc: String? = null
    when {
        ifc.isDynamic() -> desc = "Virtual dynamic interface"
        ifc.isAlias() != null -> desc = "Alias Interface"
        page.ctx.uci.section("network", ifc.sid) == null -> return null
    }
    desc = if (desc != null) "%s (%s)".format(desc, ifc.getI18n()) else ifc.getI18n()
    val changecount = if (withDevice) 0 else countChanges(changes, ifc.sid)
    val maindev = ifc.getL3Device() ?: ifc.getDevice()
    val mac = maindev?.getMAC()
    val carrier = maindev?.getCarrier()
    val cond00 = changecount == 0 && !ifc.isDynamic() && ifc.isAlias() == null
    val out = ArrayList<Item>()
    if (!withDevice) out += Item("Protocol", desc)
    if (withDevice) out += if (maindev != null) Item("Device", maindev.getShortName()) else Item("Device", "Not present", em = true)
    if (cond00 && maindev != null) out += Item("Carrier", if (carrier == true) "Present" else "Absent")
    if (changecount == 0 && ifc.isUp()) out += Item("Uptime", fmtDuration(ifc.getUptime()))
    if (cond00 && mac != null) out += Item("MAC", mac)
    if (cond00 && maindev != null) {
        out += Item("RX", "${fmtMetric(maindev.getRXBytes())}B (${maindev.getRXPackets()} Pkts.)")
        out += Item("TX", "${fmtMetric(maindev.getTXBytes())}B (${maindev.getTXPackets()} Pkts.)")
    }
    if (changecount == 0) {
        ifc.getIPAddrs().forEach { out += Item("IPv4", it) }
        ifc.getIP6Addrs().forEach { out += Item("IPv6", it) }
        ifc.getIP6Prefixes()?.forEach { out += Item("IPv6-PD", it) }
    }
    if (!withDevice && ifc.getString("disabled") == "1") out += Item("Information", "Interface disabled")
    if (!withDevice && ifc.getString("auto") == "0") out += Item("Information", "Not started on boot")
    ifc.getErrors()?.forEach { out += Item("Error", it) }
    if (changecount > 0) out += Item(null, "Interface has %d pending changes".format(changecount), em = true)
    return out
}

@Composable
private fun ItemList(items: List<Item>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { item ->
            Row(Modifier.fillMaxWidth()) {
                if (item.label != null) Text(
                    item.label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp).padding(top = 1.dp),
                )
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        item.value, style = MaterialTheme.typography.bodyMedium,
                        fontStyle = if (item.em) FontStyle.Italic else FontStyle.Normal,
                        color = if (item.em && item.label == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** render_iface() tooltip as a dialog text. */
private fun ifaceTooltip(dev: NetDevice?): String = buildString {
    if (dev != null) appendLine("Type: ${dev.getTypeI18n()}")
    appendLine("Device: ${dev?.getName() ?: "Not present"}")
    appendLine("Connected: ${if (dev?.isUp() == true) "yes" else "no"}")
    dev?.getMAC()?.let { appendLine("MAC: $it") }
    if (dev != null) {
        appendLine("RX: ${fmtMetric(dev.getRXBytes())}B (${dev.getRXPackets()} Pkts.)")
        append("TX: ${fmtMetric(dev.getTXBytes())}B (${dev.getTXPackets()} Pkts.)")
    }
}.trim()

/** render_ifacebox_status(): device icon, bridge ports and the device name. */
@Composable
private fun IfaceDevices(page: IfPage, ifc: NetIface) {
    val scope = rememberCoroutineScope()
    val dev = ifc.getL3Device() ?: ifc.getDevice()
    val subdevs = dev?.getPorts()
    val alias = ifc.isAlias()
    fun show(d: NetDevice?) { scope.launch { page.ctx.dialogs.ask(d?.getName() ?: "Not present", ifaceTooltip(d), confirmLabel = "Dismiss", dismissLabel = null) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clickable { show(dev) }.padding(2.dp)) { DeviceGlyph(if (alias != null) "alias" else dev?.getType() ?: "ethernet", dev?.isUp() == true, Modifier.size(22.dp)) }
        if (!subdevs.isNullOrEmpty()) {
            Text(" (", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            subdevs.forEach { sd -> Box(Modifier.clickable { show(sd) }.padding(1.dp)) { DeviceGlyph(sd.getType(), sd.isUp()) } }
            Text(")", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (alias != null) "Alias of \"%s\"".format(alias) else dev?.getName() ?: "Not present",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = if (alias == null && dev == null) FontStyle.Italic else FontStyle.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InterfacesTab(page: IfPage, phases: MutableMap<String, Phase>, onEdit: (String) -> Unit, onAdd: () -> Unit) {
    val ctx = page.ctx
    val scope = rememberCoroutineScope()
    page.model.version
    ctx.uci.revision.intValue
    val changes by ctx.session.changes.changes.collectAsState()
    val ro = ctx.readonly
    val tablefilters = ctx.uci.getString("luci", "main", "tablefilters")?.isNotEmpty() == true
    var filter by remember { mutableStateOf("") }
    val nets = page.networks().filter { it.sid != "loopback" }

    fun updown(up: Boolean, sid: String) {
        scope.launch {
            if (!up) {
                val info = runCatching { JsonCodec.parseToJsonElement(ctx.fs.execDirect("/usr/libexec/luci-peeraddr")) }.getOrNull()
                val inbound = info["inbound_interfaces"].arr?.mapNotNull { it.str } ?: emptyList()
                if (inbound.contains(sid)) {
                    val ok = ctx.dialogs.ask(
                        "Confirm disconnect",
                        "You appear to be currently connected to the device via the \"%s\" interface. Do you really want to shut down the interface?".format(sid),
                        confirmLabel = "Disconnect", dismissLabel = "Cancel", destructive = true,
                    )
                    if (!ok) return@launch
                }
            }
            phases[sid] = Phase(up, running = false)
            delay(300)
            phases[sid] = Phase(up, running = true)
            try { ctx.fs.exec(if (up) "/sbin/ifup" else "/sbin/ifdown", listOf(sid)) } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) }
            runCatching { page.model.load(full = false) }
            phases.remove(sid)
        }
    }

    fun remove(sid: String) {
        scope.launch {
            if (!ctx.dialogs.ask("Delete", sid, confirmLabel = "Delete", destructive = true)) return@launch
            try {
                val err = page.saveMap { page.model.deleteNetwork(sid) }
                if (err != null) ctx.toast(err)
                runCatching { page.model.load(full = false) }
            } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) }
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (tablefilters) {
            OutlinedTextField(
                filter, { filter = it }, singleLine = true, placeholder = { Text("Filter") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth(),
            )
        }
        val shown = nets.filter { n -> filter.isBlank() || n.sid.contains(filter, true) || n.getI18n().contains(filter, true) || (n.getIfname()?.contains(filter, true) ?: false) }
        if (shown.isEmpty()) EmptyState("This section contains no values yet")
        shown.forEach { ifc -> IfaceCard(page, ifc, phases[ifc.sid], changes, ro, onRestart = { updown(true, ifc.sid) }, onStop = { updown(false, ifc.sid) }, onEdit = { onEdit(ifc.sid) }, onDelete = { remove(ifc.sid) }) }
        if (!ro) {
            FilledTonalButton(onClick = onAdd, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Add new interface...")
            }
        }
    }
}

@Composable
private fun IfaceCard(
    page: IfPage, ifc: NetIface, phase: Phase?, changes: Map<String, List<ChangeRecord>>, ro: Boolean,
    onRestart: () -> Unit, onStop: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit,
) {
    val uci = page.ctx.uci
    val zone = Firewall.zoneByNetwork(uci, ifc.sid)
    val color = if (zone != null) zoneColor(zone.second) else zoneColor(null)
    val inUci = uci.section("network", ifc.sid) != null
    val dynamic = ifc.isDynamic()
    val markedDel = !dynamic && !inUci
    val pending = ifc.isPending()
    val up = ifc.isUp()
    val base = !ro && phase == null && !dynamic && !markedDel
    val restartOn = base && !pending
    val stopOn = base && (up || pending)
    val editOn = !markedDel && !dynamic && ifc.handler != null
    val deleteOn = !ro && !markedDel && !dynamic
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .drawBehind { drawRect(color, size = Size(6.dp.toPx(), size.height)) }
                .padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ifc.sid, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                if (zone?.second != null) {
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.35f)) {
                        Text(zone.second!!, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            IfaceDevices(page, ifc)
            Spacer(Modifier.height(8.dp))
            when {
                phase != null -> Text(
                    when { phase.up && !phase.running -> "Interface is reconnecting..."; phase.up -> "Interface is starting..."; !phase.running -> "Interface is shutting down..."; else -> "Interface is stopping..." },
                    style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                )
                ifc.getProtocol() != null || !inUci -> {
                    val items = statusItems(page, ifc, false, changes)
                    if (items == null) Text("Interface is marked for deletion", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                    else ItemList(items)
                }
                else -> Column {
                    Text("Unsupported protocol type.", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                    if (page.ctx.session.menu?.find("admin/system/package-manager") != null) {
                        Text(
                            "Install protocol extensions...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { page.ctx.nav.open("admin/system/package-manager") }.padding(vertical = 4.dp),
                        )
                    }
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                TextButton(onClick = onRestart, enabled = restartOn) { Text("Restart") }
                TextButton(onClick = onStop, enabled = stopOn) { Text("Stop") }
                TextButton(onClick = onEdit, enabled = editOn) { Text("Edit") }
                TextButton(onClick = onDelete, enabled = deleteOn, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            }
        }
    }
}

/** The status box of the edit dialog (render_modal_status). */
@Composable
fun ModalStatus(page: IfPage, sid: String) {
    page.model.version
    val net = page.networks().firstOrNull { it.sid == sid }
    Column(Modifier.fillMaxWidth()) {
        Text("Status", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp)) {
                val dev = net?.let { it.getL3Device() ?: it.getDevice() }
                DeviceGlyph(dev?.getType() ?: "ethernet", net?.isUp() == true, Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                if (net == null) Text("Interface not present or not connected yet.", fontStyle = FontStyle.Italic)
                else {
                    val items = statusItems(page, net, true, emptyMap())
                    if (items == null) Text("Interface is marked for deletion", fontStyle = FontStyle.Italic) else ItemList(items)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
private fun ModalScaffold(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String?,
    onConfirm: () -> Unit,
    dismissLabel: String = "Dismiss",
    confirmEnabled: Boolean = true,
    message: String? = null,
    onMessage: (String?) -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, dismissLabel) } },
                    actions = { if (confirmLabel != null) TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmLabel) } },
                )
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 920.dp).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp)) {
                    if (message != null) {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
                                IconButton(onClick = { onMessage(null) }) { Icon(Icons.Outlined.Close, "Dismiss", tint = MaterialTheme.colorScheme.onErrorContainer) }
                            }
                        }
                    }
                    CompositionLocalProvider(LocalModalMessage provides { m: String -> onMessage(m) }) { content() }
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }
}

/** s.renderMoreOptionsModal for an interface: edit dialog, protocol switch and Save / Dismiss. */
@Composable
private fun InterfaceEditor(page: IfPage, sid: String, onClose: () -> Unit) {
    val ctx = page.ctx
    val scope = rememberCoroutineScope()
    var buildKey by remember(sid) { mutableIntStateOf(0) }
    val form = remember(sid) { mutableStateOf<ModalForm?>(null) }
    var error by remember(sid) { mutableStateOf<String?>(null) }
    var msg by remember(sid) { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(sid, buildKey) {
        form.value = null
        try {
            form.value = buildInterfaceModal(ctx, page, sid) {
                val f = form.value ?: return@buildInterfaceModal
                val err = try { f.rt.saveModal(f.section, sid) } catch (e: Exception) { e.message ?: e.toString() }
                if (err != null) msg = err
                else { msg = null; runCatching { page.model.load(full = false) }; buildKey++ }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) { error = e.message ?: e.toString() }
    }
    val ro = page.map.readonly || ctx.readonly
    ModalScaffold(
        title = "Interfaces » $sid",
        message = msg,
        onMessage = { msg = it },
        onDismiss = {
            form.value?.rt?.clearEdits()
            val device = if (ctx.uci.getString("network", sid, "type") == "bridge") "br-$sid" else null
            if (device != null) ctx.uci.sections("network", "bridge-vlan").filter { it["device"] == device }.forEach { ctx.uci.remove("network", it[".name"] as String) }
            onClose()
        },
        confirmLabel = if (ro) null else "Save",
        confirmEnabled = form.value != null && !saving,
        onConfirm = {
            val f = form.value ?: return@ModalScaffold
            scope.launch {
                saving = true
                val err = try { f.rt.saveModal(f.section, sid) } catch (e: Exception) { e.message ?: e.toString() }
                saving = false
                if (err != null) msg = err else { onClose(); runCatching { page.model.load(full = false) } }
            }
        },
    ) {
        val f = form.value
        when {
            error != null -> ErrorBox(error!!) { error = null; buildKey++ }
            f == null -> LoadingBox()
            else -> SectionBody(f.rt, f.section, sid, modal = true)
        }
    }
}

/** The device dialog with handleModalCancel()'s cleanup on Dismiss. */
@Composable
private fun DeviceEditor(page: IfPage, sid0: String, added: Boolean, onClose: () -> Unit) {
    val ctx = page.ctx
    val scope = rememberCoroutineScope()
    val form = remember(sid0) { mutableStateOf<Pair<ModalForm, DeviceDialogState>?>(null) }
    var error by remember(sid0) { mutableStateOf<String?>(null) }
    var msg by remember(sid0) { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(sid0, tick) {
        try { form.value = buildDeviceModal(ctx, page, sid0) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e.message ?: e.toString() }
    }
    val ro = page.map.readonly || ctx.readonly
    val f = form.value
    ModalScaffold(
        title = f?.let { it.first.section.modalTitle?.invoke(it.first.sid) } ?: "Add device configuration",
        message = msg,
        onMessage = { msg = it },
        onDismiss = {
            f?.let { (mf, st) ->
                mf.rt.clearEdits()
                val uci = ctx.uci
                mf.addedSections.forEach { added ->
                    val name = uci.getString("network", added, "name")
                    if (name != null) uci.sections("network", "bridge-vlan").filter { it["device"] == name }.forEach { uci.remove("network", it[".name"] as String) }
                }
                st.addedVLANs.forEach { uci.remove("network", it) }
                mf.addedSections.forEach { uci.remove("network", it) }
            }
            if (added) ctx.uci.remove("network", sid0)
            onClose()
        },
        confirmLabel = if (ro) null else "Save",
        confirmEnabled = f != null && !saving,
        onConfirm = {
            val (mf, _) = f ?: return@ModalScaffold
            scope.launch {
                saving = true
                val err = try { mf.rt.saveModal(mf.section, mf.sid) } catch (e: Exception) { e.message ?: e.toString() }
                saving = false
                if (err != null) msg = err else { onClose(); runCatching { page.model.load(full = false) } }
            }
        },
    ) {
        when {
            error != null -> ErrorBox(error!!) { error = null; tick++ }
            f == null -> LoadingBox()
            else -> SectionBody(f.first.rt, f.first.section, f.first.sid, modal = true)
        }
    }
}

/** s.handleAdd: "Add new interface..." with name, protocol and device. */
@Composable
private fun AddInterfaceDialog(page: IfPage, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val ctx = page.ctx
    val scope = rememberCoroutineScope()
    val form = remember { buildAddInterface(ctx, page.model) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    ModalScaffold(
        title = "Add new interface...",
        onDismiss = onDismiss,
        dismissLabel = "Cancel",
        confirmLabel = "Create interface",
        confirmEnabled = !busy,
        message = msg,
        onMessage = { msg = it },
        onConfirm = {
            val rt = form.rt
            rt.setValue(form.name, "_new_", rt.formString(form.name, "_new_"))
            val nameval = if (rt.validateField(form.name, "_new_") == null) rt.formString(form.name, "_new_") else null
            val protoval = if (rt.validateField(form.proto, "_new_") == null) rt.formString(form.proto, "_new_") else null
            if (nameval.isNullOrEmpty() || protoval.isNullOrEmpty()) return@ModalScaffold
            val devval = rt.formString(form.device, "_new_")
            scope.launch {
                busy = true
                try {
                    val err = page.saveMap {
                        ctx.uci.add("network", "interface", nameval)
                        ctx.uci.set("network", nameval, "proto", protoval)
                        page.model.instantiateNetwork(nameval, protoval).addDevice(devval)
                    }
                    if (err != null) msg = err
                    else {
                        runCatching { page.model.load(full = false) }
                        onCreated(nameval)
                    }
                } catch (e: Exception) { msg = e.message ?: e.toString() }
                busy = false
            }
        },
    ) {
        SectionBody(form.rt, form.section, "_new_")
    }
}

// ---------------------------------------------------------------- devices

/** s.cfgsections of the Devices grid: device sections plus unconfigured devices ("dev:<name>"). */
private fun deviceRows(page: IfPage): List<String> {
    val uci = page.ctx.uci
    val sections = uci.sections("network", "device").sortedWith { a, b -> naturalCompare(a["name"] as? String, b["name"] as? String) }
    val ids = sections.map { it[".name"] as String }.toMutableList()
    for (nd in page.model.devices) {
        if (sections.any { it["name"] == nd.getName() }) continue
        if (nd.getType() == "wifi" && !nd.isUp()) continue
        val m = if (nd.isBridge()) Regex("^br-([A-Za-z0-9_]+)$").find(nd.getName()) else null
        val s = m?.let { uci.section("network", it.groupValues[1]) }
        if (s != null && s[".type"] == "interface" && s["type"] == "bridge") continue
        ids += "dev:${nd.getName()}"
    }
    return ids
}

@Composable
private fun DevicesTab(page: IfPage, onEdit: (String, Boolean) -> Unit) {
    val ctx = page.ctx
    val scope = rememberCoroutineScope()
    page.model.version
    ctx.uci.revision.intValue
    val ro = ctx.readonly
    val rows = deviceRows(page)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (rows.isEmpty()) EmptyState("This section contains no values yet")
        rows.forEach { sid ->
            val ext = sid.startsWith("dev:")
            val dev = devOfSection(page, sid)
            val uciMac = ctx.uci.getString("network", sid, "macaddr")
            val uciMtu = ctx.uci.getString("network", sid, "mtu")
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.alpha(if (ext) 0.5f else 1f)) { DeviceGlyph(dev?.getType() ?: "ethernet", dev?.isUp() == true, Modifier.size(22.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            dev?.getName() ?: ctx.uci.getString("network", sid, "name") ?: "?",
                            style = MaterialTheme.typography.titleSmall, modifier = Modifier.alpha(if (ext) 0.5f else 1f),
                        )
                        Text(devTypeDesc(page, sid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontStyle = if (devTypeOf(page, sid) == null) FontStyle.Italic else FontStyle.Normal)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("MAC Address", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(uciMac?.uppercase() ?: dev?.getMAC() ?: "-", style = MaterialTheme.typography.bodyMedium, fontWeight = if (uciMac != null) FontWeight.Bold else FontWeight.Normal)
                    }
                    Column(Modifier.weight(0.6f)) {
                        Text("MTU", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(uciMtu ?: dev?.getMTU()?.toString() ?: "-", style = MaterialTheme.typography.bodyMedium, fontWeight = if (uciMtu != null) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                if (uciMac != null || uciMtu != null) {
                    Text("The value is overridden by configuration.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                    TextButton(onClick = { onEdit(sid, false) }) { Text("Configure…") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (!ctx.dialogs.ask("Unconfigure", "Remove related device settings from the configuration", confirmLabel = "Unconfigure", destructive = true)) return@launch
                                try {
                                    val name = ctx.uci.getString("network", sid, "name")
                                    val type = ctx.uci.getString("network", sid, "type")
                                    if (name != null && type == "bridge") {
                                        ctx.uci.sections("network", "bridge-vlan").filter { it["device"] == name }.forEach { ctx.uci.remove("network", it[".name"] as String) }
                                    }
                                    ctx.uci.remove("network", sid)
                                    page.saveMap()?.let { ctx.toast(it) }
                                    runCatching { page.model.load(full = false) }
                                } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) }
                            }
                        },
                        enabled = !ro && !ext,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Unconfigure") }
                }
            }
        }
        if (!ro) {
            FilledTonalButton(onClick = {
                onEdit(ctx.uci.add("network", "device"), true)
            }, modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Add device configuration…")
            }
        }
    }
}

// ---------------------------------------------------------------- migration

@Composable
private fun MigrationView(page: IfPage, kind: Migration) {
    val ctx = page.ctx
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    PageColumn {
        SectionCard(title = if (kind == Migration.Bridge) "Network bridge configuration migration" else "Network ifname configuration migration") {
            Text("The existing network configuration needs to be changed for LuCI to function properly.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                if (kind == Migration.Bridge) "Upon pressing \"Continue\", bridges configuration will be updated and the network will be restarted to apply the updated configuration."
                else "Upon pressing \"Continue\", ifname options will get renamed and the network will be restarted to apply the updated configuration.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = {
                    scope.launch {
                        running = true
                        try {
                            runMigration(ctx, kind)
                            ctx.session.changes.refresh()
                            ctx.session.changes.apply(checked = false)
                        } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) }
                        running = false
                    }
                }, enabled = !running && !ctx.readonly) { Text("Continue") }
            }
        }
    }
}

private suspend fun runMigration(ctx: PageContext, kind: Migration) {
    val uci = ctx.uci
    if (kind == Migration.Bridge) {
        uci.sections("network", "interface").filter { it["type"] == "bridge" && it["ports"] == null && it["ifname"] != null }.forEach { ns ->
            val deviceName = "br-${ns[".name"]}"
            val values = linkedMapOf<String, Any?>("name" to deviceName, "type" to "bridge", "ports" to UciStore.toArray(ns["ifname"]),
                "mtu" to ns["mtu"], "macaddr" to ns["macaddr"], "igmp_snooping" to ns["igmp_snooping"]).filterValues { it != null }
            ctx.call("uci", "add", jsonOf("config" to "network", "type" to "device", "values" to values))
            ctx.call("uci", "set", jsonOf("config" to "network", "section" to ns[".name"], "values" to mapOf(
                "type" to "", "ifname" to "", "mtu" to "", "macaddr" to "", "igmp_snooping" to "", "device" to deviceName)))
        }
    } else {
        uci.sections("network", "device").filter { it["type"] == "bridge" && it["ports"] == null && it["ifname"] != null }.forEach { ds ->
            ctx.call("uci", "set", jsonOf("config" to "network", "section" to ds[".name"], "values" to mapOf("ifname" to "", "ports" to UciStore.toArray(ds["ifname"]))))
        }
        uci.sections("network", "interface").filter { it["device"] == null && it["ifname"] != null }.forEach { ns ->
            ctx.call("uci", "set", jsonOf("config" to "network", "section" to ns[".name"], "values" to mapOf("ifname" to "", "device" to ns["ifname"])))
        }
    }
}
