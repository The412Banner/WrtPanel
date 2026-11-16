@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.the412banner.wrtpanel.pages.network.wireless

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.form.SaveBar
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.EmptyState
import com.the412banner.wrtpanel.ui.components.ErrorBox
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.SectionCard
import com.the412banner.wrtpanel.ui.components.htmlToAnnotated
import com.the412banner.wrtpanel.ui.theme.CodeStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private sealed interface WLoad {
    data object Loading : WLoad
    data class Ready(val snap: WifiSnapshot) : WLoad
    data class Failed(val message: String) : WLoad
}

/** Network › Wireless — port of luci-mod-network view/network/wireless.js. */
@Composable
fun WirelessPage(ctx: PageContext) {
    val generation by ctx.session.changes.generation.collectAsState()
    var tick by remember { mutableIntStateOf(0) }
    var state by remember(generation, tick) { mutableStateOf<WLoad>(WLoad.Loading) }
    LaunchedEffect(generation, tick) {
        state = WLoad.Loading
        state = try {
            ctx.uci.unloadAll()
            ctx.uci.load("wireless", "system", "network", "firewall", "luci")
            ctx.session.changes.refresh()
            WLoad.Ready(loadWifiSnapshot(ctx))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            WLoad.Failed(e.message ?: e.toString())
        }
    }
    when (val s = state) {
        is WLoad.Loading -> PageColumn { LoadingBox() }
        is WLoad.Failed -> PageColumn { ErrorBox(s.message) { tick++ } }
        is WLoad.Ready -> {
            if (ctx.uci.sections("wireless", "wifi-iface").any { it[".anonymous"] == true }) MigrationView(ctx)
            else Overview(ctx, s.snap)
        }
    }
}

/** renderMigration() / handleMigration() */
@Composable
private fun MigrationView(ctx: PageContext) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    PageColumn {
        SectionCard(title = "Wireless configuration migration") {
            Text("The existing wireless configuration needs to be changed for LuCI to function properly.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                htmlToAnnotated("Upon pressing \"Continue\", anonymous \"wifi-iface\" sections will be assigned with a name in the form <em>wifinet#</em> and the network will be restarted to apply the updated configuration."),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(onClick = {
                    scope.launch {
                        busy = true
                        try {
                            var offset = 0
                            for (iface in ctx.uci.sections("wireless", "wifi-iface")) {
                                if (iface[".anonymous"] != true) continue
                                val newName = nextFreeSid(ctx.uci, offset)
                                ctx.call("uci", "rename", jsonOf("config" to "wireless", "section" to iface[".name"], "name" to newName))
                                offset = newName.substring(7).toInt() + 1
                            }
                            ctx.session.changes.refresh()
                            ctx.scope.launch { ctx.session.changes.apply(checked = false) }
                        } catch (e: Exception) {
                            ctx.toast(e.message ?: e.toString())
                        }
                        busy = false
                    }
                }, enabled = !busy && !ctx.readonly) { Text("Continue") }
            }
        }
    }
}

