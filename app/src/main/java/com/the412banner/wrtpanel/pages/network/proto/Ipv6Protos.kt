package com.the412banner.wrtpanel.pages.network.proto

import com.the412banner.wrtpanel.form.FormSection
import com.the412banner.wrtpanel.form.networkSelect

/** Tunnel protocols of luci-proto-ipv6: floating, virtual, own device name. */
abstract class TunnelProto(override val name: String, override val i18n: String, prefix: String? = null) : ProtoHandler {
    override val floating: Boolean get() = true
    override val virtual: Boolean get() = true
    override val ifnamePrefix: String = prefix ?: name
}

private fun FormSection.tunlink(p: ProtoForm) {
    networkSelect("tunlink", "Tunnel Link", tab = "advanced", nocreate = true, exclude = p.sid)
}

private fun FormSection.tunnelTtl() {
    value("ttl", "Use TTL on tunnel interface", tab = "advanced") { placeholder = "64"; datatype = "range(1,255)" }
}

private fun FormSection.tunnelMtu() {
    value("mtu", "Use MTU on tunnel interface", tab = "advanced") { placeholder = "1280"; datatype = "max(9200)" }
}

private fun FormSection.wanIpaddr(p: ProtoForm) {
    value("ipaddr", "Local IPv4 address", "Leave empty to use the current WAN address", tab = "general") {
        datatype = "ip4addr(\"nomask\")"
        p.model.getWANNetworks().firstOrNull()?.let { placeholder = it.getIPAddr() }
    }
}

private fun FormSection.encaplimit() {
    list("encaplimit", "Encapsulation limit", tab = "advanced") {
        rmempty = false
        default = "ignore"
        datatype = "or(\"ignore\",range(0,255))"
        value("ignore", "ignore")
        for (i in 0 until 256) value(i.toString())
    }
}

/** protocol/dhcpv6.js */
object Dhcpv6Proto : ProtoHandler {
    override val name = "dhcpv6"
    override val i18n = "DHCPv6 client"

    override fun renderFormOptions(s: FormSection, p: ProtoForm) {
        s.list("reqaddress", "Request IPv6-address", tab = "general") {
            value("try", "try"); value("force", "force"); value("none", "disabled")
            default = "try"
        }
        s.value(
            "reqprefix", "Request IPv6-prefix",
            "Either a prefix length hint (e.g. 56) only, whereby the operator selects the prefix, or specify a prefix also (e.g. %s)".format("<code>2001:db8::/56</code>"),
            tab = "general",
        ) {
            value("auto", "Automatic"); value("no", "disabled")
            listOf("48", "52", "56", "60", "64").forEach { value(it) }
            default = "auto"
        }
        s.flag("extendprefix", "Extend prefix", "Extend 3GPP WAN interface /64 prefix via PD to LAN (RFC 7278)", tab = "general")
        s.flag("norelease", "Do not send a Release when restarting", "Enable to minimise the chance of prefix change after a restart", tab = "general") {
            default = "1"; rmempty = false
        }
        s.list(
            "sendclientid", "Preferred client ID",
            "Selects the <abbr title=\"DHCP Unique Identifier\">DUID</abbr> to send when requesting DHCPv6.<br />" +
                "<em>Automatic</em> uses the client ID configured below, else the default DUID of this device, else a DUID-LL derived from the MAC address of this interface.<br />" +
                "<em>Default DUID</em> always uses the default DUID of this device, even if a client ID is configured below.<br />" +
                "<em>Hardware address</em> always uses a DUID-LL derived from the MAC address of this interface.",
            tab = "advanced",
        ) {
            value("auto", "Automatic"); value("global", "Default DUID"); value("hardware", "Hardware address")
            default = "auto"
        }
        s.value("clientid", "Client ID to send when requesting DHCP", tab = "advanced") {
            datatype = "hexstring"
            depends("sendclientid", "auto")
        }
    }
}

