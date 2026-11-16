package com.the412banner.wrtpanel.pages.network.interfaces

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.the412banner.wrtpanel.core.UbusCall
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.int
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.long
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.pages.common.NetTools
import com.the412banner.wrtpanel.pages.network.proto.GenericProto
import com.the412banner.wrtpanel.pages.network.proto.ProtoHandler
import com.the412banner.wrtpanel.pages.network.proto.ProtoRegistry
import com.the412banner.wrtpanel.ui.PageContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** _state.netdevs entry of network.js. */
class DevState(
    val name: String,
    var idx: Long? = null,
    var flags: JsonObject? = null,
    var link: JsonObject? = null,
    var stats: JsonObject? = null,
    var macaddr: String? = null,
    var pse: JsonElement? = null,
    var devtype: String? = null,
    var mtu: Long? = null,
    var qlen: Long? = null,
    var wireless: Boolean = false,
    var parent: String? = null,
    val ipaddrs: MutableList<String> = mutableListOf(),
    val ip6addrs: MutableList<String> = mutableListOf(),
    var bridge: BridgeState? = null,
)

class BridgeState(val name: String, val id: String?, val stp: Boolean?, val ifnames: MutableList<DevState> = mutableListOf())

/** LuCI.naturalCompare (Intl.Collator with numeric collation). */
fun naturalCompare(a: String?, b: String?): Int {
    val x = a ?: ""
    val y = b ?: ""
    val re = Regex("\\d+|\\D+")
    val ca = re.findAll(x).map { it.value }.toList()
    val cb = re.findAll(y).map { it.value }.toList()
    for (i in 0 until minOf(ca.size, cb.size)) {
        val p = ca[i]
        val q = cb[i]
        val pn = p[0].isDigit()
        val qn = q[0].isDigit()
        val c = if (pn && qn) {
            val d = p.trimStart('0').length - q.trimStart('0').length
            if (d != 0) d else p.trimStart('0').compareTo(q.trimStart('0'))
        } else {
            val ci = p.lowercase().compareTo(q.lowercase())
            if (ci != 0) ci else -p.compareTo(q)
        }
        if (c != 0) return c
    }
    return ca.size - cb.size
}

private val ifacePatternsIgnore = listOf(
    "^wmaster\\d+", "^wifi\\d+", "^hwsim\\d+", "^imq\\d+", "^ifb\\d+", "^mon\\.wlan\\d+", "^sit\\d+", "^gre\\d+",
    "^gretap\\d+", "^ip6gre\\d+", "^ip6tnl\\d+", "^tunl\\d+", "^lo$",
).map { Regex(it) }

private val ifacePatternsWireless = listOf("^wlan\\d+", "^wl\\d+", "^ath\\d+", "^\\w+\\.network\\d+").map { Regex(it) }

private val protoErrors = mapOf(
    "CONNECT_FAILED" to "Connection attempt failed",
    "INVALID_ADDRESS" to "IP address is invalid",
    "INVALID_GATEWAY" to "Gateway address is invalid",
    "INVALID_LOCAL_ADDRESS" to "Local IP address is invalid",
    "MISSING_ADDRESS" to "IP address is missing",
    "MISSING_PEER_ADDRESS" to "Peer address is missing",
    "NO_DEVICE" to "Network device is not present",
    "NO_IFACE" to "Unable to determine device name",
    "NO_IFNAME" to "Unable to determine device name",
    "NO_WAN_ADDRESS" to "Unable to determine external IP address",
    "NO_WAN_LINK" to "Unable to determine upstream interface",
    "PEER_RESOLVE_FAIL" to "Unable to resolve peer host name",
    "PIN_FAILED" to "PIN code rejected",
)

/**
 * The parts of LuCI's network.js the interfaces view uses: netifd interface state, network
 * devices, bridges, wireless networks and the registered protocol handlers.
 */
class NetModel(val ctx: PageContext) {
    val uci: UciStore get() = ctx.uci
    var ifaces: List<JsonObject> = emptyList()
        private set
    val netdevs = LinkedHashMap<String, DevState>()
    val bridges = HashMap<String, BridgeState>()
    val isBridge = HashSet<String>()
    val isTunnel = HashSet<String>()
    val isSwitch = HashSet<String>()
    /** board.json switch name → port number → netdev. */
    val switches = HashMap<String, Map<Int, String>>()
    var radios: JsonObject? = null
        private set
    var dslModemType: String? = null
        private set
    var protoSpecs: Map<String, JsonObject> = emptyMap()
        private set
    /** Registered protocols (netifd handlers with an installed LuCI protocol view). */
    val protocols = LinkedHashMap<String, ProtoHandler>()
    private val virtualPatterns = mutableListOf<Regex>()
    /** Bumped after every refresh so status UI recomposes. */
    var version by mutableIntStateOf(0)
        private set
    /** network.getDevices() of the last refresh. */
    var devices: List<NetDevice> = emptyList()
        private set

    val hasWifi: Boolean get() = ctx.session.hasFeature("wifi")

    /** L.hasSystemFeature(name, sub). */
    fun hasFeature(name: String, sub: String? = null): Boolean {
        val f = ctx.session.features[name]
        if (sub != null) return (f as? JsonObject)?.get(sub).bool == true
        return ctx.session.hasFeature(name)
    }

