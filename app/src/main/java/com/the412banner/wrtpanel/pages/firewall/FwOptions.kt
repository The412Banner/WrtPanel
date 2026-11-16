package com.the412banner.wrtpanel.pages.firewall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.core.strList
import com.the412banner.wrtpanel.form.ChoiceOption
import com.the412banner.wrtpanel.form.DummyValueOption
import com.the412banner.wrtpanel.form.DynamicListOption
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormOption
import com.the412banner.wrtpanel.form.FormRuntime
import com.the412banner.wrtpanel.form.FormSection
import com.the412banner.wrtpanel.form.MultiValueOption
import com.the412banner.wrtpanel.form.RowAction
import com.the412banner.wrtpanel.form.ValueOption
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.stripHtml
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** Status of a logical interface for zone badges (network.getNetworks(): isUp, getDevices / getL3Device). */
data class IfInfo(val up: Boolean, val devices: List<String>)

/** What the firewall views load besides UCI. */
class FwData(
    val fw4: Boolean,
    val hosts: Map<String, FwTool.Host> = emptyMap(),
    val ctHelpers: List<CtHelper> = emptyList(),
    val devices: JsonObject = JsonObject(emptyMap()),
    val ifaces: Map<String, IfInfo> = emptyMap(),
)

/** Loads firewall + network UCI and the RPC data a view's load() fetches. */
suspend fun PageContext.loadFwData(hosts: Boolean = false, helpers: Boolean = false, devices: Boolean = false): FwData {
    uci.load("firewall", "network")
    val h = if (hosts) {
        callOrNull("luci-rpc", "getHostHints").obj?.entries?.associate { (mac, v) ->
            mac to FwTool.Host(v["name"].str, (v["ipaddrs"] ?: v["ipv4"]).strList, (v["ip6addrs"] ?: v["ipv6"]).strList)
        } ?: emptyMap()
    } else emptyMap()
    val ct = if (helpers) {
        callOrNull("luci", "getConntrackHelpers")["result"].arr?.mapNotNull { e ->
            e["name"].str?.let { CtHelper(it, e["description"].str) }
        } ?: emptyList()
    } else emptyList()
    val devs = if (devices) callOrNull("luci-rpc", "getNetworkDevices").obj ?: JsonObject(emptyMap()) else JsonObject(emptyMap())
    val dump = callOrNull("network.interface", "dump")["interface"].arr ?: emptyList()
    val status = dump.associateBy { it["interface"].str ?: "" }
    val bridges = uci.sections("network", "device").filter { it["type"] == "bridge" }
        .associate { (it["name"] as? String ?: "") to UciStore.toArray(it["ports"]) }
    val ifaces = uci.sections("network", "interface").mapNotNull { s ->
        val n = s[".name"] as? String ?: return@mapNotNull null
        val st = status[n]
        val dev = st["device"].str ?: (s["device"] as? String)
        val list = bridges[dev] ?: listOfNotNull(st["l3_device"].str ?: dev)
        n to IfInfo(st["up"].bool == true, list)
    }.toMap()
    return FwData(session.hasFeature("firewall4"), h, ct, devs, ifaces)
}

private const val FW = "firewall"

/** The interface list of a zone badge: "net: dev dev" per covered network, or "(empty)". */
fun zoneIfaces(uci: UciStore, fd: FwData, zsid: String): String {
    val parts = FwModel.zoneNetworks(uci, zsid).mapNotNull { n ->
        val info = fd.ifaces[n] ?: return@mapNotNull null
        "$n: " + info.devices.joinToString(" ").ifEmpty { "(empty)" }
    }
    return parts.ifEmpty { listOf("(empty)") }.joinToString("  ")
}

fun zoneChoice(uci: UciStore, fd: FwData, zsid: String): ZoneChoice? {
    val name = FwModel.zoneName(uci, zsid) ?: return null
    return ZoneChoice(name, name, true, "", FwTool.colorForName(name), zoneIfaces(uci, fd, zsid))
}

private fun liveError(rt: FormRuntime, o: FormOption, sid: String): String? =
    rt.error(o, sid) ?: if (rt.isEdited(o, sid)) rt.validateField(o, sid) else null

