package com.the412banner.wrtpanel.pages.network.diag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.form.NetData
import com.the412banner.wrtpanel.form.SelectField
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.CodeBlock
import com.the412banner.wrtpanel.ui.components.ErrorBox
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.SectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private class DiagLoad(
    val hasPing6: Boolean,
    val hasTraceroute6: Boolean,
    val hasArpscan: Boolean,
    val devices: List<Pair<String, String>>,
    val dnsHost: String,
    val pingHost: String,
    val routeHost: String,
)

/** network.js iface_patterns_ignore */
private val ignoredIfnames = listOf(
    "^wmaster\\d+", "^wifi\\d+", "^hwsim\\d+", "^imq\\d+", "^ifb\\d+", "^mon\\.wlan\\d+", "^sit\\d+",
    "^gre\\d+", "^gretap\\d+", "^ip6gre\\d+", "^ip6tnl\\d+", "^tunl\\d+", "^lo$",
).map { Regex(it) }

/** Device.getTypeI18n() of network.js. */
private fun typeI18n(type: String): String = when (type) {
    "alias" -> "Alias Interface"
    "wifi" -> "Wireless Adapter"
    "bridge" -> "Bridge"
    "vrf" -> "Virtual Routing and Forwarding (VRF)"
    "dsa" -> "Switch port"
    "vlan" -> "Software VLAN"
    "wireguard" -> "WireGuard Interface"
    "tunnel" -> "Tunnel Interface"
    else -> "Ethernet Adapter"
}

/** fs.exec_direct(command, params, 'text', false, true, responseProgress): output as it arrives. */
private suspend fun execDirectProgress(ctx: PageContext, command: String, params: List<String>, onProgress: (String) -> Unit): String {
    val esc = { s: String -> s.replace("\\", "\\\\").replace(Regex("(\\s)"), "\\\\$1") }
    val cmd = (listOf(esc(command)) + params.map(esc)).joinToString(" ")
    val (resp, stream) = ctx.client.cgiStream("cgi-exec", mapOf("command" to cmd, "stderr" to "1"))
    val handle = currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { resp.close() } }
    try {
        return withContext(Dispatchers.IO) {
            val acc = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            stream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    acc.write(buf, 0, n)
                    onProgress(acc.toString("UTF-8"))
                }
            }
            acc.toString("UTF-8")
        }
    } finally {
        handle?.dispose()
        runCatching { resp.close() }
    }
}

