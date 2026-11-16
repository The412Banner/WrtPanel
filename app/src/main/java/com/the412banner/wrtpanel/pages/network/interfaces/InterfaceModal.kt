package com.the412banner.wrtpanel.pages.network.interfaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.form.ChoiceOption
import com.the412banner.wrtpanel.form.FlagOption
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormOption
import com.the412banner.wrtpanel.form.FormRuntime
import com.the412banner.wrtpanel.form.FormSection
import com.the412banner.wrtpanel.form.ListValueOption
import com.the412banner.wrtpanel.form.SectionKind
import com.the412banner.wrtpanel.form.SectionView
import com.the412banner.wrtpanel.form.ValueOption
import com.the412banner.wrtpanel.pages.common.NetTools
import com.the412banner.wrtpanel.pages.network.proto.ProtoForm
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.SectionCard
import kotlinx.coroutines.launch

/** A dialog form (LuCI's modal map): one named section edited as a unit. */
class ModalForm(val map: FormMap, val section: FormSection, val sid: String, val rt: FormRuntime) {
    /** Sections created while the dialog is open that Dismiss removes again. */
    val addedSections = mutableListOf<String>()
}

/** nettools.replaceOption(): drops an earlier option of that name, the new one goes last. */
internal fun FormSection.dropOption(name: String) { options.removeAll { it.option == name } }

private fun hasPeerdns(proto: String) = proto in setOf("dhcp", "dhcpv6", "qmi", "ppp", "pppoe", "pppoa", "pptp", "openvpn", "sstp")

private fun hasSourcefilter(proto: String) = proto in setOf("3g", "dhcpv6", "directip", "mbim", "modemmanager", "ncm", "ppp", "pppoa", "pppoe", "pptp", "qmi")

/** get_netmask() of the view: the netmask of the first configured subnet. */
private fun netmaskOf(rt: FormRuntime, ipOpt: FormOption?, maskOpt: FormOption?, sid: String): String? {
    if (ipOpt == null) return null
    val addrs = UciStore.toArray(rt.formvalue(ipOpt, sid))
    val mask = maskOpt?.let { rt.formString(it, sid) }?.takeIf { it.isNotEmpty() }
    val first = if (mask != null) (addrs.firstOrNull() ?: return null) + "/" + mask else addrs.firstOrNull { it.indexOf('/') > 0 } ?: return null
    val sm = first.split('/').getOrNull(1) ?: return null
    return if (sm.toDoubleOrNull() != null) NetTools.prefixToMask(sm.toDouble().toInt(), false) else sm
}

/**
 * s.addModalOptions() of interfaces.js for interface [sid]: status, protocol, device,
 * firewall zone, DHCP server, the protocol's options and the common interface options.
 */