    /** initNetworkState(): [full] also loads the protocol handlers. */
    suspend fun load(full: Boolean) {
        val calls = mutableListOf(
            UbusCall("network.interface", "dump"),
            UbusCall("luci-rpc", "getBoardJSON"),
            UbusCall("luci-rpc", "getNetworkDevices"),
        )
        if (hasWifi) calls += UbusCall("luci-rpc", "getWirelessDevices")
        val protoIdx = calls.size
        if (full) {
            calls += UbusCall("network", "get_proto_handlers")
            calls += UbusCall("file", "list", jsonOf("path" to "/www/luci-static/resources/protocol"))
        }
        val res = ctx.client.batch(calls)
        val dump = res[0].getOrNull()["interface"].arr?.mapNotNull { it.obj } ?: emptyList()
        val board = res[1].getOrNull().obj ?: JsonObject(emptyMap())
        val luciDevs = res[2].getOrNull().obj ?: JsonObject(emptyMap())
        val wl = if (hasWifi) res[3].getOrNull().obj else null
        if (full) {
            val protos = LinkedHashMap<String, JsonObject>()
            res[protoIdx].getOrNull().obj?.forEach { (k, v) -> protos[k] = v.obj ?: JsonObject(emptyMap()) }
            protos.remove("bonding")
            if (!protos.containsKey("none")) protos["none"] = JsonObject(mapOf("no_device" to JsonPrimitive(false)))
            if (!protos.containsKey("relay") && hasFeature("relayd")) protos["relay"] = JsonObject(mapOf("no_device" to JsonPrimitive(true)))
            protoSpecs = protos
            val js = res[protoIdx + 1].getOrNull()["entries"].arr?.mapNotNull { e -> e["name"].str?.takeIf { it.endsWith(".js") }?.removeSuffix(".js") }?.toSet()
            protocols.clear()
            virtualPatterns.clear()
            for (p in protos.keys) {
                if (js != null && p !in js) continue
                val h = ProtoRegistry.handlers[p] ?: GenericProto(p)
                protocols[p] = h
                h.virtualPattern?.let { virtualPatterns += it }
            }
        }
        uci.load(listOfNotNull("network", if (hasWifi) "wireless" else null, "luci"))
        buildState(dump, board, luciDevs, wl)
        devices = computeDevices()
        version++
    }

    private fun buildState(dump: List<JsonObject>, board: JsonObject, luciDevs: JsonObject, wl: JsonObject?) {
        ifaces = dump
        radios = wl
        netdevs.clear(); bridges.clear(); isBridge.clear(); isTunnel.clear(); isSwitch.clear(); switches.clear()
        for ((name, d) in luciDevs) {
            if (isVirtualIfname(name)) isTunnel += name
            if (name !in isTunnel && isIgnoredIfname(name)) continue
            val ds = DevState(
                name = name, idx = d["ifindex"].long, flags = d["flags"].obj, link = d["link"].obj, stats = d["stats"].obj,
                macaddr = d["mac"].str, pse = d["pse"], devtype = d["devtype"].str, mtu = d["mtu"].long, qlen = d["qlen"].long,
                wireless = d["wireless"].bool == true, parent = d["parent"].str,
            )
            d["ipaddrs"].arr?.forEach { ip -> ds.ipaddrs += "${ip["address"].str}/${ip["netmask"].str}" }
            d["ip6addrs"].arr?.forEach { ip -> ds.ip6addrs += "${ip["address"].str}/${ip["netmask"].str}" }
            netdevs[name] = ds
        }
        for ((name, d) in luciDevs) {
            if (d["bridge"].bool != true) continue
            val b = BridgeState(name, d["id"].str, d["stp"].bool)
            d["ports"].arr?.forEach { p ->
                val sub = netdevs[p.str ?: return@forEach] ?: return@forEach
                b.ifnames += sub
                sub.bridge = b
            }
            bridges[name] = b
            isBridge += name
        }
        for ((name, d) in luciDevs) {
            val parent = d["parent"].str ?: continue
            if (d["devtype"].str != "dsa") continue
            isSwitch += parent
            isSwitch += name
        }
        board["switch"].obj?.forEach { (swname, layout) ->
            val map = HashMap<Int, String>()
            layout["ports"].arr?.forEach { port ->
                val num = port["num"].int ?: return@forEach
                val dev = port["device"].str ?: return@forEach
                map[num] = dev
            }
            switches[swname] = map
        }
        dslModemType = board["dsl"]["modem"]["type"].str
    }

    fun isVirtualIfname(n: String) = virtualPatterns.any { it.containsMatchIn(n) }
    fun isIgnoredIfname(n: String) = ifacePatternsIgnore.any { it.containsMatchIn(n) }
    fun isWifiIfname(n: String) = ifacePatternsWireless.any { it.containsMatchIn(n) }
    fun isIgnoredDevice(n: String) = isIgnoredIfname(n)

    fun protoSpec(p: String?): JsonObject? = p?.let { protoSpecs[it] }

    // ---- wireless helpers (getWifi*) ----

    private fun radioInterfaces(): List<Triple<String, JsonObject, JsonObject>> {
        val out = ArrayList<Triple<String, JsonObject, JsonObject>>()
        radios?.forEach { (rn, rs) ->
            val ro = rs.obj ?: return@forEach
            ro["interfaces"].arr?.forEach { ns -> ns.obj?.let { out += Triple(rn, ro, it) } }
        }
        return out
    }

