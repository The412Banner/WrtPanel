@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.the412banner.wrtpanel.pages.status.graphs

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.the412banner.wrtpanel.ui.components.SectionCard
import kotlin.math.max
import kotlin.math.roundToInt

/** One drawn line of a graph: [index] into the graph's lines, in SVG paint order. */
internal class ChartSeries(val index: Int, val color: Color)

/** Text drawn on a chart with a soft backdrop so it stays readable above the filled areas. */
internal fun DrawScope.chartLabel(layout: TextLayoutResult, x: Float, y: Float, backdrop: Color) {
    val padH = 4.dp.toPx()
    val padV = 1.dp.toPx()
    drawRoundRect(
        backdrop, topLeft = Offset(x - padH, y - padV),
        size = Size(layout.size.width + padH * 2, layout.size.height + padV * 2),
        cornerRadius = CornerRadius(4.dp.toPx()),
    )
    drawText(layout, topLeft = Offset(x, y))
}

internal fun TextMeasurer.line(text: String, style: TextStyle): TextLayoutResult = measure(text, style, maxLines = 1, softWrap = false)

/** Adds a smooth curve through [xs]/[ys] (midpoint quadratic segments, no overshoot). */
private fun Path.smoothThrough(xs: FloatArray, ys: FloatArray, from: Int, to: Int) {
    moveTo(xs[from], ys[from])
    for (k in from until to) {
        val mx = (xs[k] + xs[k + 1]) / 2f
        val my = (ys[k] + ys[k + 1]) / 2f
        quadraticTo(xs[k], ys[k], mx, my)
    }
    lineTo(xs[to], ys[to])
}

/**
 * The realtime graph (svg/load.svg, bandwidth.svg, …): filled lines, the 25/50/75 % scale lines with
 * [labels] (top to bottom: label_75, label_50, label_25) and the minute marks. Width is reported to [width].
 */
