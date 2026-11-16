package com.the412banner.wrtpanel.pages.firewall

import androidx.compose.runtime.Composable
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormPage
import com.the412banner.wrtpanel.form.FormRuntime
import com.the412banner.wrtpanel.form.deviceSelect
import com.the412banner.wrtpanel.form.networkSelect
import com.the412banner.wrtpanel.ui.PageContext

/** Network › Firewall › General Settings — port of luci-app-firewall view/firewall/zones.js. */
@Composable
fun FirewallZonesPage(ctx: PageContext) = FormPage(ctx) {
    val fd = loadFwData(helpers = true)
    if (FwModel.checkLegacySNAT(uci)) return@FormPage migrationMap(null, null)
    FwModel.defaultsSid(uci)
    val fw4 = fd.fw4

    val m = FormMap(FW, "Firewall - Zone Settings", "The firewall creates zones over your network interfaces to control network traffic flow.")

    m.typed("defaults", "General Settings") {
        anonymous = true
        addremove = false
        flag("synflood_protect", "Enable SYN-flood protection") {
            cfgvalueFn = { rt, sid -> rt.uci.get(FW, sid, "synflood_protect") ?: rt.uci.get(FW, sid, "syn_flood") }
            writeFn = { rt, sid, v -> rt.uci.unset(FW, sid, "syn_flood"); rt.uci.set(FW, sid, "synflood_protect", v) }
            removeFn = { rt, sid -> rt.uci.unset(FW, sid, "syn_flood"); rt.uci.unset(FW, sid, "synflood_protect") }
        }
        flag("drop_invalid", "Drop invalid packets")
        listOf("input" to "Input", "output" to "Output", "forward" to "Forward").forEach { (n, t) ->
            list(n, t) { value("REJECT", "reject"); value("DROP", "drop"); value("ACCEPT", "accept") }
        }
    }

    if (session.hasFeature("offloading")) {
        m.typed("defaults", "Routing/NAT Offloading", "Not fully compatible with QoS/SQM.") {
            anonymous = true
            addremove = false
            list("offloading_type", "Flow offloading type") {
                value("0", "None")
                value("1", "Software flow offloading", "Software based offloading for routing/NAT.")
                value("2", "Hardware flow offloading", "Hardware based offloading for routing with/without NAT." + " " + " Requires hardware NAT support.")
                optional = false
                cfgvalueFn = { rt, sid ->
                    val fo = rt.uci.getString(FW, sid, "flow_offloading")
                    val hw = rt.uci.getString(FW, sid, "flow_offloading_hw")
                    if (fo == "1") (if (hw == "1") "2" else "1") else "0"
                }
                writeFn = { rt, sid, v ->
                    rt.uci.set(FW, sid, "flow_offloading", if (v == "0") null else "1")
                    rt.uci.set(FW, sid, "flow_offloading_hw", if (v == "2") "1" else null)
                }
            }
        }
    }

    val defInput = FwModel.defaultInput(uci)
    val defOutput = FwModel.defaultOutput(uci)
    val defForward = FwModel.defaultForward(uci)

    m.grid("zone", "Zones") {
        addremove = true
        anonymous = true
        sortable = true
        nodescriptions = true
        modalTitle = { _ -> "Firewall - Zone Settings" }
        onRemove = { rt, sid -> FwModel.deleteZone(rt.uci, sid) }

        tab("general", "General Settings")
        tab("advanced", "Advanced Settings")
        tab("conntrack", "Conntrack Settings")
        tab("extra", "Extra iptables arguments")

        dummy("_generalinfo", tab = "general") {
            modalonly = true
            cfgvalueFn = { rt, sid ->
                val name = FwModel.zoneName(rt.uci, sid) ?: "this new zone"
                "This section defines common properties of %q. The <em>input</em> and <em>output</em> options set the default policies for traffic entering and leaving this zone while the <em>forward</em> option describes the policy for forwarded traffic between different networks within the zone. <em>Covered networks</em> specifies which available networks are members of this zone."
                    .replace("%s", name).replace("%q", "\"$name\"")
            }
        }

        value("name", "Name", tab = "general") {
            placeholder = "Unnamed zone"
            modalonly = true
            rmempty = false
            datatype = if (fw4) "ucifw4zonename" else "and(ucifw4zonename,maxlength(11))"
            writeFn = { rt, sid, v ->
                val cfg = rt.uci.getString(FW, sid, "name")
                if (cfg.isNullOrEmpty()) rt.uci.set(FW, sid, "name", v)
                else if (cfg != v.toString()) FwModel.renameZone(rt.uci, cfg, v.toString())
            }
        }

        dummy("_info", "Zone ⇒ Forwards") {
            editable = true
            modalonly = false
            cfgvalueFn = { rt, sid -> FwModel.zoneName(rt.uci, sid) }
            textvalueFn = { rt, sid ->
                val name = FwModel.zoneName(rt.uci, sid)
                if (name == null || FwModel.lookupZone(rt.uci, name) == null) "" else {
                    val dests = FwModel.forwardingsBy(rt.uci, name, "src").mapNotNull { f ->
                        FwModel.lookupZone(rt.uci, rt.uci.getString(FW, f, "dest"))?.let { FwModel.zoneName(rt.uci, it) }
                    }
                    val policy = FwModel.defaultForward(rt.uci) ?: ""
                    "$name ⇒ " + (dests + (if (dests.isEmpty()) policy else "$policy all others")).joinToString(" ")
                }
            }
            render = { rt, sid ->
                val name = FwModel.zoneName(rt.uci, sid)
                val zsid = FwModel.lookupZone(rt.uci, name)
                if (name != null && zsid != null) {
                    val src = ZoneChoice(name, name, true, "", FwTool.colorForName(name), zoneTooltip(rt.uci, fd, zsid))
                    val dests = FwModel.forwardingsBy(rt.uci, name, "src").mapNotNull { f ->
                        FwModel.lookupZone(rt.uci, rt.uci.getString(FW, f, "dest"))?.let { zoneChoice(rt.uci, fd, it) }
                    }
                    ZoneForwardsView(src, dests, FwModel.defaultForward(rt.uci) ?: "")
                }
            }
        }

        listOf(Triple("input", "Input", defInput), Triple("output", "Output", defOutput), Triple("forward", "Intra zone forward", defForward))
            .forEach { (n, t, d) ->
                list(n, t, tab = "general") {
                    value("REJECT", "reject"); value("DROP", "drop"); value("ACCEPT", "accept")
                    editable = true
                    default = d
                }
            }

        flag(
            "masq", "IPv4 Masquerading",
            "Enable network address and port translation IPv4 (NAT4 or NAPT4) for outbound traffic on this zone. This is typically enabled on the <em>wan</em> zone.",
            tab = "general",
        ) {
            editable = true
            tooltip = { rt, sid ->
                val family = rt.uci.getString(FW, sid, "family")
                val limited = rt.uci.get(FW, sid, "masq_src") != null || rt.uci.get(FW, sid, "masq_dest") != null
                if ((family.isNullOrEmpty() || !family.contains('6')) && limited) "Limited masquerading enabled" else null
            }
        }

        flag("mtu_fix", "MSS clamping", tab = "general") { modalonly = true }

        networkSelect("network", "Covered networks", tab = "general", multiple = true, nocreate = false) {
            modalonly = true
            cfgvalueFn = { rt, sid -> rt.uci.get(FW, sid, "network") }
            writeFn = { rt, sid, v ->
                val name = rt.uci.getString(FW, sid, "name")
                val old = UciStore.toArray(rt.uci.get(FW, sid, "network")).sorted()
                val new = UciStore.toArray(v).sorted()
                if (old != new) {
                    val nets = if (v is List<*>) new.mapNotNull { n ->
                        val sec = rt.uci.section("network", n)
                        when {
                            sec != null && sec[".type"] == "interface" -> n
                            sec == null && Regex("^[a-zA-Z0-9_]+$").matches(n) -> {
                                rt.uci.add("network", "interface", n)
                                rt.uci.set("network", n, "proto", "none")
                                n
                            }
                            else -> null
                        }
                    } else emptyList()
                    FwModel.lookupZone(rt.uci, name)?.let { zsid ->
                        rt.uci.unset(FW, zsid, "network")
                        nets.forEach { FwModel.zoneAddNetwork(rt.uci, zsid, it) }
                    }
                }
            }
        }

        dummy("_advancedinfo", tab = "advanced") {
            modalonly = true
            cfgvalueFn = { rt, sid ->
                val name = FwModel.zoneName(rt.uci, sid) ?: "this new zone"
                FORWARD_INFO.replace("%s", name).replace("%q", name)
            }
        }

        deviceSelect("device", "Covered devices", "Use this option to classify zone traffic by raw, non-<em>uci</em> managed network devices.", tab = "advanced", multiple = true, noaliases = true) {
            modalonly = true
        }

        dynlist("subnet", "Covered subnets", "Use this option to classify zone traffic by source or destination subnet instead of networks or devices.", tab = "advanced") {
            datatype = "neg(cidr(\"true\"))"
            modalonly = true
        }

        if (fw4) {
            flag("masq6", "IPv6 Masquerading", "Enable network address and port translation IPv6 (NAT6 or NAPT6) for outbound traffic on this zone.", tab = "advanced") {
                modalonly = true
                tooltip = { rt, sid ->
                    val family = rt.uci.getString(FW, sid, "family")
                    val limited = rt.uci.get(FW, sid, "masq_src") != null || rt.uci.get(FW, sid, "masq_dest") != null
                    if ((family.isNullOrEmpty() || family.contains('6')) && limited) "Limited masquerading enabled" else null
                }
            }
        }

        list("family", "Restrict to address family", tab = "advanced") {
            value("", "IPv4 and IPv6")
            value("ipv4", "IPv4 only")
            value("ipv6", "IPv6 only")
            modalonly = true
        }

        for ((n, t) in listOf("masq_src" to "Restrict Masquerading to given source subnets", "masq_dest" to "Restrict Masquerading to given destination subnets")) {
            dynlist(n, t, tab = "advanced") {
                if (fw4) {
                    datatype = "list(neg(or(uciname,hostname,ipmask)))"
                } else {
                    depends("family", "")
                    depends("family", "ipv4")
                    datatype = "list(neg(or(uciname,hostname,ipmask4)))"
                }
                placeholder = "0.0.0.0/0"
                modalonly = true
            }
        }

        flag(
            "masq_allow_invalid", "Allow \"invalid\" traffic",
            "Do not install extra rules to reject forwarded traffic with conntrack state <em>invalid</em>. This may be required for complex asymmetric route setups.",
            tab = "conntrack",
        ) { modalonly = true }

        flag("auto_helper", "Automatic helper assignment", "Automatically assign conntrack helpers based on traffic protocol and port", tab = "conntrack") {
            default = enabled
            modalonly = true
        }

        multi("helper", "Conntrack helpers", "Explicitly choses allowed connection tracking helpers for zone traffic", tab = "conntrack") {
            depends("auto_helper", "0")
            modalonly = true
            fd.ctHelpers.forEach { value(it.name, helperLabel(it)) }
        }

        val tables = linkedMapOf("filter" to 1L, "mangle" to 2L)
        multi("log", "Enable logging", "Log matched packets on the selected tables to syslog.", tab = "advanced") {
            modalonly = true
            value("filter")
            value("mangle")
            placeholder = "No table selected"
            cfgvalueFn = { rt, sid ->
                val bits = rt.uci.getString(FW, sid, "log")?.let { FwTool.jsNumber(it) }?.takeIf { !it.isNaN() }?.toLong() ?: 0L
                tables.filter { (_, b) -> bits and b != 0L }.keys.toList()
            }
            writeFn = { rt, sid, v ->
                val bits = FwTool.toArray(v).fold(0L) { acc, t -> acc or (tables[FwTool.jsString(t)] ?: 0L) }
                rt.uci.set(FW, sid, "log", bits.toString())
            }
        }

        value("log_limit", "Limit log messages", tab = "advanced") {
            dependsNot("log" to emptyList<String>())
            placeholder = "10/minute"
            modalonly = true
        }

        if (!fw4) {
            dummy("_extrainfo", tab = "extra") {
                modalonly = true
                cfgvalueFn = { _, _ ->
                    "Passing raw iptables arguments to source and destination traffic classification rules allows to match packets based on other criteria than interfaces or subnets. These options should be used with extreme care as invalid values could render the firewall ruleset broken, completely exposing all services."
                }
            }
            value(
                "extra_src", "Extra source arguments",
                "Additional raw <em>iptables</em> arguments to classify zone source traffic, e.g. <code>-p tcp --sport 443</code> to only match inbound HTTPS traffic.",
                tab = "extra",
            ) {
                modalonly = true
                cfgvalueFn = { rt, sid -> rt.uci.get(FW, sid, "extra_src") ?: rt.uci.get(FW, sid, "extra") }
                writeFn = { rt, sid, v -> rt.uci.unset(FW, sid, "extra"); rt.uci.set(FW, sid, "extra_src", v) }
            }
            value(
                "extra_dest", "Extra destination arguments",
                "Additional raw <em>iptables</em> arguments to classify zone destination traffic, e.g. <code>-p tcp --dport 443</code> to only match outbound HTTPS traffic.",
                tab = "extra",
            ) {
                modalonly = true
                cfgvalueFn = { rt, sid -> rt.uci.get(FW, sid, "extra_dest") ?: rt.uci.get(FW, sid, "extra_src") ?: rt.uci.get(FW, sid, "extra") }
                writeFn = { rt, sid, v -> rt.uci.unset(FW, sid, "extra"); rt.uci.set(FW, sid, "extra_dest", v) }
            }
        }

        dummy("_forwardinfo", tab = "general") {
            modalonly = true
            cfgvalueFn = { rt, sid ->
                val name = FwModel.zoneName(rt.uci, sid) ?: "this new zone"
                FORWARD_INFO.replace("%s", name).replace("%q", name)
            }
        }

        val notSelf: (FormRuntime, String, String) -> Boolean = { rt, sid, z -> FwModel.zoneName(rt.uci, sid) != z }
        for ((n, t) in listOf("out" to "Allow forward to <em>destination zones</em>:", "in" to "Allow forward from <em>source zones</em>:")) {
            val out = n == "out"
            fwZoneSelect(n, t, tab = "general", fd = fd, multiple = true, nocreate = true, filter = notSelf) {
                modalonly = true
                cfgvalueFn = { rt, sid -> forwardsOf(rt, sid, out) }
                writeFn = { rt, sid, v -> writeForwards(rt, sid, out, FwTool.toArray(v).map { FwTool.jsString(it) }) }
                removeFn = { rt, sid -> writeForwards(rt, sid, out, emptyList()) }
            }
        }
    }
    m
}