    fun getWifiStateBySid(sid: String): Triple<String, JsonObject, JsonObject>? {
        val s = uci.section("wireless", sid) ?: return null
        if (s[".type"] != "wifi-iface") return null
        for ((rn, rs, ns) in radioInterfaces()) {
            val sec = ns["section"].str ?: continue
            val s2 = uci.section("wireless", sec) ?: continue
            if (s[".type"] == s2[".type"] && s[".name"] == s2[".name"]) {
                if (s2[".anonymous"] == false && sec.startsWith("@")) return null
                return Triple(rn, rs, ns)
            }
        }
        return null
    }

    private fun getWifiStateByIfname(ifname: String): Triple<String, JsonObject, JsonObject>? =
        radioInterfaces().firstOrNull { it.third["ifname"].str == ifname }

    fun getWifiSidByNetid(netid: String): String? {
        val m = Regex("^(\\w+)\\.network(\\d+)$").find(netid) ?: return null
        var n = 0
        for (s in uci.sections("wireless", "wifi-iface")) {
            if (s["device"] != m.groupValues[1]) continue
            if (++n == m.groupValues[2].toInt()) return s[".name"] as? String
        }
        return null
    }

    fun getWifiSidByIfname(ifname: String): String? {
        getWifiSidByNetid(ifname)?.let { return it }
        val res = getWifiStateByIfname(ifname) ?: return null
        return res.third["section"].str
    }

    fun getWifiNetidBySid(sid: String): Pair<String, String>? {
        val s = uci.section("wireless", sid) ?: return null
        if (s[".type"] != "wifi-iface") return null
        val radio = s["device"] as? String ?: return null
        var n = 0
        for (sec in uci.sections("wireless", "wifi-iface")) {
            if (sec["device"] != radio) continue
            n++
            if (sec[".name"] != s[".name"]) continue
            return "$radio.network$n" to radio
        }
        return null
    }

    fun getWifiNetidByNetname(name: String): Pair<String, String>? {
        for (s in uci.sections("wireless", "wifi-iface")) {
            val net = s["network"] as? String ?: continue
            if (net.split(Regex("\\s+")).any { it == name }) return getWifiNetidBySid(s[".name"] as String)
        }
        return null
    }

    // ---- networks ----

    fun instantiateNetwork(name: String, proto: String? = null): NetIface {
        val p = proto ?: uci.getString("network", name, "proto") ?: "none"
        return NetIface(this, name, protocols[p])
    }

    fun instantiateDevice(name: String, network: NetIface? = null) = NetDevice(this, name, network)

    /** network.getNetworks(): uci interfaces plus netifd-only (dynamic) ones, sorted. */
    fun getNetworks(): List<NetIface> {
        val nets = LinkedHashMap<String, NetIface>()
        uci.sections("network", "interface").forEach { s -> val n = s[".name"] as String; nets[n] = instantiateNetwork(n) }
        for (st in ifaces) {
            val n = st["interface"].str ?: continue
            if (nets[n] == null) nets[n] = instantiateNetwork(n, st["proto"].str)
        }
        return nets.values.sortedWith { a, b -> naturalCompare(a.sid, b.sid) }
    }

    fun getNetwork(name: String): NetIface? {
        val s = uci.section("network", name)
        if (s != null && s[".type"] == "interface") return instantiateNetwork(name)
        ifaces.firstOrNull { it["interface"].str == name }?.let { return instantiateNetwork(name, it["proto"].str) }
        return null
    }

    private fun statusByRoute(addr: String, mask: Int): List<JsonObject> {
        val rv = ArrayList<JsonObject>()
        for (sif in ifaces) {
            val routes = sif["route"].arr ?: continue
            for (r in routes) {
                val t = r["target"].str ?: continue
                val m = r["mask"].int ?: continue
                if (r["table"] != null && r["table"].str != "0" && r["table"].str != "") continue
                if (t != addr || m != mask) continue
                rv += sif
            }
        }
        return rv.sortedWith { a, b ->
            val c = (a["metric"].long ?: 0L).compareTo(b["metric"].long ?: 0L)
            if (c != 0) c else naturalCompare(a["interface"].str, b["interface"].str)
        }
    }

    fun getWANNetworks(): List<NetIface> = statusByRoute("0.0.0.0", 0).mapNotNull { it["interface"].str?.let { n -> n to it["proto"].str } }
        .distinctBy { it.first }.map { instantiateNetwork(it.first, it.second) }

    fun getWAN6Networks(): List<NetIface> = statusByRoute("::", 0).mapNotNull { it["interface"].str?.let { n -> n to it["proto"].str } }
        .distinctBy { it.first }.map { instantiateNetwork(it.first, it.second) }

    // ---- devices ----

    private fun deviceSortKey(d: NetDevice) = when (d.getType()) { "wifi" -> 2; "alias" -> 3; else -> 1 }

    fun sortDevices(list: List<NetDevice>): List<NetDevice> = list.sortedWith { a, b ->
        val c = deviceSortKey(a) - deviceSortKey(b)
        if (c != 0) c else naturalCompare(a.getName(), b.getName())
    }