@Composable
private fun Overview(ctx: PageContext, initial: WifiSnapshot) {
    val snap = remember(initial) { mutableStateOf(initial) }
    val restart = remember(initial) { mutableStateMapOf<String, Int>() }
    val busy = remember(initial) { mutableStateMapOf<String, Boolean>() }
    val disconnecting = remember(initial) { mutableStateMapOf<String, Boolean>() }
    var edit by remember(initial) { mutableStateOf<EditTarget?>(null) }
    var scan by remember(initial) { mutableStateOf<WifiRadio?>(null) }
    var join by remember(initial) { mutableStateOf<JoinForm?>(null) }
    val changes by ctx.session.changes.changes.collectAsState()
    val scope = rememberCoroutineScope()
    val ro = ctx.readonly
    var saving by remember { mutableStateOf(false) }

    suspend fun refresh() {
        try { snap.value = loadWifiSnapshot(ctx) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {}
    }

    LaunchedEffect(initial) {
        while (true) {
            delay(5000)
            for ((id, st) in restart.toMap()) {
                if (st == 0) {
                    restart[id] = 1
                    try { ctx.fs.exec("/sbin/wifi", listOf("up", id)) } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) }
                } else restart.remove(id)
            }
            refresh()
            disconnecting.clear()
        }
    }

    ctx.uci.revision.intValue
    val s = snap.value
    Box(Modifier.fillMaxSize()) {
        PageColumn {
            Text("Wireless Overview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 4.dp))
            if (s.radios.isEmpty()) SectionCard { EmptyState("This section contains no values yet") }
            s.radios.forEach { radio ->
                RadioCard(
                    ctx, radio, radio.networks(s.nets), restart, busy, changes["wireless"] ?: emptyList(),
                    onRestart = { restart[radio.name] = 0 },
                    onScan = { scan = radio },
                    onAdd = {
                        val sid = nextFreeSid(ctx.uci, ctx.uci.sections("wireless", "wifi-iface").size)
                        ctx.uci.unset("wireless", radio.name, "disabled")
                        ctx.uci.add("wireless", "wifi-iface", sid)
                        ctx.uci.set("wireless", sid, "device", radio.name)
                        ctx.uci.set("wireless", sid, "mode", "ap")
                        ctx.uci.set("wireless", sid, "ssid", "OpenWrt")
                        ctx.uci.set("wireless", sid, "encryption", "none")
                        edit = EditTarget(sid, added = true)
                    },
                    onToggle = { sid ->
                        scope.launch {
                            busy[sid] = true
                            try {
                                networkUpdownUci(ctx.uci, sid)
                                ctx.uci.save()
                                ctx.session.changes.afterSave()
                                ctx.scope.launch { ctx.session.changes.apply(checked = false) }
                            } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) }
                            busy.remove(sid)
                        }
                    },
                    onEdit = { sid -> edit = EditTarget(sid, added = false) },
                    onRemove = { sid ->
                        scope.launch {
                            if (!ctx.dialogs.ask("Remove", "Delete this network", confirmLabel = "Remove", destructive = true)) return@launch
                            busy[sid] = true
                            try {
                                ctx.uci.remove("wireless", sid)
                                ctx.uci.save()
                                ctx.session.changes.afterSave()
                                refresh()
                            } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) }
                            busy.remove(sid)
                        }
                    },
                )
            }
            StationsCard(ctx, s, disconnecting)
        }
        if (!ro) {
            SaveBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                busy = saving,
                showReset = false,
                onReset = {},
                onSave = { apply, unchecked ->
                    scope.launch {
                        saving = true
                        val err = try { ctx.uci.save(); ctx.session.changes.afterSave(); null } catch (e: Exception) { e.message ?: e.toString() }
                        saving = false
                        if (err != null) ctx.toast(err)
                        else if (apply) ctx.scope.launch { ctx.session.changes.apply(checked = !unchecked) }
                        else ctx.toast("Changes saved")
                    }
                },
            )
        }
    }

    edit?.let { target ->
        WifiEditHost(ctx, target, snap) { saved ->
            if (!saved && target.added) ctx.uci.remove("wireless", target.sid)
            edit = null
            if (saved) scope.launch { refresh() }
        }
    }
    scan?.let { radio ->
        ScanDialog(ctx, radio, onDismiss = { scan = null }, onJoin = { bss ->
            scan = null
            join = ctx.buildJoinForm(snap.value.st, radio, bss)
        })
    }
    join?.let { form ->
        JoinDialog(ctx, form, onCancel = { join = null }) { f ->
            val (sid, err) = ctx.joinConfirm(snap.value.st, f)
            if (err == null && sid != null) {
                refresh()
                edit = EditTarget(sid, added = false)
                join = null
            }
            err
        }
    }
}