private const val FW = "firewall"

private const val FORWARD_INFO = "The options below control the forwarding policies between this zone (%s) and other zones. <em>Destination zones</em> cover forwarded traffic <strong>originating from %q</strong>. <em>Source zones</em> match forwarded traffic from other zones <strong>targeted at %q</strong>. The forwarding rule is <em>unidirectional</em>, e.g. a forward from lan to wan does <em>not</em> imply a permission to forward from wan to lan as well."

/** The ifaces of widgets.ZoneForwards renderZone(): networks, devices and subnets of a zone. */
private fun zoneTooltip(uci: UciStore, fd: FwData, zsid: String): String {
    val parts = ArrayList<String>()
    FwModel.zoneNetworks(uci, zsid).forEach { n ->
        val info = fd.ifaces[n] ?: return@forEach
        parts += "$n: " + info.devices.joinToString(" ").ifEmpty { "(empty)" }
    }
    parts += FwModel.zoneDevices(uci, zsid)
    val subnets = FwModel.zoneSubnets(uci, zsid)
    if (subnets.isNotEmpty()) parts += "{ ${subnets.joinToString("; ")} }"
    return parts.ifEmpty { listOf("(empty)") }.joinToString("  ")
}

private fun forwardsOf(rt: FormRuntime, sid: String, out: Boolean): List<String> {
    val name = FwModel.zoneName(rt.uci, sid)
    return FwModel.forwardingsBy(rt.uci, name, if (out) "src" else "dest").mapNotNull { rt.uci.getString(FW, it, if (out) "dest" else "src") }
}

private fun writeForwards(rt: FormRuntime, sid: String, out: Boolean, value: List<String>) {
    val name = FwModel.zoneName(rt.uci, sid)
    FwModel.lookupZone(rt.uci, name) ?: return
    for (f in FwModel.forwardingsBy(rt.uci, name, if (out) "src" else "dest")) {
        val cmp = rt.uci.getString(FW, f, if (out) "dest" else "src")
        if (value.none { it == cmp }) rt.uci.remove(FW, f)
    }
    for (fv in value) if (out) FwModel.addForwardingTo(rt.uci, name, fv) else FwModel.addForwardingFrom(rt.uci, name, fv)
}
