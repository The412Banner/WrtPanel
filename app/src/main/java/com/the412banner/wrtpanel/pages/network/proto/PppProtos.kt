package com.the412banner.wrtpanel.pages.network.proto

import com.the412banner.wrtpanel.form.FormSection
import com.the412banner.wrtpanel.form.ValueOption

private const val REQPREFIX_DESC = "Either a prefix length hint (e.g. 56) only, whereby the operator selects the prefix, or specify a prefix also (e.g. %s)"

/** The options the PPP family shares: credentials, IPv6 negotiation, LCP keepalive, demand, MTU. */
private fun FormSection.pppCredentials() {
    value("username", "PAP/CHAP username", tab = "general")
    value("password", "PAP/CHAP password", tab = "general") { password = true }
}

private fun FormSection.pppIpv6(p: ProtoForm, withPrefix: Boolean) {
    if (p.hasFeature("ipv6")) {
        list("ppp_ipv6", "Obtain IPv6 address", "Enable IPv6 negotiation on the PPP link", tab = "advanced") {
            ucioption = "ipv6"
            value("auto", "Automatic"); value("0", "Disabled"); value("1", "Manual")
            default = "auto"
        }
    }
    if (!withPrefix) return
    value("reqprefix", "Request IPv6-prefix", REQPREFIX_DESC.format("<code>2001:db8::/56</code>"), tab = "advanced") {
        depends("ppp_ipv6", "auto")
    }
    flag("norelease", "Do not send a Release when restarting", "Enable to minimise the chance of prefix change after a restart", tab = "advanced") {
        depends("ppp_ipv6", "auto")
        default = "1"; rmempty = false
    }
}

private fun FormSection.pppKeepalive() {
    lateinit var failure: ValueOption
    lateinit var interval: ValueOption
    failure = value("_keepalive_failure", "LCP echo failure threshold", "Presume peer to be dead after given amount of LCP echo failures, use 0 to ignore failures", tab = "advanced") {
        placeholder = "5"; datatype = "uinteger"
        writeFn = { rt, sid, _ -> writeKeepalive(rt, sid, failure, interval) }
        removeFn = { rt, sid -> writeKeepalive(rt, sid, failure, interval) }
        cfgvalueFn = { rt, sid ->
            val v = rt.uci.get("network", sid, "keepalive")
            if (v is String && v != "") Regex("^(\\d+)[ ,]\\d+$").find(v)?.groupValues?.get(1) ?: v else null
        }
    }
    interval = value("_keepalive_interval", "LCP echo interval", "Send LCP echo requests at the given interval in seconds, only effective in conjunction with failure threshold", tab = "advanced") {
        placeholder = "1"; datatype = "and(uinteger,min(1))"
        writeFn = { rt, sid, _ -> writeKeepalive(rt, sid, failure, interval) }
        removeFn = { rt, sid -> writeKeepalive(rt, sid, failure, interval) }
        cfgvalueFn = { rt, sid ->
            val v = rt.uci.get("network", sid, "keepalive")
            if (v is String && v != "") Regex("^\\d+[ ,](\\d+)$").find(v)?.groupValues?.get(1) ?: v else null
        }
    }
}

private fun FormSection.pppDemand() {
    value("demand", "Inactivity timeout", "Close inactive connection after the given amount of seconds, use 0 to persist connection", tab = "advanced") {
        placeholder = "0"; datatype = "uinteger"
    }
}

private fun FormSection.pppMtu(p: ProtoForm) {
    val dev = p.iface.getL3Device() ?: p.iface.getDevice()
    value("mtu", "Override MTU", tab = "advanced") {
        placeholder = dev?.getMTU()?.takeIf { it != 0L }?.toString() ?: "1500"
        datatype = "max(9200)"
    }
}

/** protocol/ppp.js */
object PppProto : ProtoHandler {
    override val name = "ppp"
    override val i18n = "PPP"
    override val floating = true
    override val virtual = true
    override val ifnamePrefix = "ppp"
    override val virtualPattern = Regex("^ppp-.+$")

