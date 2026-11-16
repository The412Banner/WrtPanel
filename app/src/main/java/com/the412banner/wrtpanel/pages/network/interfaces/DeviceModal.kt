package com.the412banner.wrtpanel.pages.network.interfaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.UbusCall
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.form.ChoiceOption
import com.the412banner.wrtpanel.form.FlagOption
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormOption
import com.the412banner.wrtpanel.form.FormRuntime
import com.the412banner.wrtpanel.form.FormSection
import com.the412banner.wrtpanel.form.ListValueOption
import com.the412banner.wrtpanel.form.OptionField
import com.the412banner.wrtpanel.form.SectionKind
import com.the412banner.wrtpanel.form.ValueOption
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.EmptyState

private val devRe = Regex("^dev:(.+)$")

/** getDevice() of the view: the network device a device section (or "dev:<name>" row) is about. */
fun devOfSection(page: IfPage, sid: String): NetDevice? {
    val name = devRe.find(sid)?.groupValues?.get(1) ?: page.ctx.uci.getString("network", sid, "name")
    return page.model.devices.firstOrNull { it.getName() == name }
}

/** getDevType() */
fun devTypeOf(page: IfPage, sid: String): String? {
    val dev = devOfSection(page, sid)
    val cfg = page.ctx.uci.section("network", sid)
    val type = if (cfg != null) (page.ctx.uci.getString("network", sid, "type")?.takeIf { it.isNotEmpty() } ?: "ethernet") else (dev?.getType() ?: "")
    return when (type) {
        "" -> null
        "vlan", "8021q" -> "8021q"
        "8021ad" -> "8021ad"
        "bonding" -> "bonding"
        "bridge" -> "bridge"
        "tunnel" -> "tunnel"
        "macvlan" -> "macvlan"
        "veth" -> "veth"
        "vrf" -> "vrf"
        else -> "ethernet"
    }
}

/** getDevTypeDesc() */
fun devTypeDesc(page: IfPage, sid: String): String = when (devTypeOf(page, sid) ?: "") {
    "" -> "Device not present"
    "8021q" -> "VLAN (802.1q)"
    "8021ad" -> "VLAN (802.1ad)"
    "bonding" -> "Aggregation device"
    "bridge" -> "Bridge device"
    "tunnel" -> "Tunnel device"
    "macvlan" -> "MAC VLAN"
    "veth" -> "Virtual Ethernet"
    "vrf" -> "Virtual Routing and Forwarding (VRF)"
    else -> "Network device"
}

private fun deviceSectionExists(uci: UciStore, sid: String, devname: String): Boolean =
    uci.sections("network", "device").any { it[".name"] != sid && it["name"] == devname }

private fun isBridgePort(model: NetModel, dev: NetDevice?): Boolean {
    if (dev == null) return false
    if (dev.isBridgePort()) return true
    return model.uci.sections("network").any { s ->
        (s[".type"] == "interface" || s[".type"] == "device") && s["type"] == "bridge" && UciStore.toArray(s["ifname"]).contains(dev.getName())
    }
}

private val qosMap: (FormRuntime, String, String) -> String? = { _, _, v ->
    if (v.isEmpty()) null
    else {
        val m = Regex("^(\\d+):(\\d+)$").find(v)
        if (m == null || m.groupValues[1].toDouble() > 0xFFFFFFFFL.toDouble() || m.groupValues[2].toDouble() > 0xFFFFFFFFL.toDouble()) "Expecting two priority values separated by a colon" else null
    }
}

/** ListValue whose cfgvalue maps numeric bonding settings to names (tools/network.js cfgvalue overrides). */
private fun ListValueOption.mapCfg(map: Map<String, String>, fallback: String) {
    cfgvalueFn = { rt, sid -> map[rt.uci.getString("network", sid, ucioption ?: option) ?: ""] ?: fallback }
}

/** State of one open device dialog (s.map.addedVLANs, placeholder device). */
class DeviceDialogState {
    val addedVLANs = mutableListOf<String>()
    var placeholderDev by mutableStateOf<NetDevice?>(null)
    var vlanAdded by mutableStateOf(false)
}

/**
 * The device dialog: s.renderMoreOptionsModal / s.addModalOptions of the Devices grid with
 * nettools.addDeviceOptions(). "dev:<name>" rows get a new device section first.
 */
suspend fun buildDeviceModal(ctx: PageContext, page: IfPage, sid0: String): Pair<ModalForm, DeviceDialogState> {
    val uci = ctx.uci
    val model = page.model
    var sid = sid0
    var added: String? = null
    val m0 = devRe.find(sid0)
    if (m0 != null) {
        val devtype = devTypeOf(page, sid0)
        sid = uci.add("network", "device")
        uci.set("network", sid, "name", m0.groupValues[1])
        uci.set("network", sid, "type", if (devtype != "ethernet") devtype else null)
        added = sid
    }
    val isNew = uci.get("network", sid, "name") == null
    val dev = devOfSection(page, sid)
    val devName = dev?.getName()
    var hasPSE = false
    if (devName != null) {
        hasPSE = runCatching { ctx.call("network.device", "status", jsonOf("name" to devName)) }.getOrNull()?.get("pse") != null
    }
    val st = DeviceDialogState()
    val m = FormMap("network")
    val s = m.named(sid, "device")
    val name = devRe.find(sid0)?.groupValues?.get(1) ?: uci.getString("network", sid, "name")
    s.modalTitle = { if (name != null) "%s: \"%s\"".format(devTypeDesc(page, sid), name) else "Add device configuration" }
    val tristates = mutableListOf<Tristate>()
    addDeviceOptions(ctx, page, s, dev, isNew, hasPSE, st, tristates)

    val rt = FormRuntime(m, ctx)
    if (ctx.readonly) m.readonly = true
    for (o in m.sections.flatMap { it.allOptions() }) o.loadFn?.invoke(ctx, rt)
    val paths = tristates.mapNotNull { it.path }
    if (paths.isNotEmpty()) {
        val res = ctx.client.batch(paths.map { UbusCall("file", "read", jsonOf("path" to it)) })
        var i = 0
        tristates.forEach { t -> if (t.path != null) { t.applyRead(res[i].getOrNull()["data"].str); i++ } }
    }
    val form = ModalForm(m, s, sid, rt)
    added?.let { form.addedSections += it }
    return form to st
}

