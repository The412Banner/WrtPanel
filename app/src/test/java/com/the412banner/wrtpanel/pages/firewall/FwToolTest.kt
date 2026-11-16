package com.the412banner.wrtpanel.pages.firewall

import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected values were produced by running the LuCI JS (luci @1c75ea46) in Node. */
class FwToolTest {
    private val ct = listOf(CtHelper("ftp", "FTP passive connection tracking"), CtHelper("sip", "SIP VoIP connection tracking"))
    private val hosts = mapOf(
        "08:02:8E:9E:D4:28" to FwTool.Host("nas", listOf("192.168.1.216"), emptyList()),
        "64:82:14:48:DD:E9" to FwTool.Host(null, listOf("192.168.1.227"), emptyList()),
    )

    private fun ser(f: FwTool.Fmt): String = Regex("<span data-fmt-placeholder=\"(\\d+)\"></span>").replace(f.html) { m ->
        val b = f.values[m.groupValues[1].toInt()] as FwTool.ZoneBadge
        "[[" + b.color + "|" + (if (b.strong) "strong" else "em") + "|" + b.text + "]]"
    }

    @Test fun colors() {
        assertEquals("#90f090", FwTool.colorForName("lan"))
        assertEquals("#f09090", FwTool.colorForName("wan"))
        assertEquals("#a2e6eb", FwTool.colorForName("guest"))
        assertEquals("#f78ed4", FwTool.colorForName("iot"))
        assertEquals("#d7c0a0", FwTool.colorForName("vpn"))
        assertEquals("#b6b2ad", FwTool.colorForName("newzone"))
        assertEquals("#e392ab", FwTool.colorForName("dmz"))
        assertEquals("#b8dfc8", FwTool.colorForName("wg0"))
        assertEquals("#adf192", FwTool.colorForName("a"))
        assertEquals("#feaf94", FwTool.colorForName("ab"))
        assertEquals("#aba3b6", FwTool.colorForName("abc"))
        assertEquals("#a3a8d3", FwTool.colorForName("abcd"))
        assertEquals("#eb8ff5", FwTool.colorForName("abcde"))
        assertEquals("#91cad1", FwTool.colorForName("Zone_1"))
        assertEquals("#85f1b5", FwTool.colorForName("ümlaut"))
        assertEquals("#a8c6f4", FwTool.colorForName("trusted_lan"))
        assertEquals("115ea782", FwTool.sfh("a"))
        assertEquals("1511bb67", FwTool.sfh("lan"))
        assertEquals("a68c6882", FwTool.sfh("hello world"))
        assertEquals("c3b96050", FwTool.sfh("ümlaut"))
    }