    /** network.getDevices() (it also completes bridge / switch state like network.js does). */
    private fun computeDevices(): List<NetDevice> {
        val devices = LinkedHashMap<String, NetDevice>()
        for (uif in uci.sections("network", "interface")) {
            for (ifn in UciStore.toArray(uif["ifname"])) {
                if (ifn.startsWith("@")) continue
                if (isIgnoredIfname(ifn) || isVirtualIfname(ifn) || isWifiIfname(ifn)) continue
                devices[ifn] = instantiateDevice(ifn)
            }
        }
        for ((ifname, st) in netdevs) {
            if (devices.containsKey(ifname)) continue
            if (isIgnoredIfname(ifname) || isWifiIfname(ifname)) continue
            if (st.wireless) continue
            devices[ifname] = instantiateDevice(ifname)
        }
        for (sw in uci.sections("network", "switch_vlan")) {
            val ports = sw["ports"] as? String ?: continue
            val swdev = sw["device"] as? String ?: continue
            val sws = switches[swdev] ?: continue
            for (p in ports.split(Regex("\\s+"))) {
                val m = Regex("^(\\d+)([tu]?)$").find(p) ?: continue
                val netdev = sws[m.groupValues[1].toInt()] ?: continue
                if (!devices.containsKey(netdev)) devices[netdev] = instantiateDevice(netdev)
                isSwitch += netdev
                if (m.groupValues[2] != "t") continue
                val vid = ((sw["vid"] ?: sw["vlan"]) as? String)?.toIntOrNull() ?: continue
                if (vid < 0 || vid > 4095) continue
                val vlandev = "$netdev.$vid"
                if (!devices.containsKey(vlandev)) devices[vlandev] = instantiateDevice(vlandev)
                isSwitch += vlandev
            }
        }
        for (bvl in uci.sections("network", "bridge-vlan")) {
            val basedev = bvl["device"] as? String ?: continue
            if (bvl["local"] == "0") continue
            val vid = (bvl["vlan"] as? String)?.toIntOrNull() ?: continue
            if (vid < 0 || vid > 4095) continue
            val vlandev = "$basedev.${(bvl["alias"] as? String) ?: vid}"
            isBridge += basedev
            val br = bridges.getOrPut(basedev) { BridgeState(basedev, null, null) }
            if (!devices.containsKey(vlandev)) devices[vlandev] = instantiateDevice(vlandev)
            for (spec in UciStore.toArray(bvl["ports"])) {
                val p = Regex("^([^:]+)(?::[ut*]+)?$").find(spec)?.groupValues?.get(1) ?: continue
                if (br.ifnames.any { it.name == p }) continue
                val nd = netdevs.getOrPut(p) { DevState(p, devtype = "ethernet") }
                br.ifnames += nd
                nd.bridge = br
            }
        }
        if (hasWifi) {
            val count = HashMap<String, Int>()
            for (wf in uci.sections("wireless", "wifi-iface")) {
                val dev = wf["device"] as? String ?: continue
                count[dev] = (count[dev] ?: 0) + 1
                val netid = "$dev.network${count[dev]}"
                devices[netid] = instantiateDevice(netid)
            }
        }
        for (d in uci.sections("network", "device")) {
            val type = d["type"] as? String
            val name = d["name"] as? String
            if (type.isNullOrEmpty() || name.isNullOrEmpty() || devices.containsKey(name)) continue
            if (type == "bridge") isBridge += name
            devices[name] = instantiateDevice(name)
        }
        return sortDevices(devices.values.toList())
    }

    fun getDevice(name: String?): NetDevice? {
        if (name == null) return null
        if (netdevs.containsKey(name)) return instantiateDevice(name)
        getWifiNetidBySid(name)?.let { return instantiateDevice(it.first) }
        return null
    }

    // ---- uci list helpers (appendValue / removeValue) ----

    fun appendValue(conf: String, sid: String, opt: String, value: String): Boolean {
        val cur = uci.get(conf, sid, opt)
        val isList = cur is List<*>
        val values = UciStore.toArray(cur).toMutableList()
        var rv = false
        if (value !in values) { values += value; rv = true }
        uci.set(conf, sid, opt, if (isList) values else values.joinToString(" "))
        return rv
    }

    fun removeValue(conf: String, sid: String, opt: String, value: String): Boolean {
        val cur = uci.get(conf, sid, opt)
        val isList = cur is List<*>
        val values = UciStore.toArray(cur).toMutableList()
        val rv = values.removeAll { it == value }
        if (values.isNotEmpty()) uci.set(conf, sid, opt, if (isList) values else values.joinToString(" "))
        else uci.unset(conf, sid, opt)
        return rv
    }

    /** network.deleteNetwork(): also drops routes, rules, DHCP pools, wifi and zone references. */
    suspend fun deleteNetwork(name: String): Boolean {
        uci.load(listOfNotNull("dhcp", "luci", "firewall", if (hasWifi) "wireless" else null))
        val s = uci.section("network", name) ?: return false
        if (s[".type"] != "interface") return false
        uci.remove("network", name)
        uci.sections("luci", "ifstate").filter { it["interface"] == name }.forEach { uci.remove("luci", it[".name"] as String) }
        uci.sections("network").forEach { sec ->
            val sid = sec[".name"] as String
            when (sec[".type"]) {
                "alias", "route", "route6" -> if (sec["interface"] == name) uci.remove("network", sid)
                "rule", "rule6" -> if (sec["in"] == name || sec["out"] == name) uci.remove("network", sid)
            }
        }
        uci.sections("wireless", "wifi-iface").forEach { sec ->
            val sid = sec[".name"] as String
            val old = UciStore.toArray(sec["network"])
            val nets = old.filter { it != name }
            if (nets.size == old.size) return@forEach
            if (nets.isNotEmpty()) uci.set("wireless", sid, "network", nets.joinToString(" ")) else uci.unset("wireless", sid, "network")
        }
        uci.sections("dhcp", "dhcp").filter { it["interface"] == name }.forEach { uci.remove("dhcp", it[".name"] as String) }
        Firewall.deleteNetwork(uci, name)
        return true
    }

