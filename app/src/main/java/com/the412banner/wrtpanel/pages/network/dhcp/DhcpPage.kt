package com.the412banner.wrtpanel.pages.network.dhcp

import androidx.compose.runtime.Composable
import com.the412banner.wrtpanel.core.EmptyJson
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.core.strList
import com.the412banner.wrtpanel.form.Datatypes
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormPage
import com.the412banner.wrtpanel.form.SectionKind
import com.the412banner.wrtpanel.form.ipSelect
import com.the412banner.wrtpanel.form.networkSelect
import com.the412banner.wrtpanel.pages.common.NetTools
import com.the412banner.wrtpanel.ui.PageContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject

private data class Pool(val sectionId: String, val network: String, val netmask: String)

private class DhcpLoad(
    val hosts: JsonObject, val duids: JsonObject, val pools: List<Pool>, val macdata: JsonObject?,
    val devices: Set<String>, val services: Set<String>,
)

/** calculateNetwork() of dhcp.js: (network, netmask) of [addr] under [mask] (prefix length or dotted mask). */
private fun calculateNetwork(addr: String, mask: String): Pair<String, String>? {
    val a = Datatypes.parseIPv4(addr) ?: return null
    val bits = mask.toIntOrNull()
    val m = (if (bits != null) NetTools.prefixToMask(bits, false) else mask)?.let { Datatypes.parseIPv4(it) } ?: return null
    return (0..3).joinToString(".") { (a[it] and (m[it] and 255)).toString() } to m.joinToString(".")
}

/** getDHCPPools() of dhcp.js. */
private suspend fun PageContext.dhcpPools(): List<Pool> {
    uci.load("dhcp")
    val ifaces = callOrNull("network.interface", "dump")["interface"].arr ?: emptyList()
    return uci.sections("dhcp", "dhcp").mapNotNull { s ->
        val iface = s["interface"] as? String
        if (s["ignore"] == "1" || iface.isNullOrEmpty()) return@mapNotNull null
        val st = ifaces.firstOrNull { it["interface"].str == iface } ?: return@mapNotNull null
        val first = st["ipv4-address"].arr?.firstOrNull() ?: return@mapNotNull null
        val nm = calculateNetwork(first["address"].str ?: "", first["mask"].str ?: "") ?: return@mapNotNull null
        Pool(s[".name"].toString(), nm.first, nm.second)
    }
}

private fun validateDUIDIAID(s: String): String? {
    if (s.isEmpty()) return null
    val parts = s.split('%')
    if (parts.size > 2) return "Expecting: maximum one \"%\""
    if (parts[0].length < 20 || parts[0].length > 260 || !Regex("^([a-f0-9]{2})+$", RegexOption.IGNORE_CASE).matches(parts[0]))
        return "Expecting: DUID with an even number (20 to 260) of hexadecimal characters"
    if (parts.size == 2 && (parts[1].isEmpty() || parts[1].length > 8 || !Regex("^[a-f0-9]+$", RegexOption.IGNORE_CASE).matches(parts[1])))
        return "Expecting: IAID of 1 to 8 hexadecimal characters"
    return null
}

private fun isValidMAC(macs: List<String>): String? {
    val re = Regex("^(([0-9a-f]{2}|\\*):){5}([0-9a-f]{2}|\\*)$", RegexOption.IGNORE_CASE)
    for (mac in macs) if (!re.matches(mac)) return "Expecting a valid MAC address, optionally including wildcards; invalid MAC: $mac"
    return null
}

/** expandAndFormatMAC() of dhcp.js. */
private fun expandAndFormatMAC(macs: List<String>): MutableList<String>? =
    macs.map { mac -> mac.split(':').joinToString(":") { p -> if (p.length == 1 && p != "*") "0$p" else p }.uppercase() }
        .toMutableList().takeIf { it.isNotEmpty() }

private val reservedTags = listOf(
    "known" to "known",
    "!known" to "!known (not known)",
    "known-othernet" to "known-othernet (on different subnet)",
)

private fun validateTags(value: String): String? = if (reservedTags.any { it.first == value }) "Reserved tag" else null

