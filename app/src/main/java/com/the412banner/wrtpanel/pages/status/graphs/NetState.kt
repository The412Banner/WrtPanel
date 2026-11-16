package com.the412banner.wrtpanel.pages.status.graphs

import com.the412banner.wrtpanel.core.UbusCall
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.int
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.ui.PageContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The `values` of a `uci get` reply as ordered sections (what uci.js load() keeps). */
internal class UciConfig(values: JsonObject?) {
    val sections: List<Map<String, Any>> = (values?.values ?: emptyList())
        .mapNotNull { it.obj }
        .sortedBy { it[".index"].int ?: Int.MAX_VALUE }
        .map { s ->
            s.entries.associate { (k, v) ->
                k to when (v) {
                    is JsonArray -> v.mapNotNull { it.str }
                    is JsonPrimitive -> if (k == ".anonymous") (v.bool ?: false) else v.content
                    else -> v.toString()
                }
            }
        }

    fun sections(type: String): List<Map<String, Any>> = sections.filter { it[".type"] == type }

    /** uci.get(conf, sid) including the `@type[index]` form. */
    fun section(sid: String): Map<String, Any>? {
        val m = Regex("^@([^\\[]+)\\[(-?\\d+)\\]$").find(sid)
        if (m != null) {
            val list = sections(m.groupValues[1])
            val idx = m.groupValues[2].toInt()
            return list.getOrNull(if (idx < 0) list.size + idx else idx)
        }
        return sections.firstOrNull { it[".name"] == sid }
    }

    fun get(sid: String, opt: String): Any? = section(sid)?.get(opt)
}

/** L.naturalCompare (Intl.Collator with numeric: true), approximated for interface names. */
internal val NaturalOrder = Comparator<String> { a, b ->
    fun chunks(s: String) = Regex("\\d+|\\D+").findAll(s).map { it.value }.toList()
    fun rank(c: Char) = when { c.isDigit() -> 1; c.isLetter() -> 2; else -> 0 }
    val ca = chunks(a)
    val cb = chunks(b)
    for (i in 0 until minOf(ca.size, cb.size)) {
        val x = ca[i]
        val y = cb[i]
        val r = if (x[0].isDigit() && y[0].isDigit()) {
            val nx = x.trimStart('0').ifEmpty { "0" }
            val ny = y.trimStart('0').ifEmpty { "0" }
            if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
        } else {
            var c = 0
            for (k in 0 until minOf(x.length, y.length)) {
                val p = x[k]
                val q = y[k]
                c = if (rank(p) != rank(q)) rank(p) - rank(q) else p.lowercaseChar().compareTo(q.lowercaseChar())
                if (c != 0) break
            }
            if (c != 0) c else x.length - y.length
        }
        if (r != 0) return@Comparator r
    }
    if (ca.size != cb.size) ca.size - cb.size else a.compareTo(b)
}

/**
 * The parts of network.js initNetworkState() / getDevices() / getWifiDevices() / getWifiNetworks()
 * that the graph views use.
 */