    @Test fun forwards() {
        run {
            val s: Map<String, Any> = mapOf("name" to "web", "src" to "wan", "src_dport" to "8080", "dest" to "lan", "dest_ip" to "192.168.1.10", "dest_port" to "80", "target" to "DNAT", "proto" to "tcp")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Incoming <var>IPv4</var>, protocol <var>TCP</var>", ser(FwText.forwardProto(g, ct)))
            assertEquals("From [[#f09090|strong|wan]]", ser(FwText.forwardSrc(g, hosts)))
            assertEquals("To [[#eeeeee|em|this device]], port <var>8080</var>", ser(FwText.forwardDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"DNAT\">Forward</var> to [[#90f090|strong|lan]] IP <var>192.168.1.10</var> port <var>80</var>", ser(FwText.forwardTarget(g)))
        }
        run {
            val s: Map<String, Any> = mapOf("src" to "wan", "src_dport" to "!22", "src_dip" to "1.2.3.4", "dest" to "dmz", "dest_ip" to "FD00::5", "target" to "DNAT", "family" to "ipv6", "src_ip" to "!8.8.8.8", "src_port" to "1000-2000", "src_mac" to "08:02:8E:9E:D4:28", "mark" to "0x1", "helper" to "ftp", "limit" to "1/second")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Incoming <var>IPv6</var>, mark <var>0x1</var>, helper <var data-tooltip=\"FTP passive connection tracking\">FTP</var>", ser(FwText.forwardProto(g, ct)))
            assertEquals("From [[#f09090|strong|wan]], IP <var data-tooltip=\"Match IP addresses except 8.8.8.8.\">!8.8.8.8</var>, port <var>1000-2000</var>, MAC <var data-tooltip=\"nas\">08:02:8E:9E:D4:28</var>", ser(FwText.forwardSrc(g, hosts)))
            assertEquals("To [[#eeeeee|em|this device]], IP <var>1.2.3.4</var>, port <var data-tooltip=\"Match ports except 22.\">!22</var>", ser(FwText.forwardDest(g)))
            assertEquals("Limit matching to <var>1</var> packets per <var>second</var>", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"DNAT\">Forward</var> to [[#e392ab|strong|dmz]] IP <var>fd00::5</var>", ser(FwText.forwardTarget(g)))
        }
        run {
            val s: Map<String, Any> = mapOf("src" to "wan", "src_dport" to "53", "target" to "DNAT", "family" to "all", "proto" to "udp icmp", "icmp_type" to listOf("echo-request"))
            val g: (String) -> Any? = { s[it] }
            assertEquals("Incoming <var>IPv4</var> and <var>IPv6</var>, protocol <var>UDP</var>, <var class=\"cbi-tooltip-container\">ICMP<span class=\"cbi-tooltip\">ICMP with types <var>echo-request</var></span></var>", ser(FwText.forwardProto(g, ct)))
            assertEquals("From [[#f09090|strong|wan]]", ser(FwText.forwardSrc(g, hosts)))
            assertEquals("To [[#eeeeee|em|this device]], port <var>53</var>", ser(FwText.forwardDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"DNAT\">Forward</var> to [[#eeeeee|em|this device]]", ser(FwText.forwardTarget(g)))
        }
        run {
            val s: Map<String, Any> = mapOf("src" to "wan", "dest" to "*", "target" to "DNAT")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Incoming <var>IPv4</var>", ser(FwText.forwardProto(g, ct)))
            assertEquals("From [[#f09090|strong|wan]]", ser(FwText.forwardSrc(g, hosts)))
            assertEquals("To [[#eeeeee|em|this device]]", ser(FwText.forwardDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"DNAT\">Forward</var> to [[#eeeeee|em|any zone]]", ser(FwText.forwardTarget(g)))
        }
    }

    @Test fun rules() {
        run {
            val s: Map<String, Any> = mapOf("name" to "Allow-DHCP-Renew", "src" to "wan", "proto" to "udp", "dest_port" to "68", "target" to "ACCEPT", "family" to "ipv4")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Incoming <var>IPv4</var>, protocol <var>UDP</var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#f09090|strong|wan]]", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#eeeeee|em|this device]], port <var>68</var>", ser(FwText.ruleDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"ACCEPT\">Accept</var> input", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("name" to "Allow-Ping", "src" to "wan", "proto" to "icmp", "icmp_type" to "echo-request", "family" to "ipv4", "target" to "ACCEPT")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Incoming <var>IPv4</var>, protocol <var class=\"cbi-tooltip-container\">ICMP<span class=\"cbi-tooltip\">ICMP with types <var>echo-request</var></span></var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#f09090|strong|wan]]", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#eeeeee|em|this device]]", ser(FwText.ruleDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"ACCEPT\">Accept</var> input", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("name" to "Allow-IGMP", "src" to "wan", "proto" to "igmp", "family" to "ipv4", "target" to "ACCEPT")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Incoming <var>IPv4</var>, protocol <var>IGMP</var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#f09090|strong|wan]]", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#eeeeee|em|this device]]", ser(FwText.ruleDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"ACCEPT\">Accept</var> input", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("name" to "Allow-DHCPv6", "src" to "wan", "proto" to "udp", "dest_port" to "546", "family" to "ipv6", "target" to "ACCEPT")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Incoming <var>IPv6</var>, protocol <var>UDP</var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#f09090|strong|wan]]", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#eeeeee|em|this device]], port <var>546</var>", ser(FwText.ruleDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"ACCEPT\">Accept</var> input", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("name" to "Allow-MLD", "src" to "wan", "proto" to "icmp", "src_ip" to "fe80::/10", "icmp_type" to listOf("130/0", "131/0", "132/0", "143/0"), "family" to "ipv6", "target" to "ACCEPT")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Incoming <var>IPv6</var>, protocol <var class=\"cbi-tooltip-container\">ICMP<span class=\"cbi-tooltip\">ICMP with types <var>130/0</var>, <var>131/0</var>, <var>132/0</var>, <var>143/0</var></span></var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#f09090|strong|wan]], IP <var>fe80::/10</var>", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#eeeeee|em|this device]]", ser(FwText.ruleDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"ACCEPT\">Accept</var> input", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("name" to "Allow-ICMPv6-Input", "src" to "wan", "proto" to "icmp", "icmp_type" to listOf("echo-request", "echo-reply", "destination-unreachable", "packet-too-big", "time-exceeded", "bad-header", "unknown-header-type", "router-solicitation", "neighbour-solicitation", "router-advertisement", "neighbour-advertisement"), "limit" to "1000/sec", "family" to "ipv6", "target" to "ACCEPT")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Incoming <var>IPv6</var>, protocol <var class=\"cbi-tooltip-container\">ICMP<span class=\"cbi-tooltip\">ICMP with types <var>echo-request</var>, <var>echo-reply</var>, <var>destination-unreachable</var>, <var>packet-too-big</var>, <var>time-exceeded</var>, <var>bad-header</var>, <var>unknown-header-type</var>, <var>router-solicitation</var>, <var>neighbour-solicitation</var>, <var>router-advertisement</var>, <var>neighbour-advertisement</var></span></var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#f09090|strong|wan]]", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#eeeeee|em|this device]]", ser(FwText.ruleDest(g)))
            assertEquals("Limit matching to <var>1000</var> packets per <var>second</var>", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"ACCEPT\">Accept</var> input", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("name" to "Allow-ICMPv6-Forward", "src" to "wan", "dest" to "*", "proto" to "icmp", "icmp_type" to listOf("echo-request", "echo-reply", "destination-unreachable", "packet-too-big", "time-exceeded", "bad-header", "unknown-header-type"), "limit" to "1000/sec", "family" to "ipv6", "target" to "ACCEPT")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Forwarded <var>IPv6</var>, protocol <var class=\"cbi-tooltip-container\">ICMP<span class=\"cbi-tooltip\">ICMP with types <var>echo-request</var>, <var>echo-reply</var>, <var>destination-unreachable</var>, <var>packet-too-big</var>, <var>time-exceeded</var>, <var>bad-header</var>, <var>unknown-header-type</var></span></var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#f09090|strong|wan]]", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#eeeeee|em|any zone]]", ser(FwText.ruleDest(g)))
            assertEquals("Limit matching to <var>1000</var> packets per <var>second</var>", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"ACCEPT\">Accept</var> forward", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("name" to "Allow-IPSec-ESP", "src" to "wan", "dest" to "lan", "proto" to "esp", "target" to "ACCEPT")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Forwarded <var>IPv4</var> and <var>IPv6</var>, protocol <var>IPSEC-ESP</var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#f09090|strong|wan]]", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#90f090|strong|lan]]", ser(FwText.ruleDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"ACCEPT\">Accept</var> forward", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("name" to "Allow-ISAKMP", "src" to "wan", "dest" to "lan", "dest_port" to "500", "proto" to "udp", "target" to "ACCEPT")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Forwarded <var>IPv4</var> and <var>IPv6</var>, protocol <var>UDP</var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#f09090|strong|wan]]", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#90f090|strong|lan]], port <var>500</var>", ser(FwText.ruleDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"ACCEPT\">Accept</var> forward", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("name" to "out", "dest" to "wan", "proto" to "tcp", "dest_port" to "25", "target" to "REJECT")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Outgoing <var>IPv4</var> and <var>IPv6</var>, protocol <var>TCP</var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#eeeeee|em|this device]]", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#f09090|strong|wan]], port <var>25</var>", ser(FwText.ruleDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"REJECT\">Reject</var> output", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("name" to "fwd", "src" to "lan", "dest" to "*", "proto" to listOf("tcp", "udp"), "src_ip" to listOf("192.168.1.0/24", "!10.0.0.1"), "dest_port" to "80 443", "target" to "DROP", "family" to "any")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Forwarded <var>IPv4</var> and <var>IPv6</var>, protocol <var>TCP</var>, <var>UDP</var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#90f090|strong|lan]], IP <var>192.168.1.0/24</var>, <var data-tooltip=\"Match IP addresses except 10.0.0.1.\">!10.0.0.1</var>", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#eeeeee|em|any zone]], port <var>80</var>, <var>443</var>", ser(FwText.ruleDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"DROP\">Drop</var> forward", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("src" to "guest", "proto" to "all", "src_mac" to listOf("08:02:8e:9e:d4:28", "!aa:bb:cc:dd:ee:ff", "64:82:14:48:DD:E9"), "target" to "ACCEPT", "limit" to "10/minute", "limit_burst" to "5")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Incoming <var>IPv4</var> and <var>IPv6</var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#a2e6eb|strong|guest]], MAC <var data-tooltip=\"nas\">08:02:8E:9E:D4:28</var>, <var data-tooltip=\"Match MACs except AA:BB:CC:DD:EE:FF.\">!AA:BB:CC:DD:EE:FF</var>, <var>64:82:14:48:DD:E9</var>", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#eeeeee|em|this device]]", ser(FwText.ruleDest(g)))
            assertEquals("Limit matching to <var>10</var> packets per <var>minute</var> burst <var>5</var>", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"ACCEPT\">Accept</var> input", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("src" to "lan", "dest" to "wan", "proto" to "6", "mark" to "!0x10/0xff", "dscp" to "AF11", "helper" to "!ftp", "target" to "MARK", "set_mark" to "0x1")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Forwarded <var>IPv4</var> and <var>IPv6</var>, protocol <var>TCP</var>, mark <var data-tooltip=\"Match fwmarks except 0x10 with mask 0xFF.\">!0x10/0xFF</var>, DSCP <var>AF11</var>, helper <var data-tooltip=\"Match any helper except &quot;FTP passive connection tracking&quot;\">!FTP</var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#90f090|strong|lan]]", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#f09090|strong|wan]]", ser(FwText.ruleDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"MARK\">Assign</var> firewall mark <var>0x1</var>", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("src" to "lan", "dest" to "wan", "proto" to "17", "mark" to "255", "dscp" to "!0x2e", "helper" to "sip", "target" to "MARK", "set_xmark" to "0x2/0x3")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Forwarded <var>IPv4</var> and <var>IPv6</var>, protocol <var>UDP</var>, mark <var>255</var>, DSCP <var data-tooltip=\"Match DSCP classifications except 0x2E\">!0x2e</var>, helper <var data-tooltip=\"SIP VoIP connection tracking\">SIP</var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#90f090|strong|lan]]", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#f09090|strong|wan]]", ser(FwText.ruleDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"MARK\">XOR</var> firewall mark <var>0x2/0x3</var>", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("src" to "lan", "proto" to "gre 47 foo", "target" to "HELPER", "set_helper" to "ftp", "direction" to "in", "device" to "eth0")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Incoming <var>IPv4</var> and <var>IPv6</var>, protocol <var>GRE</var>, <var>GRE</var>, <var>foo</var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#90f090|strong|lan]], interface <var>eth0</var>", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#eeeeee|em|this device]]", ser(FwText.ruleDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"HELPER\">Assign conntrack</var> helper <var data-tooltip=\"FTP passive connection tracking\">FTP</var>", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("dest" to "lan", "target" to "DSCP", "set_dscp" to "CS1", "direction" to "out", "device" to "br-lan", "family" to "IPv6", "limit" to "3/H")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Outgoing <var>IPv6</var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#eeeeee|em|this device]]", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#90f090|strong|lan]], interface <var>br-lan</var>", ser(FwText.ruleDest(g)))
            assertEquals("Limit matching to <var>3</var> packets per <var></var>", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"DSCP\">Assign DSCP</var> classification <var>CS1</var>", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("src" to "*", "dest" to "lan", "target" to "NOTRACK", "proto" to "icmpv6", "icmp_type" to "echo-request echo-reply", "family" to "*")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Forwarded <var>IPv4</var> and <var>IPv6</var>, protocol <var class=\"cbi-tooltip-container\">IPv6-ICMP<span class=\"cbi-tooltip\">ICMP with types <var>echo-request</var>, <var>echo-reply</var></span></var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#eeeeee|em|any zone]]", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#90f090|strong|lan]]", ser(FwText.ruleDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"NOTRACK\">Do not track</var> forward", ser(FwText.ruleTarget(g, ct)))
        }
        run {
            val s: Map<String, Any> = mapOf("src" to "iot", "target" to "WEIRD", "proto" to "ipv6-icmp", "dest_ip" to "FE80::1", "helper" to "unknownhelper")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Incoming <var>IPv4</var> and <var>IPv6</var>, protocol <var>IPv6-ICMP</var>, helper <var data-tooltip=\"\">UNKNOWNHELPER</var>", ser(FwText.ruleProto(g, ct)))
            assertEquals("From [[#f78ed4|strong|iot]]", ser(FwText.ruleSrc(g, hosts)))
            assertEquals("To [[#eeeeee|em|this device]], IP <var>fe80::1</var>", ser(FwText.ruleDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("WEIRD", ser(FwText.ruleTarget(g, ct)))
        }
    }

    @Test fun nats() {
        run {
            val s: Map<String, Any> = mapOf("name" to "n1", "src" to "lan", "src_ip" to "192.168.1.0/24", "target" to "SNAT", "snat_ip" to "10.0.0.1", "snat_port" to "1000", "proto" to "all")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Forwarded <var>IPv4</var>", ser(FwText.natProto(g)))
            assertEquals("From [[#eeeeee|em|any zone]], IP <var>192.168.1.0/24</var>", ser(FwText.natSrc(g)))
            assertEquals("To [[#90f090|strong|lan]]", ser(FwText.natDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"SNAT\">Statically rewrite</var> to source IP <var>10.0.0.1</var> port <var>1000</var>", ser(FwText.natTarget(g)))
        }
        run {
            val s: Map<String, Any> = mapOf("src" to "*", "dest_ip" to "fd00::/8", "target" to "MASQUERADE", "device" to "wan", "proto" to listOf("tcp", "udp"), "mark" to "0xff", "limit" to "5/day")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Forwarded <var>IPv6</var>, protocol <var>TCP</var>, <var>UDP</var>, mark <var>0xFF</var>", ser(FwText.natProto(g)))
            assertEquals("From [[#eeeeee|em|any zone]]", ser(FwText.natSrc(g)))
            assertEquals("To [[#eeeeee|em|any zone]], via interface <var>wan</var>, IP <var>fd00::/8</var>", ser(FwText.natDest(g)))
            assertEquals("Limit matching to <var>5</var> packets per <var>day</var>", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"MASQUERADE\">Automatically rewrite</var> source IP", ser(FwText.natTarget(g)))
        }
        run {
            val s: Map<String, Any> = mapOf("target" to "ACCEPT", "family" to "ipv4", "dest_port" to "!80", "src_port" to "53")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Forwarded <var>IPv4</var>", ser(FwText.natProto(g)))
            assertEquals("From [[#eeeeee|em|any zone]], port <var>53</var>", ser(FwText.natSrc(g)))
            assertEquals("To [[#eeeeee|em|this device]], port <var data-tooltip=\"Match ports except 80.\">!80</var>", ser(FwText.natDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"ACCEPT\">Prevent source rewrite</var>", ser(FwText.natTarget(g)))
        }
        run {
            val s: Map<String, Any> = mapOf("src" to "wan", "target" to "SNAT", "snat_port" to "2000-3000")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Forwarded <var>IPv4</var>", ser(FwText.natProto(g)))
            assertEquals("From [[#eeeeee|em|any zone]]", ser(FwText.natSrc(g)))
            assertEquals("To [[#f09090|strong|wan]]", ser(FwText.natDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("<var data-tooltip=\"SNAT\">Statically rewrite</var> to source  port <var>2000-3000</var>", ser(FwText.natTarget(g)))
        }
        run {
            val s: Map<String, Any> = mapOf("src" to "lan", "target" to "BOGUS", "snat_ip" to "::1")
            val g: (String) -> Any? = { s[it] }
            assertEquals("Forwarded <var>IPv6</var>", ser(FwText.natProto(g)))
            assertEquals("From [[#eeeeee|em|any zone]]", ser(FwText.natSrc(g)))
            assertEquals("To [[#90f090|strong|lan]]", ser(FwText.natDest(g)))
            assertEquals("", ser(FwText.limit(g)))
            assertEquals("BOGUS", ser(FwText.natTarget(g)))
        }
    }
}