suspend fun buildInterfaceModal(
    ctx: PageContext,
    page: IfPage,
    sid: String,
    onSwitchProto: suspend () -> Unit,
): ModalForm {
    val model = page.model
    val uci = ctx.uci
    uci.load(listOfNotNull("network", "dhcp", "firewall", "system", "luci", if (model.hasWifi) "wireless" else null))
    val protoval = uci.getString("network", sid, "proto") ?: "none"
    val ifc = model.getNetwork(sid) ?: model.instantiateNetwork(sid)
    val protocols = model.protocols.values.sortedWith { a, b -> naturalCompare(a.name, b.name) }

    val m = FormMap("network")
    m.chain("dhcp")
    val s = m.named(sid, "interface")
    s.tab("general", "General Settings")
    s.tab("advanced", "Advanced Settings")
    s.tab("physical", "Physical Settings")
    s.tab("brport", "Bridge port specific options")
    s.tab("bridgevlan", "Bridge VLAN filtering")
    s.tab("firewall", "Firewall Settings")
    s.tab("dhcp", "DHCP Server")
    s.modalTitle = { "Interfaces » $it" }

    lateinit var protoSelect: ListValueOption

    s.flag("delegate", "Use builtin IPv6-management", tab = "advanced") { modalonly = true; default = "1" }
    s.flag("force_link", "Force link", "Set interface properties regardless of the link carrier (If set, carrier sense events do not invoke hotplug handlers).", tab = "advanced") {
        modalonly = true
        defaultFn = { rt, id -> if (rt.formString(protoSelect, id) == "static") "1" else "0" }
    }

    s.dummy("_ifacestat_modal", "Status", tab = "general") {
        modalonly = true
        writeFn = { _, _, _ -> }
        render = { _, id -> ModalStatus(page, id) }
    }

    protoSelect = s.list("proto", "Protocol", tab = "general") {
        modalonly = true
        default = "none"
    }

    val protoSwitch = s.button("_switch_proto", tab = "general") {
        modalonly = true
        title = "Really switch protocol?"
        inputtitle = { _, _ -> "Switch protocol" }
        inputstyle = "apply"
        onClick = { _, _ -> onSwitchProto() }
    }

    s.devSelect("_net_device", "Device", tab = "general", model = model, exclude = "@$sid") {
        ucioption = "device"
        optional = false
    }

    s.flag("disabled", "Disable this interface", tab = "general") { modalonly = true }
    s.flag("auto", "Bring up on boot", tab = "general") { modalonly = true; default = "1" }

    if (model.hasFeature("firewall")) {
        s.ifZoneSelect(
            "_zone", "Create / Assign firewall-zone",
            "Choose the firewall zone you want to assign to this interface. Select <em>unspecified</em> to remove the interface from the associated zone or fill out the <em>custom</em> field to define a new zone and attach the interface to it.",
            "firewall", model,
        ) {
            optional = true
            cfgvalueFn = { rt, _ -> Firewall.zoneByNetwork(rt.uci, sid)?.second }
            fun assign(rt: FormRuntime, value: String?) {
                val old = Firewall.zoneByNetwork(rt.uci, sid)
                val new = if (value != null) Firewall.lookupZone(rt.uci, value) else null
                if (old == null && new == null && value.isNullOrEmpty()) return
                if (old != null && new != null && old.second == rt.uci.getString("firewall", new, "name")) return
                if (old != null) Firewall.deleteNetworkFromZone(rt.uci, old.first, sid)
                if (new != null) Firewall.addNetwork(rt.uci, new, sid)
                else if (value != null) Firewall.addZone(rt.uci, value)?.let { Firewall.addNetwork(rt.uci, it, sid) }
            }
            writeFn = { rt, _, v -> assign(rt, v.toString()) }
            removeFn = { rt, _ -> assign(rt, null) }
        }
    }

    for (p in protocols) {
        protoSelect.value(p.name, p.i18n)
        if (p.name != protoval) protoSwitch.depends("proto", p.name)
    }

    if (model.hasFeature("dnsmasq") || model.hasFeature("odhcpd")) addDhcpServer(ctx, page, s, ifc, protoval)

    ifc.handler?.renderFormOptions(s, ProtoForm(ctx, model, ifc))

    s.dropOption("defaultroute")
    s.flag("defaultroute", "Use default gateway", "If unchecked, no default route is configured", tab = "advanced") { default = "1" }
    if (hasPeerdns(protoval)) {
        s.dropOption("peerdns")
        s.flag("peerdns", "Use DNS servers advertised by peer", "If unchecked, the advertised DNS server addresses are ignored", tab = "advanced") { default = "1" }
    }
    s.dropOption("dns")
    s.dynlist("dns", "Use custom DNS servers", tab = "advanced") {
        if (hasPeerdns(protoval)) depends("peerdns", "0")
        datatype = "ipaddr"
    }
    s.dropOption("dns_metric")
    s.value("dns_metric", "DNS weight", "The DNS server entries in the local resolv.conf are primarily sorted by the weight specified here", tab = "advanced") {
        datatype = "uinteger"; placeholder = "0"
    }
    s.dropOption("metric")
    s.value("metric", "Use gateway metric", "Metric is an ordinal, where a gateway with 1 is chosen 1st, 2 is chosen 2nd, 3 is chosen 3rd, etc", tab = "advanced") {
        datatype = "uinteger"; placeholder = "0"
    }
    if (model.hasFeature("mptcp")) {
        s.dropOption("multipath")
        s.list(
            "multipath", "Multi-Path TCP",
            "Multi-Path TCP <a href=\"https://www.rfc-editor.org/rfc/rfc8684.html\" target=\"_blank\">RFC8684</a><br/>For packets originating from this device, e.g. VPN.",
            tab = "advanced",
        ) {
            value("off", "Off", "Disables this interface for MPTCP")
            value("on", "On", "No special configuration")
            value("master", "Master", "Sets default route for all traffic")
            value("backup", "Backup", "Hot standby; use this interface; do not forward traffic until no other interface is available (faster)")
            value("handover", "Handover", "Cold standby; Establish a connection only when no other interface is available (slower)")
        }
    }
    for (t in listOf("ip4table" to "Override IPv4 routing table", "ip6table" to "Override IPv6 routing table")) {
        s.dropOption(t.first)
        s.value(t.first, t.second, tab = "advanced") {
            datatype = "or(uinteger, string)"
            page.rtTables.forEach { (id, n) -> value(n, "$n ($id)") }
        }
    }
    if (hasSourcefilter(protoval)) {
        s.dropOption("sourcefilter")
        s.flag("sourcefilter", "IPv6 source routing", "Automatically handle multiple uplink interfaces using source-based policy routing.", tab = "advanced") { default = "1" }
    }
    s.dropOption("delegate")
    s.flag("delegate", "Delegate IPv6 prefixes", "Enable downstream delegation of IPv6 prefixes available on this interface", tab = "advanced") { default = "1" }
    s.dropOption("ip6assign")
    s.value("ip6assign", "IPv6 assignment length", "Assign a part of given length of every public IPv6-prefix to this interface", tab = "advanced") {
        value("", "disabled"); value("64")
        datatype = "max(128)"
    }
    s.dropOption("ip6hint")
    s.value("ip6hint", "IPv6 assignment hint", "Assign prefix parts using this hexadecimal subprefix ID for this interface.", tab = "advanced") {
        placeholder = "0"
        validate = { _, _, v ->
            if (v.isEmpty()) null
            else {
                val n = Regex("^(0x)?[0-9a-fA-F]+").find(v)?.value?.removePrefix("0x")?.toLongOrNull(16)
                if (!Regex("^(0x)?[0-9a-fA-F]+$").matches(v) || n == null || n >= 0xffffffffL) "Expecting a hexadecimal assignment hint" else null
            }
        }
        for (i in 33..64) depends("ip6assign", i.toString())
    }
    s.dropOption("ip6class")
    s.dynlist("ip6class", "IPv6 prefix filter", "If set, downstream subnets are only allocated from the given IPv6 prefix classes.", tab = "advanced") {
        value("local", "local (Local ULA)")
        val classes = sortedMapOf<String, MutableSet<String>>()
        page.networks().forEach { net ->
            net.ubus("ipv6-prefix").arr?.forEach { pfx ->
                val c = pfx["class"].str ?: return@forEach
                classes.getOrPut(c) { sortedSetOf() } += net.sid
            }
        }
        classes.forEach { (c, nets) -> val n = nets.joinToString(", "); value(c, if (c != n) "$c ($n)" else c) }
    }
    s.dropOption("ip6ifaceid")
    s.value(
        "ip6ifaceid", "IPv6 suffix",
        "Optional. Allowed values: 'eui64', 'random', fixed value like '::1' or '::1:2'. When IPv6 prefix (like 'a:b:c:d::') is received from a delegating server, use the suffix (like '::1') to form the IPv6 address ('a:b:c:d::1') for the interface.",
        tab = "advanced",
    ) { datatype = "ip6hostid"; placeholder = "::1" }
    s.dropOption("ip6weight")
    s.value("ip6weight", "IPv6 preference", "When delegating prefixes to multiple downstreams, interfaces with a higher preference value are considered first when allocating subnets.", tab = "advanced") {
        datatype = "uinteger"; placeholder = "0"
    }

    for (o in s.options) {
        when (o.option) {
            "proto", "auto", "_dhcp", "_zone", "_switch_proto", "_ifacestat_modal" -> continue
            "igmp_snooping", "stp", "type", "_net_device" -> {
                val old = o.deps.toList()
                val deps = ArrayList<com.the412banner.wrtpanel.form.Dep>()
                for (p in protocols) {
                    if (model.instantiateNetwork("__dummy__", p.name).isVirtual()) continue
                    if (old.isNotEmpty()) old.forEach { od -> deps += od.copy(conds = mapOf("proto" to p.name) + od.conds) }
                    else deps += com.the412banner.wrtpanel.form.Dep(mapOf("proto" to p.name))
                }
                o.deps.clear(); o.deps += deps
            }
            else -> {
                if (o.deps.isNotEmpty()) {
                    val old = o.deps.toList()
                    o.deps.clear()
                    old.forEach { od -> o.deps += od.copy(conds = od.conds + ("proto" to protoval)) }
                } else o.depends("proto", protoval)
            }
        }
    }

    val rt = FormRuntime(m, ctx)
    if (ctx.readonly) m.readonly = true
    for (o in m.sections.flatMap { it.allOptions() }) o.loadFn?.invoke(ctx, rt)
    return ModalForm(m, s, sid, rt)
}