/** Network › Diagnostics — port of luci-mod-network view/network/diagnostics.js. */
@Composable
fun DiagnosticsPage(ctx: PageContext) {
    var data by remember { mutableStateOf<DiagLoad?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(tick) {
        error = null
        try {
            data = coroutineScope {
                val ping6 = async { runCatching { ctx.fs.stat("/bin/ping6") != null }.getOrDefault(false) }
                val traceroute6 = async { runCatching { ctx.fs.stat("/bin/traceroute6") != null }.getOrDefault(false) }
                val arpscan = async { runCatching { ctx.fs.stat("/usr/bin/arp-scan") != null }.getOrDefault(false) }
                val devices = async { NetData.devices(ctx) }
                ctx.uci.load("luci")
                DiagLoad(
                    hasPing6 = ping6.await(),
                    hasTraceroute6 = traceroute6.await(),
                    hasArpscan = arpscan.await(),
                    devices = devices.await()
                        .filter { d -> d.up && ignoredIfnames.none { it.containsMatchIn(d.name) } }
                        .map { d -> d.name to "${typeI18n(d.type)}: \"${d.name}\"" },
                    dnsHost = ctx.uci.getString("luci", "diag", "dns")?.ifEmpty { null } ?: "openwrt.org",
                    pingHost = ctx.uci.getString("luci", "diag", "ping")?.ifEmpty { null } ?: "openwrt.org",
                    routeHost = ctx.uci.getString("luci", "diag", "route")?.ifEmpty { null } ?: "openwrt.org",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }

    PageColumn {
        PageHeader("Diagnostics", "Execution of various network commands to check the connection and name resolution to other systems.")
        val d = data
        when {
            d == null && error != null -> ErrorBox(error!!) { tick++ }
            d == null -> LoadingBox()
            else -> DiagnosticsView(ctx, d)
        }
    }
}

@Composable
private fun DiagnosticsView(ctx: PageContext, d: DiagLoad) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var pingAddr by remember { mutableStateOf(d.pingHost) }
    var routeAddr by remember { mutableStateOf(d.routeHost) }
    var dnsAddr by remember { mutableStateOf(d.dnsHost) }
    var pingCmd by remember { mutableStateOf("ping") }
    var traceCmd by remember { mutableStateOf("traceroute") }
    var arpDev by remember { mutableStateOf(d.devices.firstOrNull()?.first ?: "") }

    fun handleCommand(exec: String, args: List<String>) {
        if (running) return
        running = true
        scope.launch {
            try {
                output = execDirectProgress(ctx, exec, args) { output = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.toast(e.message ?: e.toString())
            } finally {
                running = false
            }
        }
    }

    fun handlePing(cmd: String?, addr: String) {
        val exec = cmd ?: "ping"
        handleCommand(exec, if (exec == "ping") listOf("-4", "-c", "5", "-W", "1", addr) else listOf("-6", "-c", "5", addr))
    }

    fun handleTraceroute(cmd: String?, addr: String) {
        val exec = cmd ?: "traceroute"
        // String(L.env.rpctimeout || 20): LuCI's header never sets rpctimeout.
        handleCommand(exec, if (exec == "traceroute") listOf("-4", "-q", "1", "-w", "1", "-n", "-m", "20", addr) else listOf("-q", "1", "-w", "2", "-n", addr))
    }

    SectionCard {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToolRow(pingAddr, { pingAddr = it }) {
                if (d.hasPing6) {
                    ComboAction(
                        choices = listOf("ping" to "IPv4 Ping", "ping6" to "IPv6 Ping"),
                        selected = pingCmd, onSelect = { pingCmd = it }, enabled = !running,
                        onClick = { handlePing(it, pingAddr) },
                    )
                } else {
                    FilledTonalButton(onClick = { handlePing(null, pingAddr) }, enabled = !running) { Text("Ping") }
                }
            }
            ToolRow(routeAddr, { routeAddr = it }) {
                if (d.hasTraceroute6) {
                    ComboAction(
                        choices = listOf("traceroute" to "IPv4 Traceroute", "traceroute6" to "IPv6 Traceroute"),
                        selected = traceCmd, onSelect = { traceCmd = it }, enabled = !running,
                        onClick = { handleTraceroute(it, routeAddr) },
                    )
                } else {
                    FilledTonalButton(onClick = { handleTraceroute(null, routeAddr) }, enabled = !running) { Text("Traceroute") }
                }
            }
            ToolRow(dnsAddr, { dnsAddr = it }) {
                FilledTonalButton(onClick = { handleCommand("nslookup", listOf(dnsAddr)) }, enabled = !running) { Text("Nslookup") }
            }
            if (d.hasArpscan) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        SelectField(value = arpDev, choices = d.devices, label = null, onChange = { arpDev = it })
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { handleCommand("arp-scan", listOf("-l", "-I", arpDev)) }, enabled = !running) { Text("Arp-scan") }
                }
            }
        }
    }

    SectionCard {
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        CodeBlock(output, modifier = Modifier.heightIn(min = 320.dp), maxHeight = 560.dp)
    }
}

@Composable
private fun ToolRow(value: String, onValue: (String) -> Unit, action: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value, onValueChange = onValue, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        action()
    }
}

/** ui.ComboButton: runs the selected action; the arrow only changes the selection. */
@Composable
private fun ComboAction(
    choices: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    enabled: Boolean,
    onClick: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            onClick = { onClick(selected) }, enabled = enabled,
            shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp, topEnd = 4.dp, bottomEnd = 4.dp),
        ) { Text(choices.firstOrNull { it.first == selected }?.second ?: selected) }
        Spacer(Modifier.width(2.dp))
        Box {
            Button(
                onClick = { open = true }, enabled = enabled,
                contentPadding = PaddingValues(horizontal = 6.dp),
                shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 20.dp, bottomEnd = 20.dp),
            ) { Icon(Icons.Outlined.ArrowDropDown, "More") }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                choices.forEach { (k, l) ->
                    DropdownMenuItem(
                        text = { Text(l) },
                        leadingIcon = { if (k == selected) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.width(24.dp)) },
                        onClick = { onSelect(k); open = false },
                    )
                }
            }
        }
    }
}