/** Network › DHCP — port of luci-mod-network view/network/dhcp.js. */
@Composable
fun DhcpPage(ctx: PageContext) = FormPage(ctx) {
    val ufpd = hasSystemFeature("ufpd")
    val d = coroutineScope {
        val hosts = async { callOrNull("luci-rpc", "getHostHints").obj ?: EmptyJson }
        val duids = async { callOrNull("luci-rpc", "getDUIDHints").obj ?: EmptyJson }
        val pools = async { dhcpPools() }
        val macdata = async { if (ufpd) callOrNull("fingerprint", "fingerprint").obj ?: EmptyJson else null }
        val devices = async { callOrNull("luci-rpc", "getNetworkDevices").obj?.keys ?: emptySet() }
        val services = async { callOrNull("service", "list").obj?.keys ?: emptySet() }
        DhcpLoad(hosts.await(), duids.await(), pools.await(), macdata.await(), devices.await(), services.await())
    }

    val m = FormMap("dhcp", "DHCP")
    m.tabbed = true

    addLeasesCfg(m, d.hosts, d.duids, d.pools, d.macdata)

    if (hasSystemFeature("dnsmasq")) addDnsmasqCfg(m, d.devices, d.services)

    if (hasSystemFeature("odhcpd")) addOdhcpdCfg(m)

    m
}

private fun PageContext.tagChoices(): List<String> =
    uci.sections("dhcp", "tag").mapNotNull { it[".name"] as? String }.flatMap { listOf(it, "!$it") }