@Composable
private fun RadioCard(
    ctx: PageContext,
    radio: WifiRadio,
    nets: List<WifiNet>,
    restart: Map<String, Int>,
    busy: Map<String, Boolean>,
    changes: List<List<String>>,
    onRestart: () -> Unit,
    onScan: () -> Unit,
    onAdd: () -> Unit,
    onToggle: (String) -> Unit,
    onEdit: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val ro = ctx.readonly
    val restarting = restart.containsKey(radio.name)
    val rowBusy = restarting || busy[radio.name] == true
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioBadge(radio)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(radio.i18n.replace(Regex(" Wireless Controller .+$"), ""), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(radioStatus(radio, nets, restarting), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = onRestart, enabled = !ro && !rowBusy) {
                if (restarting) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                Text("Restart")
            }
            Button(onClick = onScan, enabled = !ro && !rowBusy) { Text("Scan") }
            FilledTonalButton(onClick = onAdd, enabled = !ro && !rowBusy) { Text("Add") }
        }
        nets.forEach { net ->
            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            val pending = changes.count { it.getOrNull(1) == net.name }
            NetRow(ctx, net, pending, busy[net.name] == true, onToggle, onEdit, onRemove)
        }
    }
}

private fun radioStatus(radio: WifiRadio, nets: List<WifiNet>, restarting: Boolean) = buildAnnotatedString {
    val em = SpanStyle(fontStyle = FontStyle.Italic)
    when {
        restarting -> withStyle(em) { append("Device is restarting…") }
        radio.isUp -> {
            val channel = nets.firstNotNullOfOrNull { it.channel }
            val frequency = nets.firstNotNullOfOrNull { it.frequency }
            val bitrate = nets.firstNotNullOfOrNull { it.bitRate }
            append(itemList(listOf(Item("Channel", "${channel ?: "?"} (${frequency ?: "?"} GHz)"), Item("Bitrate", "${bitrate ?: "?"} Mbit/s")), listOf(" | ")))
        }
        else -> withStyle(em) { append("Device is not active") }
    }
}

