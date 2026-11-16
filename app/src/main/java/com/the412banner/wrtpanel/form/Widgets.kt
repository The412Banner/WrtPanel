package com.the412banner.wrtpanel.form

import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.ui.PageContext

/**
 * The selectors of LuCI's tools/widgets.js (ZoneSelect, NetworkSelect, DeviceSelect,
 * IPSelect, UserSelect, GroupSelect) as option builders. Choices are loaded during map load.
 */
object NetData {
    data class Device(val name: String, val type: String, val up: Boolean, val wireless: Boolean, val mac: String?, val ip4: List<String>, val ip6: List<String>)

    @Suppress("UNCHECKED_CAST")
    suspend fun devices(ctx: PageContext): List<Device> {
        (ctx.session.cache["devices"] as? List<Device>)?.let { return it }
        val r = ctx.callOrNull("luci-rpc", "getNetworkDevices").obj ?: return emptyList()
        val list = r.entries.map { (k, v) ->
            Device(
                name = k,
                type = when {
                    v["wireless"].bool == true -> "wifi"
                    v["bridge"].bool == true || v["devtype"].str == "bridge" -> "bridge"
                    v["devtype"].str == "vlan" -> "vlan"
                    v["devtype"].str == "tunnel" -> "tunnel"
                    else -> v["devtype"].str ?: "ethernet"
                },
                up = v["up"].bool == true,
                wireless = v["wireless"].bool == true,
                mac = v["mac"].str,
                ip4 = v["ipaddrs"].arr?.mapNotNull { it["address"].str } ?: emptyList(),
                ip6 = v["ip6addrs"].arr?.mapNotNull { it["address"].str } ?: emptyList(),
            )
        }.sortedBy { it.name }
        ctx.session.cache["devices"] = list
        return list
    }

    /** Logical interfaces (network.interface sections). */
    suspend fun networks(ctx: PageContext): List<String> {
        ctx.uci.load("network")
        return ctx.uci.sections("network", "interface").mapNotNull { it[".name"] as? String }
    }

    suspend fun zones(ctx: PageContext): List<Pair<String, List<String>>> {
        ctx.uci.load("firewall")
        return ctx.uci.sections("firewall", "zone").mapNotNull { z ->
            val n = z["name"] as? String ?: return@mapNotNull null
            n to com.the412banner.wrtpanel.core.UciStore.toArray(z["network"])
        }
    }

    suspend fun users(ctx: PageContext): List<String> =
        (ctx.fs.readOrNull("/etc/passwd") ?: "").lines().mapNotNull { l -> l.substringBefore(':').takeIf { it.isNotEmpty() } }

    suspend fun groups(ctx: PageContext): List<String> =
        (ctx.fs.readOrNull("/etc/group") ?: "").lines().mapNotNull { l -> l.substringBefore(':').takeIf { it.isNotEmpty() } }
}

/** widgets.ZoneSelect: [allowany] adds "Any zone", [allowlocal] adds "Device (input)". */
fun FormSection.zoneSelect(
    name: String, title: String? = null, description: String? = null, tab: String? = null,
    multiple: Boolean = false, allowany: Boolean = false, allowlocal: Boolean = false, nocreate: Boolean = true,
    block: ChoiceOption.() -> Unit = {},
): ChoiceOption {
    val o: ChoiceOption = if (multiple) multi(name, title, description, tab) else list(name, title, description, tab)
    o.loadFn = {
        val zones = NetData.zones(this)
        o.clearChoices()
        if (!multiple && (o.rmempty || o.optional) && !allowlocal) o.value("", "unspecified")
        if (allowlocal) o.value("", "Device (input)")
        if (allowany) o.value("*", "Any zone")
        zones.forEach { (z, nets) -> o.value(z, if (nets.isEmpty()) z else "$z: ${nets.joinToString(", ")}") }
    }
    if (o is MultiValueOption) o.create = !nocreate
    o.block()
    return o
}

