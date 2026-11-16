package com.the412banner.wrtpanel.pages.status

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.SectionCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** What an include's Content can use. */
internal class OverviewEnv(val ctx: PageContext, val tooltip: MutableTooltip, val repoll: () -> Unit)

/** A module of view/status/include: title, load() + render() (null = nothing to show) and its content. */
internal abstract class StatusInclude(val title: String) {
    /** load() then render(); returns null when render() returns null, throws when load() rejects. */
    abstract suspend fun fetch(ctx: PageContext, net: NetState): Any?

    @Composable
    abstract fun Content(model: Any, env: OverviewEnv)
}

/** Includes shipped by luci-mod-status, by file name. */
internal val StatusIncludes: Map<String, () -> StatusInclude> = mapOf(
    "10_system" to { SystemInclude() },
    "20_memory" to { MemoryInclude() },
    "25_storage" to { StorageInclude() },
    "29_ports" to { PortsInclude() },
    "30_network" to { NetworkInclude() },
    "40_dhcp" to { DhcpInclude() },
    "50_dsl" to { DslInclude() },
    "60_wifi" to { WifiInclude() },
)

private class IncState(val inc: StatusInclude, hide: Boolean) {
    var hide by mutableStateOf(hide)
    var failed = false
    var shown by mutableStateOf(false)
    var model by mutableStateOf<Any?>(null)
}

/** Status › Overview — port of luci-mod-status template admin_status/index and view/status/index.js. */
@Composable
fun OverviewPage(ctx: PageContext) {
    val prefs = LocalContext.current.getSharedPreferences("status_overview", Context.MODE_PRIVATE)
    val generation by ctx.session.changes.generation.collectAsState()
    var includes by remember(generation) { mutableStateOf<List<IncState>?>(null) }
    var pollNow by remember { mutableStateOf(0) }
    val tooltip = rememberTooltip()
    val env = remember(ctx) { OverviewEnv(ctx, tooltip) { pollNow++ } }

    suspend fun pollStatus(list: List<IncState>, first: Boolean) {
        val net = try { NetState.load(ctx) } catch (e: CancellationException) { throw e } catch (e: Exception) { return }
        val results = coroutineScope {
            list.map { s ->
                async {
                    if (s.hide && !first) return@async null
                    try { s.inc.fetch(ctx, net) } catch (e: CancellationException) { throw e } catch (e: Exception) { s.failed = true; null }
                }
            }.awaitAll()
        }
        list.forEachIndexed { i, s ->
            if (s.hide && !first) return@forEachIndexed
            if (s.failed) return@forEachIndexed
            val content = results[i] ?: return@forEachIndexed
            s.shown = true
            if (!s.hide) s.model = content
        }
    }

    LaunchedEffect(generation) {
        ctx.uci.unloadAll()
        val dir = "/www" + (ctx.client.env?.resource ?: "/luci-static/resources") + "/view/status/include"
        val entries = resolveDefault(emptyList()) { ctx.fs.list(dir) }
        val list = entries.filter { it.type == "file" && it.name.endsWith(".js") }
            .map { it.name.removeSuffix(".js") }.sorted()
            .mapNotNull { StatusIncludes[it]?.invoke() }
            .map { IncState(it, prefs.getString(it.title, null) == "hide") }
        pollStatus(list, true)
        includes = list
    }

    val list = includes
    LaunchedEffect(list, pollNow) {
        if (list == null) return@LaunchedEffect
        val interval = ((ctx.client.env?.pollInterval ?: 5).coerceAtLeast(1)) * 1000L
        if (pollNow > 0) pollStatus(list, false)
        while (true) {
            delay(interval)
            pollStatus(list, false)
        }
    }

    PageColumn {
        PageHeader("Status", null)
        if (list == null) {
            LoadingBox(text = "Loading view…")
        } else {
            list.forEach { s ->
                if (!s.shown) return@forEach
                SectionCard(
                    title = s.inc.title.ifEmpty { "-" },
                    actions = {
                        TextButton(onClick = {
                            s.hide = !s.hide
                            if (s.hide) prefs.edit().putString(s.inc.title, "hide").apply()
                            else { s.model = null; prefs.edit().remove(s.inc.title).apply() }
                        }) { Text(if (s.hide) "Show" else "Hide") }
                    },
                ) {
                    if (!s.hide) {
                        val m = s.model
                        if (m == null) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(10.dp))
                                Text("Collecting data...", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                            }
                        } else {
                            s.inc.Content(m, env)
                        }
                    }
                }
            }
        }
    }
    TooltipHost(tooltip)
}