@Composable
private fun RadioBadge(radio: WifiRadio) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (radio.isUp) Icons.Outlined.Wifi else Icons.Outlined.WifiOff, null,
                tint = if (radio.isUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Text(radio.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun NetRow(
    ctx: PageContext,
    net: WifiNet,
    pending: Int,
    busy: Boolean,
    onToggle: (String) -> Unit,
    onEdit: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    val ro = ctx.readonly
    val disabled = net.get("disabled") == "1" || net.wifiDeviceName?.let { ctx.uci.getString("wireless", it, "disabled") } == "1"
    Column(Modifier.fillMaxWidth().alpha(if (busy) 0.5f else 1f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalBadge(signalInfo(if (net.isUp) net.signalPercent else -1, net.signal, net.noise, net.mode), wrap = true, modifier = Modifier.width(84.dp))
            Spacer(Modifier.width(12.dp))
            SelectionContainer(Modifier.weight(1f)) { Text(networkStatus(net, pending), style = MaterialTheme.typography.bodyMedium) }
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = { onToggle(net.name) }, enabled = !ro && !busy) { Text(if (disabled) "Enable" else "Disable") }
            Button(onClick = { onEdit(net.name) }, enabled = !busy) { Text("Edit") }
            OutlinedButton(
                onClick = { onRemove(net.name) }, enabled = !ro && !busy,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Remove") }
        }
    }
}

// ---------------------------------------------------------------- associated stations

private fun zoneColor(name: String?): Color {
    if (name == null) return Color(0xFFEEEEEE)
    if (name == "lan") return Color(0xFF90F090)
    if (name == "wan") return Color(0xFFF09090)
    val h = (name.hashCode() and 0x7fffffff) % 360
    return Color.hsl(h.toFloat(), 0.6f, 0.75f)
}

@Composable
private fun StationsCard(ctx: PageContext, snap: WifiSnapshot, disconnecting: MutableMap<String, Boolean>) {
    val scope = rememberCoroutineScope()
    SectionCard(title = "Associated Stations") {
        if (snap.stations.isEmpty()) EmptyState("No information available")
        else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val wide = maxWidth >= 640.dp
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (wide) 0.dp else 8.dp)) {
                if (wide) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        listOf("Network" to 1.5f, "MAC address" to 1.1f, "Host" to 1.2f, "Signal / Noise" to 1f, "RX Rate / TX Rate" to 1.8f).forEach { (h, w) ->
                            Text(h, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(w).padding(end = 8.dp))
                        }
                        if (snap.stations.any { it.net.isClientDisconnectSupported }) Spacer(Modifier.width(112.dp))
                    }
                    HorizontalDivider()
                }
                snap.stations.forEachIndexed { i, bss ->
                    val key = "${bss.net.ifname}|${bss.mac}"
                    val onDisconnect: (() -> Unit)? = if (bss.net.isClientDisconnectSupported) {
                        {
                            disconnecting[key] = true
                            scope.launch {
                                try {
                                    ctx.call(
                                        "hostapd.${bss.net.ifname}", "del_client",
                                        jsonOf("addr" to bss.mac, "deauth" to true, "reason" to 5, "ban_time" to 60000),
                                    )
                                } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) }
                            }
                        }
                    } else null
                    StationRow(ctx, snap, bss, wide, disconnecting[key] == true, onDisconnect)
                    if (wide && i < snap.stations.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

private fun hostHint(st: WifiState, mac: String): String {
    val name = st.hostName(mac)
    val ipv4 = st.hostIp4(mac)
    val ipv6 = st.hostIp6(mac)
    return when {
        name != null && ipv4 != null && ipv6 != null -> "$name ($ipv4, $ipv6)"
        name != null && (ipv4 ?: ipv6) != null -> "$name (${ipv4 ?: ipv6})"
        else -> name ?: ipv4 ?: ipv6 ?: "?"
    }
}

@Composable
private fun StationNetwork(snap: WifiSnapshot, bss: Station) {
    FlowRow(verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        bss.vlan?.let { vlan ->
            val zone = vlan.network?.let { n -> snap.st.uci.sections("firewall", "zone").firstOrNull { UciStore.toArray(it["network"]).contains(n) }?.get("name") as? String }
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(zoneColor(zone)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(vlan.i18n, style = MaterialTheme.typography.labelSmall, color = Color.Black)
            }
        }
        Icon(
            if (bss.net.isUp) Icons.Outlined.Wifi else Icons.Outlined.WifiOff, bss.radio?.i18n,
            tint = if (bss.net.isUp) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp),
        )
        val small = MaterialTheme.typography.labelSmall.toSpanStyle()
        Text(
            buildAnnotatedString {
                append(bss.net.shortName)
                append(" ")
                withStyle(small) { append("(${bss.net.ifname})") }
            },
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun StationRow(ctx: PageContext, snap: WifiSnapshot, bss: Station, wide: Boolean, disconnecting: Boolean, onDisconnect: (() -> Unit)?) {
    val sig = signalInfo(minOf((bss.signal + 110) / 70 * 100, 100.0).toInt(), bss.signal, bss.noise)
    val rates = "${formatWifiRate(bss.data["rx"])}\n${formatWifiRate(bss.data["tx"])}"
    val hint = hostHint(snap.st, bss.mac)
    val button: @Composable () -> Unit = {
        if (onDisconnect != null) {
            TextButton(onClick = onDisconnect, enabled = !ctx.readonly && !disconnecting, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                if (disconnecting) { CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                Text("Disconnect")
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).alpha(if (disconnecting) 0.5f else 1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1.5f).padding(end = 8.dp)) { StationNetwork(snap, bss) }
            SelectionContainer(Modifier.weight(1.1f).padding(end = 8.dp)) { Text(bss.mac, style = CodeStyle) }
            SelectionContainer(Modifier.weight(1.2f).padding(end = 8.dp)) { Text(hint, style = MaterialTheme.typography.bodyMedium) }
            Box(Modifier.weight(1f).padding(end = 8.dp)) { SignalBadge(sig) }
            Text(rates, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.8f).padding(end = 8.dp))
            if (snap.stations.any { it.net.isClientDisconnectSupported }) Box(Modifier.width(112.dp), contentAlignment = Alignment.CenterEnd) { button() }
        }
    } else {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().alpha(if (disconnecting) 0.5f else 1f)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { StationNetwork(snap, bss) }
                    button()
                }
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SignalBadge(sig)
                    Spacer(Modifier.width(12.dp))
                    SelectionContainer { Text(bss.mac, style = CodeStyle) }
                }
                SelectionContainer { Text(hint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                Text(rates, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
