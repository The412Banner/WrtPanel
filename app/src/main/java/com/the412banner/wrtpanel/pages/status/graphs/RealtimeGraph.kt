package com.the412banner.wrtpanel.pages.status.graphs

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.the412banner.wrtpanel.core.UbusCall
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.double
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.ui.PageContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** `pollInterval` of the realtime views (seconds). */
internal const val POLL_INTERVAL = 3

/** `step` of the realtime views: one sample per 5 px of graph width. */
internal const val GRAPH_STEP = 5

/** Extra samples kept for drawing so the left edge stays filled while the graph slides. */
internal const val GRAPH_PAD = 12

/** One entry of the `lines` argument of updateGraph(): `{ line, multiply, offset, counter }`. */
internal class GraphLine(val multiply: Double = 1.0, val offset: Double = 0.0, val counter: Boolean = false)

/** `info` handed to the updateGraph() callback. */
internal class GraphInfo(
    val current: DoubleArray,
    val average: DoubleArray,
    val peak: DoubleArray,
    val scale: Double,
    val label25: Double,
    val label50: Double,
    val label75: Double,
    val timeframe: Int,
    val interval: Int,
) {
    fun caption(): String = "(%s minute window, %s second interval)".format(LuciFmt.d(timeframe.toDouble()), LuciFmt.d(interval.toDouble()))
}

/** What the chart draws: samples per line (oldest first), the last [info], and how many samples the last poll added. */
internal class GraphFrame(val values: List<DoubleArray>, val wanted: Int, val info: GraphInfo?, val added: Int, val serial: Int)

/** Port of updateGraph() + the per-graph part of pollData() of the realtime views. */
internal class RealtimeGraph(private val lines: List<GraphLine?>, private val interval: Int = POLL_INTERVAL) {
    private val count = lines.count { it != null }
    private var wanted = 0
    private val values = List(count) { ArrayList<Double>() }
    private val history = List(count) { ArrayList<Double>() }
    private val current = DoubleArray(count) { Double.NaN }
    private var timestamp = 0.0
    private var fill = 1
    private var info: GraphInfo? = null
    private var serial = 0

    var frame by mutableStateOf(GraphFrame(List(count) { DoubleArray(0) }, 0, null, 0, 0))
        private set

    /** Sets data_wanted (graph width / step); the first call prefills the datasets with zeros. */
    fun resize(dataWanted: Int) {
        if (dataWanted <= 0 || dataWanted == wanted) return
        for (i in 0 until count) {
            fit(values[i], dataWanted)
            fit(history[i], dataWanted + GRAPH_PAD)
        }
        wanted = dataWanted
        publish(0)
    }

    private fun fit(list: ArrayList<Double>, n: Int) {
        if (list.size < n) list.addAll(0, List(n - list.size) { 0.0 })
        else if (list.size > n) list.subList(0, list.size - n).clear()
    }

    private fun col(row: DoubleArray, i: Int): Double = if (i < row.size) row[i] else Double.NaN

    private fun push(i: Int, v: Double) {
        current[i] = v
        values[i].add(v)
        history[i].add(v)
    }