private fun PageContext.addDnsmasqCfg(m: FormMap, devices: Set<String>, services: Set<String>) {
    val uci = uci
    m.typed("dnsmasq", "dnsmasq") {
        hidetitle = true
        anonymous = false
        addremove = true
        dnsmasqInstances(uci)

        tab("general", "General")
        tab("devices", "Devices & Ports")
        tab("logging", "Log")
        tab("files", "Files")
        tab("relay", "Relay")
        tab("tagsparent", "Tags")

        flag("authoritative", "Authoritative", "This is the only DHCP server in the local network.", tab = "general")
        value("domain", "Local domain", "Local domain suffix appended to DHCP names and hosts file entries.", tab = "general")
        flag("sequential_ip", "Allocate IPs sequentially", "Allocate IP addresses sequentially, starting from the lowest available address.", tab = "general") {
            optional = true
        }
        value("dhcpleasemax", "Max. DHCP leases", "Maximum allowed number of active DHCP leases.", tab = "general") {
            optional = true; datatype = "uinteger"; placeholder = "150"
        }
        flag("address_as_local", "Resolve addresses locally", "Never send queries for FQDNs in the Address option to an upstream resolver.", tab = "general") {
            optional = true
        }

        flag("nonwildcard", "Non-wildcard", "Bind only to configured interface addresses, instead of the wildcard address.", tab = "devices") {
            default = enabled; optional = false; rmempty = true
        }
        networkSelect("interface", "Listen interfaces", "Listen only on the specified interfaces, and loopback if not excluded explicitly.", tab = "devices", multiple = true, nocreate = true)
        ipSelect("listen_address", "Listen addresses", "Listen only on the specified addresses.", tab = "devices", multiple = true)
        networkSelect("notinterface", "Exclude interfaces", "Do not listen on the specified interfaces.", tab = "devices", multiple = true, nocreate = true, loopback = true)

        val logdhcp = flag("logdhcp", "Extra DHCP logging", "Log all options sent to DHCP clients and the tags used to determine them.", tab = "logging") {
            optional = true
        }
        value("logfacility", "Log facility", "Set log class/facility for syslog entries.", tab = "logging") {
            optional = true
            logFacilities.forEach { value(it) }
            value("-", "stderr")
        }
        flag("quietdhcp", "Suppress logging", "Suppress logging of the routine operation for the DHCP protocol.", tab = "logging") {
            optional = true; depends(logdhcp.option, "0")
        }

        flag("readethers", "Use <code>/etc/ethers</code>", "Read <code>/etc/ethers</code> to configure the DHCP server.", tab = "files")
        value("leasefile", "Lease file", "File to store DHCP lease information.", tab = "files")

        val relays = nested(
            SectionKind.Table, "relay", null,
            "Relay DHCP requests elsewhere. OK: v4↔v4, v6↔v6. Not OK: v4↔v6, v6↔v4." +
                "<br />" + "Note: you may also need a DHCP Proxy (currently unavailable) when specifying a non-standard Relay To port(<code>addr#port</code>)." +
                "<br />" + "You may add multiple unique Relay To on the same Listen addr.",
        ) {
            addremove = true; anonymous = true; sortable = true
            val localAddr = ipSelect("local_addr", "Relay from", multiple = false) { rmempty = false }
            lateinit var serverAddr: com.the412banner.wrtpanel.form.ValueOption
            serverAddr = value("server_addr", "Relay to address") {
                rmempty = false; optional = false; placeholder = "192.168.10.1#535"
                validate = { rt, sid, _ ->
                    val lm = rt.formString(localAddr, sid)
                    var n = rt.formString(serverAddr, sid)
                    if (lm.isEmpty() || n.isEmpty()) "Both \"Relay from\" and \"Relay to address\" must be specified."
                    else {
                        val p = n.split('#')
                        if (p.size > 1 && !Regex("^[0-9]+$").matches(p[1])) "Expected port number."
                        else {
                            n = p[0]
                            if ((Datatypes.parseIPv6(lm) != null && Datatypes.parseIPv6(n) != null) ||
                                (Datatypes.parseIPv4(lm) != null && Datatypes.parseIPv4(n) != null)) null
                            else "Address families of \"Relay from\" and \"Relay to address\" must match."
                        }
                    }
                }
            }
            networkSelect("interface", "Only accept replies via") {
                optional = true; rmempty = false; placeholder = "lan"
            }
        }
        subsection("__relays__", relays, tab = "relay")

        tab("pxe_tftp", "PXE/TFTP")
        val tftp = flag("enable_tftp", "Enable TFTP server", "Enable the built-in single-instance TFTP server.", tab = "pxe_tftp") {
            optional = true
        }
        value(
            "tftp_root", "TFTP server root",
            "Root directory for files served via TFTP. <em>Enable TFTP server</em> and <em>TFTP server root</em> turn on the TFTP server and serve files from <em>TFTP server root</em>.",
            tab = "pxe_tftp",
        ) { depends(tftp.option, "1"); optional = true; placeholder = "/" }
        value("dhcp_boot", "Network boot image", "Filename of the boot image advertised to clients.", tab = "pxe_tftp") {
            depends(tftp.option, "1"); optional = true; placeholder = "pxelinux.0"
        }

        val pxe = nested(SectionKind.Grid, "boot", null, "Special <abbr title=\"Preboot eXecution Environment\">PXE</abbr> boot options for Dnsmasq.") {
            addremove = true; anonymous = true
            modalTitle = { "Edit PXE/TFTP/BOOTP Host" }
            value("filename", "Filename", "Host requests this filename from the boot server.") { optional = false; placeholder = "pxelinux.0" }
            value("servername", "Server name", "The hostname of the boot server") { optional = false; placeholder = "myNAS" }
            value("serveraddress", "Server address", "The IP address of the boot server") { optional = false; placeholder = "192.168.1.2" }
            dynlist(
                "dhcp_option", "DHCP Options",
                "Additional options to send to the below match tags." + "<br />" +
                    "<code>0.0.0.0</code> means \"the address of the system running dnsmasq\".",
            ) { optional = true; placeholder = "option:root-path,192.168.1.2:/data/netboot/root" }
            value("networkid", "Match this Tag", "Only DHCP Clients with this tag are sent this boot option.") { optional = true }
            flag("force", "Force", "Always send the chosen DHCP options. Sometimes needed, with e.g. PXELinux.") { optional = true }
            value("instance", "Instance", "Dnsmasq instance to which this boot section is bound. If unspecified, the section is valid for all dnsmasq instances.") {
                optional = true
                dnsmasqInstanceChoices(uci).forEach { (k, l) -> value(k, l) }
            }
        }
        subsection("__pxe__", pxe, tab = "pxe_tftp")

        val tagcodestring = "<code>tag</code>"
        val exclamationmarkInvert = "<code>!</code>"
        val tagNamedOvString = "<code>option(6):&lt;opt-name&gt;,[&lt;value&gt;[,&lt;value&gt;]]</code>"
        val addtag = "Add tag"
        val dhcpOptionCode = "<code>option(6)</code>"
        val dhcpOptioncolonCode = "<code>option(6):</code>"
        val dhcpOptionClientArch = "<code>option:client-arch,6</code>"
        val dhcpValueCode = "<code>,value</code>"
        val tagMatchCodeName = "<code>match</code>"
        val tagMatchOptionSyntax = "<code>&lt;option number&gt;|option:&lt;option name&gt;[,&lt;value&gt;]</code>"
        val tagNameEfiIa32 = "<code>efi-ia32</code>"
        val wildcardCode = "<code>*</code>"
        val tags = tagChoices()

        val tagstab = nested(SectionKind.Typed, "__tagsparent__") {
            anonymous = true
            cfgsectionsOverride = { listOf("__tagsparent__") }
            tab("matchtags", "Match Tags")
            tab("settags", "Set Tags")
            tab("vc", "VC")
            tab("uc", "UC")

            val matchTags = nested(
                SectionKind.Table, "tag", null,
                "A $tagcodestring is an alphanumeric label." + " " + "They are attached to a DHCP client or transaction." + "<br />" +
                    "dnsmasq conditionally applies chosen DHCP options when a specific $tagcodestring is encountered." + "<br />" +
                    "In other words: \"This $tagcodestring gets these $tagNamedOvString\"." + "<br />" +
                    "${tagcodestring}s do not do anything by themselves. They are labels that other directives test against." + "<br />" +
                    "Note: invalid $tagNamedOvString combinations may cause dnsmasq to crash silently." + "<br /><br />" +
                    "Prepend a $tagcodestring with $exclamationmarkInvert to invert their domain of application, e.g. to send options to a host lacking a $tagcodestring." + "<br /><br />" +
                    "Use the <em>$addtag</em> button to add a new $tagcodestring.",
            ) {
                sortable = true; addremove = true
                modalTitle = { "Edit tag" }
                addbtntitle = addtag
                addNameValidator = { v ->
                    val taken = uci.sections("dhcp", "tag").mapNotNull { it[".name"] as? String }.flatMap { listOf(it, "!$it") } + services + devices
                    if (taken.any { it == v }) "Name already exists." + " " + "Choose a unique name." else null
                }
                dynlist("dhcp_option", "Apply these DHCP Options", "Options to be added for this tag.") {
                    rmempty = true; optional = true; placeholder = "3,192.168.10.1,10.10.10.1"
                }
                flag("force", "Force", "Send options to clients that did not request them.") { rmempty = false; optional = true }
            }
            subsection("__tags__", matchTags, tab = "matchtags")

            val setTags = nested(
                SectionKind.Table, "match", null,
                "Encountering chosen DHCP ${dhcpOptionCode}s (or also its $dhcpValueCode) from clients triggers dnsmasq to set alphanumeric ${tagcodestring}s." + "<br />" +
                    "In other words: \"$tagMatchCodeName these ${dhcpOptionCode}s to set this $tagcodestring\" or \"These ${dhcpOptionCode}s set this $tagcodestring\"." + "<br />" +
                    "Internally, these configuration entries are called $tagMatchCodeName." + "<br />" +
                    "Matching option syntax: $tagMatchOptionSyntax." + " " +
                    "Prefix named (IPv6) options with $dhcpOptioncolonCode." + " " +
                    "Wildcards ($wildcardCode) allowed." + "<br /><br />" +
                    "Match $dhcpOptionClientArch, Tag $tagNameEfiIa32, sets tag $tagNameEfiIa32" + " " +
                    "when number <code>6</code> appears in the list of architectures sent by the client in option <code>93</code>." + "<br />" +
                    "Use the <em>Add</em> Button to add a new $tagMatchCodeName.",
            ) {
                addremove = true; anonymous = true; sortable = true
                modalTitle = { "Edit Match" }
                value("match", "Match this client option(+value)") { rmempty = false; optional = false; placeholder = "61,8c:80:90:01:02:03" }
                value("networkid", "In order to Set this Tag") {
                    rmempty = false; optional = false
                    validate = { _, _, v -> validateTags(v) }
                    tags.forEach { value(it) }
                }
                flag("force", "Force", "Send options to clients that did not request them.") { rmempty = false; optional = true }
            }
            subsection("__settags__", setTags, tab = "settags")

            for ((type, short, long) in listOf(Triple("vendorclass", "VC", "Vendor Class"), Triple("userclass", "UC", "User Class"))) {
                val sub = nested(
                    SectionKind.Table, type, null,
                    "Match $long ($short) strings sent by DHCP clients as a trigger to set tags on them." + "<br /><br />" +
                        "Use the <em>Add</em> Button to add a new $short.",
                ) {
                    addremove = true; anonymous = true; sortable = true
                    modalTitle = { "Edit $short" }
                    value(type, "Match this $long") { rmempty = false; optional = false }
                    value("networkid", "In order to set this Tag") {
                        rmempty = false; optional = false
                        validate = { _, _, v -> validateTags(v) }
                        tags.forEach { value(it) }
                    }
                    flag("force", "Force", "Send options to clients that did not request them.") { rmempty = false; optional = true }
                }
                subsection(if (type == "vendorclass") "__vc__" else "__uc__", sub, tab = if (type == "vendorclass") "vc" else "uc")
            }
        }
        subsection("__tagsparent__", tagstab, tab = "tagsparent")
    }
}