/** widgets.NetworkSelect */
fun FormSection.networkSelect(
    name: String, title: String? = null, description: String? = null, tab: String? = null,
    multiple: Boolean = false, nocreate: Boolean = true, exclude: String? = null, novirtual: Boolean = false,
    filter: ((String) -> Boolean)? = null,
    block: ChoiceOption.() -> Unit = {},
): ChoiceOption {
    val o: ChoiceOption = if (multiple) multi(name, title, description, tab) else list(name, title, description, tab)
    o.loadFn = {
        val nets = NetData.networks(this)
        o.clearChoices()
        if (!multiple && (o.rmempty || o.optional)) o.value("", "unspecified")
        nets.filter { it != "loopback" && it != exclude && (filter?.invoke(it) ?: true) }.forEach { n ->
            if (novirtual) {
                val proto = uci.getString("network", n, "proto")
                if (proto in setOf("6in4", "6rd", "6to4", "dslite", "gre", "gretap", "ipip", "ipip6", "map", "vti", "wireguard", "pptp", "l2tp", "openvpn")) return@forEach
            }
            o.value(n, n)
        }
    }
    if (o is MultiValueOption) o.create = !nocreate
    o.datatype = if (multiple) "list(uciname)" else "uciname"
    o.block()
    return o
}


/** widgets.DeviceSelect: network devices plus "@network" aliases unless [noaliases]. */
fun FormSection.deviceSelect(
    name: String, title: String? = null, description: String? = null, tab: String? = null,
    multiple: Boolean = false, nocreate: Boolean = false, noaliases: Boolean = true, noinactive: Boolean = false,
    exclude: String? = null, filter: ((String) -> Boolean)? = null,
    block: ChoiceOption.() -> Unit = {},
): ChoiceOption {
    val o: ChoiceOption = if (multiple) dynlist(name, title, description, tab) else value(name, title, description, tab)
    o.loadFn = {
        val devs = NetData.devices(this)
        o.clearChoices()
        if (!multiple && (o.rmempty || o.optional) && nocreate) o.value("", "unspecified")
        devs.filter { it.name != "lo" && it.name != exclude && (filter?.invoke(it.name) ?: true) && (!noinactive || it.up) }.forEach { d ->
            val kind = when (d.type) { "wifi" -> "Wireless"; "bridge" -> "Bridge"; "vlan" -> "VLAN"; "tunnel" -> "Tunnel"; else -> "Ethernet" }
            o.value(d.name, "${d.name} ($kind${if (d.up) "" else ", down"})")
        }
        // Devices only configured in UCI (not present yet)
        uci.load("network")
        uci.sections("network", "device").mapNotNull { it["name"] as? String }.filter { n -> devs.none { it.name == n } && n != exclude }.forEach { o.value(it, "$it (configured)") }
        if (!noaliases) NetData.networks(this).filter { it != "loopback" }.forEach { o.value("@$it", "Alias interface: @$it") }
    }
    if (o is ValueOption) o.allowCustom = !nocreate
    o.block()
    return o
}

/** widgets.IPSelect: addresses of local devices. */
fun FormSection.ipSelect(
    name: String, title: String? = null, description: String? = null, tab: String? = null,
    multiple: Boolean = false, family: Int = 0,
    block: ChoiceOption.() -> Unit = {},
): ChoiceOption {
    val o: ChoiceOption = if (multiple) dynlist(name, title, description, tab) else value(name, title, description, tab)
    o.loadFn = {
        o.clearChoices()
        NetData.devices(this).forEach { d ->
            if (family != 6) d.ip4.forEach { o.value(it, "$it (${d.name})") }
            if (family != 4) d.ip6.forEach { o.value(it, "$it (${d.name})") }
        }
    }
    o.datatype = if (multiple) "list(ipaddr)" else "ipaddr"
    o.block()
    return o
}

fun FormSection.userSelect(name: String, title: String? = null, description: String? = null, tab: String? = null, block: ChoiceOption.() -> Unit = {}): ChoiceOption {
    val o = list(name, title, description, tab)
    o.loadFn = { o.clearChoices(); NetData.users(this).forEach { o.value(it) } }
    o.block()
    return o
}

fun FormSection.groupSelect(name: String, title: String? = null, description: String? = null, tab: String? = null, block: ChoiceOption.() -> Unit = {}): ChoiceOption {
    val o = list(name, title, description, tab)
    o.loadFn = { o.clearChoices(); NetData.groups(this).forEach { o.value(it) } }
    o.block()
    return o
}
