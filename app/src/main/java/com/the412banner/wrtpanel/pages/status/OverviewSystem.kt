package com.the412banner.wrtpanel.pages.status

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.double
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.long
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.InfoRow
import com.the412banner.wrtpanel.ui.components.InfoTable
import com.the412banner.wrtpanel.ui.components.UsageBar
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor

/** Status › Overview includes 10_system, 20_memory and 25_storage — port of luci-mod-status view/status/include/{10_system,20_memory,25_storage}.js. */
internal class SystemInclude : StatusInclude("System") {
    override suspend fun fetch(ctx: PageContext, net: NetState): Any = coroutineScope {
        val board = async { resolveDefault<JsonElement?>(null) { ctx.call("system", "board") } }
        val info = async { resolveDefault<JsonElement?>(null) { ctx.call("system", "info") } }
        val ver = async { resolveDefault<JsonElement?>(null) { ctx.call("luci", "getVersion") } }
        val time = async { resolveDefault(0L) { ctx.rpc("luci", "getUnixtime")["result"].long ?: 0L } }
        ctx.uci.load("system")
        val b = board.await().obj ?: JsonObject(emptyMap())
        val s = info.await().obj ?: JsonObject(emptyMap())
        val v = ver.await().obj
        val luciversion = if (v == null) "LuCI unknown version" else "${v["branch"].str} ${v["revision"].str}"
        val unixtime = time.await()
        val release = b["release"].obj
        val load = s["load"].arr
        listOf(
            "Hostname" to b["hostname"].str,
            "Model" to b["model"].str,
            "Architecture" to b["system"].str,
            "Target Platform" to (if (release != null) release["target"].str else ""),
            "Firmware Version" to ((if (release != null) "${release["description"].str} / " else "") + luciversion),
            "Kernel Version" to b["kernel"].str,
            "Local Time" to (if (unixtime != 0L) LuciFmt.date(unixtime * 1000, ctx.uci) else null),
            "Uptime" to s["uptime"].long?.takeIf { it != 0L }?.let { LuciFmt.t(it) },
            "Load Average" to load?.let { l ->
                String.format(java.util.Locale.US, "%.2f, %.2f, %.2f",
                    (l.getOrNull(0).double ?: Double.NaN) / 65535.0, (l.getOrNull(1).double ?: Double.NaN) / 65535.0, (l.getOrNull(2).double ?: Double.NaN) / 65535.0)
            },
        )
    }

    @Composable
    override fun Content(model: Any, env: OverviewEnv) {
        @Suppress("UNCHECKED_CAST")
        InfoTable((model as List<Pair<String, String?>>).map { it.first to (it.second ?: "?") })
    }
}

/** A cbi-progressbar row: [label] and value / max, or "?" when value is null. */
internal data class ProgressRow(val label: String, val value: Double?, val max: Double?, val bytes: Boolean)

/** progressbar(value, max, byte) of the includes: fraction and its "%s / %s (%d%%)" text. */
internal fun progress(value: Double?, max: Double?, bytes: Boolean, rawValue: String? = null, rawMax: String? = null): Pair<Float, String> {
    fun pint(d: Double?): Long? = d?.takeIf { !it.isNaN() && !it.isInfinite() }?.let { floor(it).toLong() }
    val vn = pint(value) ?: 0L
    val mn = pint(max)?.takeIf { it != 0L } ?: 100L
    val fv = if (bytes) LuciFmt.bytes(value) else (rawValue ?: LuciFmt.num(value))
    val fm = if (bytes) LuciFmt.bytes(max) else (rawMax ?: LuciFmt.num(max))
    val pc = floor((100.0 / mn) * vn).toLong()
    return (pc / 100f) to "$fv / $fm ($pc%)"
}

@Composable
internal fun ProgressTable(rows: List<ProgressRow>) {
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, r ->
            if (r.value == null) InfoRow(r.label, "?")
            else {
                val (f, t) = progress(r.value, r.max, r.bytes)
                UsageBar(r.label, f, t)
            }
            if (i < rows.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.double

internal class MemoryInclude : StatusInclude("Memory") {
    override suspend fun fetch(ctx: PageContext, net: NetState): Any {
        val info = resolveDefault<JsonElement?>(null) { ctx.call("system", "info") }
        val mem = info["memory"].obj ?: JsonObject(emptyMap())
        val swap = info["swap"].obj ?: JsonObject(emptyMap())
        fun t(k: String) = mem[k].num()?.takeIf { it != 0.0 }
        val total = mem["total"].num()
        val rows = mutableListOf(
            ProgressRow("Total Available", t("available") ?: if (t("total") != null && t("free") != null && t("buffered") != null) t("free")!! + t("buffered")!! else null, total, true),
            ProgressRow("Used", if (t("total") != null && t("free") != null) t("total")!! - t("free")!! else null, total, true),
        )
        if (t("buffered") != null) rows += ProgressRow("Buffered", t("buffered"), total, true)
        if (t("cached") != null) rows += ProgressRow("Cached", t("cached"), total, true)
        if ((swap["total"].num() ?: 0.0) > 0) rows += ProgressRow("Swap free", swap["free"].num(), swap["total"].num(), true)
        return rows
    }

    @Composable
    override fun Content(model: Any, env: OverviewEnv) {
        @Suppress("UNCHECKED_CAST")
        ProgressTable(model as List<ProgressRow>)
    }
}

internal class StorageInclude : StatusInclude("Storage") {
    private val skip = listOf("/rom", "/tmp", "/dev", "/overlay", "/")

    override suspend fun fetch(ctx: PageContext, net: NetState): Any = coroutineScope {
        val infoD = async { resolveDefault<JsonElement?>(null) { ctx.call("system", "info") } }
        val mountsD = async { resolveDefault<JsonElement?>(null) { ctx.rpc("luci", "getMountPoints")["result"] } }
        val info = infoD.await()
        val root = info["root"].obj ?: JsonObject(emptyMap())
        val tmp = info["tmp"].obj ?: JsonObject(emptyMap())
        val fields = LinkedHashMap<String, ProgressRow>()
        fun chk(name: String, used: Double?, size: Double?) { if (!fields.containsKey(name)) fields[name] = ProgressRow(name, used, size, true) }
        chk("Disk space", (root["used"].num() ?: Double.NaN) * 1024, (root["total"].num() ?: Double.NaN) * 1024)
        chk("Temp space", (tmp["used"].num() ?: Double.NaN) * 1024, (tmp["total"].num() ?: Double.NaN) * 1024)
        mountsD.await().arr?.forEach { e ->
            val mount = e["mount"].str
            if (mount in skip) return@forEach
            val size = e["size"].num() ?: Double.NaN
            val free = e["free"].num() ?: Double.NaN
            chk("${e["device"].str} ($mount)", size - free, size)
        }
        fields.values.toList()
    }

    @Composable
    override fun Content(model: Any, env: OverviewEnv) {
        @Suppress("UNCHECKED_CAST")
        ProgressTable(model as List<ProgressRow>)
    }
}