@Suppress("LongMethod")
private fun addDeviceOptions(ctx: PageContext, page: IfPage, s: FormSection, dev: NetDevice?, isNew: Boolean, hasPSE: Boolean, st: DeviceDialogState, tri: MutableList<Tristate>) {
    val model = page.model
    val uci = ctx.uci
    val parentDev = dev?.getParent()
    val devname = dev?.getName()
    val sid = s.sectionName!!

    s.tab("devgeneral", "General device options")
    s.tab("devadvanced", "Advanced device options")
    s.tab("brport", "Bridge port specific options")
    s.tab("bridgevlan", "Bridge VLAN filtering")
    if (hasPSE) s.tab("devpse", "PoE / PSE options")

    lateinit var nameComplex: ValueOption
    lateinit var typeOpt: ListValueOption
    lateinit var ss: FormSection
    var programmatic = false
    var nameEdited = false

    fun phFromValue(v: String?) { st.placeholderDev = v?.takeIf { it.isNotEmpty() }?.let { model.instantiateDevice(it) } }

    typeOpt = s.list(
        "type", "Device type",
        (if (!model.hasFeature("bonding") && isNew) "<a href=\"admin/system/package-manager?query=kmod-bonding\">For bonding, install <code>kmod-bonding</code></a><br/>" else "") +
            (if (!model.hasFeature("vrf") && isNew) "<a href=\"admin/system/package-manager?query=kmod-vrf\">For VRF, install <code>kmod-vrf</code></a><br/>" else ""),
        tab = "devgeneral",
    ) {
        readonly = !isNew
        value("", "Network device")
        if (model.hasFeature("bonding")) value("bonding", "Bonding/Aggregation device")
        value("bridge", "Bridge device")
        value("8021q", "VLAN (802.1q)")
        value("8021ad", "VLAN (802.1ad)")
        value("macvlan", "MAC VLAN")
        value("veth", "Virtual Ethernet")
        if (model.hasFeature("vrf") && model.hasFeature("netifd_vrf")) value("vrf", "VRF device")
        onChange = { rt, id, v ->
            if (v?.toString() in setOf("bonding", "bridge", "veth", "vrf")) phFromValue(rt.formString(nameComplex, id))
        }
        disabledFn = { _, _ -> st.vlanAdded }
    }
    if (typeOpt.description.isNullOrEmpty()) typeOpt.description = null

    s.devSelect("name_simple", "Existing device", tab = "devgeneral", model = model, noaliases = true,
        filter = { _, id, v -> val d = model.instantiateDevice(v); !deviceSectionExists(uci, id, v) && (d.getType() != "wifi" || d.isUp()) }) {
        readonly = !isNew
        rmempty = false
        default = devname ?: ""
        ucioption = "name"
        validate = { _, id, v -> if (deviceSectionExists(uci, id, v)) "A configuration for the device \"%s\" already exists".format(v) else null }
        onChange = { _, _, v -> phFromValue(v?.toString()) }
        depends("type", "")
    }

    lateinit var ifnameSingle: ChoiceOption
    lateinit var vidOpt: ValueOption
    fun autoName(rt: FormRuntime, id: String) {
        if (!isNew || nameEdited) return
        val type = rt.formString(typeOpt, id)
        val base = rt.formString(ifnameSingle, id)
        val vid = rt.formString(vidOpt, id)
        val newName = when {
            type == "macvlan" && base.isNotEmpty() -> {
                var i = 0
                while (deviceSectionExists(uci, id, "${base}mac$i")) i++
                "${base}mac$i"
            }
            (type == "8021q" || type == "8021ad") && base.isNotEmpty() && vid.toIntOrNull() != null -> "$base.${vid.toInt()}"
            else -> null
        } ?: return
        programmatic = true
        rt.setValue(nameComplex, id, newName)
        programmatic = false
    }

    ifnameSingle = s.devSelect("ifname_single", "Base device", tab = "devgeneral", model = model, noaliases = true,
        filter = { _, _, v -> val d = model.instantiateDevice(v); d.getType() != "wifi" || d.isUp() }) {
        readonly = !isNew
        rmempty = false
        default = devname?.takeIf { Regex("^.+\\.\\d+$").matches(it) }?.replace(Regex("\\.\\d+$"), "") ?: ""
        ucioption = "ifname"
        onChange = { rt, id, v -> phFromValue(v?.toString()); autoName(rt, id) }
        depends("type", "8021q"); depends("type", "8021ad"); depends("type", "macvlan")
    }

    vidOpt = s.value("vid", "VLAN ID", tab = "devgeneral") {
        readonly = !isNew
        datatype = "range(1, 4094)"
        rmempty = false
        default = devname?.takeIf { Regex("^.+\\.\\d+$").matches(it) }?.replace(Regex("^.+\\."), "") ?: ""
        onChange = { rt, id, _ -> autoName(rt, id) }
        depends("type", "8021q"); depends("type", "8021ad")
    }

    s.list("mode", "Mode", tab = "devgeneral") {
        value("vepa", "VEPA (Virtual Ethernet Port Aggregator)")
        value("private", "Private (Prevent communication between MAC VLANs)")
        value("bridge", "Bridge (Support direct communication between MAC VLANs)")
        value("passthru", "Pass-through (Mirror physical device to single MAC VLAN)")
        depends("type", "macvlan")
    }

    nameComplex = s.value("name_complex", "Device name", tab = "devgeneral") {
        rmempty = false
        datatype = "maxlength(15)"
        readonly = !isNew
        ucioption = "name"
        validate = { _, id, v ->
            val d = model.instantiateDevice(v)
            if (deviceSectionExists(uci, id, v) || (isNew && d.dev?.idx != null)) "The device name \"%s\" is already taken".format(v) else null
        }
        onChange = { _, _, _ -> if (!programmatic) nameEdited = true }
        disabledFn = { _, _ -> st.vlanAdded }
        dependsNot("type" to "")
    }

    s.dynlist("ingress_qos_mapping", "Ingress QoS mapping", "Defines a mapping of VLAN header priority to the Linux internal packet priority on incoming frames", tab = "devadvanced") {
        rmempty = true; validate = qosMap; depends("type", "8021q"); depends("type", "8021ad")
    }
    s.dynlist("egress_qos_mapping", "Egress QoS mapping", "Defines a mapping of Linux internal packet priority to VLAN header priority but for outgoing frames", tab = "devadvanced") {
        rmempty = true; validate = qosMap; depends("type", "8021q"); depends("type", "8021ad")
    }

    val defaultPorts = (dev?.getPorts() ?: emptyList()).filter { it.getType() != "wifi" }.map { it.getName() }
    val portFilter: (FormRuntime, String, String) -> Boolean = { rt, id, v ->
        val bridgeName = rt.uci.getString("network", id, "name")
        val choice = model.instantiateDevice(v)
        if (choice.getType() == "wifi") UciStore.toArray(rt.uci.get("network", id, "ports")).contains(v)
        else choice.getParent()?.getName().let { it == null || it != bridgeName }
    }

    s.devSelect("ifname_multi-bond", "Aggregation ports", "Specifies the wired ports to attach to this bonding.", tab = "devgeneral", model = model,
        multiple = true, noaliases = true, nobridges = true, filter = portFilter) {
        rmempty = true; ucioption = "ports"; default = defaultPorts
        onChange = { rt, id, v -> updatePorts(rt, ss, id, UciStore.toArray(v), model) }
        depends("type", "bonding")
    }

    s.list("policy", "Bonding Policy", tab = "devgeneral") {
        default = "balance-rr"
        value("active-backup", "Active backup"); value("balance-rr", "Round robin"); value("balance-xor", "Transmit hash - balance-xor")
        value("broadcast", "Broadcast"); value("802.3ad", "LACP - 802.3ad"); value("balance-tlb", "Adaptive transmit load balancing")
        value("balance-alb", "Adaptive load balancing")
        mapCfg(mapOf("active-backup" to "active-backup", "0" to "active-backup", "balance-rr" to "balance-rr", "1" to "balance-rr",
            "balance-xor" to "balance-xor", "2" to "balance-xor", "broadcast" to "broadcast", "3" to "broadcast", "802.3ad" to "802.3ad", "4" to "802.3ad",
            "balance-tlb" to "balance-tlb", "5" to "balance-tlb", "balance-alb" to "balance-alb", "6" to "balance-alb"), "balance-rr")
        depends("type", "bonding")
    }
    s.flag("all_ports_active", "All ports active", "Allow receiving on inactive ports", tab = "devgeneral") { depends("type", "bonding") }
    s.devSelect("_net_device_primary", "Primary Device", tab = "devgeneral", model = model, noaliases = true, nobridges = true) {
        ucioption = "primary"; rmempty = true; optional = false
        depends("type" to "bonding", "policy" to "active-backup")
    }
    s.list("xmit_hash_policy", "Slave selection hash policy", tab = "devadvanced") {
        default = ""
        value("", ""); value("layer2", "Layer 2"); value("layer2+3", "Layer 2+3"); value("layer3+4", "Layer 3+4"); value("encap2+3", "Encap 2+3"); value("encap3+4", "Encap 3+4")
        mapCfg(mapOf("layer2" to "layer2", "0" to "layer2", "layer2+3" to "layer2+3", "1" to "layer2+3", "layer3+4" to "layer3+4", "2" to "layer3+4",
            "encap2+3" to "encap2+3", "4" to "encap2+3", "encap3+4" to "encap3+4", "5" to "encap3+4"), "")
        depends("type" to "bonding", "policy" to "balance-xor"); depends("type" to "bonding", "policy" to "802.3ad"); depends("type" to "bonding", "policy" to "balance-tlb")
    }
    s.value("ad_actor_system", "MAC address for LACPDUs", "This specifies the mac-address for the actor in protocol packet exchanges (LACPDUs). The value cannot be NULL or multicast.", tab = "devadvanced") {
        datatype = "macaddr"; depends("type" to "bonding", "policy" to "802.3ad")
    }
    s.value("ad_actor_sys_prio", "Priority", "This specifies the AD system priority", tab = "devadvanced") {
        placeholder = "65535"; datatype = "range(1, 65535)"; depends("type" to "bonding", "policy" to "802.3ad")
    }
    s.list("ad_select", "802.3ad aggregation logic", tab = "devadvanced") {
        default = ""
        value("", ""); value("stable", "Stable"); value("bandwidth", "Bandwidth"); value("count", "Count")
        mapCfg(mapOf("stable" to "stable", "0" to "stable", "bandwidth" to "bandwidth", "1" to "bandwidth", "count" to "count", "2" to "count"), "")
        depends("type" to "bonding", "policy" to "802.3ad")
    }
    s.list("lacp_rate", "802.3ad LACPDU packet rate", tab = "devadvanced") {
        default = ""
        value("", ""); value("slow", "Slow (every 30 seconds)"); value("fast", "Fast (every second)")
        mapCfg(mapOf("slow" to "slow", "0" to "slow", "fast" to "fast", "1" to "fast"), "")
        depends("type" to "bonding", "policy" to "802.3ad")
    }
    s.value("min_links", "Min Links", "Minimum number of active links", tab = "devadvanced") {
        placeholder = "1"; datatype = "uinteger"; depends("type" to "bonding", "policy" to "802.3ad")
    }
    s.value("packets_per_slave", "Packets per slave", "Number of packets to transmit through a slave before moving to the next one. Slave is chosen at random when 0.", tab = "devadvanced") {
        placeholder = "1"; datatype = "range(1, 65535)"; depends("type" to "bonding", "policy" to "balance-rr")
    }
    s.value("lp_interval", "Learning packets Interval", "Number of seconds between sent learning packets", tab = "devadvanced") {
        placeholder = "1"; datatype = "uinteger"
        depends("type" to "bonding", "policy" to "balance-tlb"); depends("type" to "bonding", "policy" to "balance-alb")
    }
    s.flag("dynamic_lb", "Dynamic load balance", "distribute traffic according to port load", tab = "devgeneral") {
        depends("type" to "bonding", "policy" to "balance-tlb")
    }
    s.value("resend_igmp", "IGMP reports", "Specifies the number of IGMP membership reports to be issued after a failover event", tab = "devadvanced") {
        placeholder = "1"; datatype = "range(0, 255)"; depends("type", "bonding")
    }
    s.value("num_peer_notif", "Peer notifications", "Specify the number of peer notifications to be issued after a failover event.", tab = "devadvanced") {
        placeholder = "1"; datatype = "range(0, 255)"; depends("type", "bonding")
    }
    s.list("primary_reselect", "Primary port reselection policy", tab = "devadvanced") {
        default = ""
        value("", ""); value("always", "Always"); value("better", "Better"); value("failure", "Failure")
        mapCfg(mapOf("always" to "always", "0" to "always", "better" to "better", "1" to "better", "failure" to "failure", "2" to "failure"), "always")
        depends("type", "bonding")
    }
    s.list("failover_mac", "MAC address selection policy", tab = "devadvanced") {
        default = ""
        value("none", "none"); value("active", "Active"); value("follow", "Follow")
        mapCfg(mapOf("none" to "none", "0" to "none", "active" to "active", "1" to "active", "follow" to "follow", "2" to "follow"), "none")
        depends("type", "bonding")
    }
    s.list(
        "monitor_mode", "Link monitoring mode",
        if (!model.hasFeature("mii_tool")) "<a href=\"admin/system/package-manager?query=mii-tool\">Install <code>mii-tool</code></a>" else null,
        tab = "devadvanced",
    ) {
        default = ""
        value("arp", "ARP link monitoring"); value("mii", "MII link monitoring")
        mapCfg(mapOf("arp" to "arp", "1" to "arp", "mii" to "mii", "2" to "mii"), "mii")
        depends("type", "bonding")
    }
    s.value("monitor_interval", "Monitor Interval", "Specifies the link monitoring frequency in milliseconds", tab = "devadvanced") {
        placeholder = "100"; datatype = "uinteger"; depends("type", "bonding")
    }
    s.dynlist("arp_target", "ARP monitor target IP address", tab = "devadvanced") {
        datatype = "ipaddr"; depends("type" to "bonding", "monitor_mode" to "arp")
    }
    s.flag("arp_all_targets", "All ARP Targets", "All ARP targets must be reachable to consider the link valid", tab = "devgeneral") {
        depends("type" to "bonding", "monitor_mode" to "arp")
    }
    s.list("arp_validate", "ARP validation policy", tab = "devadvanced") {
        default = ""
        value("none", "None"); value("active", "Active"); value("backup", "Backup"); value("all", "All")
        value("filter", "Filter"); value("filter_active", "Filter active"); value("filter_backup", "Filter backup")
        mapCfg(mapOf("none" to "none", "0" to "none", "active" to "active", "1" to "active", "backup" to "backup", "2" to "backup", "all" to "all", "3" to "all",
            "filter" to "filter", "4" to "filter", "filter_active" to "filter_active", "5" to "filter_active", "filter_backup" to "filter_backup", "6" to "filter_backup"), "none")
        for (pol in listOf("balance-rr", "active-backup", "balance-xor", "broadcast")) depends("type" to "bonding", "policy" to pol, "monitor_mode" to "arp")
    }
    s.flag("use_carrier", "Use Carrier", "Use carrier status instead of MII result", tab = "devadvanced") {
        depends("type" to "bonding", "monitor_mode" to "mii")
    }
    s.value("updelay", "Monitor link-up delay", "Delay before enabling port after MII link up event (msec)", tab = "devadvanced") {
        placeholder = "0"; datatype = "uinteger"; depends("type" to "bonding", "monitor_mode" to "mii")
    }
    s.value("downdelay", "Monitor link-down delay", "Delay before enabling port after MII link down event (msec)", tab = "devadvanced") {
        placeholder = "0"; datatype = "uinteger"; depends("type" to "bonding", "monitor_mode" to "mii")
    }

    val isBr = dev?.getType() == "bridge"
    s.devSelect(
        "ifname_multi-bridge", if (isBr) "Bridge ports" else "Ports",
        if (isBr) "Specifies the wired ports to attach to this bridge. In order to attach wireless networks, choose the associated interface as network in the wireless settings."
        else "Specifies the devices to attach to this VRF. In order to attach wireless networks, choose the associated interface as network in the wireless settings.",
        tab = "devgeneral", model = model, multiple = true, noaliases = true, nobridges = true, filter = portFilter,
    ) {
        rmempty = true; ucioption = "ports"; default = defaultPorts
        onChange = { rt, id, v -> updatePorts(rt, ss, id, UciStore.toArray(v), model) }
        depends("type", "bridge"); depends("type", "vrf")
    }

    s.value("table", "Routing table", tab = "devgeneral") {
        page.rtTables.forEach { (id, n) -> value(n, "$n ($id)") }
        depends("type", "vrf")
    }
    s.flag("bridge_empty", "Bring up empty bridge", "Bring up the bridge interface even if no ports are attached", tab = "devgeneral") { depends("type", "bridge") }
    s.value("priority", "Priority", tab = "devadvanced") { placeholder = "32767"; datatype = "range(0, 65535)"; depends("type", "bridge") }
    s.value("ageing_time", "Ageing time", "Timeout in seconds for learned MAC addresses in the forwarding database", tab = "devadvanced") {
        placeholder = "30"; datatype = "uinteger"; depends("type", "bridge")
    }
    s.flag("stp", "Enable <abbr title=\"Spanning Tree Protocol\">STP</abbr>", "Enables the Spanning Tree Protocol on this bridge", tab = "devadvanced") { depends("type", "bridge") }
    s.value("hello_time", "Hello interval", "Interval in seconds for STP hello packets", tab = "devadvanced") {
        placeholder = "1"; datatype = "range(1, 10)"; depends("type" to "bridge", "stp" to "1")
    }
    s.value("forward_delay", "Forward delay", "Time in seconds to spend in listening and learning states", tab = "devadvanced") {
        placeholder = "8"; datatype = "range(2, 30)"; depends("type" to "bridge", "stp" to "1")
    }
    s.value("max_age", "Maximum age", "Timeout in seconds until topology updates on link loss", tab = "devadvanced") {
        placeholder = "10"; datatype = "range(6, 40)"; depends("type" to "bridge", "stp" to "1")
    }
    lateinit var igmp: FlagOption
    igmp = s.flag("igmp_snooping", "Enable <abbr title=\"Internet Group Management Protocol\">IGMP</abbr> snooping", "Enables IGMP snooping on this bridge", tab = "devadvanced") {
        depends("type", "bridge")
    }
    s.value("hash_max", "Maximum snooping table size", tab = "devadvanced") {
        placeholder = "512"; datatype = "uinteger"; depends("type" to "bridge", "igmp_snooping" to "1")
    }
    s.flag("multicast_querier", "Enable multicast querier", tab = "devadvanced") {
        defaultFn = { rt, id -> if (rt.isActive(igmp, id)) (if (rt.formString(igmp, id) == "1") "1" else "0") else null }
        depends("type", "bridge")
    }
    s.value("robustness", "Robustness", "The robustness value allows tuning for the expected packet loss on the network. If a network is expected to be lossy, the robustness value may be increased. IGMP is robust to (Robustness-1) packet losses", tab = "devadvanced") {
        placeholder = "2"; datatype = "min(1)"; depends("type" to "bridge", "multicast_querier" to "1")
    }
    lateinit var queryInterval: ValueOption
    queryInterval = s.value("query_interval", "Query interval", "Interval in centiseconds between multicast general queries. By varying the value, an administrator may tune the number of IGMP messages on the subnet; larger values cause IGMP Queries to be sent less often", tab = "devadvanced") {
        placeholder = "12500"; datatype = "uinteger"; depends("type" to "bridge", "multicast_querier" to "1")
    }
    s.value("query_response_interval", "Query response interval", "The max response time in centiseconds inserted into the periodic general queries. By varying the value, an administrator may tune the burstiness of IGMP messages on the subnet; larger values make the traffic less bursty, as host responses are spread out over a larger interval", tab = "devadvanced") {
        placeholder = "1000"; datatype = "uinteger"
        validate = { rt, id, v ->
            val qi = rt.formString(queryInterval, id).ifEmpty { queryInterval.placeholder ?: "" }
            if (v != "" && qi != "" && (v.toDoubleOrNull() ?: 0.0) >= (qi.toDoubleOrNull() ?: 0.0)) "The query response interval must be lower than the query interval value" else null
        }
        depends("type" to "bridge", "multicast_querier" to "1")
    }
    s.value("last_member_interval", "Last member interval", "The max response time in centiseconds inserted into group-specific queries sent in response to leave group messages. It is also the amount of time between group-specific query messages. This value may be tuned to modify the \"leave latency\" of the network. A reduced value results in reduced time to detect the loss of the last member of a group", tab = "devadvanced") {
        placeholder = "100"; datatype = "uinteger"; depends("type" to "bridge", "multicast_querier" to "1")
    }

    s.value("mtu", "MTU", tab = "devgeneral") {
        datatype = "range(576, 9200)"
        placeholderFn = { _, _ -> st.placeholderDev?.getMTU()?.toString() }
        validate = { _, _, v ->
            val parentMtu = if (dev?.getType() == "vlan") parentDev?.getMTU() else null
            if (parentMtu != null && (v.toDoubleOrNull() ?: 0.0) > parentMtu) "The MTU must not exceed the parent device MTU of %d bytes".format(parentMtu) else null
        }
    }
    s.value("macaddr", "MAC address", tab = "devgeneral") {
        datatype = "macaddr"
        placeholderFn = { _, _ -> st.placeholderDev?.getMAC() }
    }
    s.value("peer_name", "Peer device name", tab = "devgeneral") {
        rmempty = true; datatype = "maxlength(15)"; depends("type", "veth")
        var idx = 0
        for (d in uci.sections("network", "device")) { if (d[".name"] == sid) break else if (d["type"] == "veth") idx++ }
        placeholder = "veth$idx"
    }
    s.value("peer_macaddr", "Peer MAC address", tab = "devgeneral") { rmempty = true; datatype = "macaddr"; depends("type", "veth") }
    s.value("txqueuelen", "TX queue length", tab = "devgeneral") {
        placeholder = dev?.dev?.qlen?.toString() ?: ""
        datatype = "uinteger"
        placeholderFn = { _, _ -> st.placeholderDev?.let { it.dev?.qlen?.toString() } }
    }

    if (hasPSE) {
        s.list("pse", "PoE (C33)", "Power over Ethernet (IEEE 802.3af/at/bt) control for this port. Requires PSE hardware support.", tab = "devpse") {
            value("", "Default"); value("1", "Enabled"); value("0", "Disabled")
        }
        s.list("pse_podl", "PoDL", "Power over Data Lines (IEEE 802.3bu/cg) for single-pair Ethernet.", tab = "devpse") {
            value("", "Default"); value("1", "Enabled"); value("0", "Disabled")
        }
        s.value("pse_power_limit", "Power limit (mW)", "Maximum power budget for this port in milliwatts. Leave empty for default/maximum.", tab = "devpse") {
            datatype = "uinteger"; placeholder = "auto"; rmempty = true
        }
        s.list("pse_priority", "Port priority", "Priority level for power allocation when total power budget is exceeded.", tab = "devpse") {
            value("", "Default"); value("1", "Critical"); value("2", "High"); value("3", "Low")
        }
    }

    val flags = dev?.dev?.flags
    val dn = devname ?: "default"
    s.tristate("promisc", "Enable promiscuous mode", tab = "devadvanced", flagDefault = flags?.get("promisc")?.let { it.toString() == "true" }, flagKnown = flags != null, registry = tri)
    s.list("rpfilter", "Reverse path filter", tab = "devadvanced") {
        default = ""
        value("", "disabled"); value("loose", "Loose filtering"); value("strict", "Strict filtering")
        mapCfg(mapOf("loose" to "loose", "1" to "loose", "strict" to "strict", "2" to "strict"), "")
    }
    s.tristate("acceptlocal", "Accept local", "Accept packets with local source addresses", tab = "devadvanced", sysfs = "/proc/sys/net/ipv4/conf/$dn/accept_local", registry = tri)
    s.tristate("sendredirects", "Send ICMP redirects", tab = "devadvanced", sysfs = "/proc/sys/net/ipv4/conf/$dn/send_redirects", registry = tri)
    s.tristate("arp_accept", "Honor gratuitous ARP", "When enabled, new ARP table entries are added from received gratuitous ARP requests or replies, otherwise only preexisting table entries are updated, but no new hosts are learned.", tab = "devadvanced", sysfs = "/proc/sys/net/ipv4/conf/$dn/arp_accept", registry = tri)
    s.tristate("drop_gratuitous_arp", "Drop gratuitous ARP", "Drop all gratuitous ARP frames, for example if there’s a known good ARP proxy on the network and such frames need not be used or in the case of 802.11, must not be used to prevent attacks.", tab = "devadvanced", sysfs = "/proc/sys/net/ipv4/conf/$dn/drop_gratuitous_arp", registry = tri)
    s.value("neighreachabletime", "Neighbour cache validity", "Time in milliseconds", tab = "devadvanced") { placeholder = "30000"; datatype = "uinteger" }
    s.value("neighgcstaletime", "Stale neighbour cache timeout", "Timeout in seconds", tab = "devadvanced") { placeholder = "60"; datatype = "uinteger" }
    s.value("neighlocktime", "Minimum ARP validity time", "Minimum required time in seconds before an ARP entry may be replaced. Prevents ARP cache thrashing.", tab = "devadvanced") {
        placeholder = "0"; datatype = "uinteger"
    }
    s.tristate("ipv6", "Enable IPv6", tab = "devgeneral", sysfs = "!/proc/sys/net/ipv6/conf/$dn/disable_ipv6", registry = tri)
    s.tristate("ip6segmentrouting", "Enable IPv6 segment routing", tab = "devadvanced", sysfs = "/proc/sys/net/ipv6/conf/$dn/seg6_enabled", registry = tri) {
        depends("ipv6", Regex("1"))
    }
    s.tristate("drop_unsolicited_na", "Drop unsolicited NA", "Drop all unsolicited neighbor advertisements, for example if there’s a known good NA proxy on the network and such frames need not be used or in the case of 802.11, must not be used to prevent attacks.", tab = "devadvanced", sysfs = "/proc/sys/net/ipv6/conf/$dn/drop_unsolicited_na", registry = tri) {
        depends("ipv6", Regex("1"))
    }
    s.value("mtu6", "IPv6 MTU", tab = "devgeneral") {
        datatype = "max(9200)"; depends("ipv6", Regex("1"))
        placeholderFn = { _, _ -> st.placeholderDev?.getMTU()?.toString() }
    }
    s.value("dadtransmits", "DAD transmits", "Amount of Duplicate Address Detection probes to send", tab = "devgeneral") {
        placeholder = "1"; datatype = "uinteger"; depends("ipv6", Regex("1"))
    }
    s.tristate("multicast", "Enable multicast support", tab = "devadvanced", flagDefault = flags?.get("multicast")?.let { it.toString() == "true" }, flagKnown = flags != null, registry = tri)
    s.list("igmpversion", "Force IGMP version", tab = "devadvanced") {
        value("", "No enforcement"); value("1", "Enforce IGMPv1"); value("2", "Enforce IGMPv2"); value("3", "Enforce IGMPv3")
        depends("multicast", Regex("1"))
    }
    s.list("mldversion", "Force MLD version", tab = "devadvanced") {
        value("", "No enforcement"); value("1", "Enforce MLD version 1"); value("2", "Enforce MLD version 2")
        depends("multicast", Regex("1"))
    }

    if (isBridgePort(model, dev)) {
        s.tristate("learning", "Enable MAC address learning", tab = "brport", sysfs = "/sys/class/net/$dn/brport/learning", registry = tri)
        s.tristate("unicast_flood", "Enable unicast flooding", tab = "brport", sysfs = "/sys/class/net/$dn/brport/unicast_flood", registry = tri)
        s.tristate("isolate", "Port isolation", "Only allow communication with non-isolated bridge ports when enabled", tab = "brport", sysfs = "/sys/class/net/$dn/brport/isolated", registry = tri)
        s.list("multicast_router", "Multicast routing", tab = "brport") {
            value("", "Never"); value("1", "Learn"); value("2", "Always")
            depends("multicast", Regex("1"))
        }
        s.tristate("multicast_to_unicast", "Multicast to unicast", "Forward multicast packets as unicast packets on this device.", tab = "brport", sysfs = "/sys/class/net/$dn/brport/multicast_to_unicast", registry = tri) {
            depends("multicast", Regex("1"))
        }
        s.tristate("multicast_fast_leave", "Enable multicast fast leave", tab = "brport", sysfs = "/sys/class/net/$dn/brport/multicast_fast_leave", registry = tri) {
            depends("multicast", Regex("1"))
        }
        s.tristate("drop_v4_unicast_in_l2_multicast", "Drop nested IPv4 unicast", "Drop layer 2 multicast frames containing IPv4 unicast packets.", tab = "brport", sysfs = "/proc/sys/net/ipv4/conf/$dn/drop_unicast_in_l2_multicast", registry = tri) {
            depends("multicast", Regex("1"))
        }
        s.tristate("drop_v6_unicast_in_l2_multicast", "Drop nested IPv6 unicast", "Drop layer 2 multicast frames containing IPv6 unicast packets.", tab = "brport", sysfs = "/proc/sys/net/ipv6/conf/$dn/drop_unicast_in_l2_multicast", registry = tri) {
            depends("multicast", Regex("1"))
        }
    }

    s.flag("vlan_filtering", "Enable VLAN filtering", tab = "bridgevlan") {
        depends("type", "bridge")
        defaultFn = { rt, _ ->
            val device = rt.uci.getString("network", sid, "name")
            if (rt.uci.sections("network", "bridge-vlan").any { it["device"] == device }) "1" else "0"
        }
    }

    ss = s.nested(SectionKind.Table, "bridge-vlan") {
        addremove = true
        anonymous = true
        filter = { _, sec -> sec["device"] == uci.getString("network", sid, "name") }
    }
    ss.value("vlan", "VLAN ID") {
        datatype = "range(1, 4094)"
        editable = true
        validate = { rt, bsid, v ->
            if (rt.cfgsections(ss).any { it != bsid && rt.uci.getString("network", it, "vlan") == v }) "The VLAN ID must be unique" else null
        }
    }
    ss.flag("local", "Local") { default = "1"; editable = true }

    s.subsection("bridge-vlan", ss, tab = "bridgevlan") {
        depends("type", "bridge")
        render = { rt, id -> BridgeVlanTable(ctx, page, rt, s, ss, id, st) }
    }

    val seen = LinkedHashSet<String>()
    UciStore.toArray(uci.get("network", sid, "ports")).forEach { seen += it }
    uci.sections("network", "bridge-vlan").forEach { bvs ->
        if (uci.getString("network", sid, "name") != bvs["device"]) return@forEach
        UciStore.toArray(bvs["ports"]).forEach { spec -> Regex("^([^:]+)(?::[ut*]+)?$").find(spec)?.let { seen += it.groupValues[1] } }
    }
    updatePortsInit(ss, seen.toList(), model)
}