/** protocol/464xlat.js */
object Xlat464Proto : TunnelProto("464xlat", "464XLAT (CLAT)", "464") {
    override fun renderFormOptions(s: FormSection, p: ProtoForm) {
        s.value("ip6prefix", "NAT64 Prefix", "Leave empty to autodetect", tab = "general") { datatype = "cidr6" }
        s.tunlink(p)
        s.tunnelMtu()
    }
}

/** protocol/6in4.js */
object SixIn4Proto : TunnelProto("6in4", "IPv6-in-IPv4 (RFC4213)") {
    override fun renderFormOptions(s: FormSection, p: ProtoForm) {
        s.wanIpaddr(p)
        s.value("peeraddr", "Remote IPv4 address", "This is usually the address of the nearest PoP operated by the tunnel broker", tab = "general") {
            rmempty = false; datatype = "ip4addr(\"nomask\")"
        }
        s.value("ip6addr", "Local IPv6 address", "This is the local endpoint address assigned by the tunnel broker, it usually ends with <code>...:2/64</code>", tab = "general") {
            datatype = "cidr6"
        }
        s.dynlist("ip6prefix", "IPv6 routed prefix", "This is the prefix routed to you by the tunnel broker for use by clients", tab = "general") {
            datatype = "cidr6"
        }
        s.flag("_update", "Dynamic tunnel", "Enable HE.net dynamic endpoint update", tab = "general") {
            enabled = "1"; disabled = "0"
            writeFn = { _, _, _ -> }
            removeFn = { _, _ -> }
            cfgvalueFn = { rt, sid ->
                val t = rt.uci.getString("network", sid, "tunnelid")
                if (t != null && (t.trim().isEmpty() || t.trim().toDoubleOrNull() != null)) "1" else "0"
            }
        }
        s.value("tunnelid", "Tunnel ID", tab = "general") { datatype = "uinteger"; depends("_update", "1") }
        s.value("username", "HE.net username", "This is the plain username for logging into the account", tab = "general") {
            depends("_update", "1")
            validate = { _, _, v ->
                if (Regex("^[a-fA-F0-9]{32}$").matches(v)) "The HE.net endpoint update configuration changed, you must now use the plain username instead of the user ID!" else null
            }
        }
        s.value("password", "HE.net password", "This is either the \"Update Key\" configured for the tunnel or the account password if no update key has been configured", tab = "general") {
            password = true; depends("_update", "1")
        }
        s.tunnelTtl()
        s.tunnelMtu()
    }
}

/** protocol/6rd.js */
object SixRdProto : TunnelProto("6rd", "IPv6-over-IPv4 (6rd)") {
    override fun renderFormOptions(s: FormSection, p: ProtoForm) {
        s.wanIpaddr(p)
        s.value("peeraddr", "Remote IPv4 address", "This IPv4 address of the relay", tab = "general") { rmempty = false; datatype = "ip4addr(\"nomask\")" }
        s.value("ip6prefix", "IPv6 prefix", "The IPv6 prefix assigned to the provider, usually ends with <code>::</code>", tab = "general") { rmempty = false; datatype = "ip6addr" }
        s.value("ip6prefixlen", "IPv6 prefix length", "The length of the IPv6 prefix in bits", tab = "general") { placeholder = "16"; datatype = "range(0,128)" }
        s.value("ip4prefixlen", "IPv4 prefix length", "The length of the IPv4 prefix in bits, the remainder is used in the IPv6 addresses.", tab = "general") {
            placeholder = "0"; datatype = "range(0,32)"
        }
        s.tunnelTtl()
        s.tunnelMtu()
    }
}

/** protocol/6to4.js */
object SixToFourProto : TunnelProto("6to4", "IPv6-over-IPv4 (6to4)") {
    override fun renderFormOptions(s: FormSection, p: ProtoForm) {
        s.wanIpaddr(p)
        s.tunnelTtl()
        s.tunnelMtu()
    }
}

