package com.the412banner.wrtpanel.pages.status.graphs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.EmptyState
import com.the412banner.wrtpanel.ui.components.ErrorBox
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.theme.LocalStatusColors

/** A graph tab: device name for the stats call and the tab title. */
internal class GraphTab(val ifname: String, val title: String)

/** Status › Realtime Graphs › Bandwidth — port of luci-mod-status view/status/bandwidth.js. */
@Composable
fun BandwidthPage(ctx: PageContext) {
    var tabs by remember { mutableStateOf<List<GraphTab>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        try {
            val st = NetState.load(ctx)
            tabs = st.getDevices().mapNotNull { dev ->
                val ifname = dev.getName()
                val ssid = dev.wif?.ssid()
                if (ifname.isNullOrEmpty() || !dev.isUp() || dev.wif?.isDisabled() == true) null
                else GraphTab(ifname, if (ssid != null) "$ifname $ssid" else ifname)
            }
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    val header = @Composable { PageHeader("Bandwidth", "This page displays the bandwidth used for all available physical interfaces.") }
    val list = tabs
    when {
        error != null && list == null -> PageColumn { header(); ErrorBox(error!!) { reload++ } }
        list == null -> PageColumn { header(); LoadingBox() }
        else -> BandwidthGraphs(ctx, list, header)
    }
}

@Composable
private fun BandwidthGraphs(ctx: PageContext, tabs: List<GraphTab>, header: @Composable () -> Unit) {
    val width = remember { GraphWidth() }
    val graphs = remember(tabs) { tabs.map { RealtimeGraph(listOf(GraphLine(counter = true), null, GraphLine(counter = true))) } }
    GraphPoll(ctx, width, graphs, tabs) {
        val data = RealtimeGraph.fetch(ctx, tabs.map { RealtimeGraph.call("interface", it.ifname) })
        graphs.forEachIndexed { i, g -> g.update(data.getOrElse(i) { emptyList() }) }
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val c = LocalStatusColors.current.series
    val rx = c[0]
    val tx = c[4]

    PageColumn {
        header()
        if (tabs.isEmpty()) {
            EmptyState("No information available")
            return@PageColumn
        }
        val idx = selected.coerceIn(0, tabs.size - 1)
        GraphTabs(tabs.map { it.title }, idx) { selected = it }
        GraphCard(
            graphs[idx], listOf(ChartSeries(0, rx), ChartSeries(1, tx)), width,
            labels = { info -> listOf(info.label75, info.label50, info.label25).map { LuciFmt.rate(it, false) } },
            rows = { info ->
                listOf(
                    StatRow("Inbound:", rx, LuciFmt.rate(info?.current.at(0), true), LuciFmt.rate(info?.average.at(0), true), LuciFmt.rate(info?.peak.at(0), true)),
                    StatRow("Outbound:", tx, LuciFmt.rate(info?.current.at(1), true), LuciFmt.rate(info?.average.at(1), true), LuciFmt.rate(info?.peak.at(1), true)),
                )
            },
        )
    }
}