    /** Feeds one luci.getRealtimeStats result (rows of `[ timestamp, v1, v2, … ]`). */
    fun update(data: List<DoubleArray>) {
        if (wanted <= 0) return
        var lastTimestamp = Double.NaN
        var added = 0
        var i = 0
        for (di in lines.indices) {
            val line = lines[di] ?: continue
            val mul = line.multiply
            for (j in (if (timestamp != 0.0) 0 else 1) until data.size) {
                val ts = col(data[j], 0)
                if (ts <= timestamp) continue
                if (i == 0) { fill++; lastTimestamp = ts }
                if (line.counter) {
                    if (j > 0) {
                        val delta = ts - col(data[j - 1], 0)
                        if (delta != 0.0 && !delta.isNaN()) {
                            var c = (col(data[j], di + 1) * mul - col(data[j - 1], di + 1) * mul) / delta
                            c -= min(c, line.offset)
                            push(i, c)
                            if (i == 0) added++
                        }
                    }
                } else {
                    var c = col(data[j], di + 1) * mul
                    c -= min(c, line.offset)
                    push(i, c)
                    if (i == 0) added++
                }
            }
            i++
        }

        fill = min(fill, wanted)
        val peak = DoubleArray(count)
        val average = DoubleArray(count)
        for (k in 0 until count) {
            fit(values[k], wanted)
            fit(history[k], wanted + GRAPH_PAD)
            var p = Double.NaN
            var sum = 0.0
            for (v in values[k]) { p = if (p.isNaN()) v else max(p, v); sum += v }
            peak[k] = p
            average[k] = sum / fill
        }
        var top = peak.fold(Double.NEGATIVE_INFINITY) { a, b -> max(a, b) }
        if (!lastTimestamp.isNaN()) timestamp = lastTimestamp

        val size = when {
            top.isNaN() || top < 0 -> Double.NaN
            top == 0.0 -> Double.NEGATIVE_INFINITY
            top.isInfinite() -> top
            top < java.lang.Double.MIN_NORMAL -> floor(log2(top))
            else -> Math.getExponent(top).toDouble()
        }
        val div = 2.0.pow(size - (size % 10))
        val pod = top / div
        val mult = if (pod < 5) 2.0 else if (pod < 50) 10.0 else if (pod < 500) 100.0 else 1000.0
        top = top + (mult * div) - (top % (mult * div))

        info = GraphInfo(current.copyOf(), average, peak, top, 0.25 * top, 0.50 * top, 0.75 * top, wanted / 60, interval)
        publish(added)
    }

    private fun publish(added: Int) {
        serial++
        frame = GraphFrame(history.map { it.toDoubleArray() }, wanted, info, added, serial)
    }

    companion object {
        /** `expect: { result: [] }` of luci.getRealtimeStats, as rows of numbers. */
        fun rows(reply: JsonElement?): List<DoubleArray> =
            reply["result"].arr?.map { r -> r.arr?.map { it.double ?: Double.NaN }?.toDoubleArray() ?: DoubleArray(0) } ?: emptyList()

        /** callLuciRealtimeStats(mode, device). */
        fun call(mode: String, device: String? = null) = UbusCall("luci", "getRealtimeStats", jsonOf("mode" to mode, "device" to device))

        /** Runs [calls] as one rpc batch like rpc.js; a failed call gives `[]` (L.resolveDefault). */
        suspend fun fetch(ctx: PageContext, calls: List<UbusCall>): List<List<DoubleArray>> =
            ctx.client.batch(calls).map { r -> rows(r.getOrNull()) }
    }
}

/** The graph width (dp) shared by every graph of a page; LuCI sizes all graphs from the view width. */
internal class GraphWidth {
    var dp by mutableIntStateOf(0)
    val dataWanted: Int get() = dp / GRAPH_STEP
}

/**
 * LuCI poll.add(fn, interval) for the realtime views: waits for the graph width, sizes [graphs]
 * and runs [block] every [intervalSec] seconds. Errors are shown once until the next success.
 */
@Composable
internal fun GraphPoll(
    ctx: PageContext,
    width: GraphWidth,
    graphs: List<RealtimeGraph>,
    key: Any?,
    intervalSec: Int = POLL_INTERVAL,
    block: suspend () -> Unit,
) {
    val current by rememberUpdatedState(block)
    LaunchedEffect(key, graphs) {
        snapshotFlow { width.dataWanted }.collect { n -> if (n > 0) graphs.forEach { it.resize(n) } }
    }
    LaunchedEffect(key, graphs) {
        snapshotFlow { width.dataWanted }.first { it > 0 }
        var lastError: String? = null
        while (true) {
            val t0 = SystemClock.uptimeMillis()
            try {
                current()
                lastError = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: e.toString()
                if (msg != lastError) ctx.toast(msg)
                lastError = msg
            }
            delay((intervalSec * 1000L - (SystemClock.uptimeMillis() - t0)).coerceAtLeast(250L))
        }
    }
}