internal class NetState(
    val network: UciConfig,
    val wireless: UciConfig,
    val netdevs: Map<String, JsonObject>,
    val radios: JsonObject,
    private val switches: Map<String, Map<String, String>>,
) {
    /** network.js WifiNetwork. */
    inner class WifiNet(val sid: String, val radioName: String?, val radioState: JsonElement?, val netid: String?, val netState: JsonElement?) {
        fun get(opt: String): String? = wireless.get(sid, opt)?.let { if (it is List<*>) it.joinToString(" ") else it.toString() }
        fun isDisabled(): Boolean = radioState["disabled"].bool == true || get("disabled") == "1"
        fun mode(): String = netState["config"]["mode"].str?.ifEmpty { null } ?: get("mode")?.ifEmpty { null } ?: "ap"
        fun ssid(): String? = if (mode() == "mesh") null else netState["config"]["ssid"].str?.ifEmpty { null } ?: get("ssid")?.ifEmpty { null }
        fun ifname(): String? {
            var n = netState["ifname"].str?.ifEmpty { null } ?: netState["iwinfo"]["ifname"].str?.ifEmpty { null }
            if (n == null || Regex("^(wifi|radio)\\d").containsMatchIn(n)) n = netid
            return n
        }
        fun deviceName(): String? = radioName ?: get("device")
    }

    /** network.js Device (only what the bandwidth view reads). */
    inner class NetDevice(name: String) {
        val wif: WifiNet?
        val device: String
        init {
            val wsid = wifiSidByIfname(name)
            if (wsid != null) {
                val res = wifiStateBySid(wsid)
                val netid = wifiNetidBySid(wsid)
                val w = WifiNet(wsid, res?.first, res?.second, netid?.first, res?.third)
                wif = w
                device = w.ifname() ?: name
            } else {
                wif = null
                device = name
            }
        }
        private val dev: JsonObject? get() = netdevs[device]
        fun getName(): String? = if (wif != null) wif.ifname() else device
        fun getType(): String = when {
            device.startsWith("@") -> "alias"
            dev["devtype"].str == "wlan" || wif != null || isWifiIfname(device) -> "wifi"
            else -> "ethernet"
        }
        fun isUp(): Boolean = dev["flags"]["up"].bool ?: (getType() == "alias")
    }

    private fun radioInterfaces(): List<Triple<String, JsonElement?, JsonElement>> =
        radios.entries.flatMap { (r, st) -> (st["interfaces"].arr ?: emptyList()).map { Triple(r, st, it) } }

    fun wifiStateBySid(sid: String): Triple<String, JsonElement?, JsonElement>? {
        val s = wireless.section(sid) ?: return null
        if (s[".type"] != "wifi-iface") return null
        for (t in radioInterfaces()) {
            val section = t.third["section"].str ?: continue
            val s2 = wireless.section(section) ?: continue
            if (s[".type"] == s2[".type"] && s[".name"] == s2[".name"]) {
                if (s2[".anonymous"] == false && section.startsWith("@")) return null
                return t
            }
        }
        return null
    }

    private fun wifiStateByIfname(ifname: String) = radioInterfaces().firstOrNull { it.third["ifname"].str == ifname }

    private fun wifiSidByNetid(netid: String): String? {
        val m = Regex("^(\\w+)\\.network(\\d+)$").find(netid) ?: return null
        var n = 0
        for (s in wireless.sections("wifi-iface")) {
            if (s["device"] != m.groupValues[1]) continue
            if (++n == m.groupValues[2].toInt()) return s[".name"] as? String
        }
        return null
    }

    private fun wifiSidByIfname(ifname: String): String? = wifiSidByNetid(ifname) ?: wifiStateByIfname(ifname)?.third["section"].str

    /** getWifiNetidBySid(): ("radio0.network1", "radio0"). */
    fun wifiNetidBySid(sid: String): Pair<String, String>? {
        val s = wireless.section(sid) ?: return null
        if (s[".type"] != "wifi-iface") return null
        val radio = s["device"] as? String ?: return null
        var n = 0
        for (sec in wireless.sections("wifi-iface")) {
            if (sec["device"] != radio) continue
            n++
            if (sec[".name"] != s[".name"]) continue
            return "$radio.network$n" to radio
        }
        return null
    }

    /** Network.lookupWifiNetwork(). */
    fun lookupWifiNetwork(netname: String): WifiNet {
        val sid0 = wifiSidByNetid(netname)
        if (sid0 != null) {
            val res = wifiStateBySid(sid0)
            return WifiNet(sid0, res?.first, res?.second, netname, res?.third)
        }
        wifiStateByIfname(netname)?.let { res ->
            val sid = res.third["section"].str ?: netname
            return WifiNet(sid, res.first, res.second, wifiNetidBySid(sid)?.first, res.third)
        }
        wifiStateBySid(netname)?.let { res ->
            return WifiNet(netname, res.first, res.second, wifiNetidBySid(netname)?.first, res.third)
        }
        val n = wifiNetidBySid(netname)
        return WifiNet(netname, n?.second, null, n?.first, null)
    }

    /** Network.getDevices(), sorted with deviceSort(). */
    fun getDevices(): List<NetDevice> {
        val devices = LinkedHashMap<String, NetDevice>()
        for (uif in network.sections("interface")) {
            for (ifn in UciStore.toArray(uif["ifname"])) {
                if (ifn.startsWith("@")) continue
                if (isIgnoredIfname(ifn) || isWifiIfname(ifn)) continue
                devices[ifn] = NetDevice(ifn)
            }
        }
        for ((name, dev) in netdevs) {
            if (devices.containsKey(name)) continue
            if (isIgnoredIfname(name) || isWifiIfname(name)) continue
            if (dev["wireless"].bool == true) continue
            devices[name] = NetDevice(name)
        }
        for (sw in network.sections("switch_vlan")) {
            val ports = sw["ports"] as? String ?: continue
            val swdev = sw["device"] as? String ?: continue
            val sdevs = switches[swdev] ?: continue
            for (p in ports.trim().split(Regex("\\s+"))) {
                val m = Regex("^(\\d+)([tu]?)$").find(p) ?: continue
                val netdev = sdevs[m.groupValues[1]] ?: continue
                devices.getOrPut(netdev) { NetDevice(netdev) }
                if (m.groupValues[2] != "t") continue
                val vidRaw = (sw["vid"] as? String)?.ifEmpty { null } ?: (sw["vlan"] as? String)?.ifEmpty { null } ?: continue
                val vid = vidRaw.toDoubleOrNull() ?: Double.NaN
                if (vid < 0 || vid > 4095) continue
                val vlandev = "%s.%s".format(netdev, LuciFmt.d(vid))
                devices.getOrPut(vlandev) { NetDevice(vlandev) }
            }
        }
        for (bvl in network.sections("bridge-vlan")) {
            val vid = (bvl["vlan"] as? String)?.toDoubleOrNull() ?: Double.NaN
            if (bvl["local"] == "0") continue
            if (vid.isNaN() || vid < 0 || vid > 4095) continue
            val alias = (bvl["alias"] as? String)?.ifEmpty { null }
            val vlandev = "%s.%s".format(bvl["device"] ?: "undefined", alias ?: LuciFmt.jsString(vid))
            devices.getOrPut(vlandev) { NetDevice(vlandev) }
        }
        val networkCount = mutableMapOf<String, Int>()
        for (wf in wireless.sections("wifi-iface")) {
            val radio = wf["device"] as? String ?: continue
            val n = (networkCount[radio] ?: 0) + 1
            networkCount[radio] = n
            val netid = "$radio.network$n"
            devices[netid] = NetDevice(netid)
        }
        for (d in network.sections("device")) {
            val type = (d["type"] as? String)?.ifEmpty { null }
            val name = (d["name"] as? String)?.ifEmpty { null }
            if (type == null || name == null || devices.containsKey(name)) continue
            devices[name] = NetDevice(name)
        }
        val weight = { d: NetDevice -> when (d.getType()) { "wifi" -> 2; "alias" -> 3; else -> 1 } }
        return devices.values.sortedWith(compareBy<NetDevice> { weight(it) }.thenComparator { a, b -> NaturalOrder.compare(a.getName() ?: "", b.getName() ?: "") })
    }

    /** Radio names in configuration order (getWifiDevices()). */
    fun wifiDeviceNames(): List<String> = wireless.sections("wifi-device").mapNotNull { it[".name"] as? String }

    /** WifiDevice.isDisabled(). */
    fun isRadioDisabled(radio: String): Boolean = radios[radio]["disabled"].bool == true || wireless.get(radio, "disabled") == "1"

    /** WifiDevice.getWifiNetworks(): Network.getWifiNetworks() (sorted by id) of [radio]. */
    fun wifiNetworks(radio: String): List<WifiNet> =
        wireless.sections("wifi-iface").mapNotNull { it[".name"] as? String }.map { lookupWifiNetwork(it) }
            .sortedWith { a, b -> NaturalOrder.compare(a.netid ?: "", b.netid ?: "") }
            .filter { it.deviceName() == radio }

    companion object {
        private val ignore = listOf(
            "^wmaster\\d+", "^wifi\\d+", "^hwsim\\d+", "^imq\\d+", "^ifb\\d+", "^mon\\.wlan\\d+", "^sit\\d+", "^gre\\d+",
            "^gretap\\d+", "^ip6gre\\d+", "^ip6tnl\\d+", "^tunl\\d+", "^lo$",
        ).map { Regex(it) }
        private val wifiNames = listOf("^wlan\\d+", "^wl\\d+", "^ath\\d+", "^\\w+\\.network\\d+").map { Regex(it) }

        fun isIgnoredIfname(n: String) = ignore.any { it.containsMatchIn(n) }
        fun isWifiIfname(n: String) = wifiNames.any { it.containsMatchIn(n) }

        /** initNetworkState(): the luci-rpc / uci calls whose results these views use, in one rpc batch. */
        suspend fun load(ctx: PageContext): NetState {
            val hasWifi = ctx.session.hasFeature("wifi")
            val calls = listOf(
                UbusCall("luci-rpc", "getBoardJSON"),
                UbusCall("luci-rpc", "getNetworkDevices"),
                UbusCall("luci-rpc", "getWirelessDevices"),
                UbusCall("uci", "get", jsonOf("config" to "network")),
            ) + (if (hasWifi) listOf(UbusCall("uci", "get", jsonOf("config" to "wireless"))) else emptyList())
            val r = ctx.client.batch(calls).map { it.getOrNull() }
            val board = r[0].obj
            val luciDevs = r[1].obj ?: JsonObject(emptyMap())
            val radios = r[2].obj ?: JsonObject(emptyMap())
            val network = UciConfig(r[3]["values"].obj)
            val wireless = UciConfig(if (hasWifi) r[4]["values"].obj else null)

            val netdevs = LinkedHashMap<String, JsonObject>()
            for ((name, dev) in luciDevs) {
                if (isIgnoredIfname(name)) continue
                dev.obj?.let { netdevs[name] = it }
            }
            val switches = mutableMapOf<String, Map<String, String>>()
            board["switch"].obj?.forEach { (sw, layout) ->
                val ports = layout["ports"].arr ?: return@forEach
                val map = mutableMapOf<String, String>()
                for (p in ports) {
                    val num = p["num"].int ?: continue
                    if (p["role"].str == null && p["device"].str == null) continue
                    p["device"].str?.let { map[num.toString()] = it }
                }
                switches[sw] = map
            }
            return NetState(network, wireless, netdevs, radios, switches)
        }
    }
}
