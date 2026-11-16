package com.the412banner.wrtpanel.pages.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.form.SelectField
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.SectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val kernSeverity = listOf(
    "" to "Default", "1" to "Alert", "2" to "Critical", "3" to "Error",
    "4" to "Warning", "5" to "Notice", "6" to "Info", "7" to "Debug",
)

private data class DmesgFilter(
    val from: String = "0", val to: String = "", val invRange: Boolean = false,
    val minSeverity: String = "", val invSeverity: Boolean = false,
    val descending: Boolean = false, val text: String = "", val invText: Boolean = false,
)

private data class KLine(val severity: Int?, val isCont: Boolean, val time: Double?, val text: String)

private suspend fun retrieveDmesg(ctx: PageContext, f: DmesgFilter): String {
    val data = ctx.fs.execDirect("/bin/dmesg", listOf("-r"))
    var lines = mutableListOf<KLine>()
    var last: Int? = null
    for (line in data.trim().split('\n')) {
        val pm = Regex("^<(\\w+)>").find(line) ?: continue
        val tag = pm.groupValues[1]
        val cont = tag == "c"
        val clean = line.replaceFirst(Regex("^<\\w+>"), "")
        val time = Regex("^\\[\\s*(\\d+(?:\\.\\d+)?)]").find(clean)?.groupValues?.get(1)?.toDoubleOrNull()
        val sev = Regex("^\\d+").find(tag)?.value?.toIntOrNull()
        if (!cont) last = sev
        lines.add(KLine(if (cont) last else sev, cont, time, clean))
    }
    val start = f.from.trim().takeIf { it.isNotEmpty() }
    val end = f.to.trim().takeIf { it.isNotEmpty() }
    if (start != null || end != null) {
        val s = start?.toDoubleOrNull() ?: Double.NaN
        val e = end?.toDoubleOrNull() ?: Double.NaN
        lines = lines.filter { l ->
            val t = l.time ?: return@filter false
            val inRange = when {
                start != null && end != null -> t >= s && t <= e
                start != null -> t >= s
                else -> t <= e
            }
            if (f.invRange) !inRange else inRange
        }.toMutableList()
    }
    val min = f.minSeverity.toDoubleOrNull() ?: if (f.minSeverity.isEmpty()) 0.0 else Double.NaN
    lines = lines.filter { l ->
        if (l.isCont) false
        else {
            val sv = l.severity?.toDouble() ?: Double.NaN
            if (!f.invSeverity) sv >= min else sv < min
        }
    }.toMutableList()
    if (f.text.isNotEmpty()) lines = lines.filter { l -> val m = l.text.contains(f.text); if (f.invText) !m else m }.toMutableList()
    if (f.descending) lines.reverse()
    return lines.joinToString("\n") { it.text }
}

/** Status › System Log › Kernel Log — port of luci-mod-status view/status/dmesg.js. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DmesgPage(ctx: PageContext) {
    val state = rememberScrollState()
    val anchors = remember { ScrollAnchors() }
    var log by remember { mutableStateOf<String?>(null) }
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    var invRange by remember { mutableStateOf(false) }
    var severity by remember { mutableStateOf("") }
    var invSeverity by remember { mutableStateOf(false) }
    var descending by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var invText by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(DmesgFilter()) }
    var change by remember { mutableIntStateOf(0) }

    fun changed() {
        filter = DmesgFilter(fromText, toText, invRange, severity, invSeverity, descending, text, invText)
        change++
    }

    LaunchedEffect(change) {
        val interval = ((ctx.client.env?.pollInterval ?: 5).coerceAtLeast(1)) * 1000L
        var failed = false
        while (true) {
            try {
                log = retrieveDmesg(ctx, filter)
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
        PageHeader("Kernel Log", null)
        SectionCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabeledCheck("Not", invRange) { invRange = it; changed() }
                    OutlinedTextField(
                        fromText, { fromText = it; changed() }, label = { Text("between:") }, placeholder = { Text("0.000000") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(150.dp),
                    )
                    OutlinedTextField(
                        toText, { toText = it; changed() }, label = { Text("and:") }, placeholder = { Text("0.000000") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(150.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledCheck("Not", invSeverity) { invSeverity = it; changed() }
                    SelectField(severity, kernSeverity, null, { severity = it; changed() }, Modifier.width(170.dp))
                    Text("and above", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                }
                LabeledCheck("Reverse sort", descending) { descending = it; changed() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabeledCheck("Not", invText) { invText = it; changed() }
                    OutlinedTextField(text, { text = it; changed() }, label = { Text("including:") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(12.dp))
            val l = log
            if (l == null) LoadingBox() else LogBox(l, state, anchors)
        }
    }
}
