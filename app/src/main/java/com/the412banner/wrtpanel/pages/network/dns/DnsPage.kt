package com.the412banner.wrtpanel.pages.network.dns

import androidx.compose.runtime.Composable
import com.the412banner.wrtpanel.core.EmptyJson
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.form.Datatypes
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormPage
import com.the412banner.wrtpanel.form.FormRuntime
import com.the412banner.wrtpanel.form.SectionKind
import com.the412banner.wrtpanel.form.ValueOption
import com.the412banner.wrtpanel.form.ipSelect
import com.the412banner.wrtpanel.form.networkSelect
import com.the412banner.wrtpanel.pages.network.dhcp.dnsmasqInstances
import com.the412banner.wrtpanel.pages.network.dhcp.hasSystemFeature
import com.the412banner.wrtpanel.pages.network.dhcp.hostHintIPv4
import com.the412banner.wrtpanel.pages.network.dhcp.logFacilities
import com.the412banner.wrtpanel.pages.network.dhcp.validateHostname
import com.the412banner.wrtpanel.ui.PageContext

private fun validateAddressList(s: String): String? {
    if (s.isEmpty()) return null
    val m = Regex("^/(.+)/$").find(s)
    val names = if (m != null) m.groupValues[1].split('/') else listOf(s)
    for (name in names) validateHostname(name)?.let { return it }
    return null
}

private fun validateServerSpec(s: String): String? {
    if (s.isEmpty()) return null
    val m = Regex("^(/.*/)?(.*)$").find(s) ?: return "Expecting: valid hostname"
    val domains = m.groups[1]?.value
    if (domains != "//" && domains != "/#/") validateAddressList(domains ?: "")?.let { return it }
    val rest = m.groupValues[2]
    if (rest == "" || rest == "#") return null

    val m2 = Regex("^([0-9a-f:.]+)(?:%[^#@]+)?(?:#(\\d+))?(?:@([0-9a-f:.]+)(?:@[^#]+)?(?:#(\\d+))?)?$").find(rest)
        ?: return "Expecting: valid IP address"
    val addr = m2.groupValues[1]
    val src = m2.groups[3]?.value
    if (Datatypes.parseIPv4(addr) != null) {
        if (src != null && Datatypes.parseIPv4(src) == null) return "Expecting: valid IPv4 address"
    } else if (Datatypes.parseIPv6(addr) != null) {
        if (src != null && Datatypes.parseIPv6(src) == null) return "Expecting: valid IPv6 address"
    } else {
        return "Expecting: valid IP address"
    }
    fun tooBig(p: String?) = p != null && (p.trimStart('0').length > 5 || (p.toIntOrNull() ?: 0) > 65535)
    if (tooBig(m2.groups[2]?.value) || tooBig(m2.groups[4]?.value)) return "Expecting: valid port value"
    return null
}

private val noi18nstrings = mapOf(
    "etc_hosts" to "<code>/etc/hosts</code>",
    "localhost_v6" to "<code>::1</code>",
    "loopback_slash_8_v4" to "<code>127.0.0.0/8</code>",
    "not_found" to "<code>Not found</code>",
    "nxdomain" to "<code>NXDOMAIN</code>",
    "rfc_1918_link" to "<a href=\"https://www.rfc-editor.org/rfc/rfc1918\">RFC1918</a>",
    "rfc_4193_link" to "<a href=\"https://www.rfc-editor.org/rfc/rfc4193\">RFC4193</a>",
    "rfc_4291_link" to "<a href=\"https://www.rfc-editor.org/rfc/rfc4291\">RFC4291</a>",
    "rfc_6303_link" to "<a href=\"https://www.rfc-editor.org/rfc/rfc6303\">RFC6303</a>",
    "reverse_arpa" to "<code>*.IN-ADDR.ARPA,*.IP6.ARPA</code>",
    "servers_file_entry01" to "<code>server=1.2.3.4</code>",
    "servers_file_entry02" to "<code>server=/domain/1.2.3.4</code>",
)

