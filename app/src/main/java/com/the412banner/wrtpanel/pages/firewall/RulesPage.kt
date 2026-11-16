package com.the412banner.wrtpanel.pages.firewall

import androidx.compose.runtime.Composable
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormPage
import com.the412banner.wrtpanel.form.FormSection
import com.the412banner.wrtpanel.form.deviceSelect
import com.the412banner.wrtpanel.ui.PageContext

/** Network › Firewall › Traffic Rules — port of luci-app-firewall view/firewall/rules.js. */
@Composable
fun FirewallRulesPage(ctx: PageContext) = FormPage(ctx) {
    val fd = loadFwData(hosts = true, helpers = true)
    if (FwModel.checkLegacySNAT(uci)) return@FormPage migrationMap(null, null)

    val m = FormMap(
        FW, "Firewall - Traffic Rules",
        "Traffic rules define policies for packets travelling between different zones, for example to reject traffic between certain hosts or to open WAN ports on the router.",
    )

    m.grid("rule", "Traffic Rules") {
        addremove = true
        anonymous = true
        sortable = true
        cloneable()

        tab("general", "General Settings")
        tab("advanced", "Advanced Settings")
        tab("timed", "Time Restrictions")

        filter = { _, sec -> sec["target"] != "SNAT" }
        val nameOf: (String) -> String = { sid -> uci.getString(FW, sid, "name")?.takeIf { it.isNotEmpty() } ?: "Unnamed rule" }
        sectionTitle = nameOf
        modalTitle = { sid -> "Firewall - Traffic Rules - ${nameOf(sid)}" }
        onAdd = { rt, sid ->
            rt.uci.set(FW, sid, "src", "wan")
            rt.uci.set(FW, sid, "dest", "lan")
        }

        value("name", "Name", tab = "general") {
            placeholder = "Unnamed rule"
            modalonly = true
        }

        richColumn("_match", "Match") { rt, sid ->
            val g: (String) -> Any? = { rt.uci.get(FW, sid, it) }
            listOf(FwText.ruleProto(g, fd.ctHelpers), FwText.ruleSrc(g, fd.hosts), FwText.ruleDest(g), FwText.limit(g))
        }

        richColumn("_target", "Action") { rt, sid -> listOf(FwText.ruleTarget({ rt.uci.get(FW, sid, it) }, fd.ctHelpers)) }

        flag("enabled", "Enable") {
            modalonly = false
            default = enabled
            rmempty = false
            editable = true
            tooltip = { rt, sid ->
                val timed = listOf("weekdays", "monthdays", "start_time", "stop_time", "start_date", "stop_date").any { rt.uci.get(FW, sid, it) != null }
                if (timed) "Time restrictions are enabled for this rule" else null
            }
        }

        list("direction", "Match device", tab = "advanced") {
            modalonly = true
            value("", "unspecified")
            value("in", "Inbound device")
            value("out", "Outbound device")
            cfgvalueFn = { rt, sid ->
                when (rt.uci.getString(FW, sid, "direction")) {
                    "in", "ingress" -> "in"
                    "out", "egress" -> "out"
                    else -> null
                }
            }
        }

        deviceSelect("device", "Device name", "Specifies whether to tie this traffic rule to a specific inbound or outbound network device.", tab = "advanced", noaliases = true) {
            modalonly = true
            rmempty = false
            depends("direction", "in")
            depends("direction", "out")
        }

        list("family", "Restrict to address family", tab = "advanced") {
            modalonly = true
            rmempty = true
            value("", "IPv4 and IPv6")
            value("ipv4", "IPv4 only")
            value("ipv6", "IPv6 only")
        }

        protoSelect("proto", "Protocol", "general", "tcp udp") { modalonly = true }

        multi("icmp_type", "Match ICMP type", tab = "advanced") {
            modalonly = true
            placeholder = "any/all"
            ICMP_TYPES.forEach { value(it) }
            dependsContains("proto", "icmp")
            dependsContains("proto", "icmpv6")
        }

        val section = this
        fwZoneSelect("src", "Source zone", tab = "general", fd = fd, nocreate = true, allowany = true, allowlocal = true) {
            modalonly = true
            onChange = { rt, sid, v ->
                val dest = section.opt("dest")
                if ((v?.toString() ?: "") == "" && dest != null && rt.formString(dest, sid) == "") rt.setValue(dest, sid, "*")
            }
        }

        ipsetOption(uci)

        addMACOption("advanced", "src_mac", "Source MAC address", null, fd)
        addIPOption("general", "src_ip", "Source address", null, "", fd, multiple = true)

        value("src_port", "Source port", tab = "general") {
            modalonly = true
            datatype = "list(neg(portrange))"
            placeholder = "any"
            dependsContains("proto", "tcp")
            dependsContains("proto", "udp")
        }

        fwZoneSelect("dest", "Destination zone", tab = "general", fd = fd, nocreate = true, allowany = true, allowlocal = true) {
            modalonly = true
        }

        addIPOption("general", "dest_ip", "Destination address", null, "", fd, multiple = true)

        value("dest_port", "Destination port", tab = "general") {
            modalonly = true
            datatype = "list(neg(portrange))"
            placeholder = "any"
            dependsContains("proto", "tcp")
            dependsContains("proto", "udp")
        }

        list("target", "Action", tab = "general") {
            modalonly = true
            default = "ACCEPT"
            value("DROP", "drop")
            value("ACCEPT", "accept")
            value("REJECT", "reject")
            value("NOTRACK", "don't track")
            value("HELPER", "assign conntrack helper")
            value("MARK_SET", "apply firewall mark")
            value("MARK_XOR", "XOR firewall mark")
            value("DSCP", "DSCP classification")
            cfgvalueFn = { rt, sid ->
                val t = rt.uci.getString(FW, sid, "target")
                if (t == "MARK") (if (rt.uci.get(FW, sid, "set_mark") != null) "MARK_SET" else "MARK_XOR") else t
            }
            writeFn = { rt, sid, v -> rt.uci.set(FW, sid, "target", if (v == "MARK_SET" || v == "MARK_XOR") "MARK" else v) }
        }

        addMarkOption(1)
        addMarkOption(2)
        addDSCPOption(true)

        list("set_helper", "Tracking helper", "Assign the specified connection tracking helper to matched traffic.", tab = "general") {
            modalonly = true
            placeholder = "any"
            depends("target", "HELPER")
            fd.ctHelpers.forEach { value(it.name, helperLabel(it)) }
            default = fd.ctHelpers.firstOrNull()?.name
        }

        matchHelperOption(fd)

        addMarkOption(0)
        addDSCPOption(false)
        addLimitOption()
        addLimitBurstOption()

        flag("log", "Enable logging", "Log matched packets to syslog.", tab = "advanced") { modalonly = true }

        value("log_limit", "Limit log messages", tab = "advanced") {
            depends("log", "1")
            placeholder = "10/minute"
            modalonly = true
        }

        if (!fd.fw4) {
            value("extra", "Extra arguments", "Passes additional arguments to iptables. Use with care!", tab = "advanced") { modalonly = true }
        }

        timeRestrictions()
    }
    m
}