/**
 * widgets.ZoneSelect. A "dest" option is titled "Output zone" while the "src" option of the
 * section is empty, else "Destination zone" (renderWidget sets this.title).
 */
fun FormSection.fwZoneSelect(
    name: String, title: String?, description: String? = null, tab: String? = null, fd: FwData,
    multiple: Boolean = false, allowany: Boolean = false, allowlocal: Boolean = false, nocreate: Boolean = false,
    filter: ((rt: FormRuntime, sid: String, zone: String) -> Boolean)? = null,
    block: ChoiceOption.() -> Unit = {},
): ChoiceOption {
    val o: ChoiceOption = if (multiple) multi(name, title, description, tab) else list(name, title, description, tab)
    var dt = "ucifw4zonename"
    if (!fd.fw4) dt = "and($dt,maxlength(11))"
    if (allowany && nocreate) dt = "or($dt,\"*\")"
    if (multiple) dt = "list($dt)"
    o.datatype = dt
    if (o is MultiValueOption) o.create = !nocreate

    fun outputOnly(rt: FormRuntime, sid: String): Boolean {
        if (o.option != "dest") return false
        val src = o.section.options.firstOrNull { it.option == "src" } ?: return false
        return rt.formString(src, sid).isEmpty()
    }

    fun choices(rt: FormRuntime, sid: String): List<ZoneChoice> {
        val isOutputOnly = outputOnly(rt, sid)
        val neutral = FwTool.colorForName(null)
        val out = ArrayList<ZoneChoice>()
        if (allowlocal) out += ZoneChoice("", "Device", true, " (${if (o.option != "dest") "output" else "input"})", neutral, null)
        else if (!multiple && (o.rmempty || o.optional)) out += ZoneChoice("", "unspecified", false, "", neutral, null)
        if (allowany) out += ZoneChoice("*", "Any zone", true, if (allowlocal && !isOutputOnly) " (forward)" else "", neutral, null)
        for (zsid in FwModel.zones(rt.uci)) {
            val z = zoneChoice(rt.uci, fd, zsid) ?: continue
            if (filter != null && !filter(rt, sid, z.key)) continue
            if (out.none { it.key == z.key }) out += z
        }
        if (isOutputOnly) out.removeAll { it.key == "" }
        return out
    }

    o.choicesFn = { rt, sid -> choices(rt, sid).map { it.key to (it.text + it.suffix) } }
    o.render = { rt, sid ->
        if (o.option == "dest") o.title = if (outputOnly(rt, sid)) "Output zone" else "Destination zone"
        val label = o.title?.let(::stripHtml)
        val enabled = !rt.isDisabled(o, sid)
        if (multiple) {
            ZoneMultiSelectField(label, rt.formList(o, sid), choices(rt, sid), enabled, o.description, liveError(rt, o, sid)) { rt.setValue(o, sid, it) }
        } else {
            ZoneSelectField(label, rt.formString(o, sid), choices(rt, sid), enabled, o.description, liveError(rt, o, sid)) { rt.setValue(o, sid, it) }
        }
    }
    o.block()
    return o
}

private val numProto = Regex("^(0x[0-9a-f]{1,2}|[0-9]{1,3})$")

private fun protoKey(v: String): String {
    val m = numProto.find(v)
    val p = FwTool.lookupProto(if (m != null) FwTool.jsNumber(m.groupValues[1]).toLong() else v) ?: return v
    return if (p.num > -1) p.name else p.label
}