/** The "DHCP Server" tab: the dhcp pool of this interface (form.SectionValue over dhcp sections). */
private fun addDhcpServer(ctx: PageContext, page: IfPage, s: FormSection, ifc: NetIface, protoval: String) {
    val model = page.model
    val ifname = ifc.sid
    val ss = s.nested(SectionKind.Typed, "dhcp") {
        uciconfig = "dhcp"
        addremove = false
        anonymous = true
        tab("general", "General Setup")
        tab("ipv4", "IPv4 Settings")
        tab("ipv6", "IPv6 Settings")
        tab("dhcpv6", "DHCPv6 Settings")
        tab("ipv6-ra", "IPv6 RA Settings")
        filter = { _, sec -> sec["interface"] == ifname }
    }
    s.subsection("_dhcp", ss, tab = "dhcp") {
        render = { rt, id -> DhcpTab(ctx, rt, s, ss, id, protoval) }
    }

    if (model.hasFeature("dnsmasq")) {
        ss.flag("ignore", "Ignore interface", "Disable <abbr title=\"Dynamic Host Configuration Protocol\">DHCP</abbr> for this interface (dnsmasq only).", tab = "general")
    }

    if (protoval == "static") {
        ss.flag("dynamicdhcp", "Dynamic <abbr title=\"Dynamic Host Configuration Protocol\">DHCP</abbr>", "Dynamically allocate DHCP addresses for clients. If disabled, only clients having static leases will be served.", tab = "general") {
            default = "1"
        }
        ss.value("leasetime", "Lease time", "Expiry time of leased addresses, minimum is 2 minutes (<code>2m</code>).", tab = "general") {
            optional = true
            default = "12h"
            validate = { _, _, v ->
                if (v == "infinite" || v == "deprecated" || Regex("^[0-9]+[smhdw]?$", RegexOption.IGNORE_CASE).matches(v)) null
                else "Invalid DHCP lease time format. Use integer values optionally followed by s, m, h, d, or w."
            }
        }
        if (model.hasFeature("dnsmasq")) {
            ss.flag("force", "Force", "Force DHCP on this network even if another server is detected (dnsmasq only).", tab = "general")
            ss.dynlist("dhcp_option", "DHCP-Options", "Define additional DHCP options,  for example \"<code>6,192.168.2.1,192.168.2.2</code>\" which advertises different DNS servers to clients (dnsmasq only).", tab = "general")
            ss.dynlist("dhcp_option_force", "Force DHCP-Options", "As DHCP-Options; send unsolicited (dnsmasq only).", tab = "general")
        }
        if (model.hasFeature("odhcpd", "dhcpv4") || model.hasFeature("dnsmasq")) {
            ss.list("dhcpv4", "DHCPv4 Service", "Enable or disable DHCPv4 services on this interface.", tab = "ipv4") {
                optional = true
                value("disabled", "disabled", "Do not provide DHCPv4 services on this interface.")
                value("server", "enabled", "Provide DHCPv4 services on this interface.")
            }
        }
        if (model.hasFeature("odhcpd", "dhcpv4")) {
            ss.value(
                "ipv6_only_preferred", "IPv6-Only Preferred",
                "Specifies how often (in seconds) clients should check whether IPv6-only mode is still preferred (see <a href=\"https://www.rfc-editor.org/rfc/rfc8925\" target=\"_blank\">RFC8925</a>, odhcpd only).",
                tab = "ipv4",
            ) { optional = true; datatype = "or(0, range(300,86400))"; default = "0" }
        }
        ss.value("start", "Start", "Lowest leased address as offset from the network address.", tab = "ipv4") {
            optional = true; datatype = "or(uinteger,ip4addr(\"nomask\"))"; default = "100"
        }
        ss.value("limit", "Limit", "Maximum number of leased addresses.", tab = "ipv4") {
            optional = true; datatype = "uinteger"; default = "150"
        }
        if (model.hasFeature("dnsmasq")) {
            ss.value(
                "netmask", "<abbr title=\"Internet Protocol Version 4\">IPv4</abbr>-Netmask",
                "Override the netmask sent to clients. Normally it is calculated from the subnet that is served (dnsmasq only).",
                tab = "ipv4",
            ) {
                optional = true; datatype = "ip4addr"
                placeholderFn = { rt, _ ->
                    netmaskOf(rt, s.options.firstOrNull { it.option == "ipaddr" }, s.options.firstOrNull { it.option == "netmask" }, ifname)
                }
            }
        }
    }

    val otherMaster = ctx.uci.sections("dhcp", "dhcp").firstOrNull { it["interface"] != ifname && it["master"] == "1" }
    val raServerAllowed = protoval !in setOf("dhcp", "dhcpv6", "3g", "l2tp", "ppp", "pppoa", "pppoe", "pptp", "pppossh", "ipip", "gre", "grev6")
    val masterTouched = mutableStateOf(false)
    lateinit var raOpt: ListValueOption
    lateinit var dhcpv6Opt: ListValueOption
    lateinit var ndpOpt: ListValueOption
    lateinit var masterOpt: FlagOption

    val hybridDownstream = "Operate in <em>relay mode</em> if a designated master interface is configured and active, otherwise fall back to <em>server mode</em>."
    val ndpDownstream = "Operate in <em>relay mode</em> if a designated master interface is configured and active, otherwise disable <abbr title=\"Neighbour Discovery Protocol\">NDP</abbr> proxying."
    val hybridMaster = "Operate in <em>relay mode</em> if an upstream IPv6 prefix is present, otherwise disable service."

    masterOpt = ss.flag("master", "Designated master", tab = "ipv6") {
        readonly = otherMaster != null
        description = if (otherMaster != null)
            "Interface \"%s\" is already marked as designated master.".format((otherMaster["interface"] as? String) ?: (otherMaster[".name"] as String))
        else "Set this interface as master for RA and DHCPv6 relaying as well as NDP proxying."
        onChange = { rt, dsid, v ->
            masterTouched.value = true
            val checked = v?.toString() == "1"
            if (checked || !raServerAllowed) {
                if (rt.formString(dhcpv6Opt, dsid) == "server") rt.setValue(dhcpv6Opt, dsid, "hybrid")
                if (rt.formString(raOpt, dsid) == "server") rt.setValue(raOpt, dsid, "hybrid")
            }
            val hyb = if (checked) hybridMaster else hybridDownstream
            dhcpv6Opt.choiceDescriptions["hybrid"] = hyb
            raOpt.choiceDescriptions["hybrid"] = hyb
            ndpOpt.choiceDescriptions["hybrid"] = if (checked) hybridMaster else ndpDownstream
        }
    }
    val serverFilter: (FormRuntime, String, ChoiceOption) -> List<Pair<String, String>> = { rt, dsid, o ->
        if (masterTouched.value && (rt.formString(masterOpt, dsid) == "1" || !raServerAllowed)) o.choices.filter { it.first != "server" } else o.choices
    }

    raOpt = ss.list("ra", "<abbr title=\"Router Advertisement\">RA</abbr>-Service", "Configures the operation mode of the <abbr title=\"Router Advertisement\">RA</abbr> service on this interface.", tab = "ipv6") {
        optional = true
        value("", "disabled", "Do not send any <abbr title=\"Router Advertisement, ICMPv6 Type 134\">RA</abbr> messages on this interface.")
        value("server", "server mode", "Send <abbr title=\"Router Advertisement, ICMPv6 Type 134\">RA</abbr> messages advertising this device as IPv6 router.")
        value("relay", "relay mode", "Forward <abbr title=\"Router Advertisement, ICMPv6 Type 134\">RA</abbr> messages received on the designated master interface to downstream interfaces.")
        value("hybrid", "hybrid mode", " ")
    }
    raOpt.choicesFn = { rt, dsid -> serverFilter(rt, dsid, raOpt) }

    fun ChoiceOption.raDeps() { depends("ra", "server"); depends("ra" to "hybrid", "master" to "0") }

    ss.list("ra_default", "Default router", "Configures the default router advertisement in <abbr title=\"Router Advertisement\">RA</abbr> messages.", tab = "ipv6-ra") {
        optional = true
        value("", "automatic", "Announce this device as default router if a local IPv6 default route is present.")
        value("1", "on available prefix", "Announce this device as default router if a public IPv6 prefix is available, regardless of local default route availability.")
        value("2", "forced", "Announce this device as default router regardless of whether a prefix or default route is present.")
        raDeps()
    }
    ss.flag("ra_slaac", "Enable <abbr title=\"Stateless Address Auto Config\">SLAAC</abbr>", "Set the autonomous address-configuration flag in the prefix information options of sent <abbr title=\"Router Advertisement\">RA</abbr> messages. When enabled, clients will perform stateless IPv6 address autoconfiguration.", tab = "ipv6-ra") {
        default = "1"
        depends("ra", "server"); depends("ra" to "hybrid", "master" to "0")
    }
    ss.list("ra_preference", "Router Priority", "A tie-breaker for clients and their routes when multiple routers exist on the same network.", tab = "ipv6-ra") {
        default = "medium"
        value("low", "Low"); value("medium", "Medium"); value("high", "High")
        depends("ra", "server")
    }
    lateinit var raFlags: com.the412banner.wrtpanel.form.MultiValueOption
    raFlags = ss.multi("ra_flags", "<abbr title=\"Router Advertisement\">RA</abbr> Flags", "Specifies the flags sent in <abbr title=\"Router Advertisement\">RA</abbr> messages, for example to instruct clients to request further information via stateful DHCPv6.", tab = "ipv6-ra") {
        optional = true
        value("managed-config", "managed config (M)", "The <em>Managed address configuration</em> (M) flag indicates that IPv6 addresses are available via DHCPv6.")
        value("other-config", "other config (O)", "The <em>Other configuration</em> (O) flag indicates that other information, such as DNS servers, is available via DHCPv6.")
        value("home-agent", "mobile home agent (H)", "The <em>Mobile IPv6 Home Agent</em> (H) flag indicates that the device is also acting as Mobile IPv6 home agent on this link.")
        placeholder = "none"
        raDeps()
        cfgvalueFn = { rt, dsid -> UciStore.toArray(rt.uci.get("dhcp", dsid, "ra_flags")).ifEmpty { listOf("other-config") } }
        removeFn = { rt, dsid ->
            val existing = UciStore.toArray(rt.uci.get("dhcp", dsid, "ra_flags"))
            if (rt.isActive(raFlags, dsid)) {
                if (existing.size != 1 || existing[0] != "none") rt.uci.set("dhcp", dsid, "ra_flags", listOf("none"))
            } else if (existing.isNotEmpty()) rt.uci.unset("dhcp", dsid, "ra_flags")
        }
    }
    ss.multi("_ra_pio_flags", "RA <abbr title=\"Prefix Information Option\">PIO</abbr> Flags", "Allow <abbr title=\"Prefix Information Option\">PIO</abbr> flags sent in RA messages, globally for all prefixes.", tab = "ipv6-ra") {
        optional = true
        value("pd", "prefix delegation (PD) preferred (P)", "The PD <em>preferred</em> (P) flag indicates that the network prefers clients use PD instead of individual addresses via DHCPv6 or SLAAC. Requires DHCPv6 and DHCPv6-PD.")
        default = ""
        placeholder = "none"
        depends("ra", "server"); depends("ra" to "hybrid", "master" to "0")
        cfgvalueFn = { rt, dsid -> if (rt.uci.get("dhcp", dsid, "dhcpv6_pd_preferred") != null) listOf("pd") else emptyList<String>() }
        removeFn = { rt, dsid -> rt.uci.unset("dhcp", dsid, "dhcpv6_pd_preferred") }
        writeFn = { rt, dsid, v -> if (UciStore.toArray(v).contains("pd")) rt.uci.set("dhcp", dsid, "dhcpv6_pd_preferred", "1") }
    }
    ss.value(
        "ra_pref64", "NAT64 prefix",
        "Announce NAT64 prefix in <abbr title=\"Router Advertisement\">RA</abbr> messages. See <a href=\"https://www.rfc-editor.org/rfc/rfc6146\" target=\"_blank\">RFC6146</a> and <a href=\"https://www.rfc-editor.org/rfc/rfc8781\" target=\"_blank\">RFC8781</a>.",
        tab = "ipv6-ra",
    ) { optional = true; datatype = "cidr6"; placeholder = "64:ff9b::/96"; raDeps() }
    ss.value("ra_maxinterval", "Max <abbr title=\"Router Advertisement\">RA</abbr> interval", "Maximum time allowed  between sending unsolicited <abbr title=\"Router Advertisement, ICMPv6 Type 134\">RA</abbr>. Default is 600 seconds.", tab = "ipv6-ra") {
        optional = true; datatype = "uinteger"; placeholder = "600"; raDeps()
    }
    ss.value("ra_mininterval", "Min <abbr title=\"Router Advertisement\">RA</abbr> interval", "Minimum time allowed  between sending unsolicited <abbr title=\"Router Advertisement, ICMPv6 Type 134\">RA</abbr>. Default is 200 seconds.", tab = "ipv6-ra") {
        optional = true; datatype = "uinteger"; placeholder = "200"; raDeps()
    }
    ss.value("ra_reachabletime", "<abbr title=\"Router Advertisement\">RA</abbr> Reachability Timer", "Units: milliseconds. 0 means unspecified. Dictates how long a node assumes a neighbor is reachable after a reachability confirmation; published in <abbr title=\"Router Advertisement\">RA</abbr> messages.", tab = "ipv6-ra") {
        optional = true; datatype = "range(0, 3600000)"; placeholder = "0"; raDeps()
    }
    ss.value("ra_retranstime", "<abbr title=\"Router Advertisement\">RA</abbr> Retransmission Timer", "Units: milliseconds. 0 means unspecified. Controls retransmitted Neighbor Solicitation messages; published in <abbr title=\"Router Advertisement\">RA</abbr> messages.", tab = "ipv6-ra") {
        optional = true; placeholder = "0"; datatype = "range(0, 60000)"; raDeps()
    }
    ss.value("ra_lifetime", "<abbr title=\"Router Advertisement\">RA</abbr> Lifetime", "Router Lifetime published  in <abbr title=\"Router Advertisement, ICMPv6 Type 134\">RA</abbr> messages.  Maximum is 9000 seconds.", tab = "ipv6-ra") {
        optional = true; datatype = "range(0, 9000)"; placeholder = "2700"; raDeps()
    }
    ss.value("ra_mtu", "<abbr title=\"Router Advertisement\">RA</abbr> MTU", "The <abbr title=\"Maximum Transmission Unit\">MTU</abbr>  to be published in <abbr title=\"Router Advertisement, ICMPv6 Type 134\">RA</abbr> messages. Minimum is 1280 bytes.", tab = "ipv6-ra") {
        optional = true; datatype = "range(1280, 65535)"; raDeps()
        loadFn = { _ ->
            val dev = ifc.getL3Device()
            placeholder = if (dev == null) "0" else (fs.readOrNull("/proc/sys/net/ipv6/conf/${dev.getName()}/mtu")?.trim()?.toLongOrNull() ?: dev.getMTU())?.toString()
        }
    }
    ss.value("ra_hoplimit", "<abbr title=\"Router Advertisement\">RA</abbr> Hop Limit", "The maximum hops  to be published in <abbr title=\"Router Advertisement\">RA</abbr> messages. Maximum is 255 hops.", tab = "ipv6-ra") {
        optional = true; datatype = "range(0, 255)"; raDeps()
        loadFn = { _ ->
            val dev = ifc.getL3Device()
            placeholder = if (dev == null) "0" else (fs.readOrNull("/proc/sys/net/ipv6/conf/${dev.getName()}/hop_limit")?.trim()?.toLongOrNull() ?: 64L).toString()
        }
    }
    ss.value("max_preferred_lifetime", "IPv6 Preferred Prefix Lifetime", "Maximum preferred lifetime for a prefix.", tab = "ipv6-ra") {
        optional = true; placeholder = "45m"
        value("5m", "5m (5 minutes)"); value("45m", "45m (45 minutes - default)"); value("3h", "3h (3 hours)"); value("12h", "12h (12 hours)"); value("7d", "7d (7 days)")
        raDeps()
    }
    ss.value("max_valid_lifetime", "IPv6 Valid Prefix Lifetime", "Maximum valid lifetime for a prefix.", tab = "ipv6-ra") {
        optional = true; placeholder = "90m"
        value("5m", "5m (5 minutes)"); value("90m", "90m (90 minutes - default)"); value("3h", "3h (3 hours)"); value("12h", "12h (12 hours)"); value("7d", "7d (7 days)")
        raDeps()
    }

    dhcpv6Opt = ss.list("dhcpv6", "DHCPv6-Service", "Configures the operation mode of the DHCPv6 service on this interface.", tab = "ipv6") {
        optional = true
        value("", "disabled", "Do not offer DHCPv6 service on this interface.")
        value("server", "server mode", "Provide a DHCPv6 server on this interface and reply to DHCPv6 solicitations and requests.")
        value("relay", "relay mode", "Forward DHCPv6 messages between the designated master interface and downstream interfaces.")
        value("hybrid", "hybrid mode", " ")
    }
    dhcpv6Opt.choicesFn = { rt, dsid -> serverFilter(rt, dsid, dhcpv6Opt) }

    ss.flag("dhcpv6_pd", "DHCPv6-<abbr title=\"Prefix Delegation\">PD</abbr>", "Toggle IPv6 PD via DHCPv6.", tab = "dhcpv6") { depends("dhcpv6", "server") }
    ss.value(
        "dhcpv6_pd_min_len", "<abbr title=\"Prefix Delegation\">PD</abbr> minimum length",
        "Configures the minimum delegated prefix length assigned to a requesting downstream router, potentially overriding a requested prefix length. If left unspecified, the device will assign the smallest available prefix greater than or equal to the requested prefix.",
        tab = "dhcpv6",
    ) { placeholder = "62"; datatype = "range(1,64)"; depends("dhcpv6" to "server", "dhcpv6_pd" to "1") }

    fun ChoiceOption.dnsDeps() {
        depends("ra", "server"); depends("ra" to "hybrid", "master" to "0")
        depends("dhcpv6", "server"); depends("dhcpv6" to "hybrid", "master" to "0")
    }
    ss.dynlist(
        "dns", "Announce IPv4/6 DNS servers",
        "Specifies a fixed list of DNS server addresses to announce via DHCPv6.<br/>Specifies a fixed list of DNS server addresses to announce via RA.<br/>If left unspecified, the device will announce itself as DNS server unless the <em>Local IPv6 DNS server</em> option is disabled.",
        tab = "ipv6",
    ) { datatype = "ipaddr(\"nomask\")"; dnsDeps() }
    ss.flag("dns_service", "Local IPv6 DNS server", "Announce this device as IPv6 DNS server.", tab = "ipv6") {
        default = "1"
        depends("ra" to "server", "dns" to null)
        depends("ra" to "hybrid", "dns" to null, "master" to "0")
        depends("dhcpv6" to "server", "dns" to null)
        depends("dhcpv6" to "hybrid", "dns" to null, "master" to "0")
    }
    ss.dynlist(
        "dnr", "Announce encrypted DNS servers",
        "Specifies a fixed list of encrypted DNS server addresses to announce via DHCPv6/<abbr title=\"Router Advertisement\">RA</abbr> (see <a href=\"https://www.rfc-editor.org/rfc/rfc9463\" target=\"_blank\">RFC9463</a>).<br/>" +
            "IPv4 addresses are only supported if <code>odhcpd</code> also handles DHCPv4.<br/>" +
            "Syntax: <code>&lt;numeric priority&gt; &lt;domain-name&gt; [IP,...] [SVC parameter ...]</code><br/>" +
            "Example: <code>100 dns.example.com 2001:db8::53,192.168.1.53 alpn=doq port=853</code><br/>" +
            "Note: the <code>_lifetime=&lt;seconds&gt;</code> SVC parameter sets the lifetime of the announced server (use <code>0</code> to indicate a server which should no longer be used).",
        tab = "ipv6",
    ) { datatype = "string"; dnsDeps() }
    ss.dynlist("domain", "Announce DNS domains", "Specifies a fixed list of DNS search domains to announce via DHCPv6.", tab = "ipv6") { datatype = "hostname"; dnsDeps() }

    ss.dynlist("ntp", "NTP Servers", "DHCPv6 option 56. <a href=\"https://www.rfc-editor.org/rfc/rfc5908#section-4\" target=\"_blank\">RFC5908</a>.", tab = "dhcpv6") {
        datatype = "host(0)"
        UciStore.toArray(ctx.uci.get("system", "ntp", "server")).forEach { value(it) }
        if (ctx.uci.get("system", "ntp", "enable_server") != null) {
            page.networks().forEach { n ->
                n.getIPAddrs().forEach { value(it.split('/')[0]) }
                n.getIP6Addrs().forEach { value(it.split('/')[0]) }
            }
        }
        optional = true; rmempty = true
        depends("dhcpv6", "server"); depends("dhcpv6" to "hybrid", "master" to "0")
    }

    ndpOpt = ss.list("ndp", "<abbr title=\"Neighbour Discovery Protocol\">NDP</abbr>-Proxy", "Configures the operation mode of the NDP proxy service on this interface.", tab = "ipv6") {
        optional = true
        value("", "disabled", "Do not proxy any <abbr title=\"Neighbour Discovery Protocol\">NDP</abbr> packets.")
        value("relay", "relay mode", "Forward <abbr title=\"Neighbour Discovery Protocol\">NDP</abbr> <abbr title=\"Neighbour Solicitation, Type 135\">NS</abbr> and <abbr title=\"Neighbour Advertisement, Type 136\">NA</abbr> messages between the designated master interface and downstream interfaces.")
        value("hybrid", "hybrid mode", " ")
    }
    ss.flag("ndproxy_routing", "Learn routes", "Set up routes for proxied IPv6 neighbours.", tab = "ipv6") {
        default = "1"; depends("ndp", "relay"); depends("ndp", "hybrid")
    }
    ss.flag("ndproxy_slave", "NDP-Proxy slave", "Set interface as NDP-Proxy external slave. Default is off.", tab = "ipv6") {
        depends("ndp" to "relay", "master" to "0"); depends("ndp" to "hybrid", "master" to "0")
    }
}

