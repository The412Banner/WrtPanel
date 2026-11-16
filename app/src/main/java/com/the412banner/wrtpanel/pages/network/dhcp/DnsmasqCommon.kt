package com.the412banner.wrtpanel.pages.network.dhcp

import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.core.strList
import com.the412banner.wrtpanel.form.FormSection
import com.the412banner.wrtpanel.ui.PageContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** L.hasSystemFeature(name[, sub]) */
internal fun PageContext.hasSystemFeature(name: String, sub: String? = null): Boolean {
    val ft = session.features[name]
    if (sub != null) return (ft as? JsonObject)?.get(sub)?.bool == true
    return ft != null && ft !is JsonNull && ft.bool != false
}

/** validateHostname() of dhcp.js / dns.js; null = valid. */
internal fun validateHostname(s: String): String? {
    if (s.isEmpty()) return null
    if (s.length > 256) return "Expecting: valid hostname"
    val labels = s.replace(Regex("^\\*?\\.?|\\.$"), "").split('.')
    val re = Regex("^[a-z0-9_](?:[a-z0-9-]{0,61}[a-z0-9])?$", RegexOption.IGNORE_CASE)
    for (label in labels) if (!re.matches(label)) return "Expecting: valid hostname"
    return null
}

/** The renderContents() override of both views: instance headings and remove buttons only with 2+ instances. */
internal fun FormSection.dnsmasqInstances(uci: UciStore) {
    addbtntitle = "Add server instance"
    addNamePlaceholder = "New instance name…"
    sectionTitle = { sid ->
        val list = uci.sections(config, "dnsmasq")
        val i = list.indexOfFirst { it[".name"] == sid }
        val section = list.getOrNull(i)
        when {
            list.size < 2 -> ""
            section == null || section[".anonymous"] == true -> if (i > 0) "Unnamed instance #${i + 1}" else "Default instance"
            else -> "Instance \"${section[".name"]}\""
        }
    }
    noDelete = { uci.sections(config, "dnsmasq").size < 2 }
}

/** generateDnsmasqInstanceEntry() of dhcp.js: (name, label) choices of the "Instance" options. */
internal fun dnsmasqInstanceChoices(uci: UciStore): List<Pair<String, String>> =
    uci.sections("dhcp", "dnsmasq").map { d ->
        val idx = d[".index"] ?: 0
        val name = d[".name"]?.toString() ?: ""
        val label = if (d[".anonymous"] == true) "dnsmasq[$idx]" else name
        val parts = mutableListOf("$idx (Name: $label")
        (d["domain"] as? String)?.takeIf { it.isNotEmpty() }?.let { parts += "Domain: $it" }
        (d["local"] as? String)?.takeIf { it.isNotEmpty() }?.let { parts += "Local: $it" }
        name to parts.joinToString(", ") + ")"
    }

internal val logFacilities = listOf(
    "KERN", "USER", "MAIL", "DAEMON", "AUTH", "LPR", "NEWS", "UUCP", "CRON",
    "LOCAL0", "LOCAL1", "LOCAL2", "LOCAL3", "LOCAL4", "LOCAL5", "LOCAL6", "LOCAL7",
)

/** L.sortedKeys(obj, null, 'addr') sort key. */
internal fun addrSortKey(v: String): String =
    Regex("(?:^|[.:])([0-9a-fA-F]{1,4})").replace(v) { ("000" + it.groupValues[1].lowercase()).takeLast(4) }

/** IPv4 hint addresses of getHostHints → hostname or MAC, sorted like L.sortedKeys(..., 'addr'). */
internal fun hostHintIPv4(hosts: JsonObject): List<Pair<String, String>> {
    val ipaddrs = LinkedHashMap<String, String>()
    hosts.forEach { (mac, h) ->
        val name = h["name"].str
        (h["ipaddrs"] ?: h["ipv4"]).strList.forEach { ipaddrs[it] = name ?: mac }
    }
    return ipaddrs.keys.sortedBy(::addrSortKey).map { it to ipaddrs.getValue(it) }
}