/** protocol/dslite.js */
object DsliteProto : TunnelProto("dslite", "Dual-Stack Lite (RFC6333)", "ds") {
    override fun renderFormOptions(s: FormSection, p: ProtoForm) {
        s.value("peeraddr", "DS-Lite AFTR address", tab = "general") {
            rmempty = false
            datatype = "or(hostname,ip6addr(\"nomask\"))"
            value("gw.transix.jp", "Transix (Japan only)")
            value("dgw.xpass.jp", "Cross Pass (Japan only)")
        }
        s.value("ip6addr", "Local IPv6 address", "Leave empty to use the current WAN address", tab = "general") {
            datatype = "ip6addr(\"nomask\")"
            p.model.getWAN6Networks().firstOrNull()?.let { placeholder = it.getIP6Addr() }
        }
        s.tunlink(p)
        s.encaplimit()
        s.tunnelMtu()
    }
}

/** protocol/ipip6.js */
object Ipip6Proto : TunnelProto("ipip6", "IPv4 over IPv6 (RFC2473-IPIPv6)") {
    override fun renderFormOptions(s: FormSection, p: ProtoForm) {
        s.value("peeraddr", "Remote IPv6 address or FQDN", tab = "general") { rmempty = false; datatype = "or(hostname,ip6addr(\"nomask\"))" }
        s.value("ip4ifaddr", "Local IPv4 address", tab = "general") { rmempty = false; datatype = "ip4addr(\"nomask\")" }
        s.value("ip6addr", "Local IPv6 address", "Leave empty to use the current WAN address", tab = "general") {
            datatype = "ip6addr(\"nomask\")"
            p.model.getWAN6Networks().firstOrNull()?.let { placeholder = it.getIP6Addr() }
        }
        s.tunlink(p)
        s.encaplimit()
        s.flag("defaultroute", "Default gateway", "If unchecked, no default route is configured", tab = "advanced") { default = "1" }
        s.value("metric", "Use gateway metric", tab = "advanced") { placeholder = "0"; datatype = "uinteger"; depends("defaultroute", "1") }
        s.tunnelMtu()
    }
}

/** protocol/map.js */
object MapProto : TunnelProto("map", "MAP / LW4over6") {
    override fun renderFormOptions(s: FormSection, p: ProtoForm) {
        s.list("maptype", "Type", tab = "general") { value("map-e", "MAP-E"); value("map-t", "MAP-T"); value("lw4o6", "LW4over6") }
        s.value("peeraddr", "BR / DMR / AFTR", tab = "general") { rmempty = false; datatype = "ip6addr" }
        s.value("ipaddr", "IPv4 prefix", tab = "general") { datatype = "ip4addr" }
        s.value("ip4prefixlen", "IPv4 prefix length", "The length of the IPv4 prefix in bits, the remainder is used in the IPv6 addresses.", tab = "general") {
            placeholder = "32"; datatype = "range(0,32)"
        }
        s.value("ip6prefix", "IPv6 prefix", "The IPv6 prefix assigned to the provider, usually ends with <code>::</code>", tab = "general") { rmempty = false; datatype = "ip6addr" }
        s.value("ip6prefixlen", "IPv6 prefix length", "The length of the IPv6 prefix in bits", tab = "general") { placeholder = "16"; datatype = "or(range(0,64),128)" }
        s.value("ealen", "EA-bits length", tab = "general") { datatype = "range(0,48)" }
        s.value("psidlen", "PSID-bits length", tab = "general") { datatype = "range(0,16)" }
        s.value("offset", "PSID offset", tab = "general") { datatype = "range(0,16)" }
        s.tunlink(p)
        s.tunnelTtl()
        s.tunnelMtu()
        s.flag("legacymap", "Use legacy MAP", "Use legacy MAP interface identifier format (draft-ietf-softwire-map-00) instead of RFC7597", tab = "advanced")
    }
}
