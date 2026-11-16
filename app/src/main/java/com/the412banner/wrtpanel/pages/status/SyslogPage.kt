package com.the412banner.wrtpanel.pages.status

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.long
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.form.SelectField
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.CodeBlock
import com.the412banner.wrtpanel.ui.components.ContentMaxWidth
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.SectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** PageColumn with a hoisted scroll state, for the log views' scroll buttons. */
@Composable
internal fun ScrollPageColumn(state: ScrollState, onContentY: (Float) -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(state)
                .onGloballyPositioned { onContentY(it.positionInRoot().y) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = { content(); Spacer(Modifier.height(96.dp)) },
        )
    }
}

/** "Not" label + checkbox of the log filters. */
@Composable
internal fun LabeledCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Checkbox(checked = checked, onCheckedChange = onChange)
    }
}

/** Offsets the log views' scroll buttons use (plain fields, read only on click). */
internal class ScrollAnchors { var content = 0f; var head = 0f }

/** The log textarea between "Scroll to tail" and "Scroll to head", as in LuCI's log views. */
@Composable
internal fun LogBox(text: String, state: ScrollState, anchors: ScrollAnchors) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = { scope.launch { state.animateScrollTo(state.maxValue) } },
            modifier = Modifier.onGloballyPositioned { anchors.head = it.positionInRoot().y },
        ) { Text("Scroll to tail") }
        CodeBlock(text)
        OutlinedButton(onClick = { scope.launch { state.animateScrollTo((anchors.head - anchors.content).toInt().coerceAtLeast(0)) } }) {
            Text("Scroll to head")
        }
    }
}

private val facilities = listOf(
    "any" to "Any", "kern" to "Kernel", "user" to "User", "mail" to "Mail", "daemon" to "Daemon", "auth" to "Auth",
    "syslog" to "Syslog", "lpr" to "LPR", "news" to "News", "uucp" to "UUCP", "cron" to "Cron", "authpriv" to "Auth Priv",
    "ftp" to "FTP", "ntp" to "NTP", "security" to "Log audit", "console" to "Log alert", "cron" to "Scheduling daemon",
    "local0" to "Local 0", "local1" to "Local 1", "local2" to "Local 2", "local3" to "Local 3", "local4" to "Local 4",
    "local5" to "Local 5", "local6" to "Local 6", "local7" to "Local 7",
)
private val severities = listOf(
    "any" to "Any", "emerg" to "Emergency", "alert" to "Alert", "crit" to "Critical", "err" to "Error",
    "warn" to "Warning", "notice" to "Notice", "info" to "Info", "debug" to "Debug",
)

private class LogFilter(
    val facility: String = "any", val invFacility: Boolean = false,
    val severity: String = "any", val invSeverity: Boolean = false,
    val text: String = "", val invText: Boolean = false,
    val maxRows: Int? = 1000,
)

private suspend fun retrieveSyslog(ctx: PageContext, f: LogFilter, tag: String): String {
    val lines: List<String> = try {
        val entries = ctx.rpc("log", "read", jsonOf("lines" to f.maxRows, "stream" to false, "oneshot" to true))["log"].arr ?: emptyList()
        entries.map { e ->
            val prio = e["priority"].long ?: 0
            val time = e["time"].long ?: 0
            val datestr = LuciFmt.date(time, ctx.uci)
            val facility = facilities.getOrNull((prio / 8).toInt() + 1)?.first ?: "unknown"
            val severity = severities.getOrNull((prio % 8).toInt() + 1)?.first ?: "unknown"
            "[$datestr] $facility.$severity: ${e["msg"].str}"
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val logger = resolveDefault(null) { ctx.fs.stat("/usr/libexec/syslog-wrapper") }?.name
            ?: throw e
        ctx.fs.execDirect(logger).trim().split('\n')
    }
    return lines.filter { line ->
        val sevMatch = f.severity == "any" || line.contains(".${f.severity}")
        val facMatch = f.facility == "any" || line.contains("${f.facility}.")
        (f.invSeverity != sevMatch) && (f.invFacility != facMatch)
    }.filter { it.lowercase().contains(tag.lowercase()) }
        .filter { val m = it.contains(f.text); if (f.invText) !m else m }
        .joinToString("\n")
}

/** Status › System Log › System Log — port of luci-mod-status view/status/syslog.js (tools/views.js LogreadBox). */
@Composable
fun SyslogPage(ctx: PageContext) = LogreadBox(ctx, null, "System Log")

/** tools/views.js LogreadBox(logtag, name). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogreadBox(ctx: PageContext, logtag: String?, name: String?) {
    val state = rememberScrollState()
    val anchors = remember { ScrollAnchors() }
    var log by remember { mutableStateOf<String?>(null) }
    var facility by remember { mutableStateOf("any") }
    var invFacility by remember { mutableStateOf(false) }
    var severity by remember { mutableStateOf("any") }
    var invSeverity by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var invText by remember { mutableStateOf(false) }
    var maxRowsText by remember { mutableStateOf("") }
    var maxRows by remember { mutableStateOf<Int?>(1000) }
    var change by remember { mutableIntStateOf(0) }
    val tag = logtag ?: ""

    fun filterChanged() { maxRows = maxRowsText.trim().toIntOrNull(); change++ }

    LaunchedEffect(Unit) { ctx.uci.unloadAll(); runCatching { ctx.uci.load("system") } }
    LaunchedEffect(change) {
        val interval = ((ctx.client.env?.pollInterval ?: 5).coerceAtLeast(1)) * 1000L
        var failed = false
        while (true) {
            try {
                if (!ctx.uci.isLoaded("system")) resolveDefault(Unit) { ctx.uci.load("system") }
                log = retrieveSyslog(ctx, LogFilter(facility, invFacility, severity, invSeverity, text, invText, maxRows), tag)
                failed = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!failed) ctx.toast("Unable to load log data: " + (e.message ?: e.toString()))
                failed = true
                if (log == null) log = ""
            }
            delay(interval)
        }
    }

    ScrollPageColumn(state, onContentY = { anchors.content = it }) {
        PageHeader(name ?: "System Log", if (tag.isNotEmpty()) "The syslog output, pre-filtered for messages related to: $tag" else null)
        SectionCard {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledCheck("Not", invFacility) { invFacility = it; filterChanged() }
                    SelectField(facility, facilities, "facility:", { facility = it; filterChanged() }, Modifier.width(200.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledCheck("Not", invSeverity) { invSeverity = it; filterChanged() }
                    SelectField(severity, severities, "severity:", { severity = it; filterChanged() }, Modifier.width(200.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledCheck("Not", invText) { invText = it; filterChanged() }
                    OutlinedTextField(text, { text = it; filterChanged() }, label = { Text("including:") }, singleLine = true, modifier = Modifier.width(220.dp))
                }
                OutlinedTextField(
                    maxRowsText, { maxRowsText = it; filterChanged() }, label = { Text("Max rows:") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(140.dp).padding(start = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            val l = log
            if (l == null) com.the412banner.wrtpanel.ui.components.LoadingBox() else LogBox(l, state, anchors)
        }
    }
}