    fun protoErrorText(code: String): String = protoErrors[code] ?: "Unknown error (%s)".replace("%s", code)
}

/** network.WifiNetwork (the parts devices and status need). */
class WifiNet(
    private val model: NetModel,
    val sid: String,
    val radioname: String?,
    private val radiostate: JsonObject?,
    private val netid: String?,
    private val netstate: JsonObject?,
) {
    private fun net(vararg path: String): JsonElement? {
        var v: JsonElement? = netstate
        for (p in path) v = v[p]
        return v
    }
    fun get(opt: String): String? = model.uci.getString("wireless", sid, opt)
    fun getMode(): String = net("config", "mode").str ?: get("mode") ?: "ap"
    fun getID(): String? = netid
    fun getIfname(): String? {
        var i = net("ifname").str ?: net("iwinfo", "ifname").str
        if (i == null || Regex("^(wifi|radio)\\d").containsMatchIn(i)) i = netid
        return i
    }
    fun getActiveMode(): String = when (val m = net("iwinfo", "mode").str ?: getMode()) {
        "ap" -> "Master"; "sta" -> "Client"; "adhoc" -> "Ad-Hoc"; "mesh" -> "Mesh Point"; "monitor" -> "Monitor"; else -> m
    }
    fun getActiveModeI18n(): String = when (val m = getActiveMode()) {
        "Master" -> "Access Point"; "Ad-Hoc" -> "Ad-Hoc"; "Client" -> "Client"; "Monitor" -> "Monitor"
        "Master(VLAN)" -> "Master (VLAN)"; "WDS" -> "WDS"; "Mesh Point" -> "Mesh Point"; "P2P Client" -> "P2P Client"
        "P2P Go" -> "P2P Go"; "Unknown" -> "Unknown"; else -> m
    }
    fun getActiveSSID(): String? = net("iwinfo", "ssid").str ?: net("config", "ssid").str ?: get("ssid")
    fun getActiveBSSID(): String? = net("iwinfo", "bssid").str ?: net("config", "bssid").str ?: get("bssid")
    fun getShortName(): String = "%s \"%s\"".format(getActiveModeI18n(), getActiveSSID() ?: getActiveBSSID() ?: getID() ?: "")
    fun getI18n(): String = "Wireless Network: %s \"%s\"".format(getActiveMode(), getActiveSSID() ?: getActiveBSSID() ?: getID() ?: "?")
}

/** network.Device */
class NetDevice(val model: NetModel, name: String, val network: NetIface?) {
    val wif: WifiNet?
    val device: String
    val dev: DevState?
    val config: Map<String, Any>

    init {
        val wsid = model.getWifiSidByIfname(name)
        var d: String? = null
        wif = if (wsid != null) {
            val res = model.getWifiStateBySid(wsid)
            val netid = model.getWifiNetidBySid(wsid)
            WifiNet(model, wsid, res?.first, res?.second, netid?.first, res?.third).also { d = it.getIfname() }
        } else null
        device = d ?: name
        dev = model.netdevs[device]
        config = model.uci.sections("network", "device").firstOrNull { it["name"] == name } ?: emptyMap()
    }

    fun getName(): String = wif?.getIfname() ?: device
    fun getMAC(): String? = dev?.macaddr?.uppercase()
    fun getMTU(): Long? = dev?.mtu
    fun getIPAddrs(): List<String> = dev?.ipaddrs ?: emptyList()
    fun getIP6Addrs(): List<String> = dev?.ip6addrs ?: emptyList()

    fun getType(): String = when {
        device.startsWith("@") -> "alias"
        dev?.devtype == "wlan" || wif != null || model.isWifiIfname(device) -> "wifi"
        dev?.devtype == "bridge" || device in model.isBridge -> "bridge"
        dev?.devtype == "wireguard" -> "wireguard"
        device in model.isTunnel -> "tunnel"
        dev?.devtype == "vlan" || device.contains('.') -> "vlan"
        dev?.devtype == "dsa" || device in model.isSwitch -> "switch"
        config["type"] == "8021q" || config["type"] == "8021ad" -> "vlan"
        config["type"] == "bridge" -> "bridge"
        config["type"] == "vrf" -> "vrf"
        else -> "ethernet"
    }

    fun getShortName(): String = wif?.getShortName() ?: device

    fun getI18n(): String = wif?.getI18n() ?: "%s: \"%s\"".format(getTypeI18n(), getName())

    fun getTypeI18n(): String = when (getType()) {
        "alias" -> "Alias Interface"
        "wifi" -> "Wireless Adapter"
        "bridge" -> "Bridge"
        "vrf" -> "Virtual Routing and Forwarding (VRF)"
        "switch" -> if (model.netdevs[device]?.devtype == "dsa") "Switch port" else "Ethernet Switch"
        "vlan" -> if (device in model.isSwitch) "Switch VLAN" else "Software VLAN"
        "wireguard" -> "WireGuard Interface"
        "tunnel" -> "Tunnel Interface"
        else -> "Ethernet Adapter"
    }