    override fun renderFormOptions(s: FormSection, p: ProtoForm) {
        s.value("_modem_device", "Modem device", tab = "general") {
            ucioption = "device"; rmempty = false
            val self = this
            loadFn = { _ ->
                for (dir in listOf("/dev/", "/dev/tts/")) {
                    val names = runCatching { fs.list(dir) }.getOrDefault(emptyList()).map { it.name }
                        .filter { Regex("^tty[A-Z]").containsMatchIn(it) || Regex("^cdc-wdm").containsMatchIn(it) || Regex("^[0-9]+$").matches(it) }
                        .map { dir + it }.sorted()
                    names.forEach { self.value(it) }
                }
            }
        }
        s.pppCredentials()
        s.pppIpv6(p, true)
        s.pppKeepalive()
        s.pppDemand()
        s.pppMtu(p)
    }
}

/** protocol/pppoe.js */
object PppoeProto : ProtoHandler {
    override val name = "pppoe"
    override val i18n = "PPPoE"
    override val ifnamePrefix = "pppoe"
    override val virtualPattern = Regex("^pppoe-.+$")

    override fun renderFormOptions(s: FormSection, p: ProtoForm) {
        s.pppCredentials()
        s.value("ac", "Access Concentrator", "Leave empty to autodetect", tab = "general") { placeholder = "auto" }
        s.value("ac_mac", "<abbr title=\"Access Concentrator\">AC</abbr> MAC Address", "Leave empty to autodetect", tab = "general") {
            placeholder = "auto"; datatype = "macaddr"
        }
        s.value("service", "Service Name", "Leave empty to autodetect", tab = "general") { placeholder = "auto" }
        s.pppIpv6(p, true)
        s.pppKeepalive()
        s.value("host_uniq", "Host-Uniq tag content", "Raw hex-encoded bytes. Leave empty unless your ISP require this", tab = "advanced") {
            placeholder = "auto"; datatype = "hexstring"
        }
        s.pppDemand()
        s.pppMtu(p)
    }
}

/** protocol/pppoa.js */
object PppoaProto : ProtoHandler {
    override val name = "pppoa"
    override val i18n = "PPPoATM"
    override val floating = true
    override val virtual = true
    override val ifnamePrefix = "pppoa"
    override val virtualPattern = Regex("^pppoa-.+$")

    override fun renderFormOptions(s: FormSection, p: ProtoForm) {
        s.list("encaps", "PPPoA Encapsulation", tab = "general") { value("vc", "VC-Mux"); value("llc", "LLC") }
        s.value("atmdev", "ATM device number", tab = "general") { default = "0"; datatype = "uinteger" }
        s.value("vci", "ATM Virtual Channel Identifier (VCI)", tab = "general") { default = "35"; datatype = "uinteger" }
        s.value("vpi", "ATM Virtual Path Identifier (VPI)", tab = "general") { default = "8"; datatype = "uinteger" }
        s.pppCredentials()
        s.pppIpv6(p, true)
        s.pppKeepalive()
        s.pppDemand()
        s.pppMtu(p)
    }
}

/** protocol/pptp.js */
object PptpProto : ProtoHandler {
    override val name = "pptp"
    override val i18n = "PPtP"
    override val floating = true
    override val virtual = true
    override val ifnamePrefix = "pptp"
    override val virtualPattern = Regex("^pptp-.+$")

    override fun renderFormOptions(s: FormSection, p: ProtoForm) {
        s.value("server", "VPN Server", tab = "general") { datatype = "host(0)" }
        s.pppCredentials()
        s.pppIpv6(p, true)
        s.pppKeepalive()
        s.pppDemand()
        s.pppMtu(p)
    }
}

/** protocol/l2tp.js */
object L2tpProto : ProtoHandler {
    override val name = "l2tp"
    override val i18n = "L2TP"
    override val floating = true
    override val virtual = true
    override val ifnamePrefix = "l2tp"
    override val virtualPattern = Regex("^l2tp-.+$")

    override fun renderFormOptions(s: FormSection, p: ProtoForm) {
        s.value("server", "L2TP Server", tab = "general") { datatype = "or(host(1), hostport(1))" }
        s.pppCredentials()
        s.pppIpv6(p, false)
        s.pppMtu(p)
        s.value("hostname", "L2TP Hostname", tab = "advanced")
    }
}