/** A port column of the bridge VLAN table (cbiTagValue). */
class PortTagOption(section: FormSection, option: String, val port: String) : FormOption(section, option, port)

private fun portOptions(ss: FormSection) = ss.options.filterIsInstance<PortTagOption>()

private fun makePortOption(ss: FormSection, d: NetDevice): PortTagOption {
    val o = PortTagOption(ss, "port_${Prng.sfhHex(d.getName())}", d.getName())
    o.cfgvalueFn = { rt, bsid ->
        val ports = UciStore.toArray(rt.uci.get("network", bsid, "ports"))
        var r: List<String> = listOf("-")
        for (p in ports) {
            val parts = p.split(':')
            if (parts[0] != o.port) continue
            val f = parts.getOrNull(1) ?: ""
            val t = if (f.contains('t')) "t" else "u"
            r = if (f.contains('*')) listOf(t, "*") else listOf(t)
            break
        }
        r
    }
    o.writeFn = { rt, bsid, _ ->
        val ports = ArrayList<String>()
        for (po in portOptions(ss)) {
            when (val v = rt.formList(po, bsid).joinToString("")) {
                "-", "" -> {}
                "u" -> ports += po.port
                else -> ports += "${po.port}:$v"
            }
        }
        rt.uci.set("network", bsid, "ports", ports.ifEmpty { null })
    }
    o.removeFn = { _, _ -> }
    return o
}

