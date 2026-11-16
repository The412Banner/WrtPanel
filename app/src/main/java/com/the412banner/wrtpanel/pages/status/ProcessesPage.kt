package com.the412banner.wrtpanel.pages.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.ErrorBox
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.ResponsiveTable
import com.the412banner.wrtpanel.ui.components.SectionCard
import kotlinx.coroutines.launch

private data class Proc(val pid: Int, val user: String, val command: String, val cpu: String, val mem: String)

/** Status › Processes — port of luci-mod-status view/status/processes.js. */
@Composable
fun ProcessesPage(ctx: PageContext) {
    var procs by remember { mutableStateOf<List<Proc>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    suspend fun load() {
        try {
            val r = ctx.call("luci", "getProcessList")["result"].arr ?: emptyList()
            procs = r.map {
                Proc(it["PID"].str?.toIntOrNull() ?: 0, it["USER"].str ?: "", it["COMMAND"].str ?: "", it["%CPU"].str ?: "", it["%MEM"].str ?: "")
            }.sortedBy { it.pid }
            error = null
        } catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(tick) { load() }

    fun signal(sig: Int, pid: Int) {
        scope.launch {
            try { ctx.fs.exec("/bin/kill", listOf("-$sig", "$pid")) } catch (e: Exception) { ctx.toast(e.message ?: "kill failed") }
            load()
        }
    }

    PageColumn {
        PageHeader("Processes", "This list gives an overview over currently running system processes and their status.")
        val list = procs
        when {
            error != null && list == null -> ErrorBox(error!!) { tick++ }
            list == null -> LoadingBox()
            else -> SectionCard {
                OutlinedTextField(
                    filter, { filter = it }, singleLine = true, placeholder = { Text("Filter") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                val shown = list.filter { filter.isBlank() || it.command.contains(filter, true) || it.user.contains(filter, true) || it.pid.toString() == filter.trim() }
                ResponsiveTable(
                    headers = listOf("PID", "Owner", "Command", "CPU usage (%)", "Memory usage (%)"),
                    rows = shown.map { listOf(it.pid.toString(), it.user, it.command, it.cpu, it.mem) },
                    weights = listOf(0.6f, 0.8f, 3f, 0.8f, 0.8f),
                    primaryColumn = 2,
                    empty = "No information available",
                    rowActions = { i -> SignalMenu(shown[i].pid) { sig, pid -> signal(sig, pid) } },
                )
            }
        }
    }
}

@Composable
private fun SignalMenu(pid: Int, onSignal: (Int, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Signal") }
    DropdownMenu(open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text("Hang Up") }, onClick = { open = false; onSignal(1, pid) })
        DropdownMenuItem(text = { Text("Terminate", color = MaterialTheme.colorScheme.error) }, onClick = { open = false; onSignal(15, pid) })
        DropdownMenuItem(text = { Text("Kill", color = MaterialTheme.colorScheme.error) }, onClick = { open = false; onSignal(9, pid) })
    }
}
