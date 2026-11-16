package com.the412banner.wrtpanel.pages.network

import androidx.compose.runtime.Composable
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormPage
import com.the412banner.wrtpanel.form.networkSelect
import com.the412banner.wrtpanel.pages.common.NetTools
import com.the412banner.wrtpanel.ui.PageContext

/** Network › Routing — port of luci-mod-network view/network/routes.js. */
@Composable
fun RoutesPage(ctx: PageContext) = FormPage(ctx) {
    val netDevs = com.the412banner.wrtpanel.form.NetData.devices(this)
    val rtTables = NetTools.parseRtTables(fs.readOrNull("/etc/iproute2/rt_tables") ?: "")

    val m = FormMap(
        "network", "Routing",
        "Routing defines over which interface and gateway a certain host or network can be reached.<br/>" +
            "Routes go in routing tables and define the specific path to reach destinations.<br/>" +
            "Rules determine which routing table to use, based on conditions like source address or interface.",
    )
    m.tabbed = true

    for (family in listOf(4, 6)) {
        val v6 = family == 6
        m.grid(if (v6) "route6" else "route", if (v6) "Static IPv6 Routes" else "Static IPv4 Routes") {
            anonymous = true; addremove = true; sortable = true; nodescriptions = true
            tab("general", "General Settings")
            tab("advanced", "Advanced Settings")

            networkSelect("interface", "Interface", "Specifies the logical interface name of the parent (or master) interface this route belongs to", tab = "general") {
                rmempty = true
            }
            list("type", "Route type", "Specifies the route type to be created", tab = "general") {
                modalonly = true
                value("", "unicast")
                listOf("local", "broadcast", "multicast", "unreachable", "prohibit", "blackhole", "anycast", "throw").forEach { value(it) }
            }
            value("target", "Target", "Network address", tab = "general") {
                rmempty = false
                datatype = if (v6) "cidr6" else "cidr4"
                placeholder = if (v6) "::/0" else "0.0.0.0/0"
                cfgvalueFn = { rt, sid ->
                    val type = rt.uci.section("network", sid)?.get(".type")
                    val target = rt.uci.getString("network", sid, "target")
                    val mask = rt.uci.getString("network", sid, "netmask")
                    val sv6 = type == "route6"
                    val bits = if (mask != null) NetTools.maskToPrefix(mask, sv6) else if (sv6) 128 else 32
                    if (target != null) (if (target.contains('/')) target else "$target/$bits") else null
                }
                writeFn = { rt, sid, v ->
                    rt.uci.set("network", sid, "target", v)
                    rt.uci.unset("network", sid, "netmask")
                }
            }
            value(
                "gateway", "Gateway",
                "Specifies the network gateway. If omitted, the gateway from the parent interface is taken if any, otherwise creates a link scope route. If set to 0.0.0.0 no gateway will be specified for the route",
                tab = "general",
            ) {
                datatype = if (v6) "ip6addr(\"nomask\")" else "ip4addr(\"nomask\")"
                placeholder = if (v6) "fe80::1" else "192.168.0.1"
            }
            value("metric", "Metric", "Ordinal: routes with the lowest metric match first", tab = "advanced") {
                datatype = "uinteger"; placeholder = "0"
                textvalueFn = { rt, sid -> rt.uci.getString("network", sid, "metric") ?: "auto" }
            }
            value("mtu", "MTU", "Packets exceeding this value may be fragmented", tab = "advanced") {
                modalonly = true; datatype = "and(uinteger,range(64,9000))"; placeholder = "1500"
            }
            value(
                "table", "Table",
                "Routing table into which to insert this rule.<br/>A numeric table index, or symbol alias declared in <code>/etc/iproute2/rt_tables</code>. Special aliases local (255), main (254) and default (253) are also valid<br/>Only interfaces using this table (via override) will use this route.",
                tab = "advanced",
            ) {
                datatype = "or(uinteger, string)"
                rtTables.forEach { (id, n) -> value(n, "$n ($id)") }
                textvalueFn = { rt, sid -> rt.uci.getString("network", sid, "table") ?: "auto" }
            }
            value(
                "source", "Source",
                "Specifies the preferred source address when sending to destinations covered by the target<br/>This is only used if no default route matches the destination gateway",
                tab = "advanced",
            ) {
                modalonly = true
                datatype = if (v6) "ip6addr" else "ip4addr"
                netDevs.forEach { d -> (if (v6) d.ip6 else d.ip4).forEach { a -> value(a.substringBefore('/')) } }
            }
            flag("onlink", "On-link", "When enabled, gateway is on-link even if the gateway does not match any interface prefix", tab = "advanced") {
                modalonly = true
            }
            flag("disabled", "Disable", tab = "advanced") {
                modalonly = false; editable = true
            }
        }
    }

    for (family in listOf(4, 6)) {
        val v6 = family == 6
        m.grid(if (v6) "rule6" else "rule", if (v6) "IPv6 Rules" else "IPv4 Rules") {
            anonymous = true; addremove = true; sortable = true; nodescriptions = true
            tab("general", "General Settings")
            tab("advanced", "Advanced Settings")

            value("priority", "Priority", "Execution order of this IP rule: lower numbers go first", tab = "general") {
                datatype = "uinteger"; placeholder = "30000"
                textvalueFn = { rt, sid -> rt.uci.getString("network", sid, "priority") ?: "auto" }
            }
            list("action", "Rule type", "Specifies the rule target routing action", tab = "general") {
                modalonly = true
                value("", "unicast"); listOf("unreachable", "prohibit", "blackhole", "throw").forEach { value(it) }
            }
            networkSelect("in", "Incoming interface", "Match traffic from this interface", tab = "general")
            value("src", "Source", "Match traffic from this source subnet (CIDR notation)", tab = "general") {
                datatype = if (v6) "cidr6" else "cidr4"; placeholder = if (v6) "::/0" else "0.0.0.0/0"
                textvalueFn = { rt, sid -> rt.uci.getString("network", sid, "src") ?: "any" }
            }
            value("ipproto", "IP Protocol", "Match traffic IP protocol type", tab = "general") {
                datatype = "range(0,255)"
                NetTools.protocols.forEach { p -> value(p.i.toString(), p.d) }
            }
            networkSelect("out", "Outgoing interface", "Match traffic destined to this interface", tab = "general")
            value("dest", "Destination", "Match traffic destined to this subnet (CIDR notation)", tab = "general") {
                datatype = if (v6) "cidr6" else "cidr4"; placeholder = if (v6) "::/0" else "0.0.0.0/0"
                textvalueFn = { rt, sid -> rt.uci.getString("network", sid, "dest") ?: "any" }
            }
            value(
                "lookup", "Table",
                "Routing table to use for traffic matching this rule.<br/>A numeric table index, or symbol alias declared in <code>/etc/iproute2/rt_tables</code>. Special aliases local (255), main (254) and default (253) are also valid<br/>Matched traffic re-targets to an interface using this table.",
                tab = "advanced",
            ) {
                datatype = "or(uinteger, string)"
                rtTables.forEach { (id, n) -> value(n, "$n ($id)") }
            }
            value("goto", "Jump to rule", "Jumps to another rule specified by its priority value", tab = "advanced") {
                modalonly = true; datatype = "uinteger"; placeholder = "80000"
            }
            value("mark", "Firewall mark", "Specifies the fwmark and optionally its mask to match, e.g. 0xFF to match mark 255 or 0x0/0x1 to match any even mark value", tab = "advanced") {
                modalonly = true; datatype = "string"; placeholder = "0x1/0xf"
            }
            value("sport", "Source port", "Match traffic from this source port (range)", tab = "advanced") {
                modalonly = true; datatype = "portrange"; placeholder = "0-65535"
            }
            value("dport", "Destination port", "Match traffic from this destination port (range)", tab = "advanced") {
                modalonly = true; datatype = "portrange"; placeholder = "0-65535"
            }
            value("tos", "Type of service", "Specifies the TOS value to match in IP headers", tab = "advanced") {
                modalonly = true; datatype = "uinteger"; placeholder = "10"
            }
            value("uidrange", "User identifier", "Specifies an individual UID or range of UIDs to match, e.g. 1000 to match corresponding UID or 1000-1005 to inclusively match all UIDs within the corresponding range", tab = "advanced") {
                modalonly = true; datatype = "string"; placeholder = "1000-1005"
            }
            value(
                "suppress_prefixlength", "Prefix suppressor",
                "Reject routing decisions that have a prefix length less than or equal to the specified value<br/>Prevents overly broad routes being considered. Setting 16 would consider /17, /24, /28 or more specific routes yet ignore /16, /8, /0 (default) routes",
                tab = "advanced",
            ) {
                modalonly = true; datatype = if (v6) "ip6prefix" else "ip4prefix"; placeholder = if (v6) "64" else "24"
            }
            flag("invert", "Invert match", "If set, the meaning of the match options is inverted", tab = "advanced") { modalonly = true }
            flag("disabled", "Disable", tab = "advanced") { modalonly = false; editable = true }
        }
    }
    m
}