/** fwtool.CBIProtocolSelect */
fun FormSection.protoSelect(name: String, title: String, tab: String, default: String, block: MultiValueOption.() -> Unit = {}): MultiValueOption {
    val o = multi(name, title, tab = tab)
    o.default = default
    o.create = true
    fun rawValues(rt: FormRuntime, sid: String): List<String> =
        FwTool.toArray(rt.uci.get(FW, sid, name) ?: default).map { FwTool.jsString(it) }.filter { it.isNotEmpty() }.sorted()
    o.cfgvalueFn = { rt, sid ->
        val raw = rawValues(rt, sid)
        if (raw.size == 1 && raw[0] in setOf("*", "any", "all")) listOf("all") else raw.map(::protoKey).sorted()
    }
    o.choicesFn = { rt, sid ->
        val out = ArrayList<Pair<String, String>>()
        fun add(k: String, l: String) { if (out.none { it.first == k }) out += k to l }
        (listOf("all", "tcp", "udp", "icmp") + rawValues(rt, sid) + rt.formList(o, sid)).forEach { v ->
            when (v) {
                "all", "any", "*" -> add("all", "Any")
                "tcpudp" -> { add("tcp", "TCP"); add("udp", "UDP") }
                else -> {
                    val m = numProto.find(v)
                    FwTool.lookupProto(if (m != null) FwTool.jsNumber(m.groupValues[1]).toLong() else v)?.let { p -> add(p.name, p.label) }
                }
            }
        }
        out
    }
    val check: (String) -> String? = { v ->
        if (v == "all") null else {
            val m = numProto.find(v)
            if (if (m != null) FwTool.jsNumber(m.groupValues[1]) > 255 else (FwTool.lookupProto(v)?.num ?: -1) == -1) "Unrecognized protocol" else null
        }
    }
    o.validate = { _, _, v -> check(v) }
    o.render = { rt, sid ->
        ProtoSelectField(title, rt.formList(o, sid), o.choicesFor(rt, sid), !rt.isDisabled(o, sid), liveError(rt, o, sid), check) { rt.setValue(o, sid, it) }
    }
    o.block()
    return o
}

/** fwtool.addDSCPOption(s, is_target) */
fun FormSection.addDSCPOption(isTarget: Boolean): ValueOption = value(
    if (isTarget) "set_dscp" else "dscp",
    if (isTarget) "DSCP mark" else "Match DSCP",
    if (isTarget) "Apply the given DSCP class or value to established connections." else "Matches traffic carrying the specified DSCP marking.",
    tab = if (isTarget) "general" else "advanced",
) {
    modalonly = true
    rmempty = !isTarget
    placeholder = "any"
    if (isTarget) depends("target", "DSCP")
    listOf("CS0", "CS1", "CS2", "CS3", "CS4", "CS5", "CS6", "CS7", "BE", "AF11", "AF12", "AF13", "AF21", "AF22", "AF23",
        "AF31", "AF32", "AF33", "AF41", "AF42", "AF43", "EF").forEach { value(it) }
    validateEmpty = true
    validate = { _, _, value0 ->
        if (value0 == "") {
            if (isTarget) "DSCP mark required" else null
        } else {
            val v = if (!isTarget) value0.replace(Regex("^!\\s*"), "") else value0
            val m = Regex("^(?:CS[0-7]|BE|AF[1234][123]|EF|(0x[0-9a-f]{1,2}|[0-9]{1,2}))$").find(v)
            if (m == null || (m.groups[1] != null && FwTool.jsNumber(m.groupValues[1]) > 0x3f)) "Invalid DSCP mark" else null
        }
    }
}

/** fwtool.addMarkOption(s, is_target): 0 = match, 1 = set, 2 = xor */
fun FormSection.addMarkOption(isTarget: Int): ValueOption = value(
    if (isTarget > 1) "set_xmark" else if (isTarget == 1) "set_mark" else "mark",
    if (isTarget > 1) "XOR mark" else if (isTarget == 1) "Set mark" else "Match mark",
    if (isTarget > 1) "Apply a bitwise XOR of the given value and the existing mark value on established connections. Format is value[/mask]. If a mask is specified then those bits set in the mask are zeroed out."
    else if (isTarget == 1) "Set the given mark value on established connections. Format is value[/mask]. If a mask is specified then only those bits set in the mask are modified."
    else "Matches a specific firewall mark or a range of different marks.",
    tab = if (isTarget > 0) "general" else "advanced",
) {
    modalonly = true
    rmempty = true
    if (isTarget > 1) depends("target", "MARK_XOR") else if (isTarget == 1) depends("target", "MARK_SET")
    validateEmpty = true
    validate = { _, _, value0 ->
        if (value0 == "") {
            if (isTarget > 0) "Valid firewall mark required" else null
        } else {
            val v = if (isTarget == 0) value0.replace(Regex("^!\\s*"), "") else value0
            val m = Regex("^(0x[0-9a-f]{1,8}|[0-9]{1,10})(?:/(0x[0-9a-f]{1,8}|[0-9]{1,10}))?$", RegexOption.IGNORE_CASE).find(v)
            if (m == null || FwTool.jsNumber(m.groupValues[1]) > 0xffffffffL.toDouble() ||
                (m.groups[2] != null && FwTool.jsNumber(m.groupValues[2]) > 0xffffffffL.toDouble())
            ) "Expecting: valid firewall mark" else null
        }
    }
}