private fun updatePortsInit(ss: FormSection, ports: List<String>, model: NetModel) {
    val devices = ports.map { model.instantiateDevice(it) }.filter { it.getType() != "wifi" || it.isUp() }
        .sortedWith { a, b -> naturalCompare(a.getName(), b.getName()) }
    ss.options.removeAll { it is PortTagOption }
    devices.forEach { ss.options += makePortOption(ss, it) }
}

/** ss.updatePorts(): new port columns, and drop port specs of removed ports from the VLANs. */
private fun updatePorts(rt: FormRuntime, ss: FormSection, @Suppress("UNUSED_PARAMETER") sid: String, ports: List<String>, model: NetModel) {
    updatePortsInit(ss, ports, model)
    val names = portOptions(ss).map { it.port }.toSet()
    for (bsid in rt.cfgsections(ss)) {
        val old = UciStore.toArray(rt.uci.get("network", bsid, "ports"))
        val new = old.filter { names.contains(it.replace(Regex(":[ut*]+$"), "")) }
        if (old.size != new.size) rt.uci.set("network", bsid, "ports", new.ifEmpty { null })
    }
    rt.revision.intValue++
}

/** tools/network.js updatePortStatus() text. */
fun portStatusText(d: NetDevice): String {
    val carrier = d.getCarrier()
    val speed = d.getSpeed()
    val duplex = d.getDuplex()
    return when {
        carrier && speed != null && speed > 0 && duplex != null -> "%d%s".format(speed, if (duplex == "full") "FD" else "HD")
        carrier -> "Connected"
        else -> "no link"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BridgeVlanTable(ctx: PageContext, page: IfPage, rt: FormRuntime, s: FormSection, ss: FormSection, sid: String, st: DeviceDialogState) {
    rt.revision.intValue
    val ro = rt.map.readonly || ctx.readonly
    val sids = rt.cfgsections(ss)
    val ports = portOptions(ss)
    val vlanOpt = ss.options.first { it.option == "vlan" }
    val localOpt = ss.options.first { it.option == "local" }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sids.isEmpty()) EmptyState("This section contains no values yet")
        sids.forEach { bsid ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.widthIn(max = 160.dp)) { OptionField(rt, vlanOpt, bsid, compact = true) }
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.weight(1f)) { OptionField(rt, localOpt, bsid, compact = true) }
                        if (!ro) IconButton(onClick = {
                            rt.uci.remove("network", bsid)
                            st.addedVLANs.remove(bsid)
                            rt.revision.intValue++
                        }) { Icon(Icons.Outlined.Delete, "Delete", tint = MaterialTheme.colorScheme.error) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ports.forEach { po -> PortTagCell(rt, ss, po, bsid, page, ro) }
                    }
                }
            }
        }
        if (!ro) FilledTonalButton(onClick = {
            val err = rt.parseSection(s, listOf(sid), modal = true)
            if (err != null) { ctx.toast(err); return@FilledTonalButton }
            val device = rt.uci.getString("network", sid, "name") ?: return@FilledTonalButton
            var maxVid = 0
            for (b in rt.cfgsections(ss)) { val v = rt.uci.getString("network", b, "vlan")?.toIntOrNull() ?: 0; if (v > maxVid) maxVid = v }
            val nsid = rt.uci.add("network", "bridge-vlan")
            rt.uci.set("network", nsid, "device", device)
            rt.uci.set("network", nsid, "vlan", (maxVid + 1).toString())
            st.vlanAdded = true
            st.addedVLANs += nsid
            rt.revision.intValue++
        }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text("Add") }
    }
}