private fun customi18n(template: String, values: Map<String, String> = noi18nstrings): String =
    Regex("\\{(\\w+)\\}").replace(template) { values[it.groupValues[1]] ?: it.value }

private val recordtypes = listOf(
    "ANY", "A", "AAAA", "ALIAS", "CAA", "CERT", "CNAME", "DS", "HINFO", "HIP", "HTTPS", "KEY", "LOC", "MX",
    "NAPTR", "NS", "OPENPGPKEY", "PTR", "RP", "SIG", "SOA", "SRV", "SSHFP", "SVCB", "TLSA", "TXT", "URI",
)

/** JavaScript parseInt(s, 16) of one or two chars; NaN as 0 (String.fromCharCode(NaN) is "\u0000"). */
private fun hexByte(s: String): Int = Regex("^[0-9a-fA-F]+").find(s.trim())?.value?.toInt(16) ?: 0

/** Network › DNS — port of luci-mod-network view/network/dns.js. */
@Composable
fun DnsPage(ctx: PageContext) = FormPage(ctx) {
    val hosts = callOrNull("luci-rpc", "getHostHints").obj ?: EmptyJson
    uci.load("firewall")
    val uci = uci

    val m = FormMap("dhcp", "DNS")

    m.typed("dnsmasq") {
        anonymous = false
        addremove = true
        dnsmasqInstances(uci)

        tab("general", "General")
        tab("cache", "Cache")
        tab("devices", "Devices & Ports")
        tab("dnsrecords", "DNS Records")
        tab("dnssecopt", "DNSSEC")
        tab("filteropts", "Filter")
        tab("forward", "Forwards")
        tab("limits", "Limits")
        tab("logging", "Log")
        tab("files", "Resolv & Hosts Files")
        tab("ipsets", "IP Sets")

        value("local", "Resolve these locally", "Never forward these matching domains or subdomains; resolve from DHCP or hosts files only.", tab = "general") {
            placeholder = "/internal.example.com/private.example.com/example.org"
        }
        value("domain", "Local domain", "Local domain suffix appended to DHCP names and hosts file entries.", tab = "general")
        flag("expandhosts", "Expand hosts", "Add local domain suffix to names served from hosts files.", tab = "general")
        dynlist(
            "address", "Addresses",
            "Resolve specified FQDNs to an IP." + "<br />" +
                customi18n("Syntax: {code_syntax}.", mapOf("code_syntax" to "<code>/fqdn[/fqdn…]/[ipaddr]</code>")) + "<br />" +
                customi18n("{example_nx} returns {nxdomain}.", mapOf("example_nx" to "<code>/example.com/</code>", "nxdomain" to "<code>NXDOMAIN</code>")) + "<br />" +
                customi18n("{any_domain} matches any domain (and returns {nxdomain}).", mapOf("any_domain" to "<code>/#/</code>", "nxdomain" to "<code>NXDOMAIN</code>")) + "<br />" +
                customi18n(
                    "{example_null} returns {null_addr} addresses ({null_ipv4}, {null_ipv6}) for {example_com} and its subdomains.",
                    mapOf(
                        "example_null" to "<code>/example.com/#</code>",
                        "null_addr" to "<code>NULL</code>",
                        "null_ipv4" to "<code>0.0.0.0</code>",
                        "null_ipv6" to "<code>::</code>",
                        "example_com" to "<code>example.com</code>",
                    ),
                ),
            tab = "general",
        ) { optional = true; placeholder = "/router.local/router.lan/192.168.0.1" }
        flag("allservers", "All servers", "Query all available upstream resolvers." + " " + "First answer wins.", tab = "general") { optional = true }

        multi(
            "cache_rr", "Cache arbitrary RR",
            "By default, dnsmasq caches A, AAAA, CNAME and SRV DNS record types." + "<br/>" + "This option adds additional record types to the cache.",
            tab = "cache",
        ) {
            optional = true; create = true; placeholder = "-- Please choose --"
            recordtypes.forEach { value(it) }
        }

        flag("nonwildcard", "Non-wildcard", "Bind only to configured interface addresses, instead of the wildcard address.", tab = "devices") {
            default = enabled; optional = false; rmempty = true
        }
        networkSelect("interface", "Listen interfaces", "Listen only on the specified interfaces, and loopback if not excluded explicitly.", tab = "devices", multiple = true, nocreate = true)
        ipSelect("listen_address", "Listen addresses", "Listen only on the specified addresses.", tab = "devices", multiple = true)
        networkSelect("notinterface", "Exclude interfaces", "Do not listen on the specified interfaces.", tab = "devices", multiple = true, nocreate = true, loopback = true)
        value("port", "DNS server port", "Listening port for inbound DNS queries.", tab = "devices") {
            optional = true; datatype = "port"; placeholder = "53"
        }
        value("queryport", "DNS query port", "Fixed source port for outbound DNS queries.", tab = "devices") {
            optional = true; datatype = "port"; placeholder = "any"
        }
        value("minport", "Minimum source port #", "Min valid value <code>1024</code>." + " " + "Useful for systems behind firewalls.", tab = "devices") {
            optional = true; datatype = "port"; placeholder = "1024"; depends("queryport", "")
        }
        value("maxport", "Maximum source port #", "Max valid value <code>65535</code>." + " " + "Useful for systems behind firewalls.", tab = "devices") {
            optional = true; datatype = "port"; placeholder = "50000"; depends("queryport", "")
        }

        val dnss = nested(SectionKind.Typed, "__dnsrecords__") {
            anonymous = true
            cfgsectionsOverride = { listOf("__dnsrecords__") }
            tab("hosts", "Hostnames")
            tab("srvhosts", "SRV")
            tab("mxhosts", "MX")
            tab("cnamehosts", "CNAME")
            tab("dnsrr", "DNS-RR")

            val srv = nested(
                SectionKind.Table, "srvhost", null,
                "Bind service records to a domain name: specify the location of services. See <a href=\"https://datatracker.ietf.org/doc/html/rfc2782\">RFC2782</a>." +
                    "<br />" + "_service: _sip, _ldap, _imap, _stun, _xmpp-client, … . (Note: while _http is possible, no browsers support SRV records.)" +
                    "<br />" + "_proto: _tcp, _udp, _sctp, _quic, … ." +
                    "<br />" + "You may add multiple records for the same Target." +
                    "<br />" + "Larger weights (of the same prio) are given a proportionately higher probability of being selected.",
            ) {
                addremove = true; anonymous = true; sortable = true
                value("srv", "SRV", "Syntax:" + " " + "<code>_service._proto.example.com.</code>") {
                    rmempty = false; datatype = "hostname"; placeholder = "_sip._tcp.example.com."
                }
                value("target", "Target", "CNAME or fqdn") { rmempty = false; datatype = "hostname"; placeholder = "sip.example.com." }
                value("port", "Port") { rmempty = false; datatype = "port"; placeholder = "5060" }
                value("class", "Priority", "Ordinal: lower comes first.") { rmempty = true; datatype = "range(0,65535)"; placeholder = "10" }
                value("weight", "Weight") { rmempty = true; datatype = "range(0,65535)"; placeholder = "50" }
            }
            subsection("__srvhosts__", srv, tab = "srvhosts")

            val mx = nested(
                SectionKind.Table, "mxhost", null,
                "Bind service records to a domain name: specify the location of services." + "<br />" + "You may add multiple records for the same domain.",
            ) {
                addremove = true; anonymous = true; sortable = true
                value("domain", "Domain") { rmempty = false; datatype = "hostname"; placeholder = "example.com." }
                value("relay", "Relay") { rmempty = false; datatype = "hostname"; placeholder = "relay.example.com." }
                value("pref", "Priority", "Ordinal: lower comes first.") { rmempty = true; datatype = "range(0,65535)"; placeholder = "0" }
            }
            subsection("__mxhosts__", mx, tab = "mxhosts")

            val cname = nested(SectionKind.Table, "cname", null, "Set an alias for a hostname.") {
                addremove = true; anonymous = true; sortable = true
                value("cname", "Domain") { rmempty = false; validate = { _, _, v -> validateHostname(v) }; placeholder = "www.example.com." }
                value("target", "Target") { rmempty = false; datatype = "hostname"; placeholder = "example.com." }
            }
            subsection("__cname__", cname, tab = "cnamehosts")

            val hostnames = nested(
                SectionKind.Grid, "domain", null,
                "Hostnames are used to bind a domain name to an IP address. This setting is redundant for hostnames already configured with static leases, but it can be useful to rebind an FQDN.",
            ) {
                addremove = true; anonymous = true; sortable = true
                value("name", "Hostname") { rmempty = false; datatype = "hostname" }
                value("ip", "IP address") {
                    rmempty = false; datatype = "ipaddr(\"nomask\")"
                    hostHintIPv4(hosts).forEach { (ip, n) -> value(ip, "$ip ($n)") }
                }
            }
            subsection("__hosts__", hostnames, tab = "hosts")

            val dnsrr = nested(
                SectionKind.Grid, "dnsrr", null,
                "Set an arbitrary resource record (RR) type." + "<br/>" + "Hexdata is automatically en/decoded on save and load",
            ) {
                addremove = true; anonymous = true; sortable = true

                value("rrname", "Resource Record Name") { rmempty = false; datatype = "hostname"; placeholder = "svcb.example.com." }
                value("rrnumber", "Resource Record Number") { rmempty = false; datatype = "uinteger"; placeholder = "64" }
                value("_hexdata", "Raw Data") {
                    rmempty = true; datatype = "string"; placeholder = "free-form string"; modalonly = true
                    cfgvalueFn = { rt, sid ->
                        val v = (rt.uci.getString("dhcp", sid, "hexdata") ?: "").replace(Regex("[\\s:]"), "")
                        String(CharArray((v.length + 1) / 2) { hexByte(v.substring(it * 2, minOf(v.length, it * 2 + 2))).toChar() })
                    }
                    writeFn = { rt, sid, v ->
                        val s = v.toString()
                        if (s.isEmpty()) rt.uci.unset("dhcp", sid, "hexdata")
                        else rt.uci.set("dhcp", sid, option, s.map { it.code.toString(16).padStart(2, '0') }.joinToString(""))
                    }
                    dependsNot("rrnumber" to "65")
                }
                dummy("hexdata", "Hex Data") {
                    width = "50%"
                    cfgvalueFn = { rt, sid ->
                        (rt.uci.getString("dhcp", sid, "hexdata") ?: "").replace(":", "").replace(Regex("(.{2})"), "$1 ")
                    }
                }

                lateinit var prio: ValueOption
                lateinit var target: ValueOption
                val writetype65: (FormRuntime, String, Any) -> Unit = { rt, sid, v ->
                    if (rt.uci.getString("dhcp", sid, "rrnumber") == "65") {
                        val priority = Regex("^\\s*[+-]?\\d+").find(rt.formString(prio, sid))?.value?.trim()?.toIntOrNull()
                        val tgt = rt.formString(target, sid).ifEmpty { "." }
                        val params = v.toString().trim().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                        rt.uci.set("dhcp", sid, "hexdata", DnsRecordHandlers.buildSvcbHex(priority, tgt, params))
                    }
                }
                fun loadtype65(rt: FormRuntime, sid: String): DnsRecordHandlers.Svcb? =
                    if (rt.uci.getString("dhcp", sid, "rrnumber") != "65") null
                    else DnsRecordHandlers.parseSvcbHex(rt.uci.getString("dhcp", sid, "hexdata"))

                prio = value("_svc_priority", "Svc Priority") {
                    placeholder = "1"; datatype = "and(uinteger,min(0),max(65535))"; modalonly = true
                    depends("rrnumber", "65")
                    writeFn = writetype65
                    cfgvalueFn = { rt, sid -> loadtype65(rt, sid)?.priority?.toString() ?: "" }
                }
                target = value("_svc_target", "Svc Target") {
                    placeholder = "svc.example.com."; modalonly = true
                    depends("rrnumber", "65")
                    writeFn = writetype65
                    cfgvalueFn = { rt, sid -> loadtype65(rt, sid)?.target?.ifEmpty { null } ?: "" }
                }
                text("_svc_params", "Svc Parameters") {
                    placeholder = "alpn=h2,h3\nipv4hint=192.0.2.1,192.0.2.2\nipv6hint=2001:db8::1,2001:db8::2\nport=8000"
                    modalonly = true; rows = 4
                    depends("rrnumber", "65")
                    writeFn = writetype65
                    cfgvalueFn = { rt, sid -> loadtype65(rt, sid)?.params?.joinToString("\n")?.ifEmpty { null } ?: "" }
                }
            }
            subsection("__dnsrr__", dnsrr, tab = "dnsrr")
        }
        subsection("__dnsrecords__", dnss, tab = "dnsrecords")

        if (hasSystemFeature("dnsmasq", "dnssec")) {
            flag("dnssec", "DNSSEC", "Validate DNS replies and cache DNSSEC data, requires upstream to support DNSSEC.", tab = "dnssecopt") { optional = true }
            flag("dnsseccheckunsigned", "DNSSEC check unsigned", "Verify unsigned domain responses really come from unsigned domains.", tab = "dnssecopt") {
                default = enabled; optional = true
            }
        }

        flag(
            "domainneeded", "Domain required",
            "Never forward DNS queries which lack dots or domain parts." + "<br />" + customi18n("Names not in {etc_hosts} are answered {not_found}."),
            tab = "filteropts",
        )
        flag(
            "rebind_protection", "Rebind protection",
            customi18n("Discard upstream responses containing {rfc_1918_link} addresses.") + "<br />" +
                customi18n("Discard also upstream responses containing {rfc_4193_link}, Link-Local and private IPv4-Mapped {rfc_4291_link} IPv6 Addresses."),
            tab = "filteropts",
        ) { rmempty = false }
        flag(
            "rebind_localhost", "Allow localhost",
            customi18n("Exempt {loopback_slash_8_v4} and {localhost_v6} from rebinding checks, e.g. for <abbr title=\"Real-time Block List\">RBL</abbr> services."),
            tab = "filteropts",
        ) { depends("rebind_protection", "1") }
        dynlist("rebind_domain", "Domain whitelist", customi18n("List of domains to allow {rfc_1918_link} responses for."), tab = "filteropts") {
            depends("rebind_protection", "1"); optional = true; placeholder = "ihost.netflix.com"
            validate = { _, _, v -> validateAddressList(v) }
        }
        flag("localservice", "Local service only", "Accept DNS queries only from hosts whose address is on a local subnet.", tab = "filteropts") {
            optional = false; rmempty = false
        }
        flag(
            "boguspriv", "Filter private",
            customi18n("Reject reverse lookups to {rfc_6303_link} IP ranges ({reverse_arpa}) not in {etc_hosts}."),
            tab = "filteropts",
        ) { default = enabled }
        flag(
            "filterwin2k", "Filter SRV/SOA service discovery",
            "Filters SRV/SOA service discovery, to avoid triggering dial-on-demand links." + "<br />" + "May prevent VoIP or other services from working.",
            tab = "filteropts",
        )
        flag(
            "filter_aaaa", "Filter IPv6 AAAA records",
            "Remove IPv6 addresses from the results and only return IPv4 addresses." + "<br />" + "Can be useful if ISP has IPv6 nameservers but does not provide IPv6 routing.",
            tab = "filteropts",
        ) { optional = true }
        flag("filter_a", "Filter IPv4 A records", "Remove IPv4 addresses from the results and only return IPv6 addresses.", tab = "filteropts") { optional = true }
        multi("filter_rr", "Filter arbitrary RR", "Removes records of the specified type(s) from answers.", tab = "filteropts") {
            optional = true; create = true; placeholder = "-- Please choose --"
            recordtypes.forEach { value(it) }
        }
        flag(
            "localise_queries", "Localise queries",
            customi18n("Limit response records (from {etc_hosts}) to those that fall within the subnet of the querying interface.") + "<br />" +
                "This prevents unreachable IPs in subnets not accessible to you." + "<br />" + "Note: IPv4 only.",
            tab = "filteropts",
        )
        flag("nonegcache", "No negative cache", "Do not cache negative replies, e.g. for non-existent domains.", tab = "filteropts")
        dynlist(
            "bogusnxdomain", customi18n("IPs to override with {nxdomain}"),
            customi18n("Transform replies which contain the specified addresses or subnets into {nxdomain} responses."),
            tab = "filteropts",
        ) { optional = true; placeholder = "64.94.110.11" }

        dynlist("server", "DNS Forwards", "Forward specific domain queries to specific upstream servers.", tab = "forward") {
            optional = true; placeholder = "/*.example.org/10.1.2.3"
            validate = { _, _, v -> validateServerSpec(v) }
        }
        value(
            "serversfile", "Additional servers file",
            customi18n("File listing upstream resolvers, optionally domain-specific, e.g. {servers_file_entry01}, {servers_file_entry02}."),
            tab = "forward",
        ) { placeholder = "/etc/dnsmasq.servers" }
        value(
            "addmac", "Add requestor MAC",
            "Add the MAC address of the requestor to DNS queries which are forwarded upstream." + " " + "<br />" +
                "<code>enabled</code> uses the default MAC address format encoding" + " " + "<br />" +
                "<code>base64</code> uses an alternative encoding of the MAC as base64" + " " + "<br />" +
                "<code>text</code> uses a human-readable encoding of hex-and-colons",
            tab = "forward",
        ) {
            optional = true
            value("", "off")
            value("1", "enabled (default)")
            value("base64")
            value("text")
        }
        flag("stripmac", "Remove MAC address before forwarding query", "Remove any MAC address information already in downstream queries before forwarding upstream.", tab = "forward")
        value(
            "addsubnet", "Add subnet address to forwards",
            "Add a subnet address to the DNS queries which are forwarded upstream, leaving this value empty disables the feature." + " " +
                "If an address is specified in the flag, it will be used, otherwise, the address of the requestor will be used." + " " +
                "The amount of the address forwarded depends on the prefix length parameter: 32 (128 for IPv6) forwards the whole address, zero forwards none of it but still marks the request so that no upstream nameserver will add client address information either." + " " + "<br />" +
                "The default (<code>0,0</code>) is zero for both IPv4 and IPv6." + " " + "<br />" +
                "<code>24,96</code> adds the /24 and /96 subnets of the requestor for IPv4 and IPv6 requestors, respectively." + " " + "<br />" +
                "<code>1.2.3.4/24</code> adds 1.2.3.0/24 for IPv4 requestors and ::/0 for IPv6 requestors." + " " + "<br />" +
                "<code>1.2.3.4/24,1.2.3.4/24</code> adds 1.2.3.0/24 for both IPv4 and IPv6 requestors.",
            tab = "forward",
        ) { optional = true }
        flag("stripsubnet", "Remove subnet address before forwarding query", "Remove any subnet address already present in a downstream query before forwarding it upstream.", tab = "forward")

        value("ednspacket_max", "Max. EDNS0 packet size", "Maximum allowed size of EDNS0 UDP packets.", tab = "limits") {
            optional = true; datatype = "uinteger"; placeholder = "1280"
        }
        value("dnsforwardmax", "Max. concurrent queries", "Maximum allowed number of concurrent DNS queries.", tab = "limits") {
            optional = true; datatype = "uinteger"; placeholder = "150"
        }
        value("cachesize", "Size of DNS query cache", "Number of cached DNS entries, 10000 is maximum, 0 is no caching.", tab = "limits") {
            optional = true; datatype = "range(0,10000)"; placeholder = "150"
        }
        value(
            "min_cache_ttl", "Min cache TTL",
            "Extend short TTL values to the seconds value given when caching them. Use with caution." + " (Max 1h == 3600)",
            tab = "limits",
        ) { optional = true; placeholder = "60" }
        value("max_cache_ttl", "Max cache TTL", "Set a maximum seconds TTL value for entries in the cache.", tab = "limits") {
            optional = true; placeholder = "3600"
        }

        flag("logqueries", "Log queries", "Write received DNS queries to syslog." + " " + "Dump cache on SIGUSR1, include requesting IP.", tab = "logging") {
            optional = true
        }
        value("logfacility", "Log facility", "Set log class/facility for syslog entries.", tab = "logging") {
            optional = true
            logFacilities.forEach { value(it) }
            value("-", "stderr")
        }

        flag("noresolv", "Ignore resolv file", tab = "files") { optional = true }
        value("resolvfile", "Resolv file", "File with upstream resolvers.", tab = "files") {
            depends("noresolv", "0"); placeholder = "/tmp/resolv.conf.d/resolv.conf.auto"; optional = true
        }
        flag("strictorder", "Strict order", "Query upstream resolvers in the order they appear in the resolv file.", tab = "files") { optional = true }
        flag(
            "ignore_hosts_dir", "Ignore hosts files directory",
            "On: use instance specific hosts file only" + "<br/>" + "Off: use all files in the directory including the instance specific hosts file",
            tab = "files",
        ) { optional = true }
        flag("nohosts", customi18n("Ignore {etc_hosts} file"), tab = "files") { optional = true }
        dynlist("addnhosts", "Additional hosts files", tab = "files") { optional = true; placeholder = "/etc/dnsmasq.hosts" }

        val ipsets = nested(
            SectionKind.Grid, "ipset", null,
            "List of IP sets to populate with the IPs of DNS lookup results of the FQDNs also specified here." + "<br />" +
                "The netfilter components below are only regarded when running fw4.",
        ) {
            addremove = true; anonymous = true; sortable = true
            modalTitle = { "Edit IP set" }
            dynlist("name", "Name of the set") {
                uci.sections("firewall", "ipset").forEach { s ->
                    val n = s["name"] as? String ?: return@forEach
                    val comment = s["comment"] as? String
                    value(n, if (!comment.isNullOrEmpty()) "$n ($comment)" else n)
                }
                rmempty = false; editable = false; datatype = "string"
            }
            dynlist("domain", "FQDN") { rmempty = false; editable = false; datatype = "hostname" }
            value("table", "Netfilter table name", "Defaults to fw4.") { editable = false; placeholder = "fw4"; rmempty = true }
            list(
                "table_family", "Table IP family",
                "Defaults to IPv4+6." + " " + "Can be hinted by adding 4 or 6 to the name." + "<br />" + "Adding an IPv6 to an IPv4 set and vice-versa silently fails.",
            ) {
                editable = false; rmempty = true
                value("inet", "IPv4+6")
                value("ip", "IPv4")
                value("ip6", "IPv6")
            }
        }
        subsection("__ipsets__", ipsets, tab = "ipsets")
    }
    m
}