/** Placeholder with "Set up DHCP Server", or the DHCP pool form. */
@androidx.compose.runtime.Composable
private fun DhcpTab(ctx: PageContext, rt: FormRuntime, s: FormSection, ss: FormSection, sid: String, protoval: String) {
    rt.revision.intValue
    val sids = rt.sectionIdsFor(ss, sid)
    val scope = rememberCoroutineScope()
    if (sids.isEmpty()) {
        SectionCard {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("No DHCP Server configured for this interface", style = MaterialTheme.typography.bodyMedium)
                if (!(rt.map.readonly || ctx.readonly)) FilledTonalButton(onClick = {
                    scope.launch {
                        try {
                            rt.parseSection(s, listOf(sid), modal = true)?.let { ctx.toast(it); return@launch }
                            rt.uci.add("dhcp", "dhcp", sid)
                            rt.uci.set("dhcp", sid, "interface", sid)
                            if (protoval == "static") {
                                rt.uci.set("dhcp", sid, "start", "100")
                                rt.uci.set("dhcp", sid, "limit", "150")
                                rt.uci.set("dhcp", sid, "leasetime", "12h")
                                rt.uci.set("dhcp", sid, "dhcpv4", "server")
                            } else rt.uci.set("dhcp", sid, "ignore", "1")
                            rt.uci.save()
                            rt.clearEdits()
                            ctx.session.changes.afterSave()
                        } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) }
                    }
                }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Set up DHCP Server") }
            }
        }
        Spacer(Modifier.height(4.dp))
    } else {
        SectionView(rt, ss, sid)
    }
}