@Composable
internal fun RealtimeChart(
    frame: GraphFrame,
    series: List<ChartSeries>,
    labels: List<String>?,
    width: GraphWidth,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val backdrop = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.82f)
    val shift = remember { Animatable(0f) }
    val scale = remember { Animatable(Float.NaN) }
    val shown = remember { intArrayOf(-1) }

    LaunchedEffect(frame) {
        val target = frame.info?.scale?.toFloat() ?: Float.NaN
        val stepPx = with(density) { GRAPH_STEP.dp.toPx() }
        val first = shown[0] < 0
        shown[0] = frame.serial
        if (first || target.isNaN() || scale.value.isNaN() || target <= 0f) scale.snapTo(target)
        if (!first && frame.added in 1..8) shift.snapTo(shift.value + frame.added * stepPx) else shift.snapTo(0f)
        kotlinx.coroutines.coroutineScope {
            if (!target.isNaN() && target > 0f && scale.value != target) {
                launch { scale.animateTo(target, tween(700, easing = FastOutSlowInEasing)) }
            }
            if (shift.value != 0f) launch { shift.animateTo(0f, tween(1200, easing = FastOutSlowInEasing)) }
        }
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .onSizeChanged { width.dp = with(density) { it.width.toDp().value.toInt() } },
    ) {
        val w = size.width
        val h = size.height
        val dp1 = 1.dp.toPx()
        val stepPx = GRAPH_STEP.dp.toPx()
        val thin = Stroke(width = dp1 * 0.8f)

        for (f in floatArrayOf(0.25f, 0.5f, 0.75f)) drawLine(grid, Offset(0f, h * f), Offset(w, h * f), strokeWidth = thin.width)
        drawLine(grid, Offset(0f, h - dp1 / 2), Offset(w, h - dp1 / 2), strokeWidth = dp1)

        val wd = (w / dp1).toInt()
        var i = wd % (GRAPH_STEP * 60)
        val minuteMarks = ArrayList<Pair<Float, String>>()
        while (i < wd) {
            val x = i * dp1
            drawLine(
                grid, Offset(x, 0f), Offset(x, h), strokeWidth = thin.width,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3 * dp1, 3 * dp1)),
            )
            minuteMarks.add(x to "${((wd - i).toDouble() / GRAPH_STEP / 60).roundToInt()}m")
            i += GRAPH_STEP * 60
        }

        val top = scale.value
        clipRect(0f, 0f, w, h) {
            for (s in series) {
                val vals = frame.values.getOrNull(s.index) ?: continue
                val n = vals.size
                if (n < 2 || frame.wanted <= 0) continue
                val xs = FloatArray(n)
                val ys = FloatArray(n)
                for (k in 0 until n) {
                    xs[k] = ((frame.wanted - 1) - (n - 1 - k)) * stepPx + shift.value
                    val v = vals[k]
                    ys[k] = if (top.isNaN() || top <= 0f || v.isNaN()) h else (h - (v / top * h).toFloat()).coerceIn(0f, h)
                }
                var first = 0
                while (first < n - 2 && xs[first + 1] < 0f) first++
                val line = Path().apply {
                    smoothThrough(xs, ys, first, n - 1)
                    lineTo(max(w, xs[n - 1]), ys[n - 1])
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(max(w, xs[n - 1]), h)
                    lineTo(xs[first], h)
                    close()
                }
                drawPath(area, Brush.verticalGradient(listOf(s.color.copy(alpha = 0.34f), s.color.copy(alpha = 0.04f)), startY = 0f, endY = h))
                drawPath(line, s.color, style = Stroke(width = 1.8f * dp1, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }

        for ((x, t) in minuteMarks) chartLabel(measurer.line(t, labelStyle), x + 5 * dp1, 4 * dp1, backdrop)
        labels?.forEachIndexed { k, t ->
            if (t.isBlank()) return@forEachIndexed
            val layout = measurer.line(t, labelStyle)
            val y = h * (0.25f * (k + 1)) - layout.size.height - 3 * dp1
            chartLabel(layout, 12 * dp1, y, backdrop)
        }
    }
}

/** One row of the table under a graph: `<label> <current>  Average: <avg>  Peak: <peak>`. */
internal class StatRow(val label: String, val color: Color, val current: String, val average: String, val peak: String)

@Composable
private fun StatCell(title: String, value: String, modifier: Modifier) {
    Column(modifier.padding(start = 8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
internal fun GraphStats(rows: List<StatRow>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        rows.forEachIndexed { i, r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1.25f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 14.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(r.color))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            r.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        r.current, style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                StatCell("Average:", r.average, Modifier.weight(1f))
                StatCell("Peak:", r.peak, Modifier.weight(1f))
            }
            if (i < rows.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

/** A graph, its scale line ("(… minute window, … second interval)") and its value table in one card. */
@Composable
internal fun GraphCard(
    graph: RealtimeGraph,
    series: List<ChartSeries>,
    width: GraphWidth,
    labels: (GraphInfo) -> List<String>,
    rows: (GraphInfo?) -> List<StatRow>,
) {
    val frame = graph.frame
    val info = frame.info
    SectionCard(contentPadding = PaddingValues(top = 10.dp, bottom = 6.dp)) {
        key(graph) { RealtimeChart(frame, series, info?.let(labels), width) }
        Text(
            info?.caption() ?: "-", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        GraphStats(rows(info))
    }
}

/** ui.tabs group of a view (one tab per interface / radio). */
@Composable
internal fun GraphTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    if (titles.isEmpty()) return
    PrimaryScrollableTabRow(selectedTabIndex = selected.coerceIn(0, titles.size - 1), edgePadding = 0.dp, containerColor = Color.Transparent) {
        titles.forEachIndexed { i, t -> Tab(selected = i == selected, onClick = { onSelect(i) }, text = { Text(t, maxLines = 1) }) }
    }
}

/** A value of `info.line_*[i]` (undefined before the first poll → NaN). */
internal fun DoubleArray?.at(i: Int): Double = this?.getOrNull(i) ?: Double.NaN