@Composable
private fun PortTagCell(rt: FormRuntime, ss: FormSection, po: PortTagOption, bsid: String, page: IfPage, ro: Boolean) {
    val d = page.model.instantiateDevice(po.port)
    val value = rt.formList(po, bsid)
    var open by remember { mutableStateOf(false) }
    fun set(v: List<String>) { rt.setValue(po, bsid, v) }
    fun toggle(k: String) {
        val sel = value.toMutableSet()
        when (k) {
            "-" -> if (!sel.contains("-")) set(listOf("-"))
            "t", "u" -> if (!sel.contains(k)) {
                val star = sel.contains("*")
                set(if (star) listOf(k, "*") else listOf(k))
            }
            "*" -> if (sel.contains("*")) set(value.filter { it != "*" }) else if (!sel.contains("-")) {
                set(value + "*")
                for (other in rt.cfgsections(ss)) {
                    if (other == bsid) continue
                    val ov = rt.formList(po, other)
                    if (ov.contains("*")) { rt.setValue(po, other, ov.filter { it != "*" }); break }
                }
            }
        }
    }
    val code = buildString {
        if (value.contains("-") || value.isEmpty()) append("—")
        if (value.contains("u")) append("U")
        if (value.contains("t")) append("T")
        if (value.contains("*")) append("*")
    }
    Column(Modifier.widthIn(min = 92.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceGlyph(d.getType(), d.getCarrier())
            Spacer(Modifier.width(4.dp))
            Text(d.getName(), style = MaterialTheme.typography.labelLarge)
        }
        Text(portStatusText(d), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, enabled = !ro) { Text(code, fontFamily = FontFamily.Monospace) }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                listOf("-" to "Not Member", "u" to "Untagged", "t" to "Tagged", "*" to "Is Primary VLAN").forEach { (k, label) ->
                    val enabled = k != "*" || !value.contains("-")
                    DropdownMenuItem(
                        text = { Text(label) },
                        enabled = enabled,
                        leadingIcon = { Checkbox(checked = value.contains(k), onCheckedChange = null, enabled = enabled) },
                        onClick = { toggle(k) },
                    )
                }
            }
        }
    }
}