/** The "Add new interface..." form (a map over the not yet existing section "_new_"). */
class AddIfaceForm(val rt: FormRuntime, val section: FormSection, val name: ValueOption, val proto: ListValueOption, val device: ChoiceOption)

fun buildAddInterface(ctx: PageContext, model: NetModel): AddIfaceForm {
    val protocols = model.protocols.values.sortedWith { a, b -> naturalCompare(a.name, b.name) }
    val m = FormMap("network")
    lateinit var name: ValueOption
    lateinit var proto: ListValueOption
    lateinit var device: ChoiceOption
    val s = m.virtual("_new_") {
        name = value("name", "Name") {
            rmempty = false
            datatype = "uciname"
            placeholder = "New interface name…"
            validate = { rt, _, v ->
                when {
                    rt.uci.section("network", v) != null -> "The interface name is already used"
                    v.length > 15 -> "The interface name is too long"
                    else -> null
                }
            }
        }
        proto = list("proto", "Protocol") {
            protocols.firstOrNull()?.let { default = it.name }
        }
        device = devSelect("device", "Device", model = model) { optional = false }
    }
    for (p in protocols) {
        proto.value(p.name, p.i18n)
        if (!model.instantiateNetwork("__dummy__", p.name).isVirtual()) device.depends("proto", p.name)
    }
    return AddIfaceForm(FormRuntime(m, ctx), s, name, proto, device)
}
