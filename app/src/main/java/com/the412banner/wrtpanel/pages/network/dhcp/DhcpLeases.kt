package com.the412banner.wrtpanel.pages.network.dhcp

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.double
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.core.strList
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.ResponsiveTable
import com.the412banner.wrtpanel.ui.components.formatUptime
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Result of the view's poll of luci-rpc getDHCPLeases, shared by both lease tables. */
internal class LeasePoll {
    val v4 = mutableStateOf<List<JsonElement>?>(null)
    val v6 = mutableStateOf<List<JsonElement>?>(null)

    suspend fun run(ctx: PageContext) {
        while (true) {
            try {
                val r = ctx.call("luci-rpc", "getDHCPLeases")
                v4.value = r["dhcp_leases"].arr ?: emptyList()
                v6.value = r["dhcp6_leases"].arr ?: emptyList()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            delay((ctx.client.env?.pollInterval ?: 5) * 1000L)
        }
    }
}

private fun expires(e: JsonElement?): String {
    val p = e as? JsonPrimitive
    if (p != null && p !is JsonNull && !p.isString && p.booleanOrNull == false) return "unlimited"
    val n = e.double ?: return formatUptime(0)
    return if (n <= 0) "expired" else formatUptime(n.toLong())
}

/** CBILeaseStatus / CBILease6Status of dhcp.js. */
@Composable
internal fun LeaseStatusTable(ctx: PageContext, poll: LeasePoll, v6: Boolean, hosts: JsonObject, macdata: JsonObject?) {
    if (!v6) LaunchedEffect(poll) { poll.run(ctx) }
    val withIface = ctx.hasSystemFeature("odhcpd", if (v6) "dhcpv6" else "dhcpv4")
    val headers = buildList {
        if (withIface) add("Interface")
        add("Hostname")
        if (v6) add("IPv6 addresses") else { add("IPv4 address"); add("MAC address") }
        add("DUID"); add("IAID"); add("Remaining time")
    }
    Column(Modifier.fillMaxWidth()) {
        Text(if (v6) "Active DHCPv6 Leases" else "Active DHCPv4 Leases", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 6.dp))
        val leases = (if (v6) poll.v6 else poll.v4).value
        if (leases == null) {
            Text("Collecting data...", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val rows = leases.map { lease -> if (v6) row6(lease, hosts) else row4(lease, hosts, macdata) }
            .map { if (withIface) listOf(it.first) + it.second else it.second }
        ResponsiveTable(
            headers = headers,
            rows = rows,
            primaryColumn = if (withIface) 1 else 0,
            empty = "There are no active leases",
        )
    }
}

private fun row4(lease: JsonElement, hosts: JsonObject, macdata: JsonObject?): Pair<String, List<String>> {
    val mac = lease["macaddr"].str
    var vendor: String? = null
    macdata?.forEach { (m, d) -> if (m.uppercase() == mac) vendor = d["vendor"].str?.takeIf { it.isNotEmpty() }?.let { " ($it)" } }
    val name = mac?.let { hosts[it] }?.get("name").str?.takeIf { it.isNotEmpty() }
    val hostname = lease["hostname"].str?.takeIf { it.isNotEmpty() }
    val host = when {
        name != null && hostname != null && hostname != name -> "$hostname ($name)"
        hostname != null -> hostname
        else -> null
    }
    return (lease["interface"].str ?: "-") to listOf(
        host ?: "-",
        lease["ipaddr"].str ?: "",
        if (vendor != null) (mac ?: "") + vendor else mac ?: "",
        lease["duid"].str?.takeIf { it.isNotEmpty() } ?: "-",
        lease["iaid"].str?.takeIf { it.isNotEmpty() } ?: "-",
        expires(lease["expires"]),
    )
}

private fun row6(lease: JsonElement, hosts: JsonObject): Pair<String, List<String>> {
    val mac = lease["macaddr"].str
    val hint = mac?.let { hosts[it] }
    val name = if (hint != null) {
        hint["name"].str?.takeIf { it.isNotEmpty() }
            ?: (hint["ipaddrs"] ?: hint["ipv4"]).strList.firstOrNull()
            ?: (hint["ip6addrs"] ?: hint["ipv6"]).strList.firstOrNull()
    } else null
    val hostname = lease["hostname"].str?.takeIf { it.isNotEmpty() }
    val host = when {
        name != null && hostname != null && hostname != name && lease["ip6addr"].str != name -> "$hostname ($name)"
        hostname != null -> hostname
        name != null -> name
        else -> null
    }
    val addrs = lease["ip6addrs"].arr?.let { a -> a.mapNotNull { it.str }.joinToString("\n") } ?: lease["ip6addr"].str ?: ""
    return (lease["interface"].str ?: "-") to listOf(
        host ?: "-",
        addrs,
        lease["duid"].str ?: "",
        lease["iaid"].str ?: "",
        expires(lease["expires"]),
    )
}
