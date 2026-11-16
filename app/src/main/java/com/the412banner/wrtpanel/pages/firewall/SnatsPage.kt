package com.the412banner.wrtpanel.pages.firewall

import androidx.compose.runtime.Composable
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormPage
import com.the412banner.wrtpanel.form.FormRuntime
import com.the412banner.wrtpanel.form.deviceSelect
import com.the412banner.wrtpanel.ui.PageContext

/** Network › Firewall › NAT Rules — port of luci-app-firewall view/firewall/snats.js. */
@Composable
fun FirewallSnatsPage(ctx: PageContext) = FormPage(ctx) {
    val fd = loadFwData(hosts = true, devices = true)
    if (FwModel.checkLegacySNAT(uci)) return@FormPage migrationMap(null, null)
    val fw4 = fd.fw4

    val m = FormMap(FW, "Firewall - NAT Rules", "NAT rules allow fine grained control over the source IP to use for outbound or forwarded traffic.")

    m.grid("nat", "NAT Rules") {
        addremove = true
        anonymous = true
        sortable = true
        cloneable()

        tab("general", "General Settings")
        tab("advanced", "Advanced Settings")
        tab("timed", "Time Restrictions")

        val nameOf: (String) -> String = { sid -> uci.getString(FW, sid, "name")?.takeIf { it.isNotEmpty() } ?: "Unnamed NAT" }
        sectionTitle = nameOf
        modalTitle = { sid -> "Firewall - NAT Rules - ${nameOf(sid)}" }

        val section = this
        fun validateFamily(rt: FormRuntime, sid: String, opt: String): String? {
            val sopt = section.opt("src_ip")
            val dopt = section.opt("dest_ip")
            val rwopt = section.opt("snat_ip")
            val fmopt = section.opt("family")
            val tgopt = section.opt("target")
            if (!basicValid(rt, sopt, sid) && opt != "src_ip") return null
            if (!basicValid(rt, dopt, sid) && opt != "dest_ip") return null
            if (!basicValid(rt, rwopt, sid) && opt != "snat_ip") return null
            if (!basicValid(rt, fmopt, sid) && opt != "family") return null
            if (!basicValid(rt, tgopt, sid) && opt != "target") return null
            val sip = sopt?.let { rt.formString(it, sid) } ?: ""
            val dip = dopt?.let { rt.formString(it, sid) } ?: ""
            val rwip = rwopt?.let { rt.formString(it, sid) } ?: ""
            val fm = fmopt?.let { rt.formString(it, sid) } ?: ""
            val tg = tgopt?.let { rt.formString(it, sid) }
            val v6 = (sip.contains(':') || sip == "") && (dip.contains(':') || dip == "") && ((rwip.contains(':') && tg == "SNAT") || rwip == "")
            val v4 = !sip.contains(':') && !dip.contains(':') && ((!rwip.contains(':') && tg == "SNAT") || rwip == "")
            if (fm == "ipv6" && v6) return null
            if (fm == "ipv4" && v4) return null
            if ((fm == "" || fm == "any") && (v6 || v4)) return null
            return "Address family, source address, destination address, rewrite IP address must match"
        }

        value("name", "Name", tab = "general") {
            placeholder = "Unnamed NAT"
            modalonly = true
        }

        richColumn("_match", "Match") { rt, sid ->
            val g: (String) -> Any? = { rt.uci.get(FW, sid, it) }
            listOf(FwText.natProto(g), FwText.natSrc(g), FwText.natDest(g), FwText.limit(g))
        }

        richColumn("_target", "Action") { rt, sid -> listOf(FwText.natTarget { rt.uci.get(FW, sid, it) }) }

        flag("enabled", "Enable") {
            modalonly = false
            default = enabled
            editable = true
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

        protoSelect("proto", "Protocol", "general", "all") { modalonly = true }

        fwZoneSelect("src", "Outbound zone", tab = "general", fd = fd, nocreate = true, allowany = true) {
            modalonly = true
            rmempty = false
            default = "lan"
        }

        addIPOption("general", "src_ip", "Source address", "Match forwarded traffic from this IP or range.", if (!fw4) "ipv4" else "", fd) {
            rmempty = true
            datatype = if (!fw4) "neg(ipmask4(\"true\"))" else "neg(ipmask(\"true\"))"
            validateEmpty = true
            validate = { rt, sid, _ -> if (!fw4) null else validateFamily(rt, sid, "src_ip") }
        }

        value("src_port", "Source port", "Match forwarded traffic originating from the given source port or port range.", tab = "general") {
            modalonly = true
            rmempty = true
            datatype = "neg(portrange)"
            placeholder = "any"
            dependsContains("proto", "tcp")
            dependsContains("proto", "udp")
        }

        addIPOption("general", "dest_ip", "Destination address", "Match forwarded traffic directed at the given IP address.", if (!fw4) "ipv4" else "", fd) {
            rmempty = true
            datatype = if (!fw4) "neg(ipmask4(\"true\"))" else "neg(ipmask(\"true\"))"
            validateEmpty = true
            validate = { rt, sid, _ -> if (!fw4) null else validateFamily(rt, sid, "dest_ip") }
        }

        value("dest_port", "Destination port", "Match forwarded traffic directed at the given destination port or port range.", tab = "general") {
            modalonly = true
            rmempty = true
            placeholder = "any"
            datatype = "neg(portrange)"
            dependsContains("proto", "tcp")
            dependsContains("proto", "udp")
        }

        list("target", "Action", tab = "general") {
            modalonly = true
            default = "SNAT"
            value("SNAT", "SNAT - Rewrite to specific source IP or port")
            value("MASQUERADE", "MASQUERADE - Automatically rewrite to outbound interface IP")
            value("ACCEPT", "ACCEPT - Disable address rewriting")
            validate = { rt, sid, _ -> if (!fw4) null else validateFamily(rt, sid, "target") }
        }

        addLocalIPOption("general", "snat_ip", "Rewrite IP address", "Rewrite matched traffic to the specified source IP address.", fd) {
            placeholder = null
            depends("target", "SNAT")
            validateEmpty = true
            validate = { rt, sid, value ->
                val a = rt.formString(this, sid)
                val p = section.opt("snat_port")?.let { rt.formString(it, sid) } ?: ""
                if (a == "" && p == "" && value == "") "A rewrite IP must be specified!"
                else if (!fw4) null else validateFamily(rt, sid, "snat_ip")
            }
        }

        value("snat_port", "Rewrite port", "Rewrite matched traffic to the specified source port or port range.", tab = "general") {
            modalonly = true
            rmempty = true
            placeholder = "do not rewrite"
            datatype = "portrange"
            dependsContains("proto", "tcp")
            dependsContains("proto", "udp")
        }

        if (!fw4) ipsetOption(uci)

        deviceSelect("device", "Outbound device", "Matches forwarded traffic using the specified outbound network device.", tab = "advanced", noaliases = true) {
            modalonly = true
            rmempty = true
        }

        addMarkOption(0)
        addLimitOption()
        addLimitBurstOption()

        flag("log", "Enable logging", "Log matched packets to syslog.", tab = "advanced") { modalonly = true }

        if (!fw4) {
            value("extra", "Extra arguments", "Passes additional arguments to iptables. Use with care!", tab = "advanced") {
                modalonly = true
                rmempty = true
            }
        }

        timeRestrictions()
    }
    m
}

private const val FW = "firewall"
