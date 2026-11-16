package com.the412banner.wrtpanel.pages.firewall

import androidx.compose.runtime.Composable
import com.the412banner.wrtpanel.form.Datatypes
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormOption
import com.the412banner.wrtpanel.form.FormPage
import com.the412banner.wrtpanel.form.FormRuntime
import com.the412banner.wrtpanel.form.FormSection
import com.the412banner.wrtpanel.ui.PageContext

/** Network › Firewall › Port Forwards — port of luci-app-firewall view/firewall/forwards.js. */
@Composable
fun FirewallForwardsPage(ctx: PageContext) = FormPage(ctx) {
    val fd = loadFwData(hosts = true, helpers = true, devices = true)
    if (FwModel.checkLegacySNAT(uci)) return@FormPage migrationMap(null, null)
    val fw4 = fd.fw4

    val m = FormMap(FW, "Firewall - Port Forwards", "Port forwarding allows remote computers on the Internet to connect to a specific computer or service within the private LAN.")

    m.grid("redirect", "Port Forwards") {
        addremove = true
        anonymous = true
        sortable = true
        cloneable()

        tab("general", "General Settings")
        tab("advanced", "Advanced Settings")

        filter = { _, sec -> sec["target"] != "SNAT" }
        val nameOf: (String) -> String = { sid -> uci.getString(FW, sid, "name")?.takeIf { it.isNotEmpty() } ?: "Unnamed forward" }
        sectionTitle = nameOf
        modalTitle = { sid -> "Firewall - Port Forwards - ${nameOf(sid)}" }
        onAdd = { rt, sid ->
            rt.uci.set(FW, sid, "dest", "lan")
            rt.uci.set(FW, sid, "target", "DNAT")
        }

        val section = this
        fun validateFamily(rt: FormRuntime, sid: String, opt: String): String? {
            val dopt = section.opt("dest_ip")
            val fmopt = section.opt("family")
            if (!basicValid(rt, dopt, sid) && opt != "dest_ip") return null
            if (!basicValid(rt, fmopt, sid) && opt != "family") return null
            val dip = dopt?.let { rt.formString(it, sid) } ?: ""
            val fm = fmopt?.let { rt.formString(it, sid) } ?: ""
            if (fm == "" || (fm == "any" && dip == "") || (fm == "ipv6" && (dip.contains(':') || dip == "")) || (fm == "ipv4" && !dip.contains(':'))) return null
            return "Address family, Internal IP address must match"
        }

        value("name", "Name", tab = "general") {
            placeholder = "Unnamed forward"
            modalonly = true
        }

        if (fw4) {
            list("family", "Restrict to address family", tab = "general") {
                modalonly = true
                rmempty = true
                value("", "automatic")
                value("any", "IPv4 and IPv6")
                value("ipv4", "IPv4 only")
                value("ipv6", "IPv6 only")
                cfgvalueFn = { rt, sid -> familyCfgvalue(rt.uci.getString(FW, sid, "family")) }
                validateEmpty = true
                validate = { rt, sid, _ -> validateFamily(rt, sid, "family") }
            }
        }

        richColumn("_match", "Match") { rt, sid ->
            val g: (String) -> Any? = { rt.uci.get(FW, sid, it) }
            listOf(FwText.forwardProto(g, fd.ctHelpers), FwText.forwardSrc(g, fd.hosts), FwText.forwardDest(g), FwText.limit(g))
        }

        richColumn("_dest", "Action") { rt, sid -> listOf(FwText.forwardTarget { rt.uci.get(FW, sid, it) }) }

        flag("enabled", "Enable") {
            modalonly = false
            default = enabled
            editable = true
        }

        protoSelect("proto", "Protocol", "general", "tcp udp") { modalonly = true }

        fwZoneSelect("src", "Source zone", tab = "general", fd = fd, nocreate = true) {
            modalonly = true
            rmempty = false
            default = "wan"
        }

        ipsetOption(uci)

        addMACOption("advanced", "src_mac", "Source MAC address", "Only match incoming traffic from these MACs.", fd) {
            rmempty = true
            datatype = "list(neg(macaddr))"
        }

        addIPOption("advanced", "src_ip", "Source IP address", "Only match incoming traffic from this IP or range.", if (!fw4) "ipv4" else "", fd) {
            rmempty = true
            datatype = if (!fw4) "neg(ipmask4(\"true\"))" else "neg(ipmask(\"true\"))"
        }

        value("src_port", "Source port", "Only match incoming traffic originating from the given source port or port range on the client host", tab = "advanced") {
            modalonly = true
            rmempty = true
            datatype = "neg(portrange)"
            placeholder = "any"
            dependsContains("proto", "tcp")
            dependsContains("proto", "udp")
        }

        addLocalIPOption("advanced", "src_dip", "External IP address", "Only match incoming traffic directed at the given IP address.", fd) {
            datatype = if (!fw4) "neg(ipmask4(\"true\"))" else "neg(ipmask(\"true\"))"
            rmempty = true
        }

        value("src_dport", "External port", "Match incoming traffic directed at the given destination port or port range on this host", tab = "general") {
            modalonly = true
            rmempty = false
            datatype = "neg(portrange)"
            dependsContains("proto", "tcp")
            dependsContains("proto", "udp")
        }

        fwZoneSelect("dest", "Internal zone", tab = "general", fd = fd, nocreate = true) {
            modalonly = true
            rmempty = true
        }

        addIPOption("general", "dest_ip", "Internal IP address", "Redirect matched incoming traffic to the specified internal host", if (!fw4) "ipv4" else "", fd) {
            rmempty = true
            datatype = if (!fw4) "ipmask4" else "ipmask"
        }

        value("dest_port", "Internal port", "Redirect matched incoming traffic to the given port on the internal host", tab = "general") {
            modalonly = true
            rmempty = true
            placeholder = "any"
            datatype = "portrange"
            dependsContains("proto", "tcp")
            dependsContains("proto", "udp")
        }

        flag("reflection", "Enable NAT Loopback", tab = "advanced") {
            modalonly = true
            rmempty = true
            default = enabled
        }

        list("reflection_src", "Loopback source IP", "Specifies whether to use the external or the internal IP address for reflected traffic.", tab = "advanced") {
            modalonly = true
            depends("reflection", "1")
            value("internal", "Use internal IP address")
            value("external", "Use external IP address")
            default = "internal"
            writeFn = { rt, sid, v -> rt.uci.set(FW, sid, "reflection_src", if (v != "internal") v else null) }
        }

        fwZoneSelect(
            "reflection_zone", "Reflection zones", "Zones from which reflection rules shall be created. If unset, only the destination zone is used.",
            tab = "advanced", fd = fd, multiple = true, nocreate = true,
        ) {
            modalonly = true
            depends("reflection", "1")
        }

        matchHelperOption(fd)

        addMarkOption(0)
        addLimitOption()
        addLimitBurstOption()

        flag("log", "Enable logging", "Log matched packets to syslog.", tab = "advanced") { modalonly = true }

        value("log_limit", "Limit log messages", tab = "advanced") {
            depends("log", "1")
            placeholder = "10/minute"
            modalonly = true
        }

        if (!fw4) {
            value("extra", "Extra arguments", "Passes additional arguments to iptables. Use with care!", tab = "advanced") {
                modalonly = true
                rmempty = true
            }
        }
    }
    m
}

private const val FW = "firewall"

/** Datatype check only: LuCI's isValid() of another field (its widget state, no re-validation). */
internal fun basicValid(rt: FormRuntime, o: FormOption?, sid: String): Boolean {
    if (o == null) return true
    val v = rt.formString(o, sid)
    val dt = o.datatype ?: return true
    return v.isEmpty() || Datatypes.validate(dt, v) == null
}

internal fun FormSection.opt(name: String): FormOption? = options.firstOrNull { it.option == name }

/** The family cfgvalue of forwards.js / snats.js. */
internal fun familyCfgvalue(v: String?): String? = when {
    v.isNullOrEmpty() -> ""
    v == "any" || v == "all" || v == "*" -> "any"
    v == "inet" || v.contains('4') -> "ipv4"
    v.contains('6') -> "ipv6"
    else -> null
}