private fun PageContext.addOdhcpdCfg(m: FormMap) {
    m.typed("odhcpd", "odhcpd") {
        hidetitle = true
        anonymous = true

        tab("general", "General", "Note that many options are set on a per-interface basis in the <a href=\"./network\">Interfaces</a> tab.")

        flag(
            "maindhcp", "DHCPv4",
            "Use <code>odhcp</code> for DHCPv4. This will disable DHCPv4 support in <code>dnsmasq</code>." + "<br />" +
                "The DHCPv4 functionality also needs to be enabled on a per-interface basis.",
            tab = "general",
        )
        value("leasefile", "Lease file", "File to store active DHCP leases in.", tab = "general")
        value("leasetrigger", "Lease trigger", "Path to a script to run each time the lease file changes.", tab = "general")
        value("hostsdir", "Hosts file", "Directory to store hosts files (IP address to hostname mapping) in. Used by e.g. <code>dnsmasq</code>.", tab = "general")
        value("piodir", "PIO directory", "Directory to store IPv6 prefix information files in (to detect and announce stale prefixes).", tab = "general")
        value("loglevel", "Log level", "Log level of the <code>odhcpd</code> daemon.", tab = "general") {
            listOf("Emergency", "Alert", "Critical", "Error", "Warning", "Notice", "Info", "Debug").forEachIndexed { i, l -> value(i.toString(), l) }
        }

        tab("pxe6", "PXE over IPv6")
        val pxe6 = nested(
            SectionKind.Table, "boot6", null,
            "<abbr title=\"Preboot eXecution Environment\">PXE</abbr> over IPv6 boot options." + "<br />" +
                "The last entry without an architecture becomes the default.",
        ) {
            addremove = true; anonymous = true; sortable = true
            value("url", "URL") { optional = false; datatype = "string"; placeholder = "tftp://[fd11::1]/pxe.efi" }
            value("arch", "Architecture") {
                optional = true; rmempty = true; datatype = "range(0,65535)"; default = ""
                value("")
                listOf(
                    "0" to "00: x86 BIOS", "6" to "06: x86 UEFI (IA32)", "7" to "07: x64 UEFI", "10" to "10: ARM 32-bit UEFI",
                    "11" to "11: ARM 64-bit UEFI", "15" to "15: x86 UEFI boot from HTTP", "16" to "16: x64 UEFI boot from HTTP",
                    "17" to "17: ebc boot from HTTP", "18" to "18: ARM UEFI 32 boot from HTTP", "19" to "19: ARM UEFI 64 boot from HTTP",
                    "20" to "20: pc/at bios boot from HTTP", "21" to "21: ARM 32 uboot", "22" to "22: ARM 64 uboot",
                    "23" to "23: ARM uboot 32 boot from HTTP", "24" to "24: ARM uboot 64 boot from HTTP", "25" to "25: RISC-V 32-bit UEFI",
                    "26" to "26: RISC-V 32-bit UEFI boot from HTTP", "27" to "27: RISC-V 64-bit UEFI", "28" to "28: RISC-V 64-bit UEFI boot from HTTP",
                    "29" to "29: RISC-V 128-bit UEFI", "30" to "30: RISC-V 128-bit UEFI boot from HTTP", "31" to "31: s390 Basic",
                    "32" to "32: s390 Extended", "33" to "33: MIPS 32-bit UEFI", "34" to "34: MIPS 64-bit UEFI", "35" to "35: Sunway 32-bit UEFI",
                    "36" to "36: Sunway 64-bit UEFI", "37" to "37: LoongArch 32-bit UEFI", "38" to "38: LoongArch 32-bit UEFI boot from HTTP",
                    "39" to "39: LoongArch 64-bit UEFI", "39" to "40: LoongArch 64-bit UEFI boot from HTTP", "41" to "41: ARM rpiboot",
                ).forEach { (k, l) -> value(k, l) }
            }
        }
        subsection("__pxe6__", pxe6, tab = "pxe6")
    }
}