/** fwtool.addLimitOption(s) */
fun FormSection.addLimitOption(): ValueOption = value("limit", "Limit matching", "Limits traffic matching to the specified rate.", tab = "advanced") {
    modalonly = true
    rmempty = true
    placeholder = "unlimited"
    value("10/second"); value("60/minute"); value("3/hour"); value("500/day")
    // tools/firewall.js only checks the pattern; its unit loop does not affect the result.
    validate = { _, _, v ->
        if (v == "" || Regex("^(?:0x[0-9a-f]{1,8}|[0-9]{1,10})/([a-z]+)$").matches(v.lowercase())) null else "Invalid limit value"
    }
}

/** fwtool.addLimitBurstOption(s) */
fun FormSection.addLimitBurstOption(): ValueOption = value(
    "limit_burst", "Limit burst",
    "Maximum initial number of packets to match: this number gets recharged by one every time the limit specified above is not reached, up to this number.",
    tab = "advanced",
) {
    modalonly = true
    rmempty = true
    placeholder = "5"
    datatype = "uinteger"
    dependsNot("limit" to null)
}

/** CBIDynamicMultiValueList: an item with several space separated values becomes several items. */
private fun ChoiceOption.splitItemsOnWrite() {
    writeFn = { rt, sid, v ->
        rt.uci.set(FW, sid, option, FwTool.toArray(v).flatMap { FwTool.toArray(it) }.map { FwTool.jsString(it) })
    }
}

/**
 * fwtool.addIPOption(s, tab, name, label, description, family, hosts, multiple). The host hints follow
 * the section's "family" option when there is one (fwtool.updateHostHints in its validate()).
 */
fun FormSection.addIPOption(
    tab: String, name: String, label: String, description: String?, family: String?, fd: FwData, multiple: Boolean = false,
    block: ChoiceOption.() -> Unit = {},
): ChoiceOption {
    val o: ChoiceOption = if (multiple) dynlist(name, label, description, tab) else value(name, label, description, tab)
    o.modalonly = true
    o.datatype = if (fd.fw4) "list(neg(or(ipmask(\"true\"),iprange)))" else "list(neg(ipmask(\"true\")))"
    o.placeholder = if (multiple) "-- add IP --" else "any"
    if (family != null) {
        o.choicesFn = { rt, sid ->
            val fam = o.section.options.firstOrNull { it.option == "family" }
            FwTool.transformHostHints(if (fam != null) rt.formString(fam, sid) else family, fd.hosts)
        }
    }
    if (multiple) o.splitItemsOnWrite()
    o.block()
    return o
}

/** fwtool.addLocalIPOption(s, tab, name, label, description, devices) */
fun FormSection.addLocalIPOption(tab: String, name: String, label: String, description: String?, fd: FwData, block: ValueOption.() -> Unit = {}): ValueOption {
    val o = value(name, label, description, tab)
    o.modalonly = true
    o.datatype = if (!fd.fw4) "ip4addr(\"nomask\")" else "ipaddr(\"nomask\")"
    o.placeholder = "any"
    fd.devices.entries.mapNotNull { (k, v) -> v["name"].str?.let { k to it } }
        .sortedWith { a, b -> FwTool.naturalCompare(a.second, b.second) }
        .forEach { (dev, _) ->
            val d = fd.devices[dev]
            val flags = d["flags"].obj
            if (flags == null || flags["loopback"].bool == true) return@forEach
            d["ipaddrs"].arr?.forEach { a -> a["address"].str?.takeIf { it.isNotEmpty() }?.let { o.value(it, "$it ($dev)") } }
            if (fd.fw4) d["ip6addrs"].arr?.forEach { a -> a["address"].str?.takeIf { it.isNotEmpty() }?.let { o.value(it, "$it ($dev)") } }
        }
    o.block()
    return o
}