    fun getPorts(): List<NetDevice>? {
        val br = model.bridges[device] ?: return null
        return model.sortDevices(br.ifnames.map { model.instantiateDevice(it.name) })
    }

    fun isUp(): Boolean = dev?.flags?.get("up").bool ?: (getType() == "alias")
    fun isBridge() = getType() == "bridge"
    fun isBridgePort() = dev?.bridge != null
    fun getTXBytes(): Long = dev?.stats?.get("tx_bytes").long ?: 0
    fun getRXBytes(): Long = dev?.stats?.get("rx_bytes").long ?: 0
    fun getTXPackets(): Long = dev?.stats?.get("tx_packets").long ?: 0
    fun getRXPackets(): Long = dev?.stats?.get("rx_packets").long ?: 0
    fun getCarrier(): Boolean = dev?.link?.get("carrier").bool ?: false
    fun getSpeed(): Long? = dev?.link?.get("speed").long?.takeIf { it != 0L }
    fun getDuplex(): String? = dev?.link?.get("duplex").str?.takeIf { it != "unknown" }

    fun getNetworks(): List<NetIface> = model.getNetworks().filter { it.containsDevice(device) || it.getIfname() == device }

    fun getParent(): NetDevice? {
        dev?.parent?.let { return model.instantiateDevice(it) }
        val t = config["type"]
        if ((t == "8021q" || t == "802ad") && config["ifname"] is String) return model.instantiateDevice(config["ifname"] as String)
        return null
    }
}

/** network.Protocol: one logical interface; [handler] is null for unsupported protocols. */
class NetIface(val model: NetModel, val sid: String, val handler: ProtoHandler?) {
    private val uci get() = model.uci

    fun ubus(field: String): JsonElement? = model.ifaces.firstOrNull { it["interface"].str == sid }?.get(field)

    fun get(opt: String): Any? = uci.get("network", sid, opt)
    fun getString(opt: String): String = when (val v = get(opt)) { is List<*> -> v.joinToString(" "); null -> ""; else -> v.toString() }

    fun getName() = sid
    fun getProtocol(): String? = handler?.name

    fun getI18n(): String = handler?.i18n ?: "Unknown"

    fun isFloating(): Boolean = handler?.floating ?: false
    fun isVirtual(): Boolean = handler?.let { it.virtual ?: (model.protoSpec(it.name)?.get("no_device").bool == true) } ?: false

    fun getIfname(): String? {
        val h = handler
        if (h?.ifnamePrefix != null) return ubus("l3_device").str ?: "${h.ifnamePrefix}-$sid"
        val ifname = if (isFloating()) ubus("l3_device").str else ubus("device").str ?: ubus("l3_device").str
        if (ifname != null) return ifname
        return model.getWifiNetidByNetname(sid)?.first
    }

    fun getType(): String = getString("type")
    fun getUptime(): Long = ubus("uptime").long ?: 0
    fun isDynamic() = ubus("dynamic").bool == true
    fun isPending() = ubus("pending").bool == true
    fun isUp() = ubus("up").bool == true

    fun getIPAddrs(): List<String> = ubus("ipv4-address").arr?.map { "${it["address"].str}/${it["mask"].long}" } ?: emptyList()
    fun getIPAddr(): String? = ubus("ipv4-address").arr?.firstOrNull()?.get("address").str

    fun getIP6Addrs(): List<String> {
        val rv = LinkedHashSet<String>()
        ubus("ipv6-address").arr?.forEach { a -> a.obj?.let { rv += "${it["address"].str}/${it["mask"].long}" } }
        ubus("ipv6-prefix-assignment").arr?.forEach { a ->
            val la = a["local-address"].obj ?: return@forEach
            rv += "${la["address"].str}/${la["mask"].long}"
        }
        return rv.toList()
    }

    fun getIP6Addr(): String? = getIP6Addrs().firstOrNull()

    fun getIP6Prefixes(): List<String>? = ubus("ipv6-prefix").arr?.mapNotNull { p -> p.obj?.let { "${it["address"].str}/${it["mask"].long}" } }?.ifEmpty { null }

    fun getGatewayAddr(): String? = ubus("route").arr?.firstOrNull { it["target"].str == "0.0.0.0" && it["mask"].int == 0 }?.get("nexthop").str

    fun getErrors(): List<String>? {
        val errs = ubus("errors").arr ?: return null
        val rv = errs.mapNotNull { e -> e["code"].str?.let { model.protoErrorText(it) } }
        return rv.ifEmpty { null }
    }

    fun isBridge() = !isVirtual() && getType() == "bridge"

    fun isAlias(): String? {
        var parent: String? = null
        for (ifn in UciStore.toArray(get("device"))) {
            if (ifn.startsWith("@")) parent = ifn.substring(1) else if (parent != null) parent = null
        }
        return parent
    }