private fun PageContext.addLeasesCfg(m: FormMap, hosts: JsonObject, duids: JsonObject, pools: List<Pool>, macdata: JsonObject?) {
    val hasDhcpv6 = hasSystemFeature("dnsmasq", "dhcpv6") || hasSystemFeature("odhcpd")
    val tags = tagChoices()
    val instances = dnsmasqInstanceChoices(uci)
    val poll = LeasePoll()

    m.virtual("__leases__", "Leases") {
        hidetitle = true
        anonymous = true

        val ss = nested(
            SectionKind.Grid, "host", null,
            "Static leases are used to assign fixed IP addresses and symbolic hostnames to DHCP clients. They are also required for non-dynamic interface configurations where only hosts with a corresponding lease are served." + "<br /><br />" +
                "Use the <em>Add</em> Button to add a new lease entry. The <em>MAC address</em> identifies the host, the <em>IPv4 address</em> specifies the fixed address to use, and the <em>Hostname</em> is assigned as a symbolic name to the requesting host. The optional <em>Lease time</em> can be used to set non-standard host-specific lease time, e.g. 12h, 3d or infinite." + "<br /><br />" +
                "The tag construct filters which host directives are used; more than one tag can be provided, in this case the request must match all of them. Tagged directives are used in preference to untagged ones. Note that one of mac, duid or hostname still needs to be specified (can be a wildcard).",
        ) {
            anonymous = true; addremove = true; sortable = true
            maxCols = 8
            modalTitle = { "Edit static lease" }

            val name = value("name", "Hostname", "The hostname for this host (optional).") {
                validate = { _, _, v -> validateHostname(v) }
                rmempty = true
                writeFn = { rt, sid, v ->
                    rt.uci.set("dhcp", sid, "name", v)
                    rt.uci.set("dhcp", sid, "dns", "1")
                }
                removeFn = { rt, sid ->
                    rt.uci.unset("dhcp", sid, "name")
                    rt.uci.unset("dhcp", sid, "dns")
                }
            }

            lateinit var mac: com.the412banner.wrtpanel.form.DynamicListOption
            mac = dynlist(
                "mac", "MAC Addresses",
                "The hardware address(es) of this host." + "<br /><br />" +
                    "The same IPv4 address will be (re)assigned to <em>any</em> host using one of the MAC addresses listed above." + "<br />" +
                    "Only one of the MAC addresses is expected to be in active use on the network at any given time.",
            ) {
                rmempty = true
                cfgvalueFn = { rt, sid ->
                    val formatted = expandAndFormatMAC(UciStore.toArray(rt.uci.get("dhcp", sid, "mac")))
                    if (macdata != null && formatted != null) {
                        for (i in formatted.indices) {
                            val entry = macdata[formatted[i].lowercase()]
                            if (entry != null) formatted[i] += " (${entry["vendor"].str?.takeIf { it.isNotEmpty() } ?: "null"})"
                        }
                    }
                    formatted
                }
                validate = { rt, sid, v ->
                    val thisMacs = rt.formList(mac, sid).map { it.uppercase() }
                    var err: String? = null
                    for (host in rt.uci.sections("dhcp", "host")) {
                        if (host[".name"] == sid) continue
                        val hostMacs = UciStore.toArray(host["mac"]).map { it.uppercase() }
                        val dup = hostMacs.firstOrNull { it in thisMacs }
                        if (dup != null) { err = "The MAC address $dup is already used by another static lease in the same DHCP pool"; break }
                    }
                    err ?: isValidMAC(UciStore.toArray(v))
                }
                hosts.forEach { (hmac, h) ->
                    val vendor = macdata?.get(hmac.lowercase())?.let { it["vendor"].str }
                    val hint = vendor?.takeIf { it.isNotEmpty() } ?: h["name"].str?.takeIf { it.isNotEmpty() }
                        ?: (h["ipaddrs"] ?: h["ipv4"]).strList.firstOrNull()
                    value(hmac, if (hint != null) "$hmac ($hint)" else hmac)
                }
            }

            value("ip", "IPv4 address", "The IPv4 address for this host, or <em>ignore</em> to ignore DHCP requests from this host.") {
                value("ignore", "Ignore")
                datatype = "or(ip4addr,\"ignore\")"
                validateEmpty = true
                validate = { rt, sid, v ->
                    when {
                        rt.formList(mac, sid).isEmpty() && rt.formString(name, sid).isEmpty() -> "One of hostname or MAC address must be specified!"
                        v.isEmpty() || v == "ignore" -> null
                        rt.uci.sections("dhcp", "host").any { it[".name"] != sid && it["ip"] == v } -> "The IP address $v is already used by another static lease"
                        pools.any { p -> calculateNetwork(v, p.netmask)?.first == p.network } -> null
                        else -> "The IP address is outside of any DHCP pool address range"
                    }
                }
            }

            value("leasetime", "Lease time", "Host-specific lease time, e.g. <code>5m</code>, <code>3h</code>, <code>7d</code>.") {
                rmempty = true
                value("5m", "5m (5 minutes)")
                value("3h", "3h (3 hours)")
                value("12h", "12h (12 hours - default)")
                value("7d", "7d (7 days)")
                value("infinite", "infinite (lease does not expire)")
            }

            dynlist(
                "duid", "DUID/IAIDs",
                "The <abbr title=\"Dynamic Host Configuration Protocol for IPv6\">DHCPv6</abbr>-<abbr title=\"DHCP Unique Identifier\">DUID</abbr>s and, optionally, <abbr title=\"Identity Association Identifier\">IAID</abbr>s of this host." + "<br /><br />" +
                    "The same IPv6 addresses will be (re)assigned to <em>any</em> host using one of the <code>DUID</code> or <code>DUID%IAID</code> values listed above. Only one is expected to be in active use on the network at any given time." + "<br /><br />" +
                    "Syntax: <code>&lt;DUID-hex-str&gt;</code> <em>or</em> <code>&lt;DUID-hex-str&gt;%&lt;IAID-hex-str&gt;</code>",
            ) {
                rmempty = true
                validate = { _, _, v -> validateDUIDIAID(v) }
                duids.forEach { (duidIaid, d) ->
                    val desc = d["hostname"].str?.takeIf { it.isNotEmpty() } ?: d["macaddr"].str?.takeIf { it.isNotEmpty() }
                        ?: d["ip6addrs"].strList.firstOrNull() ?: "?"
                    value(duidIaid, "$duidIaid ($desc)")
                }
            }

            value(
                "hostid", "IPv6 Token",
                "The hexadecimal <abbr title=\"Address suffix\"><a href=\"https://datatracker.ietf.org/doc/html/draft-chown-6man-tokenised-ipv6-identifiers-02\">IPv6 token</a></abbr> for this host (up to 16 chars, i.e. 64 bits).",
            ) { datatype = "and(rangelength(0,16),hexstring)" }

            dynlist("tag", "Set Tag", "Additional tags for this host.") {
                validate = { _, _, v -> validateTags(v) }
                tags.forEach { value(it) }
            }

            dynlist(
                "match_tag", "Match Tag",
                "When a host matches an entry then the special tag <code>known</code> is set. Use <code>known</code> to match all known hosts." + "<br /><br />" +
                    "Ignore requests from unknown machines using <code>!known</code>." + "<br /><br />" +
                    "If a host matches an entry which cannot be used because it specifies an address on a different subnet, the tag <code>known-othernet</code> is set.",
            ) {
                reservedTags.forEach { (k, l) -> value(k, l) }
                optional = true
            }

            value("instance", "Instance", "Dnsmasq instance to which this DHCP host section is bound. If unspecified, the section is valid for all dnsmasq instances.") {
                optional = true
                instances.forEach { (k, l) -> value(k, l) }
            }

            flag("broadcast", "Broadcast", "Force broadcast DHCP response.")
            flag("dns", "Forward/reverse DNS", "Add static forward and reverse DNS entries for this host.")
        }
        subsection("__static_leases__", ss)

        dummy("__status__") { render = { rt, _ -> LeaseStatusTable(rt.ctx, poll, v6 = false, hosts = hosts, macdata = macdata) } }

        if (hasDhcpv6) dummy("__status6__") { render = { rt, _ -> LeaseStatusTable(rt.ctx, poll, v6 = true, hosts = hosts, macdata = macdata) } }
    }
}
