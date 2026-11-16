package com.the412banner.wrtpanel.pages.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.int
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.EmptyState
import com.the412banner.wrtpanel.ui.components.ErrorBox
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.SectionCard
import com.the412banner.wrtpanel.ui.components.htmlToAnnotated
import com.the412banner.wrtpanel.ui.theme.LocalStatusColors
import com.the412banner.wrtpanel.ui.theme.Mono
import kotlinx.coroutines.launch

private data class InitScript(val name: String, val start: Int, val enabled: Boolean)

/** System › Startup — port of luci-mod-system view/system/startup.js. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StartupPage(ctx: PageContext) {
    var inits by remember { mutableStateOf<List<InitScript>?>(null) }
    var rcLocal by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tick) {
        error = null
        try {
            rcLocal = ctx.fs.readOrNull("/etc/rc.local") ?: ""
            val list = ctx.call("rc", "list").obj ?: emptyMap()
            inits = list.mapNotNull { (name, v) ->
                val start = v["start"].int ?: return@mapNotNull null
                if (start >= 100) null else InitScript(name, start, v["enabled"].bool == true)
            }.sortedWith(compareBy<InitScript> { it.start }.thenBy { it.name })
        } catch (e: Exception) { error = e.message ?: e.toString() }
    }

    suspend fun action(name: String, action: String): Boolean = try {
        val ret = ctx.call("rc", "init", jsonOf("name" to name, "action" to action))
        if (ret != null) throw Exception("Command failed")
        true
    } catch (e: Exception) {
        ctx.toast("Failed to execute \"/etc/init.d/$name $action\" action: ${e.message}")
        false
    }

    PageColumn {
        PageHeader("Startup", null)
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Initscripts") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Local Startup") })
        }
        val list = inits
        when {
            error != null && list == null -> ErrorBox(error!!) { tick++ }
            list == null -> LoadingBox()
            tab == 0 -> SectionCard {
                Text(
                    htmlToAnnotated("You can enable or disable installed init scripts here. Changes will be applied after a device reboot.<br /><strong>Warning: If you disable essential init scripts like \"network\", your device might become inaccessible!</strong>"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (list.isEmpty()) EmptyState("No data")
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val wide = maxWidth >= 640.dp
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Text("Start priority", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(min = 64.dp).padding(end = 12.dp))
                            Text("Initscript", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                        list.forEachIndexed { i, init ->
                            InitRow(
                                init, wide, enabled = !ctx.readonly,
                                onToggle = {
                                    scope.launch {
                                        if (action(init.name, if (init.enabled) "disable" else "enable")) {
                                            inits = inits?.map { if (it.name == init.name) it.copy(enabled = !init.enabled) else it }
                                        }
                                    }
                                },
                                onAction = { a -> scope.launch { action(init.name, a) } },
                            )
                            if (i < list.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
            else -> SectionCard {
                Text(
                    "This is the content of /etc/rc.local. Insert your own commands here (in front of 'exit 0') to execute them at the end of the boot process.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                FileTextArea(rcLocal ?: "", { rcLocal = it }, rows = 20, enabled = !ctx.readonly && !saving)
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                    Button(
                        enabled = !ctx.readonly && !saving,
                        onClick = {
                            val value = editorValue(rcLocal ?: "")
                            scope.launch {
                                saving = true
                                try {
                                    ctx.fs.write("/etc/rc.local", value)
                                    rcLocal = value
                                    ctx.toast("Contents have been saved.")
                                } catch (e: Exception) {
                                    ctx.toast("Unable to save contents: ${e.message}")
                                } finally { saving = false }
                            }
                        },
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun InitRow(init: InitScript, wide: Boolean, enabled: Boolean, onToggle: () -> Unit, onAction: (String) -> Unit) {
    val s = LocalStatusColors.current
    val actions = listOf("start" to "Start", "restart" to "Restart", "reload" to "Reload", "stop" to "Stop")
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("%02d".format(init.start), fontFamily = Mono, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.widthIn(min = 64.dp).padding(end = 12.dp))
        Text(init.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val c = if (init.enabled) s.ok else s.bad
        OutlinedButton(
            onClick = onToggle, enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c),
            border = androidx.compose.foundation.BorderStroke(1.dp, c.copy(alpha = 0.6f)),
        ) { Text(if (init.enabled) "Enabled" else "Disabled") }
        if (wide) {
            actions.forEach { (a, label) ->
                Spacer(Modifier.width(6.dp))
                FilledTonalButton(onClick = { onAction(a) }, enabled = enabled) { Text(label) }
            }
        } else {
            var open by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { open = true }, enabled = enabled) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    actions.forEach { (a, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onAction(a) })
                    }
                }
            }
        }
    }
}
