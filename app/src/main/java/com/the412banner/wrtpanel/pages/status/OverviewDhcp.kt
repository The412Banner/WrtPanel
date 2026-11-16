package com.the412banner.wrtpanel.pages.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.long
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.form.Datatypes
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.ResponsiveTable
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class LeaseRow(
    val key: String,
    val cells: List<String>,
    val italicCol: Int?,
    val canReserve: Boolean,
    val disabled: Boolean,
    val v6: Boolean,
    val hostname: String?,
    val ipaddr: String?,
    val macaddr: String?,
    val duid: String?,
    val iaid: String?,
    val ip6first: String?,
)

internal data class DhcpModel(
    val noLeases: Boolean,
    val headers4: List<String>,
    val headers6: List<String>,
    val rows4: List<LeaseRow>,
    val rows6: List<LeaseRow>,
    val readonly: Boolean,
)

/** Status › Overview include 40_dhcp — port of luci-mod-status view/status/include/40_dhcp.js. */
internal class DhcpInclude : StatusInclude("DHCP Leases") {
    override suspend fun fetch(ctx: PageContext, net: NetState): Any? {
        val leasesRes = ctx.rpc("luci-rpc", "getDHCPLeases").obj ?: JsonObject(emptyMap())
        val hints = net.hostHints()
        val ufp = if (ctx.sysFeature("ufpd")) ctx.rpc("fingerprint", "fingerprint").obj else null
        resolveDefault(Unit) { ctx.uci.load("dhcp") }

        if (!(ctx.sysFeature("dnsmasq") || ctx.sysFeature("odhcpd"))) return null

        val leases = leasesRes["dhcp_leases"].arr ?: emptyList()
        val leases6 = leasesRes["dhcp6_leases"].arr ?: emptyList()
        val readonly = ctx.isReadonlyView
        if (leases.isEmpty() && leases6.isEmpty()) return DhcpModel(true, emptyList(), emptyList(), emptyList(), emptyList(), readonly)
        val machints = hints.macHints(false)

        val macStatic = mutableSetOf<String>()
        val duidStatic = mutableSetOf<String>()
        val duidIaidStatic = mutableSetOf<String>()
        for (host in ctx.uci.sections("dhcp", "host")) {
            UciStore.toArray(host["mac"]).forEach { macStatic += it.lowercase() }
            for (d in UciStore.toArray(host["duid"]).map { it.lowercase() }) {
                val parts = d.split('%').size
                if (parts == 1) duidStatic.add(d) else if (parts == 2) duidIaidStatic.add(d)
            }
        }

        fun exp(e: JsonElement?): Pair<String, Boolean> = when {
            e is JsonPrimitive && !e.isString && e.content == "false" -> "unlimited" to true
            e.long != null && e.long!! <= 0 -> "expired" to true
            else -> LuciFmt.t(e.long) to false
        }

        val v4iface = ctx.sysFeature("odhcpd", "dhcpv4")
        val v6iface = ctx.sysFeature("odhcpd", "dhcpv6")

        val rows4 = leases.mapIndexed { i, lease ->
            val (expText, expEm) = exp(lease["expires"])
            val mac = lease["macaddr"].str
            val hostname = lease["hostname"].str?.ifEmpty { null }
            val hint = if (mac != null) machints.firstOrNull { it.first == mac } else null
            val host = if (hint != null && hostname != null && hostname != hint.second) "$hostname (${hint.second})" else hostname
            val vendor = if (ufp != null && mac != null) ufp[mac.lowercase()]["vendor"].str else null
            val cells = mutableListOf(
                host ?: "-",
                lease["ipaddr"].str ?: "",
                if (vendor != null) "$mac ($vendor)" else (mac ?: ""),
                lease["duid"].str?.ifEmpty { null } ?: "-",
                lease["iaid"].str?.ifEmpty { null } ?: "-",
                expText,
            )
            if (v4iface) cells.add(0, lease["interface"].str?.ifEmpty { null } ?: "-")
            LeaseRow(
                key = "4-$i-$mac", cells = cells, italicCol = if (expEm) cells.size - 1 else null,
                canReserve = !readonly && mac != null, disabled = mac != null && macStatic.contains(mac.lowercase()), v6 = false,
                hostname = hostname, ipaddr = lease["ipaddr"].str, macaddr = mac, duid = null, iaid = null, ip6first = null,
            )
        }

        val rows6 = leases6.mapIndexed { i, lease ->
            val (expText, expEm) = exp(lease["expires"])
            val mac = lease["macaddr"].str
            val hostname = lease["hostname"].str?.ifEmpty { null }
            val hint = if (mac != null) machints.firstOrNull { it.first == mac } else null
            val host = when {
                hint != null && hostname != null && hostname != hint.second && lease["ip6addr"].str != hint.second -> "$hostname (${hint.second})"
                hostname != null -> hostname
                hint != null -> hint.second
                else -> null
            }
            val duid = lease["duid"].str?.ifEmpty { null }?.lowercase()
            val iaid = lease["iaid"].str?.ifEmpty { null }?.lowercase()
            val disabled = duid == null || duidStatic.contains(duid) || (iaid != null && duidIaidStatic.contains("$duid%$iaid"))
            val addrs = lease["ip6addrs"].arr?.mapNotNull { it.str }
            val cells = mutableListOf(
                host ?: "-",
                addrs?.joinToString("\n") ?: (lease["ip6addr"].str ?: ""),
                duid ?: "-",
                iaid ?: "-",
                expText,
            )
            if (v6iface) cells.add(0, lease["interface"].str?.ifEmpty { null } ?: "-")
            LeaseRow(
                key = "6-$i-$duid", cells = cells, italicCol = if (expEm) cells.size - 1 else null,
                canReserve = !readonly && lease["duid"].str?.isNotEmpty() == true, disabled = disabled, v6 = true,
                hostname = hostname, ipaddr = null, macaddr = mac, duid = lease["duid"].str, iaid = lease["iaid"].str,
                ip6first = addrs?.firstOrNull(),
            )
        }

        val h4 = mutableListOf("Hostname", "IPv4 address", "MAC address", "DUID", "IAID", "Remaining time")
        if (v4iface) h4.add(0, "Interface")
        val h6 = mutableListOf("Hostname", "IPv6 addresses", "DUID", "IAID", "Remaining time")
        if (v6iface) h6.add(0, "Interface")
        return DhcpModel(false, h4, h6, rows4, rows6, readonly)
    }