    fun getDevice(): NetDevice? {
        if (isVirtual()) {
            val ifname = "${getProtocol()}-$sid"
            model.isTunnel += ifname
            return model.instantiateDevice(ifname, this)
        }
        if (isBridge()) {
            val ifname = "br-$sid"
            model.isBridge += ifname
            return NetDevice(model, ifname, this)
        }
        val ifnames = UciStore.toArray(get("device"))
        if (ifnames.isNotEmpty()) {
            val m = Regex("^([^:/]+)").find(ifnames[0])?.groupValues?.get(1)
            return if (!m.isNullOrEmpty()) model.instantiateDevice(m, this) else null
        }
        return model.getWifiNetidByNetname(sid)?.let { model.instantiateDevice(it.first, this) }
    }

    fun getL2Device(): NetDevice? = ubus("device").str?.let { model.instantiateDevice(it, this) }
    fun getL3Device(): NetDevice? = ubus("l3_device").str?.let { model.instantiateDevice(it, this) }

    fun containsDevice(device0: String?): Boolean {
        val device = device0?.replace(Regex(":.+$"), "") ?: return false
        if (handler?.floating == true && handler.ifnamePrefix != null) return device == getIfname()
        if (isVirtual() && "${getProtocol()}-$sid" == device) return true
        if (isBridge() && "br-$sid" == device) return true
        (get("device") as? String)?.let { n ->
            val m = Regex("^([^:/]+)").find(n)?.groupValues?.get(1)
            if (m != null && m == device) return true
        }
        val wif = model.getWifiSidByIfname(device)
        if (wif != null) return UciStore.toArray(uci.get("wireless", wif, "network")).any { it == sid }
        return false
    }

    /** Protocol.addDevice */
    fun addDevice(device: String?): Boolean {
        val d = device?.replace(Regex(":.+$"), "")?.takeIf { it.isNotEmpty() } ?: return false
        if (isFloating()) return false
        val wif = model.getWifiSidByIfname(d)
        if (wif != null) return model.appendValue("wireless", wif, "network", sid)
        return model.appendValue("network", sid, "device", d)
    }
}

/** firewall.js helpers used by the view. */
object Firewall {
    fun zones(uci: UciStore): List<Pair<String, String?>> = uci.sections("firewall", "zone")
        .map { (it[".name"] as String) to (it["name"] as? String) }
        .sortedWith { a, b -> naturalCompare(a.second ?: "", b.second ?: "") }

    fun zoneNetworks(uci: UciStore, zsid: String): List<String> = UciStore.toArray(uci.get("firewall", zsid, "network"))

    /** getZoneByNetwork → (section id, name) of the first zone listing the network. */
    fun zoneByNetwork(uci: UciStore, network: String): Pair<String, String?>? = uci.sections("firewall", "zone")
        .firstOrNull { UciStore.toArray(it["network"]).contains(network) }?.let { (it[".name"] as String) to (it["name"] as? String) }

    fun lookupZone(uci: UciStore, name: String): String? {
        val z = uci.section("firewall", name)
        if (z != null && z[".type"] == "zone") return name
        return uci.sections("firewall", "zone").firstOrNull { it["name"] == name }?.get(".name") as? String
    }

    private fun parsePolicy(s: String?): String? {
        if (s.isNullOrEmpty()) return null
        val u = s.uppercase()
        return listOf("DROP", "REJECT", "ACCEPT").firstOrNull { it.startsWith(u) }
    }

    fun addZone(uci: UciStore, name: String): String? {
        if (!Regex("^[a-zA-Z0-9_]+$").matches(name)) return null
        if (lookupZone(uci, name) != null) return null
        val d = uci.firstSid("firewall", "defaults") ?: uci.add("firewall", "defaults")
        val z = uci.add("firewall", "zone")
        uci.set("firewall", z, "name", name)
        uci.set("firewall", z, "input", parsePolicy(uci.getString("firewall", d, "input")) ?: "DROP")
        uci.set("firewall", z, "output", parsePolicy(uci.getString("firewall", d, "output")) ?: "DROP")
        uci.set("firewall", z, "forward", parsePolicy(uci.getString("firewall", d, "forward")) ?: "DROP")
        return z
    }

    fun addNetwork(uci: UciStore, zsid: String, network: String): Boolean {
        val s = uci.section("network", network)
        if (s == null || s[".type"] != "interface") return false
        val nets = zoneNetworks(uci, zsid)
        if (nets.contains(network)) return false
        uci.set("firewall", zsid, "network", nets + network)
        return true
    }

    fun deleteNetworkFromZone(uci: UciStore, zsid: String, network: String): Boolean {
        val old = zoneNetworks(uci, zsid)
        val new = old.filter { it != network }
        uci.set("firewall", zsid, "network", new.ifEmpty { null })
        return new.size < old.size
    }

    fun deleteNetwork(uci: UciStore, network: String) {
        zones(uci).forEach { (zsid, _) -> deleteNetworkFromZone(uci, zsid, network) }
    }

    /** firewall.getColorForName */
    fun colorForName(name: String?): Long = when (name) {
        null -> 0xeeeeee
        "lan" -> 0x90f090
        "wan" -> 0xf09090
        else -> Prng.deriveColor(name)
    }
}

/** tools/prng.js derive_color with cbi.js sfh(). */
object Prng {
    private const val M32 = 0xFFFFFFFFL

