package com.the412banner.wrtpanel.pages.network.wireless

import com.the412banner.wrtpanel.core.UbusCall
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.double
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.ui.PageContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

private val EmptyObj = JsonObject(emptyMap())

/** JavaScript truthiness of a JSON value. */
internal fun jsTruthy(e: JsonElement?): Boolean = when (e) {
    null, is JsonNull -> false
    is JsonPrimitive -> when {
        e.isString -> e.content.isNotEmpty()
        e.booleanOrNull != null -> e.booleanOrNull == true
        else -> e.doubleOrNull?.let { it != 0.0 && !it.isNaN() } ?: true
    }
    else -> true
}

internal fun firstTruthy(vararg es: JsonElement?): JsonElement? = es.firstOrNull { jsTruthy(it) }

internal fun truthy(s: String?): String? = s?.takeIf { it.isNotEmpty() }

/** A number as JavaScript prints it (no ".0" for integers). */
internal fun jsNum(d: Double): String =
    if (!d.isNaN() && !d.isInfinite() && d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

/** Intl.Collator({ numeric: true }) compare, as L.naturalCompare. */
internal fun naturalCompare(a: String?, b: String?): Int {
    val x = a ?: ""; val y = b ?: ""
    val re = Regex("\\d+|\\D+")
    val pa = re.findAll(x).map { it.value }.toList()
    val pb = re.findAll(y).map { it.value }.toList()
    for (i in 0 until minOf(pa.size, pb.size)) {
        val s = pa[i]; val t = pb[i]
        val c = if (s[0].isDigit() && t[0].isDigit()) {
            val n = s.trimStart('0').ifEmpty { "0" }; val m = t.trimStart('0').ifEmpty { "0" }
            if (n.length != m.length) n.length - m.length else n.compareTo(m)
        } else s.compareTo(t, ignoreCase = true).let { if (it == 0) s.compareTo(t) else it }
        if (c != 0) return c
    }
    return pa.size - pb.size
}

private val wirelessIfnamePatterns = listOf(Regex("^wlan\\d+"), Regex("^wl\\d+"), Regex("^ath\\d+"), Regex("^\\w+\\.network\\d+"))
private val virtualProtos = setOf("6in4", "6rd", "6to4", "dslite", "gre", "gretap", "grev6", "grev6tap", "ipip", "ipip6", "map", "vti", "vtiv6", "xfrm", "wireguard", "pptp", "l2tp", "openvpn", "relay")

/** network.js state from initNetworkState(): radios, host hints, devices, netifd interfaces, hostapd objects. */
internal class WifiState(
    val uci: UciStore,
    val radios: JsonObject,
    val hosts: JsonObject,
    val netdevs: JsonObject,
    val ifaces: List<JsonElement>,
    val hostapd: Map<String, JsonObject>,
) {
    fun section(sid: String?): Map<String, Any>? = sid?.let { uci.section("wireless", it) }

    fun sidByNetid(netid: String): String? {
        val m = Regex("^(\\w+)\\.network(\\d+)$").find(netid) ?: return null
        var n = 0
        for (s in uci.sections("wireless", "wifi-iface")) {
            if (s["device"] != m.groupValues[1]) continue
            if (++n == m.groupValues[2].toInt()) return s[".name"] as? String
        }
        return null
    }

    fun stateBySid(sid: String): Triple<String, JsonElement?, JsonElement>? {
        val s = section(sid) ?: return null
        if (s[".type"] != "wifi-iface") return null
        for ((radioname, radio) in radios) {
            for (netstate in radio["interfaces"].arr ?: emptyList()) {
                val sec = (netstate["section"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val s2 = section(sec) ?: continue
                if (s[".type"] == s2[".type"] && s[".name"] == s2[".name"]) {
                    if (s2[".anonymous"] == false && sec.startsWith("@")) return null
                    return Triple(radioname, radio, netstate)
                }
            }
        }
        return null
    }

    fun stateByIfname(ifname: String): Triple<String, JsonElement?, JsonElement>? {
        for ((radioname, radio) in radios) {
            for (netstate in radio["interfaces"].arr ?: emptyList()) {
                if (netstate["ifname"].str == ifname) return Triple(radioname, radio, netstate)
            }
        }
        return null
    }

    fun sidByIfname(ifname: String): String? = sidByNetid(ifname) ?: stateByIfname(ifname)?.third?.get("section")?.str

    /** [netid, radio] of a wifi-iface section, like getWifiNetidBySid(). */
    fun netidBySid(sid: String): Pair<String, String>? {
        val s = section(sid) ?: return null
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

    fun netidByNetname(name: String): Pair<String, String>? {
        for (s in uci.sections("wireless", "wifi-iface")) {
            val nets = s["network"] as? String ?: continue
            if (nets.split(Regex("\\s+")).any { it == name }) return netidBySid(s[".name"] as String)
        }
        return null
    }

    /** Network.lookupWifiNetwork() */
    fun lookupNet(netname: String): WifiNet {
        var sid: String? = sidByNetid(netname)
        var netid: String? = null
        var radioname: String? = null
        var radiostate: JsonElement? = null
        var netstate: JsonElement? = null
        if (sid != null) {
            val r = stateBySid(sid)
            netid = netname; radioname = r?.first; radiostate = r?.second; netstate = r?.third
        } else {
            val r = stateByIfname(netname)
            if (r != null) {
                radioname = r.first; radiostate = r.second; netstate = r.third
                sid = netstate["section"].str
                netid = sid?.let { netidBySid(it)?.first }
            } else {
                val r2 = stateBySid(netname)
                if (r2 != null) {
                    radioname = r2.first; radiostate = r2.second; netstate = r2.third
                    sid = netname; netid = netidBySid(netname)?.first
                } else {
                    netidBySid(netname)?.let { netid = it.first; radioname = it.second; sid = netname }
                }
            }
        }
        val hap = netstate?.get("ifname")?.str?.let { hostapd[it] }
        return WifiNet(this, sid ?: netname, netid, radioname, radiostate, netstate, hap)
    }

    /** Network.getWifiDevices(): radios in configuration order. */
    fun radioList(): List<WifiRadio> = uci.sections("wireless", "wifi-device").mapNotNull { s -> (s[".name"] as? String)?.let { WifiRadio(this, it) } }

    /** Network.getWifiNetworks(), sorted by network id. */
    fun netList(): List<WifiNet> =
        uci.sections("wireless", "wifi-iface").mapNotNull { s -> (s[".name"] as? String)?.let { lookupNet(it) } }
            .sortedWith { a, b -> naturalCompare(a.netid, b.netid) }

    fun hostName(mac: String): String? = hosts[mac]?.get("name")?.str?.takeIf { it.isNotEmpty() }
    fun hostIp4(mac: String): String? = hostAddrs(mac, "ipaddrs", "ipv4").firstOrNull()?.takeIf { it.isNotEmpty() }
    fun hostIp6(mac: String): String? = hostAddrs(mac, "ip6addrs", "ipv6").firstOrNull()?.takeIf { it.isNotEmpty() }

    private fun hostAddrs(mac: String, a: String, b: String): List<String> {
        val h = hosts[mac] ?: return emptyList()
        val v = firstTruthy(h[a], h[b]) ?: return emptyList()
        return v.arr?.mapNotNull { it.str } ?: listOfNotNull(v.str)
    }

    /** Hosts.getMACHints() */
    fun macHints(): List<Pair<String, String?>> = hosts.keys.map { mac ->
        mac to (hostName(mac) ?: hostAddrs(mac, "ipaddrs", "ipv4").firstOrNull() ?: hostAddrs(mac, "ip6addrs", "ipv6").firstOrNull())
    }.sortedWith { a, b -> naturalCompare(a.first, b.first) }

    // ---- Device helpers (network.js Device) ----

    fun deviceConfig(name: String): Map<String, Any>? = uci.sections("network", "device").firstOrNull { it["name"] == name }

    fun deviceType(name: String): String {
        val dev = netdevs[name]
        val cfg = deviceConfig(name)
        return when {
            name.startsWith("@") -> "alias"
            dev["devtype"].str == "wlan" || sidByIfname(name) != null || wirelessIfnamePatterns.any { it.containsMatchIn(name) } -> "wifi"
            dev["devtype"].str == "bridge" || dev["bridge"].bool == true -> "bridge"
            dev["devtype"].str == "wireguard" -> "wireguard"
            dev["devtype"].str == "vlan" || name.contains('.') -> "vlan"
            dev["devtype"].str == "dsa" -> "switch"
            cfg?.get("type") == "8021q" || cfg?.get("type") == "8021ad" -> "vlan"
            cfg?.get("type") == "bridge" -> "bridge"
            cfg?.get("type") == "vrf" -> "vrf"
            else -> "ethernet"
        }
    }

    fun deviceParent(name: String): String? {
        netdevs[name]?.get("parent")?.str?.let { return it }
        val cfg = deviceConfig(name) ?: return null
        if ((cfg["type"] == "8021q" || cfg["type"] == "802ad") && cfg["ifname"] is String) return cfg["ifname"] as String
        return null
    }

    // ---- Protocol helpers ----

    fun networkExists(name: String): Boolean =
        uci.section("network", name)?.get(".type") == "interface" || ifaces.any { it["interface"].str == name }

    fun networkProto(name: String): String? =
        uci.getString("network", name, "proto") ?: ifaces.firstOrNull { it["interface"].str == name }?.get("proto")?.str

    fun networkIsVirtual(name: String): Boolean = networkProto(name) in virtualProtos

    /** Protocol.isEmpty() */
    fun networkIsEmpty(name: String): Boolean {
        if (networkIsVirtual(name)) return false
        val dev = uci.getString("network", name, "device")
        if (dev != null && Regex("\\S+").containsMatchIn(dev)) return false
        if (netidBySid(name) != null) return false
        return true
    }

    /** Protocol.getDevice() name and type. */
    fun networkDevice(name: String): String? {
        if (networkIsVirtual(name)) return "${networkProto(name)}-$name"
        if (uci.getString("network", name, "type") == "bridge") return "br-$name"
        val ifnames = UciStore.toArray(uci.get("network", name, "device"))
        for (ifn in ifnames) return Regex("^([^:/]+)").find(ifn)?.groupValues?.get(1)
        return netidByNetname(name)?.first
    }

    fun networkDeviceType(name: String, dev: String): String = when {
        networkIsVirtual(name) -> "tunnel"
        dev == "br-$name" && uci.getString("network", name, "type") == "bridge" -> "bridge"
        else -> deviceType(dev)
    }

    /** All logical interfaces (enumerateNetworks()). */
    fun networkNames(): List<String> {
        val names = LinkedHashSet<String>()
        uci.sections("network", "interface").forEach { s -> (s[".name"] as? String)?.let { names += it } }
        ifaces.forEach { i -> i["interface"].str?.let { names += it } }
        return names.sortedWith { a, b -> naturalCompare(a, b) }
    }
}

/** network.js WifiDevice */
internal class WifiRadio(val st: WifiState, val sid: String) {
    val dev: JsonElement? get() = st.radios[sid]
    val name: String get() = sid
    fun get(opt: String): String? = st.uci.getString("wireless", sid, opt)
    val isUp: Boolean get() = dev is JsonObject && dev["up"].bool == true
    val hwModes: List<String> get() = dev["iwinfo"]["hwmodes"].arr?.mapNotNull { it.str } ?: listOf("b", "g")
    val htModes: List<String>? get() = dev["iwinfo"]["htmodes"].arr?.mapNotNull { it.str }?.ifEmpty { null }
    val i18n: String
        get() {
            val hw = dev["iwinfo"]["hardware"]
            var type = if (hw is JsonObject) truthy(hw["name"].str) else null
            val modes = truthy(dev["iwinfo"]["hwmodes_text"].str)
            if (dev["iwinfo"]["type"].str == "wl") type = "Broadcom"
            return "%s %s Wireless Controller (%s)".format(type ?: "Generic", if (modes != null) "802.11$modes" else "unknown", name)
        }
    fun networks(all: List<WifiNet>): List<WifiNet> = all.filter { it.wifiDeviceName == name }
}

internal class WifiVlan(val ifname: String?, val vid: String?, val name: String?, val network: String?) {
    val i18n: String get() = "vlan %s%s".format(vid ?: "", if (name != null && name != vid) " ($name)" else "")
}

/** network.js WifiNetwork */
internal class WifiNet(
    val st: WifiState,
    val sid: String,
    val netid: String?,
    private val radioName: String?,
    val dev: JsonElement?,
    val net: JsonElement?,
    val hostapd: JsonObject?,
) {
    val name: String get() = sid
    fun get(opt: String): String? = st.uci.getString("wireless", sid, opt)
    private fun cfg(k: String): JsonElement? = net["config"][k]
    private fun iw(k: String): JsonElement? = net["iwinfo"][k]

    val mode: String get() = firstTruthy(cfg("mode"))?.str ?: truthy(get("mode")) ?: "ap"
    val ssid: String? get() = if (mode == "mesh") null else firstTruthy(cfg("ssid"))?.str ?: truthy(get("ssid"))
    val meshId: String? get() = if (mode != "mesh") null else firstTruthy(cfg("mesh_id"))?.str ?: truthy(get("mesh_id"))
    val networkNames: List<String> get() = firstTruthy(cfg("network"))?.let { e -> e.arr?.mapNotNull { it.str } ?: UciStore.toArray(e.str) } ?: UciStore.toArray(st.uci.get("wireless", sid, "network"))
    val ifname: String?
        get() {
            val n = firstTruthy(net["ifname"], iw("ifname"))?.str
            return if (n == null || Regex("^(wifi|radio)\\d").containsMatchIn(n)) netid else n
        }
    val wifiDeviceName: String? get() = truthy(radioName) ?: get("device")
    val isUp: Boolean get() = ifname?.let { n -> if (n.startsWith("@")) true else st.netdevs[n]["flags"]["up"].bool == true } ?: false
    val activeMode: String
        get() = when (val m = firstTruthy(iw("mode"))?.str ?: mode) {
            "ap" -> "Master"; "sta" -> "Client"; "adhoc" -> "Ad-Hoc"; "mesh" -> "Mesh Point"; "monitor" -> "Monitor"
            else -> m
        }
    val activeModeI18n: String
        get() = when (val m = activeMode) {
            "Master" -> "Access Point"; "Ad-Hoc" -> "Ad-Hoc"; "Client" -> "Client"; "Monitor" -> "Monitor"
            "Master(VLAN)" -> "Master (VLAN)"; "WDS" -> "WDS"; "Mesh Point" -> "Mesh Point"; "P2P Client" -> "P2P Client"
            "P2P Go" -> "P2P Go"; "Unknown" -> "Unknown"
            else -> m
        }
    val activeSSID: String? get() = firstTruthy(iw("ssid"), cfg("ssid"))?.str ?: truthy(get("ssid"))
    val activeBSSID: String? get() = firstTruthy(iw("bssid"), cfg("bssid"))?.str ?: truthy(get("bssid"))
    val activeEncryption: String get() = formatWifiEncryption(iw("encryption")) ?: "-"
    val frequency: String? get() = iw("frequency").double?.takeIf { it > 0 }?.let { "%.3f".format(java.util.Locale.ROOT, it / 1000) }
    val bitRate: String? get() = iw("bitrate").double?.takeIf { it > 0 }?.let { jsNum(it / 1000) }
    val channel: String? get() = firstTruthy(iw("channel"), dev["config"]["channel"])?.str ?: truthy(get("channel"))
    val signal: Double get() = firstTruthy(iw("signal"))?.double ?: 0.0
    val noise: Double get() = firstTruthy(iw("noise"))?.double ?: 0.0
    val countryCode: String get() = firstTruthy(iw("country"), dev["config"]["country"])?.str ?: "00"
    val txPower: Double? get() = iw("txpower").double
    val txPowerOffset: Double get() = firstTruthy(iw("txpower_offset"))?.double ?: 0.0
    val signalPercent: Int
        get() {
            val qc = firstTruthy(iw("quality"))?.double ?: 0.0
            val qm = firstTruthy(iw("quality_max"))?.double ?: 0.0
            return if (qc > 0 && qm > 0) Math.floor((100 / qm) * qc).toInt() else 0
        }
    private val label: String get() = activeSSID ?: activeBSSID ?: netid ?: ""
    val shortName: String get() = "%s \"%s\"".format(activeModeI18n, label)
    val i18n: String get() = "%s: %s \"%s\" (%s)".format("Wireless Network", activeModeI18n, label, ifname)
    val vlans: List<WifiVlan>
        get() = net["vlans"].arr?.map { v ->
            val c = v["config"]
            WifiVlan(v["ifname"].str, c["vid"].str, c["name"].str, c["network"].arr?.firstOrNull()?.str)
        } ?: emptyList()
    val isClientDisconnectSupported: Boolean get() = hostapd?.get("del_client") is JsonObject
}

/** network.formatWifiEncryption() */
internal fun formatWifiEncryption(enc: JsonElement?): String? {
    if (enc !is JsonObject) return null
    if (!jsTruthy(enc["enabled"])) return "None"
    val ciphers = enc["ciphers"].arr?.mapNotNull { it.str?.uppercase() } ?: listOf("NONE")
    enc["wep"].arr?.let { wep ->
        val open = wep.any { it.str == "open" }
        val shared = wep.any { it.str == "shared" }
        return when {
            open && shared -> "WEP Open/Shared (%s)".format(ciphers.joinToString(", "))
            open -> "WEP Open System (%s)".format(ciphers.joinToString(", "))
            shared -> "WEP Shared Auth (%s)".format(ciphers.joinToString(", "))
            else -> "WEP"
        }
    }
    enc["wpa"].arr?.let { wpa ->
        val suites = enc["authentication"].arr?.mapNotNull { it.str?.uppercase() } ?: listOf("NONE")
        val versions = wpa.map { v -> if (v.str == "1") "WPA" else "WPA${v.str}" }
        return if (versions.size > 1) "mixed %s %s (%s)".format(versions.joinToString("/"), suites.joinToString(", "), ciphers.joinToString(", "))
        else "%s %s (%s)".format(versions.firstOrNull() ?: "undefined", suites.joinToString(", "), ciphers.joinToString(", "))
    }
    return "Unknown"
}

/** format_wifirate() of wireless.js */
internal fun formatWifiRate(rate: JsonElement?): String {
    fun t(k: String) = jsTruthy(rate[k])
    fun v(k: String) = rate[k]?.let { if (it is JsonNull) "null" else it.str } ?: "undefined"
    val r = rate["rate"].double?.let { jsNum(it / 1000) } ?: "NaN"
    var s = "$r Mbit/s, ${v("mhz")} MHz"
    if (t("ht") || t("vht")) s += listOfNotNull(
        if (t("vht")) ", VHT-MCS ${v("mcs")}" else null,
        if (t("nss")) ", VHT-NSS ${v("nss")}" else null,
        if (t("ht")) ", MCS ${v("mcs")}" else null,
        if (t("short_gi")) ", " + "Short GI".replace(" ", " ") else null,
    ).joinToString("")
    if (t("he")) s += listOfNotNull(
        ", HE-MCS ${v("mcs")}",
        if (t("nss")) ", HE-NSS ${v("nss")}" else null,
        if (t("he_gi")) ", HE-GI ${v("he_gi")}" else null,
        if (t("he_dcm")) ", HE-DCM ${v("he_dcm")}" else null,
    ).joinToString("")
    if (t("eht")) s += listOfNotNull(
        ", EHT-MCS ${v("mcs")}",
        if (t("nss")) ", EHT-NSS ${v("nss")}" else null,
        if (t("eht_gi")) ", EHT-GI ${v("eht_gi")}" else null,
        if (t("eht_dcm")) ", EHT-DCM ${v("eht_dcm")}" else null,
    ).joinToString("")
    return s
}

/** An associated station with its radio / network (poll data[3]). */
internal class Station(val radio: WifiRadio?, val net: WifiNet, val vlan: WifiVlan?, val data: JsonElement) {
    val mac: String get() = data["mac"].str ?: ""
    val signal: Double get() = data["signal"].double ?: 0.0
    val noise: Double? get() = data["noise"].double
}

/** One poll: radios, networks (with runtime state) and associated stations. */
internal class WifiSnapshot(val st: WifiState, val radios: List<WifiRadio>, val nets: List<WifiNet>, val stations: List<Station>)

/** initNetworkState(true): netifd interfaces, devices, radios, host hints and the hostapd objects. */
internal suspend fun loadWifiState(ctx: PageContext): WifiState {
    val res = ctx.client.batch(
        listOf(
            UbusCall("network.interface", "dump"),
            UbusCall("luci-rpc", "getNetworkDevices"),
            UbusCall("luci-rpc", "getWirelessDevices"),
            UbusCall("luci-rpc", "getHostHints"),
        ),
    )
    val ifaces = res[0].getOrNull()["interface"].arr ?: emptyList()
    val netdevs = res[1].getOrNull().obj ?: EmptyObj
    val radios = res[2].getOrNull().obj ?: EmptyObj
    val hosts = res[3].getOrNull().obj ?: EmptyObj
    val objects = radios.values.flatMap { r -> r["interfaces"].arr?.mapNotNull { it["ifname"].str } ?: emptyList() }.map { "hostapd.$it" }
    val hostapd = if (objects.isEmpty()) emptyMap() else runCatching { ctx.client.list(objects) }.getOrNull()?.entries?.mapNotNull { (k, v) ->
        Regex("^hostapd\\.(.+)$").find(k)?.let { it.groupValues[1] to (v.obj ?: EmptyObj) }
    }?.toMap() ?: emptyMap()
    return WifiState(ctx.uci, radios, hosts, netdevs, ifaces, hostapd)
}

/** The poll of wireless.js: state, networks and the association lists. */
internal suspend fun loadWifiSnapshot(ctx: PageContext): WifiSnapshot {
    val st = loadWifiState(ctx)
    val radios = st.radioList().sortedBy { it.name }
    val nets = st.netList()
    val calls = ArrayList<UbusCall>()
    val owners = ArrayList<Triple<WifiNet, WifiVlan?, WifiRadio?>>()
    for (n in nets) {
        val radio = radios.firstOrNull { it.name == n.wifiDeviceName }
        for (v in n.vlans) {
            calls += UbusCall("iwinfo", "assoclist", jsonOf("device" to v.ifname))
            owners += Triple(n, v, radio)
        }
        calls += UbusCall("iwinfo", "assoclist", jsonOf("device" to n.ifname))
        owners += Triple(n, null, radio)
    }
    val res = if (calls.isEmpty()) emptyList() else ctx.client.batch(calls)
    val stations = ArrayList<Station>()
    res.forEachIndexed { i, r ->
        val (n, v, radio) = owners[i]
        r.getOrNull()["results"].arr?.forEach { stations += Station(radio, n, v, it) }
    }
    return WifiSnapshot(st, radios, nets, stations)
}

/** next_free_sid() */
internal fun nextFreeSid(uci: UciStore, offset0: Int): String {
    var offset = offset0
    var sid = "wifinet$offset"
    while (uci.section("wireless", sid) != null) sid = "wifinet${++offset}"
    return sid
}

/** network.js appendValue() */
internal fun appendValue(uci: UciStore, config: String, section: String, option: String, value: String): Boolean {
    val cur = uci.get(config, section, option)
    val isList = cur is List<*>
    val values = UciStore.toArray(cur).toMutableList()
    var rv = false
    if (!values.contains(value)) { values += value; rv = true }
    uci.set(config, section, option, if (isList) values else values.joinToString(" "))
    return rv
}

/** network.js removeValue() */
internal fun removeValue(uci: UciStore, config: String, section: String, option: String, value: String): Boolean {
    val cur = uci.get(config, section, option)
    val isList = cur is List<*>
    val values = UciStore.toArray(cur).toMutableList()
    val rv = values.removeAll { it == value }
    if (values.isNotEmpty()) uci.set(config, section, option, if (isList) values else values.joinToString(" "))
    else uci.unset(config, section, option)
    return rv
}

/** network_updown(): toggles a wifi-iface (and its radio) in UCI. */
internal fun networkUpdownUci(uci: UciStore, id: String) {
    val radio = uci.getString("wireless", id, "device") ?: ""
    val disabled = uci.getString("wireless", id, "disabled") == "1" || uci.getString("wireless", radio, "disabled") == "1"
    if (disabled) {
        uci.unset("wireless", id, "disabled")
        uci.unset("wireless", radio, "disabled")
    } else {
        uci.set("wireless", id, "disabled", "1")
        val allDisabled = uci.sections("wireless", "wifi-iface").none { it["device"] == radio && it["disabled"] != "1" }
        if (allDisabled) uci.set("wireless", radio, "disabled", "1")
    }
}

/** network.addNetwork(name, options): the interface section, or null when it cannot be created. */
internal fun addNetwork(st: WifiState, name: String, options: Map<String, String>): String? {
    val exists = st.networkExists(name)
    if (Regex("^[a-zA-Z0-9_]+$").matches(name) && !exists) {
        val sid = st.uci.add("network", "interface", name)
        options.forEach { (k, v) -> st.uci.set("network", sid, k, v) }
        return sid
    }
    if (exists && st.networkIsEmpty(name)) {
        options.forEach { (k, v) -> st.uci.set("network", name, k, v) }
        return name
    }
    return null
}

/** The NetworkSelect write of the interface "network" option: attach the wifi device to networks. */
internal fun writeWifiNetworks(st: WifiState, sid: String, value: Any?) {
    val netid = st.netidBySid(sid)?.first ?: return
    val wif = st.sidByIfname(netid) ?: return
    val devName = st.lookupNet(wif).ifname ?: netid
    val attached = UciStore.toArray(st.uci.get("wireless", wif, "network"))
    val old = st.networkNames().filter { n ->
        attached.contains(n) || Regex("^([^:/]+)").find(st.uci.getString("network", n, "device") ?: "")?.groupValues?.get(1) == devName
    }.toSet()
    val values = UciStore.toArray(value)
    for (v in values) {
        if (v in old) continue
        val net = if (st.networkExists(v)) v else addNetwork(st, v, mapOf("proto" to "none")) ?: continue
        if (!st.networkIsEmpty(net)) {
            var target = st.networkDevice(net)
            var type = target?.let { st.networkDeviceType(net, it) }
            var guard = 0
            while (target != null && type == "vlan" && guard++ < 8) {
                target = st.deviceParent(target)
                type = target?.let { st.deviceType(it) }
            }
            if (target == null || type != "bridge") st.uci.set("network", net, "type", "bridge")
        }
        val w = st.sidByIfname(devName)
        if (w != null) appendValue(st.uci, "wireless", w, "network", net)
        else appendValue(st.uci, "network", net, "device", devName)
    }
    for (name in old) {
        if (name in values) continue
        if (!st.networkExists(name)) continue
        st.sidByIfname(devName)?.let { removeValue(st.uci, "wireless", it, "network", name) }
        removeValue(st.uci, "network", name, "device", devName)
    }
}

/** firewall.js lookupZone(): the zone section of a zone name (or section id). */
internal fun lookupZone(uci: UciStore, name: String): String? {
    val z = uci.section("firewall", name)
    if (z != null && z[".type"] == "zone") return z[".name"] as String
    return uci.sections("firewall", "zone").firstOrNull { it["name"] == name }?.get(".name") as? String
}

private fun parsePolicy(s: String?, default: String): String {
    val u = s?.uppercase()?.takeIf { it.isNotEmpty() } ?: return default
    return listOf("DROP", "REJECT", "ACCEPT").firstOrNull { it.startsWith(u) } ?: default
}

/** firewall.addZone(name) */
internal fun addZone(uci: UciStore, name: String): String? {
    if (!Regex("^[a-zA-Z0-9_]+$").matches(name)) return null
    if (lookupZone(uci, name) != null) return null
    val d = uci.sections("firewall", "defaults").firstOrNull()?.get(".name") as? String
    val dget = { o: String -> d?.let { uci.getString("firewall", it, o) } }
    val z = uci.add("firewall", "zone")
    uci.set("firewall", z, "name", name)
    uci.set("firewall", z, "input", parsePolicy(dget("input"), "DROP"))
    uci.set("firewall", z, "output", parsePolicy(dget("output"), "DROP"))
    uci.set("firewall", z, "forward", parsePolicy(dget("forward"), "DROP"))
    return z
}

/** firewall.deleteNetwork(network): removes the network from every zone. */
internal fun firewallDeleteNetwork(uci: UciStore, network: String) {
    for (z in uci.sections("firewall", "zone")) {
        val sid = z[".name"] as? String ?: continue
        val old = UciStore.toArray(z["network"])
        val new = old.filter { it != network }
        uci.set("firewall", sid, "network", new.ifEmpty { null })
    }
}

/** Zone.addNetwork(network) */
internal fun zoneAddNetwork(uci: UciStore, zone: String, network: String) {
    if (uci.section("network", network)?.get(".type") != "interface") return
    val nets = UciStore.toArray(uci.get("firewall", zone, "network"))
    if (nets.contains(network)) return
    uci.set("firewall", zone, "network", nets + network)
}

/** L.hasSystemFeature(a) with luci getFeatures data. */
internal fun PageContext.feature(name: String): Boolean {
    val v = session.features[name]
    return v != null && v !is JsonNull && !(v is JsonPrimitive && v.booleanOrNull == false)
}

/** L.hasSystemFeature(a, b) */
internal fun PageContext.feature(name: String, sub: String): Boolean {
    val v = session.features[name] as? JsonObject ?: return false
    return jsTruthy(v[sub])
}
