package com.the412banner.wrtpanel.pages.status.graphs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.theme.LocalStatusColors

/** Status › Realtime Graphs › Load — port of luci-mod-status view/status/load.js. */
@Composable
fun LoadPage(ctx: PageContext) {
    val width = remember { GraphWidth() }
    val graph = remember { RealtimeGraph(listOf(GraphLine(), GraphLine(), GraphLine())) }
    val graphs = remember(graph) { listOf(graph) }
    GraphPoll(ctx, width, graphs, Unit) {
        graph.update(RealtimeGraph.fetch(ctx, listOf(RealtimeGraph.call("load")))[0])
    }

    val c = LocalStatusColors.current.series
    val colors = listOf(c[5], c[2], c[3])
    val titles = listOf("1 Minute Load:", "5 Minute Load:", "15 Minute Load:")
    val load = { v: Double -> LuciFmt.f(v / 100, 2) }

    PageColumn {
        PageHeader("System load", "Load Average is a metric that is used by Linux to keep track of system resources.")
        GraphCard(
            graph, colors.mapIndexed { i, col -> ChartSeries(i, col) }, width,
            labels = { info -> listOf(info.label75, info.label50, info.label25).map(load) },
            rows = { info ->
                titles.mapIndexed { i, t ->
                    StatRow(t, colors[i], load(info?.current.at(i)), load(info?.average.at(i)), load(info?.peak.at(i)))
                }
            },
        )
    }
}