private const val FW = "firewall"

private val ICMP_TYPES = listOf(
    "address-mask-reply", "address-mask-request", "address-unreachable", "bad-header",
    "certification-path-solicitation-message", "certification-path-advertisement-message", "communication-prohibited",
    "destination-unreachable", "duplicate-address-request", "duplicate-address-confirmation", "echo-reply", "echo-request",
    "extended-echo-request", "extended-echo-reply", "fmipv6-message", "fragmentation-needed",
    "home-agent-address-discovery-reply-message", "home-agent-address-discovery-request-message", "host-precedence-violation",
    "host-prohibited", "host-redirect", "host-unknown", "host-unreachable", "ilnpv6-locator-update-message",
    "inverse-neighbour-discovery-advertisement-message", "inverse-neighbour-discovery-solicitation-message", "ip-header-bad",
    "mobile-prefix-advertisement", "mobile-prefix-solicitation", "mpl-control-message", "multicast-listener-query",
    "multicast-listener-report", "multicast-listener-done", "multicast-router-advertisement", "multicast-router-solicitation",
    "multicast-router-termination", "neighbour-advertisement", "neighbour-solicitation", "network-prohibited", "network-redirect",
    "network-unknown", "network-unreachable", "no-route", "node-info-query", "node-info-response", "packet-too-big",
    "parameter-problem", "port-unreachable", "precedence-cutoff", "protocol-unreachable", "redirect", "required-option-missing",
    "router-advertisement", "router-renumbering", "router-solicitation", "rpl-control-message", "source-quench",
    "source-route-failed", "time-exceeded", "timestamp-reply", "timestamp-request", "TOS-host-redirect", "TOS-host-unreachable",
    "TOS-network-redirect", "TOS-network-unreachable", "ttl-zero-during-reassembly", "ttl-zero-during-transit",
    "v2-multicast-listener-report", "unknown-header-type", "unknown-option",
)

/** The "Time Restrictions" tab of rules.js and snats.js. */
internal fun FormSection.timeRestrictions() {
    multi("weekdays", "Week Days", tab = "timed") {
        modalonly = true
        asString = true
        placeholder = "Any day"
        value("Sun", "Sunday"); value("Mon", "Monday"); value("Tue", "Tuesday"); value("Wed", "Wednesday")
        value("Thu", "Thursday"); value("Fri", "Friday"); value("Sat", "Saturday")
    }
    multi("monthdays", "Month Days", tab = "timed") {
        modalonly = true
        asString = true
        placeholder = "Any day"
        for (i in 1..31) value(i.toString())
    }
    value("start_time", "Start Time (hh:mm:ss)", tab = "timed") { modalonly = true; datatype = "timehhmmss" }
    value("stop_time", "Stop Time (hh:mm:ss)", tab = "timed") { modalonly = true; datatype = "timehhmmss" }
    value("start_date", "Start Date (yyyy-mm-dd)", tab = "timed") { modalonly = true; datatype = "dateyyyymmdd" }
    value("stop_date", "Stop Date (yyyy-mm-dd)", tab = "timed") { modalonly = true; datatype = "dateyyyymmdd" }
    flag("utc_time", "Time in UTC", tab = "timed") { modalonly = true; default = disabled }
}
