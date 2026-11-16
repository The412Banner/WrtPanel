package com.the412banner.wrtpanel.pages.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.long
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormPage
import com.the412banner.wrtpanel.form.networkSelect
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.rememberPolled
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private fun formatTime(uci: UciStore, epoch: Long): String {
    val zn = uci.getString("system", "@system[0]", "zonename")?.replace(' ', '_') ?: "UTC"
    val full = (uci.getString("system", "@system[0]", "clock_timestyle") ?: "0") != "0"
    val hc = uci.getString("system", "@system[0]", "clock_hourcycle")
    val zone = runCatching { ZoneId.of(zn) }.getOrDefault(ZoneId.of("UTC"))
    val t = Instant.ofEpochSecond(epoch).atZone(zone)
    val pattern = when (hc) { "h12" -> "MMM d, yyyy, h:mm:ss a"; "h23" -> "MMM d, yyyy, HH:mm:ss"; else -> null }
    val base = if (pattern != null) DateTimeFormatter.ofPattern(pattern) else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
    return t.format(base) + " " + t.format(DateTimeFormatter.ofPattern(if (full) "zzzz" else "O"))
}

/** System › System — port of luci-mod-system view/system/system.js. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SystemPage(ctx: PageContext) = FormPage(ctx) {
    val rcEntry = callOrNull("rc", "list", jsonOf("name" to "sysntpd")).obj?.values?.firstOrNull()
    var ntpdEnabled = if (rcEntry["enabled"].bool == true) 1 else 0
    val timezones = callOrNull("luci", "getTimezones").obj ?: kotlinx.serialization.json.JsonObject(emptyMap())
    uci.load("luci", "system")

    val m = FormMap("system", "System", "Here you can configure the basic aspects of your device like its hostname or the timezone.")
    m.chain("luci")

    m.typed("system", "System Properties") {
        anonymous = true; addremove = false
        tab("general", "General Settings")
        tab("logging", "Logging")
        tab("timesync", "Time Synchronization")
        tab("language", "Language and Style")

        dummy("_systime", "Local Time", tab = "general") {
            render = { rt, _ ->
                val scope = rememberCoroutineScope()
                val time by rememberPolled(intervalMs = (rt.ctx.client.env?.pollInterval ?: 5) * 1000L) {
                    rt.ctx.call("luci", "getUnixtime")["result"].long ?: 0L
                }
                Column(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = time.value?.let { formatTime(rt.uci, it) } ?: "…", onValueChange = {}, readOnly = true,
                        label = { Text("Local Time") }, modifier = Modifier.fillMaxWidth(),
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        FilledTonalButton(onClick = {
                            scope.launch {
                                try { rt.ctx.call("luci", "setLocaltime", jsonOf("localtime" to System.currentTimeMillis() / 1000)); rt.ctx.toast("Time synchronized with this phone") }
                                catch (e: Exception) { rt.ctx.toast(e.message ?: "Failed") }
                            }
                        }, enabled = !rt.isDisabled(this@dummy, "")) { Text("Sync with browser") }
                        if (ntpdEnabled == 1) FilledTonalButton(onClick = {
                            scope.launch {
                                try { rt.ctx.call("rc", "init", jsonOf("name" to "sysntpd", "action" to "restart")); rt.ctx.toast("NTP sync started") }
                                catch (e: Exception) { rt.ctx.toast(e.message ?: "Failed") }
                            }
                        }, enabled = !rt.isDisabled(this@dummy, "")) { Text("Sync with NTP-Server") }
                    }
                }
            }
        }
        value("hostname", "Hostname", tab = "general") { datatype = "hostname" }
        value("description", "Description", "An optional, short description for this device", tab = "general") { optional = true }
        text("notes", "Notes", "Optional, free-form notes about this device", tab = "general") { optional = true }
        list("zonename", "Timezone", tab = "general") {
            value("UTC")
            timezones.keys.sorted().forEach { value(it) }
            writeFn = { rt, sid, v ->
                val tz = timezones[v.toString()]["tzstring"].str
                rt.uci.set("system", sid, "zonename", v)
                rt.uci.set("system", sid, "timezone", tz)
            }
        }
        flag("clock_timestyle", "Full TimeZone Name", "Unchecked means the timezone offset (E.g. GMT+1) is displayed", tab = "general")
        list("clock_hourcycle", "Time Format", tab = "general") {
            value("", "Default"); value("h12", "12-Hour Clock"); value("h23", "24-Hour Clock")
        }

        value("log_size", "System log buffer size", "kiB", tab = "logging") { optional = true; placeholder = "128"; datatype = "uinteger" }
        value("log_ip", "External system log server", tab = "logging") { optional = true; placeholder = "0.0.0.0"; datatype = "host" }
        value("log_port", "External system log server port", tab = "logging") { optional = true; placeholder = "514"; datatype = "port" }
        list("log_proto", "External system log server protocol", tab = "logging") { value("udp", "UDP"); value("tcp", "TCP") }
        value("log_file", "Write system log to file", tab = "logging") { optional = true; placeholder = "/tmp/system.log" }
        list("conloglevel", "Log output level", "Only affects dmesg kernel log", tab = "logging") {
            value("8", "Debug"); value("7", "Info"); value("6", "Notice"); value("5", "Warning")
            value("4", "Error"); value("3", "Critical"); value("2", "Alert"); value("1", "Emergency")
        }
        list("cronloglevel", "Cron Log Level", tab = "logging") {
            default = "7"; value("7", "Normal"); value("9", "Disabled"); value("5", "Debug")
        }

        if (session.hasFeature("zram")) {
            tab("zram", "ZRam Settings")
            value("zram_size_mb", "ZRam Size", "Size of the ZRam device in megabytes", tab = "zram") { optional = true; placeholder = "16"; datatype = "uinteger" }
            list("zram_comp_algo", "ZRam Compression Algorithm", tab = "zram") {
                optional = true; default = "lzo"; value("lzo", "lzo"); value("lz4", "lz4"); value("zstd", "zstd")
            }
        }

        list("_lang", "Language", tab = "language") {
            uciconfig = "luci"; ucisection = "main"; ucioption = "lang"
            value("auto", "auto")
            val langs = (mapOf("en" to "English") + (uci.section("luci", "languages")?.filterKeys { !it.startsWith(".") }?.mapValues { it.value.toString() } ?: emptyMap()))
            langs.keys.sorted().forEach { k -> value(k, langs[k] ?: k) }
        }
        list("_mediaurlbase", "Design", tab = "language") {
            uciconfig = "luci"; ucisection = "main"; ucioption = "mediaurlbase"
            val th = uci.section("luci", "themes")?.filterKeys { !it.startsWith(".") } ?: emptyMap()
            th.keys.sorted().forEach { t -> value(th[t].toString(), t) }
        }
        flag("_tablefilters", "Table Filters", tab = "language") {
            uciconfig = "luci"; ucisection = "main"; ucioption = "tablefilters"
        }

        if (session.hasFeature("sysntpd")) {
            val defaultServers = listOf("0.openwrt.pool.ntp.org", "1.openwrt.pool.ntp.org", "2.openwrt.pool.ntp.org", "3.openwrt.pool.ntp.org")
            flag("enabled", "Enable NTP client", tab = "timesync") {
                rmempty = false; ucisection = "ntp"
                cfgvalueFn = { rt, _ ->
                    if (ntpdEnabled == 1 && rt.uci.section("system", "ntp") != null && rt.uci.getString("system", "ntp", "enabled") != "0") "1" else "0"
                }
                writeFn = { rt, _, v ->
                    ntpdEnabled = v.toString().toIntOrNull() ?: 0
                    if (ntpdEnabled == 1 && rt.uci.section("system", "ntp") == null) {
                        rt.uci.add("system", "timeserver", "ntp")
                        rt.uci.set("system", "ntp", "server", defaultServers)
                    }
                    if (ntpdEnabled == 0) rt.uci.set("system", "ntp", "enabled", "0") else rt.uci.unset("system", "ntp", "enabled")
                    rt.ctx.scope.launch { runCatching { rt.ctx.call("rc", "init", jsonOf("name" to "sysntpd", "action" to "enable")) } }
                }
            }
            flag("enable_server", "Provide NTP server", tab = "timesync") { ucisection = "ntp"; depends("enabled", "1") }
            networkSelect("interface", "Bind NTP server", "Provide the NTP server to the selected interface or, if unspecified, to all interfaces", tab = "timesync") {
                ucisection = "ntp"; depends("enable_server", "1"); optional = true
            }
            flag("use_dhcp", "Use DHCP advertised servers", tab = "timesync") { ucisection = "ntp"; default = "1"; depends("enabled", "1") }
            dynlist("server", "NTP server candidates", "List of upstream NTP server candidates with which to synchronize.", tab = "timesync") {
                datatype = "host(0)"; ucisection = "ntp"; depends("enabled", "1")
                cfgvalueFn = { rt, _ -> rt.uci.get("system", "ntp", "server")?.let { UciStore.toArray(it) } }
            }
        }
    }
    m
}