    fun sfh(s: String): Long? {
        val bytes = s.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xff }
        if (bytes.isEmpty()) return null
        fun u16(off: Int): Long = (((bytes[off + 1] shl 8) + bytes[off]).toLong()) and M32
        fun s8(off: Int): Long { val n = bytes[off]; return if (n > 0x7f) (n - 256).toLong() and M32 else n.toLong() }
        var hash = bytes.size.toLong() and M32
        var len = bytes.size ushr 2
        var off = 0
        while (len-- > 0) {
            hash = (hash + u16(off)) and M32
            val tmp = (((u16(off + 2) shl 11) and M32) xor hash) and M32
            hash = (((hash shl 16) and M32) xor tmp) and M32
            hash = (hash + (hash ushr 11)) and M32
            off += 4
        }
        when (bytes.size and 3) {
            3 -> {
                hash = (hash + u16(off)) and M32
                hash = (hash xor ((hash shl 16) and M32)) and M32
                hash = (hash xor ((s8(off + 2) shl 18) and M32)) and M32
                hash = (hash + (hash ushr 11)) and M32
            }
            2 -> {
                hash = (hash + u16(off)) and M32
                hash = (hash xor ((hash shl 11) and M32)) and M32
                hash = (hash + (hash ushr 17)) and M32
            }
            1 -> {
                hash = (hash + s8(off)) and M32
                hash = (hash xor ((hash shl 10) and M32)) and M32
                hash = (hash + (hash ushr 1)) and M32
            }
        }
        hash = (hash xor ((hash shl 3) and M32)) and M32
        hash = (hash + (hash ushr 5)) and M32
        hash = (hash xor ((hash shl 4) and M32)) and M32
        hash = (hash + (hash ushr 17)) and M32
        hash = (hash xor ((hash shl 25) and M32)) and M32
        hash = (hash + (hash ushr 6)) and M32
        return hash
    }

    fun sfhHex(s: String): String = sfh(s)?.let { "%08x".format(it) } ?: ""

    private class Rng(seedVal: Long) {
        var st = LongArray(4)
        init {
            val n = (seedVal - 1).toInt()
            st[0] = (n and 0xffff).toLong()
            st[1] = ((n.toLong() and M32) ushr 16)
        }
        private fun mul(a: LongArray, b: LongArray): LongArray {
            val r = LongArray(8)
            for (j in 0 until 4) {
                var k = 0L
                for (i in 0 until 4) {
                    val t = a[i] * b[j] + r[i + j] + k
                    r[i + j] = t and 0xffff
                    k = (t and M32) ushr 16
                }
                r[j + 4] = k
            }
            return r.copyOf(4)
        }
        private fun add(a: LongArray, n: Long): LongArray {
            val r = LongArray(4)
            var k = n
            for (i in 0 until 4) {
                val t = a[i] + k
                r[i] = t and 0xffff
                k = (t and M32) ushr 16
            }
            return r
        }
        private fun shr(a: LongArray, n0: Int): LongArray {
            val r = longArrayOf(a[0], a[1], a[2], a[3], 0)
            var n = n0
            var i = 4
            var k = 0L
            while (n > 16) { for (j in 0 until 4) r[j] = r[j + 1]; n -= 16; i-- }
            while (i > 0) {
                val s = r[i - 1]
                r[i - 1] = (s ushr n) or k
                k = (s and ((1L shl n) - 1)) shl (16 - n)
                i--
            }
            return r.copyOf(4)
        }
        fun int(): Long {
            st = mul(st, longArrayOf(0x7f2d, 0x4c95, 0xf42d, 0x5851))
            st = add(st, 1)
            val r = shr(st, 33)
            return ((r[1] shl 16) or r[0]).toInt().toLong()
        }
        fun get(): Double = (int() % 0x7fffffff).toDouble() / 0x7fffffff
        fun get(upper: Int): Int = kotlin.math.floor(get() * upper).toInt() + 1
    }

    fun deriveColor(s: String): Long {
        val rng = Rng(sfh(s) ?: 0L)
        val r = rng.get(128)
        val g = rng.get(128)
        var min = 0
        var max = 128
        if (r + g < 128) min = 128 - r - g else max = 255 - r - g
        val b = min + kotlin.math.floor(rng.get() * (max - min)).toInt()
        return (((0xff - r) and 0xff).toLong() shl 16) or (((0xff - g) and 0xff).toLong() shl 8) or ((0xff - b) and 0xff).toLong()
    }
}

/** '%.2mB': SI scaled bytes like LuCI's String.format('%.2m'). */
fun fmtMetric(v: Long): String {
    var value = v.toDouble()
    val units = listOf(" ", " K", " M", " G", " T", " P", " E")
    var i = 0
    while (i < units.size && value > 1000) { value /= 1000; i++ }
    return if (i > 0) "%.2f%s".format(java.util.Locale.ROOT, value, units[i]) else "$v "
}

/** '%t' formatting of LuCI. */
fun fmtDuration(sec: Long): String {
    var ts = sec
    var tm = 0L
    var th = 0L
    var td = 0L
    if (ts > 59) { tm = ts / 60; ts %= 60 }
    if (tm > 59) { th = tm / 60; tm %= 60 }
    if (th > 23) { td = th / 24; th %= 24 }
    return if (td > 0) "${td}d ${th}h ${tm}m ${ts}s" else "${th}h ${tm}m ${ts}s"
}

/** network.prefixToMask for IPv4 (dotted). */
fun prefixToMask4(bits: Int): String? = NetTools.prefixToMask(bits, false)