/** fwtool.addMACOption(s, tab, name, label, description, hosts) */
fun FormSection.addMACOption(tab: String, name: String, label: String, description: String?, fd: FwData, block: DynamicListOption.() -> Unit = {}): DynamicListOption {
    val o = dynlist(name, label, description, tab)
    o.modalonly = true
    o.datatype = "list(macaddr)"
    o.placeholder = "-- add MAC --"
    fd.hosts.keys.sortedWith { a, b -> FwTool.naturalCompare(a, b) }.forEach { mac ->
        val h = fd.hosts.getValue(mac)
        o.value(mac, "$mac (${h.name?.takeIf { it.isNotEmpty() } ?: h.ipaddrs.firstOrNull() ?: h.ip6addrs.firstOrNull() ?: "?"})")
    }
    o.splitItemsOnWrite()
    o.block()
    return o
}

/** The "Use ipset" option of forwards / rules / snats. */
fun FormSection.ipsetOption(uci: UciStore): ValueOption = value("ipset", "Use ipset", tab = "advanced") {
    uci.sections(FW, "ipset").forEach { s ->
        val n = s["name"] as? String ?: return@forEach
        val c = s["comment"] as? String
        value(n, if (!c.isNullOrEmpty()) "$n ($c)" else n)
    }
    modalonly = true
    rmempty = true
}

/** The "Match helper" option of forwards / rules. */
fun FormSection.matchHelperOption(fd: FwData): ValueOption =
    value("helper", "Match helper", "Match traffic using the specified connection tracking helper.", tab = "advanced") {
        modalonly = true
        placeholder = "any"
        fd.ctHelpers.forEach { value(it.name, "${it.description} (${it.name.uppercase()})") }
        validate = { _, _, v0 ->
            if (v0 == "") null else {
                val v = v0.replace(Regex("^!\\s*"), "")
                if (fd.ctHelpers.any { it.name == v }) null else "Unknown or not installed conntrack helper \"$v\""
            }
        }
    }

/** A grid column rendered from fmt() lines (the `_match` / `_target` / `_dest` columns). */
fun FormSection.richColumn(name: String, title: String, lines: (FormRuntime, String) -> List<FwTool.Fmt>): DummyValueOption = dummy(name, title) {
    modalonly = false
    editable = true
    textvalueFn = { rt, sid -> fmtPlain(lines(rt, sid)) }
    render = { rt, sid -> RichCell(title, lines(rt, sid)) }
}

/** s.cloneable = true: a "Clone" row action (handleClone with put_next). */
fun FormSection.cloneable() {
    rowActions += RowAction("Clone") { sid -> FwModel.cloneSection(this, sectionType, sid) }
}

/** fwtool.renderMigration(): shown instead of the view while "redirect" sections with target SNAT exist. */
fun migrationMap(title: String?, description: String?): FormMap {
    val m = FormMap(FW, title, description)
    m.hideSave = true
    m.hideSaveApply = true
    m.hideReset = true
    m.virtual("migration", "Firewall configuration migration") {
        dummy("_migration") {
            render = { rt, _ ->
                val scope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("The existing firewall configuration needs to be changed for LuCI to function properly.", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Upon pressing \"Continue\", \"redirect\" sections with target \"SNAT\" will be converted to \"nat\" sections and the firewall will be restarted to apply the updated configuration.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                try { FwModel.handleMigration(rt.ctx) } catch (e: Exception) { rt.ctx.toast(e.message ?: e.toString()) }
                                busy = false
                            }
                        },
                        enabled = !busy && !rt.isDisabled(this@dummy, ""),
                    ) { Text("Continue") }
                }
            }
        }
    }
    return m
}

/** "%s (%s)".format(description, NAME) labels of conntrack helper choices. */
fun helperLabel(h: CtHelper): String = "${h.description} (${h.name.uppercase()})"
