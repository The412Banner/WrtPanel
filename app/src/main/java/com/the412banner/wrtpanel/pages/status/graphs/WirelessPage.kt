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

/** Status › Realtime Graphs › Wireless — port of luci-mod-status view/status/wireless.js. */
@Composable
fun WirelessPage(ctx: PageContext) {
    var tabs by remember { mutableStateOf<List<GraphTab>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        try {
            val st = NetState.load(ctx)
            val nets = ArrayList<NetState.WifiNet>()
            for (radio in st.wifiDeviceNames()) {
                if (st.isRadioDisabled(radio)) continue
                for (net in st.wifiNetworks(radio)) if (!net.isDisabled()) nets.add(net)
            }
            tabs = nets.mapNotNull { net ->
                val ifname = net.ifname()
                if (ifname.isNullOrEmpty()) null else GraphTab(ifname, listOfNotNull(ifname, net.ssid()).joinToString(" "))
            }
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    val header = @Composable { PageHeader("Wireless", "This page displays the wireless metrics for each available radio interface.") }
    val list = tabs
    when {
        error != null && list == null -> PageColumn { header(); ErrorBox(error!!) { reload++ } }
        list == null -> PageColumn { header(); LoadingBox() }
        else -> WirelessGraphs(ctx, list, header)
    }
}

@Composable
private fun WirelessGraphs(ctx: PageContext, tabs: List<GraphTab>, header: @Composable () -> Unit) {
    val width = remember { GraphWidth() }
    val signal = remember(tabs) { tabs.map { RealtimeGraph(listOf(null, GraphLine(offset = 155.0), GraphLine(offset = 155.0))) } }
    val rate = remember(tabs) { tabs.map { RealtimeGraph(listOf(GraphLine(multiply = 0.001))) } }
    val all = remember(signal, rate) { tabs.indices.flatMap { listOf(signal[it], rate[it]) } }
    GraphPoll(ctx, width, all, tabs) {
        val data = RealtimeGraph.fetch(ctx, tabs.flatMap { t -> List(2) { RealtimeGraph.call("wireless", t.ifname) } })
        all.forEachIndexed { i, g -> g.update(data.getOrElse(i) { emptyList() }) }
    }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val c = LocalStatusColors.current.series
    val rssiColor = c[0]
    val noiseColor = c[5]
    val rateColor = c[4]
    val dbm = { v: Double -> "%s %s".format(LuciFmt.d(v - 100), "dBm") }
    val mbit = { v: Double -> "%s %s".format(LuciFmt.d(v), "Mbit/s") }

    PageColumn {
        header()
        if (tabs.isEmpty()) {
            EmptyState("No information available")
            return@PageColumn
        }
        val idx = selected.coerceIn(0, tabs.size - 1)
        GraphTabs(tabs.map { it.title }, idx) { selected = it }
        GraphCard(
            signal[idx], listOf(ChartSeries(0, rssiColor), ChartSeries(1, noiseColor)), width,
            labels = { info -> listOf(info.label75, info.label50, info.label25).map(dbm) },
            rows = { info ->
                listOf(
                    StatRow("Signal:", rssiColor, dbm(info?.current.at(0)), dbm(info?.average.at(0)), dbm(info?.peak.at(0))),
                    StatRow("Noise:", noiseColor, dbm(info?.current.at(1)), dbm(info?.average.at(1)), dbm(info?.peak.at(1))),
                )
            },
        )
        GraphCard(
            rate[idx], listOf(ChartSeries(0, rateColor)), width,
            labels = { info -> listOf(info.label75, info.label50, info.label25).map { "%s %s".format(LuciFmt.f(it, 2), "Mbit/s") } },
            rows = { info -> listOf(StatRow("Phy Rate:", rateColor, mbit(info?.current.at(0)), mbit(info?.average.at(0)), mbit(info?.peak.at(0)))) },
        )
    }
}
