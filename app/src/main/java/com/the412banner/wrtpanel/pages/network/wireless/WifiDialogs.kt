@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.the412banner.wrtpanel.pages.network.wireless

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.the412banner.wrtpanel.core.double
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormRuntime
import com.the412banner.wrtpanel.form.SectionBody
import com.the412banner.wrtpanel.form.SelectField
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.SectionCard
import com.the412banner.wrtpanel.ui.components.htmlToAnnotated
import com.the412banner.wrtpanel.ui.components.stripHtml
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** A full-screen modal with a top bar (LuCI's cbi-modal). */
@Composable
internal fun FullDialog(
    title: String,
    onDismiss: () -> Unit,
    navIcon: @Composable () -> Unit = { IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Dismiss") } },
    actions: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = navIcon,
                    actions = { actions() },
                )
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 920.dp).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp)) {
                    content()
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }
}

@Composable
private fun DialogError(text: String?) {
    if (text == null) return
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

/** The network a modal edits; [added] = created by "Add" (removed again on Dismiss). */
internal data class EditTarget(val sid: String, val added: Boolean)

internal class QrData(val text: String)

/** Opens the edit modal (renderMoreOptionsModal) for [target]. */
@Composable
internal fun WifiEditHost(ctx: PageContext, target: EditTarget, snap: State<WifiSnapshot>, onClose: (saved: Boolean) -> Unit) {
    val qr = remember(target) { mutableStateOf<QrData?>(null) }
    var form by remember(target) { mutableStateOf<Pair<WifiEditForm, FormRuntime>?>(null) }
    var error by remember(target) { mutableStateOf<String?>(null) }
    val hooks = remember(target) {
        EditHooks(
            liveNet = { snap.value.nets.firstOrNull { it.sid == target.sid } },
            updown = { rt ->
                networkUpdownUci(ctx.uci, target.sid)
                val err = try { rt.save() } catch (e: Exception) { e.message ?: e.toString() }
                if (err != null) error = err
                else { onClose(true); ctx.scope.launch { ctx.session.changes.apply(checked = false) } }
            },
            showQr = { rt, s -> showQr(rt, s, qr) { error = it } },
        )
    }
    LaunchedEffect(target) {
        try {
            val f = ctx.buildWifiEdit(snap.value, target.sid, hooks)
            if (ctx.readonly) f.map.readonly = true
            ctx.uci.load(f.map.configs)
            val rt = FormRuntime(f.map, ctx)
            for (o in f.map.sections.flatMap { it.allOptions() }) o.loadFn?.invoke(ctx, rt)
            form = f to rt
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ctx.toast(e.message ?: e.toString())
            onClose(false)
        }
    }
    val f = form
    if (f == null) {
        FullDialog("Loading data…", onDismiss = { onClose(false) }) { LoadingBox() }
        return
    }
    val (ef, rt) = f
    val q = qr.value
    if (q != null) {
        QrView(ef.title, q, onBack = { qr.value = null })
        return
    }
    val scope = rememberCoroutineScope()
    var busy by remember(target) { mutableStateOf(false) }
    val ro = ef.map.readonly || ctx.readonly
    FullDialog(
        ef.title,
        onDismiss = { onClose(false) },
        actions = {
            TextButton(onClick = {
                scope.launch {
                    busy = true
                    val err = try { rt.save() } catch (e: Exception) { e.message ?: e.toString() }
                    busy = false
                    if (err != null) error = err else onClose(true)
                }
            }, enabled = !ro && !busy) { Text("Save") }
        },
    ) {
        DialogError(error)
        SectionCard(title = ef.device.title) { SectionBody(rt, ef.device, ef.radio, modal = true) }
        Spacer(Modifier.height(14.dp))
        SectionCard(title = ef.iface.title) { SectionBody(rt, ef.iface, ef.sid, modal = true) }
    }
}

/** handleGenerateQR(): saves the modal silently, then shows the code. */
private suspend fun showQr(rt: FormRuntime, sid: String, qr: MutableState<QrData?>, onError: (String) -> Unit) {
    val err = try { rt.save() } catch (e: Exception) { e.message ?: e.toString() }
    if (err != null) { onError(err); return }
    val opt = { name: String -> rt.map.sections.flatMap { it.options }.firstOrNull { it.option == name && it.section.sectionName == sid } }
    val value = { name: String -> opt(name)?.let { rt.formString(it, sid) } ?: "" }
    qr.value = QrData(wifiQrText(value("ssid"), value("encryption"), value("_wpa_key"), value("hidden") == "1"))
}

/** The "QR Code" DummyValue: a button with a small sample code. */
@Composable
internal fun QrButton(rt: FormRuntime, sid: String, hooks: EditHooks) {
    val scope = rememberCoroutineScope()
    val o = rt.map.sections.flatMap { it.options }.firstOrNull { it.option == "_qrops" }
    Column(Modifier.fillMaxWidth()) {
        Text("QR Code", style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { scope.launch { hooks.showQr(rt, sid) } }, modifier = Modifier.padding(top = 4.dp)) {
            QrImage("openwrt.org", "L", Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Generate QR…")
        }
        o?.description?.let { Text(htmlToAnnotated(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun QrView(title: String, q: QrData, onBack: () -> Unit) {
    var ecc by remember(q) { mutableStateOf("L") }
    FullDialog(
        "$title » Generate WiFi QR…",
        onDismiss = onBack,
        navIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to settings") } },
    ) {
        Text("Scan this QR code with the client device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        SelectField(
            value = ecc, choices = listOf("L" to "Low", "M" to "Medium", "Q" to "Quartile", "H" to "High"),
            label = "QR Error Correction Code Level", onChange = { ecc = it },
        )
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { QrImage(q.text, ecc) }
        Spacer(Modifier.height(12.dp))
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            SelectionContainer { Text(q.text, style = com.the412banner.wrtpanel.ui.theme.CodeStyle, modifier = Modifier.padding(12.dp)) }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { OutlinedButton(onClick = onBack) { Text("Back to settings") } }
    }
}

// ---------------------------------------------------------------- scan

private class ScanEntry(val data: JsonElement, var stale: Boolean)

/** s.handleScan(): the scan results of a radio, refreshed with the poll interval. */
@Composable
internal fun ScanDialog(ctx: PageContext, radio: WifiRadio, onDismiss: () -> Unit, onJoin: (JsonElement) -> Unit) {
    val cache = remember { LinkedHashMap<String, ScanEntry>() }
    var rows by remember { mutableStateOf<List<Pair<JsonElement, Boolean>>?>(null) }
    var refresh by remember { mutableStateOf(true) }
    var waiting by remember { mutableStateOf(true) }
    val interval = (ctx.client.env?.pollInterval ?: 5) * 1000L
    LaunchedEffect(refresh) {
        while (refresh) {
            val results = try {
                ctx.call("iwinfo", "scan", jsonOf("device" to radio.name))["results"]?.let { it as? kotlinx.serialization.json.JsonArray }?.toList() ?: emptyList()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { emptyList() }
            val fresh = results.map { ScanEntry(it, false) }
            fresh.forEach { e -> cache[e.data["bssid"].str ?: "undefined"] = e }
            val list = fresh + cache.values.filter { it.stale }
            val sorted = list.sortedWith { a, b ->
                val qa = a.data["quality"].double; val qb = b.data["quality"].double
                val ca = a.data["channel"].double; val cb = b.data["channel"].double
                val dq = if (qa != null && qb != null) qb - qa else 0.0
                val dc = if (ca != null && cb != null) ca - cb else 0.0
                when {
                    dq != 0.0 -> dq.compareTo(0.0)
                    dc != 0.0 -> dc.compareTo(0.0)
                    else -> {
                        val sa = a.data["ssid"].str ?: ""; val sb = b.data["ssid"].str ?: ""
                        if (sa != sb) sa.compareTo(sb) else (a.data["bssid"].str ?: "").compareTo(b.data["bssid"].str ?: "")
                    }
                }
            }
            rows = sorted.map { it.data to it.stale }
            sorted.forEach { it.stale = true }
            waiting = false
            delay(interval)
        }
    }
    FullDialog("Join Network: Wireless Scan", onDismiss = onDismiss) {
        val list = rows
        when {
            list == null -> LoadingBox(text = "Starting wireless scan...")
            list.isEmpty() -> Text("No data", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { (res, stale) -> ScanRow(res, stale, onJoin) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            if (rows != null) {
                OutlinedButton(onClick = { if (refresh) refresh = false else { waiting = true; refresh = true } }, enabled = !(refresh && waiting)) {
                    if (refresh && waiting) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (refresh) "Stop refresh" else "Start refresh")
                }
            }
            OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

@Composable
private fun ScanRow(res: JsonElement, stale: Boolean, onJoin: (JsonElement) -> Unit) {
    val qv = res["quality"].double ?: 0.0
    val qm = res["quality_max"].double ?: 0.0
    val q = if (qv > 0 && qm > 0) Math.floor((100 / qm) * qv).toInt() else 0
    val ssid = res["ssid"].str?.takeIf { it.isNotEmpty() }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().alpha(if (stale) 0.5f else 1f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SignalBadge(signalInfo(q, res["signal"].double, res["noise"].double), wrap = true, modifier = Modifier.width(72.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (ssid != null) Text(ssid, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                else Text("hidden", style = MaterialTheme.typography.titleSmall, fontStyle = FontStyle.Italic)
                Text(
                    itemList(
                        listOf(
                            Item("Channel", res["channel"].str ?: "undefined"),
                            Item("Mode", res["mode"].str ?: "undefined"),
                            Item("BSSID", res["bssid"].str ?: "undefined"),
                            Item("Encryption", formatWifiEncryption(res["encryption"]) ?: "null"),
                        ),
                        listOf(" | ", "\n", "\n"),
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onJoin(res) }) { Text("Join Network") }
        }
    }
}

// ---------------------------------------------------------------- join

/** s.handleJoin(): the "Joining Network" form (virtual section `_new_`). */
internal class JoinForm(val rt: FormRuntime, val radio: WifiRadio, val bss: JsonElement)

internal fun PageContext.buildJoinForm(st: WifiState, radio: WifiRadio, bss: JsonElement): JoinForm {
    val enc = bss["encryption"] as? kotlinx.serialization.json.JsonObject
    val isWep = enc?.get("wep") is kotlinx.serialization.json.JsonArray
    val auth = com.the412banner.wrtpanel.core.UciStore.toArray(enc?.get("authentication")?.let { e -> (e as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.str } ?: e.str })
    val isPsk = enc?.get("wpa") is kotlinx.serialization.json.JsonArray && auth.any { it == "psk" || it == "sae" }
    val ssid = bss["ssid"].str

    fun nameUsed(name: String): Boolean {
        val s = uci.section("network", name)
        if (s != null && s[".type"] != "interface") return true
        return s != null && !st.networkIsEmpty(name)
    }

    val m = FormMap("wireless")
    m.virtual("_new_") {
        if (ssid == null) value("ssid", "Network SSID", "The correct SSID must be manually specified when joining a hidden wireless network") { rmempty = false }
        flag("replace", "Replace wireless configuration", "Check this option to delete the existing networks from this radio.")
        value(
            "name", "Name of the new network",
            "Name for OpenWrt network configuration. (No relation to wireless network name/SSID)" + "<br />" +
                "The allowed characters are: <code>A-Z</code>, <code>a-z</code>, <code>0-9</code> and <code>_</code>",
        ) {
            datatype = "uciname"; rmempty = false
            var d = "wwan"
            var i = 2
            while (nameUsed(d)) d = "wwan%d".format(i++)
            default = d
            validate = { _, _, v -> if (nameUsed(v)) "The network name is already used" else null }
        }
        if (isWep || isPsk) {
            value("password", if (isWep) "WEP passphrase" else "WPA passphrase", "Specify the secret encryption key here.") {
                datatype = if (isWep) "wepkey" else "wpakey"; password = true; rmempty = false
            }
        }
        if (ssid != null) {
            flag("bssid", "Lock to BSSID", "Instead of joining any network with a matching SSID, only connect to the BSSID <code>%s</code>.".format(bss["bssid"].str ?: "")) {
                default = "0"
            }
        }
        value(
            "zone", "Create / Assign firewall-zone",
            "Choose the firewall zone you want to assign to this interface. Select <em>unspecified</em> to remove the interface from the associated zone or fill out the <em>custom</em> field to define a new zone and attach the interface to it.",
        ) {
            default = "wan"
            value("", "unspecified")
            uci.sections("firewall", "zone").forEach { z ->
                val n = z["name"] as? String ?: return@forEach
                val nets = com.the412banner.wrtpanel.core.UciStore.toArray(z["network"])
                value(n, if (nets.isEmpty()) n else "$n: ${nets.joinToString(", ")}")
            }
        }
    }
    return JoinForm(FormRuntime(m, this), radio, bss)
}

@Composable
internal fun JoinDialog(ctx: PageContext, form: JoinForm, onCancel: () -> Unit, onSubmit: suspend (JoinForm) -> String?) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val section = form.rt.map.sections.first()
    FullDialog("Joining Network: \"${form.bss["ssid"].str ?: ""}\"", onDismiss = onCancel) {
        DialogError(error)
        SectionBody(form.rt, section, "_new_", modal = true)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            OutlinedButton(onClick = onCancel, enabled = !busy) { Text("Cancel") }
            Button(onClick = {
                scope.launch {
                    busy = true
                    error = try { onSubmit(form) } catch (e: Exception) { e.message ?: e.toString() }
                    busy = false
                }
            }, enabled = !busy && !ctx.readonly) { Text("Submit") }
        }
    }
}

/** s.handleJoinConfirm(): creates the client network, its interface and zone, stages it; returns the new section or an error. */
internal suspend fun PageContext.joinConfirm(st: WifiState, form: JoinForm): Pair<String?, String?> {
    val rt = form.rt
    val opts = rt.map.sections.first().options
    fun opt(n: String) = opts.firstOrNull { it.option == n }
    fun validValue(n: String): String? = opt(n)?.let { o -> if (rt.validateField(o, "_new_") == null) rt.formString(o, "_new_") else null }
    val nameopt = opt("name")!!
    val passopt = opt("password")
    val nameval = validValue("name")
    val passval = validValue("password")
    val ssidval = validValue("ssid")
    val bssidval = validValue("bssid")
    val zoneval = opt("zone")?.let { rt.formString(it, "_new_") }?.takeIf { it.isNotEmpty() }
    if (nameval == null || (passopt != null && passval == null)) {
        val o = if (nameval == null) nameopt else passopt!!
        return null to (rt.validateField(o, "_new_") ?: "Expecting: non-empty value").let { "${stripHtml(o.title)}: $it" }
    }
    val bss = form.bss
    val radio = form.radio.name
    val enc = bss["encryption"] as? kotlinx.serialization.json.JsonObject
    val wpa = enc?.get("wpa") as? kotlinx.serialization.json.JsonArray
    val auth = com.the412banner.wrtpanel.core.UciStore.toArray(enc?.get("authentication")?.let { e -> (e as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.str } ?: e.str })
    val isWep = enc?.get("wep") is kotlinx.serialization.json.JsonArray
    val isPsk = wpa != null && auth.any { it == "psk" }
    val isSae = wpa != null && auth.any { it == "sae" }

    val wifiSections = uci.sections("wireless", "wifi-iface")
    if (rt.formString(opt("replace")!!, "_new_") == "1") {
        for (ws in wifiSections) if (ws["device"] == radio) uci.remove("wireless", ws[".name"] as String)
    }
    if (uci.getString("wireless", radio, "disabled") == "1") {
        for (ws in wifiSections) if (ws["device"] == radio) uci.set("wireless", ws[".name"] as String, "disabled", "1")
        uci.unset("wireless", radio, "disabled")
    }
    val htmodes = form.radio.htModes
    val vht = bss["vht_operation"]
    val ht = bss["ht_operation"]
    if (jsTruthy(vht) && htmodes != null && htmodes.contains("VHT20")) {
        var w = vht["channel_width"].double ?: Double.NaN
        while (w >= 20) {
            if (htmodes.contains("VHT" + jsNum(w))) { uci.set("wireless", radio, "htmode", "VHT" + jsNum(w)); break }
            w /= 2
        }
    } else if (jsTruthy(ht) && htmodes != null && htmodes.contains("HT20")) {
        val w = if (ht["secondary_channel_offset"].str == "no secondary") 20 else 40
        uci.set("wireless", radio, "htmode", "HT$w")
    } else {
        uci.unset("wireless", radio, "htmode")
    }
    uci.set("wireless", radio, "channel", bss["channel"].str)

    val sid = nextFreeSid(uci, wifiSections.size)
    uci.add("wireless", "wifi-iface", sid)
    uci.set("wireless", sid, "device", radio)
    uci.set("wireless", sid, "mode", if (bss["mode"].str == "Ad-Hoc") "adhoc" else "sta")
    uci.set("wireless", sid, "network", nameval)
    val bssSsid = bss["ssid"].str
    val bssBssid = bss["bssid"].str
    if (bssSsid != null) {
        uci.set("wireless", sid, "ssid", bssSsid)
        if (bssidval == "1") uci.set("wireless", sid, "bssid", bssBssid)
    } else if (bssBssid != null) {
        uci.set("wireless", sid, "bssid", bssBssid)
    }
    if (ssidval != null) uci.set("wireless", sid, "ssid", ssidval)
    when {
        isSae -> { uci.set("wireless", sid, "encryption", "sae"); uci.set("wireless", sid, "key", passval) }
        isPsk -> {
            for (i in wpa!!.indices.reversed()) {
                val v = wpa[i].str
                if (v == "2") { uci.set("wireless", sid, "encryption", "psk2"); break }
                else if (v == "1") { uci.set("wireless", sid, "encryption", "psk"); break }
            }
            uci.set("wireless", sid, "key", passval)
        }
        isWep -> {
            uci.set("wireless", sid, "encryption", "wep-open")
            uci.set("wireless", sid, "key", "1")
            uci.set("wireless", sid, "key1", passval)
        }
        else -> uci.set("wireless", sid, "encryption", "none")
    }

    val net = addNetwork(st, nameval, mapOf("proto" to "dhcp"))
    if (net != null) {
        firewallDeleteNetwork(uci, net)
        if (zoneval != null) {
            val zone = lookupZone(uci, zoneval) ?: addZone(uci, zoneval)
            if (zone != null) zoneAddNetwork(uci, zone, net)
        }
    }
    uci.save()
    session.changes.afterSave()
    return sid to null
}