    private suspend fun reserve(ctx: PageContext, l: LeaseRow) {
        val cfg = ctx.uci.add("dhcp", "host")
        if (!l.v6) {
            ctx.uci.set("dhcp", cfg, "name", l.hostname)
            ctx.uci.set("dhcp", cfg, "ip", l.ipaddr)
            ctx.uci.set("dhcp", cfg, "mac", listOf(l.macaddr!!.lowercase()))
        } else {
            val ip6addr = l.ip6first?.replace(Regex("/128$"), "")
            val ip6arr = ip6addr?.let { Datatypes.parseIPv6(it) }
            var duidIaid = l.duid!!.lowercase()
            if (!l.iaid.isNullOrEmpty()) duidIaid += "%${l.iaid}".lowercase()
            ctx.uci.set("dhcp", cfg, "name", l.hostname)
            ctx.uci.set("dhcp", cfg, "duid", listOf(duidIaid))
            if (!l.macaddr.isNullOrEmpty()) ctx.uci.set("dhcp", cfg, "mac", listOf(l.macaddr.lowercase()))
            if (ip6arr != null) ctx.uci.set("dhcp", cfg, "hostid", (ip6arr[6].toLong() * 0xFFFF + ip6arr[7]).toString(16))
        }
        ctx.uci.save()
        ctx.session.changes.afterSave()
    }

    @Composable
    override fun Content(model: Any, env: OverviewEnv) {
        val m = model as DhcpModel
        if (m.noLeases) {
            Text("No active leases found", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
            return
        }
        val busy = remember { mutableStateListOf<String>() }
        fun actions(rows: List<LeaseRow>): @Composable RowScope.(Int) -> Unit = { r ->
            val l = rows[r]
            if (l.canReserve) {
                FilledTonalButton(
                    onClick = {
                        busy += l.key
                        env.ctx.scope.launch {
                            try { reserve(env.ctx, l) } catch (e: Exception) { env.ctx.toast(e.message ?: "Error"); busy -= l.key }
                        }
                    },
                    enabled = !l.disabled && l.key !in busy,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) { Text("Reserve IP", style = MaterialTheme.typography.labelMedium) }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Triple("Active DHCPv4 Leases", m.headers4, m.rows4), Triple("Active DHCPv6 Leases", m.headers6, m.rows6)).forEach { (title, headers, rows) ->
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                if (rows.isEmpty()) {
                    Text("No active leases found", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    ResponsiveTable(
                        headers = headers,
                        rows = rows.map { it.cells },
                        primaryColumn = if (headers.first() == "Interface") 1 else 0,
                        cell = { r, c, t ->
                            SelectionContainer {
                                Text(
                                    t.ifEmpty { "-" }, style = MaterialTheme.typography.bodyMedium,
                                    fontStyle = if (rows[r].italicCol == c) FontStyle.Italic else FontStyle.Normal,
                                )
                            }
                        },
                        rowActions = actions(rows).takeIf { !m.readonly },
                    )
                }
            }
        }
    }
}
