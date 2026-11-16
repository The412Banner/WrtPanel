package com.the412banner.wrtpanel.pages.status

import com.the412banner.wrtpanel.core.UbusCall
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.double
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.int
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.long
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.core.strList
import com.the412banner.wrtpanel.ui.PageContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Status helpers — the parts of LuCI's network.js and firewall.js state model the Status pages use. */
internal data class ProtoSpec(val i18n: String, val floating: Boolean = false, val virtual: Boolean? = null, val pattern: String? = null)

/** Protocol handlers of the luci-proto-* packages (getI18n, isFloating, isVirtual, registerPatternVirtual). */
internal val ProtoTable: Map<String, ProtoSpec> = mapOf(
    "none" to ProtoSpec("Unmanaged"),
    "static" to ProtoSpec("Static address"),
    "dhcp" to ProtoSpec("DHCP client"),
    "dhcpv6" to ProtoSpec("DHCPv6 client"),
    "autoip" to ProtoSpec("Avahi IPv4LL"),
    "hnet" to ProtoSpec("Automatic Homenet (HNCP)"),
    "pppoe" to ProtoSpec("PPPoE", pattern = "^pppoe-.+$"),
    "3g" to ProtoSpec("UMTS/GPRS/EV-DO", true, true, "^3g-.+$"),
    "batadv" to ProtoSpec("Batman Device", true, true, "^bat\\d+"),
    "batadv_hardif" to ProtoSpec("Batman Interface", false, false, "^bat.+$"),
    "batadv_vlan" to ProtoSpec("Batman VLAN Interface", true, true, "^bat\\d_\\d+$"),
    "external" to ProtoSpec("Externally managed interface", true, true),
    "gre" to ProtoSpec("GRE tunnel over IPv4", true, true, "^gre4-.+$"),
    "gretap" to ProtoSpec("GRETAP tunnel over IPv4", true, true, "^gre4t-.+$"),
    "grev6" to ProtoSpec("GRE tunnel over IPv6", true, true, "^gre6-.+$"),
    "grev6tap" to ProtoSpec("GRETAP tunnel over IPv6", true, true, "^gre6t-.+$"),
    "ipip" to ProtoSpec("IPv4-in-IPv4 (RFC2003)", true, true, "^ipip-.+$"),
    "464xlat" to ProtoSpec("464XLAT (CLAT)", true, true, "^464-.+$"),
    "6in4" to ProtoSpec("IPv6-in-IPv4 (RFC4213)", true, true, "^6in4-.+$"),
    "6rd" to ProtoSpec("IPv6-over-IPv4 (6rd)", true, true, "^6rd-.+$"),
    "6to4" to ProtoSpec("IPv6-over-IPv4 (6to4)", true, true, "^6to4-.+$"),
    "dslite" to ProtoSpec("Dual-Stack Lite (RFC6333)", true, true, "^ds-.+$"),
    "ipip6" to ProtoSpec("IPv4 over IPv6 (RFC2473-IPIPv6)", true, true, "^ipip6-.+$"),
    "map" to ProtoSpec("MAP / LW4over6", true, true, "^map-.+$"),
    "mbim" to ProtoSpec("MBIM Cellular", true, true, "^mbim-.+$"),
    "modemmanager" to ProtoSpec("ModemManager", true, true, "^mobiledata-.+$"),
    "ncm" to ProtoSpec("NCM", true, true, "^ncm-.+$"),
    "nebula" to ProtoSpec("Nebula Network", true, true),
    "openconnect" to ProtoSpec("OpenConnect", true, true, "^vpn-.+$"),
    "openfortivpn" to ProtoSpec("OpenFortivpn", true, true, "^vpn-.+$"),
    "l2tp" to ProtoSpec("L2TP", true, true, "^l2tp-.+$"),
    "ppp" to ProtoSpec("PPP", true, true, "^ppp-.+$"),
    "pppoa" to ProtoSpec("PPPoATM", true, true, "^pppoa-.+$"),
    "pptp" to ProtoSpec("PPtP", true, true, "^pptp-.+$"),
    "pppossh" to ProtoSpec("PPPoSSH", true, true, "^pppossh-.+$"),
    "qmi" to ProtoSpec("QMI Cellular", true, true, "^qmi-.+$"),
    "relay" to ProtoSpec("Relay bridge", true, true, "^relay-.+$"),
    "sstp" to ProtoSpec("SSTP", true, true, "^sstp-.+$"),
    "unet" to ProtoSpec("Unet", true, true),
    "vpnc" to ProtoSpec("VPNC (CISCO 3000 (and others) VPN)", true, true),
    "vti" to ProtoSpec("VTI", true, true),
    "vxlan" to ProtoSpec("VXLAN (RFC7348)", true, true),
    "vxlan6" to ProtoSpec("VXLANv6 (RFC7348)", true, true),
    "wireguard" to ProtoSpec("WireGuard VPN", true, true),
    "xfrm" to ProtoSpec("IPsec XFRM", true, true),
    "yggdrasil" to ProtoSpec("Yggdrasil Network", true, true),
)

private val ignorePatterns = listOf(
    "^wmaster\\d+", "^wifi\\d+", "^hwsim\\d+", "^imq\\d+", "^ifb\\d+", "^mon\\.wlan\\d+", "^sit\\d+", "^gre\\d+",
    "^gretap\\d+", "^ip6gre\\d+", "^ip6tnl\\d+", "^tunl\\d+", "^lo$",
).map { Regex(it) }
private val wirelessPatterns = listOf("^wlan\\d+", "^wl\\d+", "^ath\\d+", "^\\w+\\.network\\d+").map { Regex(it) }

private fun JsonElement?.isObj() = this is JsonObject

/** initNetworkState() result. */
internal class NetState(
    val uci: UciStore,
    val ifaces: List<JsonObject>,
    val netdevs: MutableMap<String, JsonObject>,
    val radios: JsonObject,
    val hosts: JsonObject,
    val protos: Map<String, JsonObject>,
    val hostapd: Map<String, JsonElement>,
    val hasDSLModem: JsonObject?,
) {
    val isTunnel = mutableSetOf<String>()
    val isBridge = mutableSetOf<String>()
    val isSwitch = mutableSetOf<String>()
    val bridgePorts = mutableMapOf<String, String>()
    private val virtualPatterns: List<Regex> =
        protos.keys.mapNotNull { ProtoTable[it]?.pattern }.distinct().map { Regex(it) }

    fun isVirtualIfname(n: String) = virtualPatterns.any { it.containsMatchIn(n) }
    fun isIgnoredIfname(n: String) = ignorePatterns.any { it.containsMatchIn(n) }
    fun isWifiIfname(n: String) = wirelessPatterns.any { it.containsMatchIn(n) }

    // --- wireless lookups (network.js private helpers) ---
    private fun wifiIfaces() = uci.sections("wireless", "wifi-iface")

    fun getWifiStateBySid(sid: String): Triple<String, JsonObject, JsonObject>? {
        val s = uci.section("wireless", sid) ?: return null
        if (s[".type"] != "wifi-iface") return null
        for ((radioname, radio) in radios) {
            for (netstate in radio["interfaces"].arr ?: continue) {
                val sec = netstate["section"].str ?: continue
                val s2 = uci.section("wireless", sec) ?: continue
                if (s[".type"] == s2[".type"] && s[".name"] == s2[".name"]) {
                    if (s2[".anonymous"] == false && sec.startsWith("@")) return null
                    return Triple(radioname, radio as JsonObject, netstate as JsonObject)
                }
            }
        }
        return null
    }

    fun getWifiStateByIfname(ifname: String): Triple<String, JsonObject, JsonObject>? {
        for ((radioname, radio) in radios) {
            for (netstate in radio["interfaces"].arr ?: continue) {
                if (netstate["ifname"].str == ifname) return Triple(radioname, radio as JsonObject, netstate as JsonObject)
            }
        }
        return null
    }

    fun getWifiSidByNetid(netid: String): String? {
        val m = Regex("^(\\w+)\\.network(\\d+)$").find(netid) ?: return null
        var n = 0
        for (s in wifiIfaces()) {
            if (s["device"] != m.groupValues[1]) continue
            if (++n == m.groupValues[2].toInt()) return s[".name"] as? String
        }
        return null
    }

    fun getWifiSidByIfname(ifname: String): String? =
        getWifiSidByNetid(ifname) ?: getWifiStateByIfname(ifname)?.third?.get("section").str

    fun getWifiNetidBySid(sid: String): Pair<String, String>? {
        val s = uci.section("wireless", sid) ?: return null
        if (s[".type"] != "wifi-iface") return null
        val radio = s["device"] as? String ?: return null
        var n = 0
        for (sec in wifiIfaces()) {
            if (sec["device"] != radio) continue
            n++
            if (sec[".name"] != s[".name"]) continue
            return "$radio.network$n" to radio
        }
        return null
    }

    fun getWifiNetidByNetname(name: String): Pair<String, String>? {
        for (s in wifiIfaces()) {
            val net = s["network"] as? String ?: continue
            if (net.split(Regex("\\s+")).any { it == name }) return getWifiNetidBySid(s[".name"] as String)
        }
        return null
    }

    // --- objects ---
    fun instantiateNetwork(name: String, proto: String? = null): Protocol {
        val p = proto ?: (uci.getString("network", name, "proto")?.ifEmpty { null } ?: "none")
        return Protocol(this, name, p)
    }

    fun instantiateDevice(name: String): Device = Device(this, name)

    fun getNetworks(): List<Protocol> {
        val nets = LinkedHashMap<String, Protocol>()
        uci.sections("network", "interface").forEach { s -> (s[".name"] as? String)?.let { nets[it] = instantiateNetwork(it) } }
        ifaces.forEach { st ->
            val n = st["interface"].str ?: return@forEach
            if (nets[n] == null) nets[n] = instantiateNetwork(n, st["proto"].str)
        }
        return nets.values.sortedWith { a, b -> LuciFmt.naturalCompare.compare(a.sid, b.sid) }
    }

    private fun statusByRoute(addr: String, mask: Int): List<JsonObject> =
        ifaces.filter { sif ->
            sif["route"].arr?.any { r ->
                r is JsonObject && (r["target"] as? JsonPrimitive)?.isString == true && r["mask"].int != null &&
                    r["table"].truthyStr() == null && r["target"].str == addr && r["mask"].int == mask
            } == true
        }.sortedWith { a, b ->
            LuciFmt.naturalCompare.compare(a["metric"].str, b["metric"].str).takeIf { it != 0 }
                ?: LuciFmt.naturalCompare.compare(a["interface"].str, b["interface"].str)
        }

    private fun wanNets(addr: String): List<Protocol> {
        val seen = mutableSetOf<String>()
        return statusByRoute(addr, 0).mapNotNull { s ->
            val n = s["interface"].str ?: return@mapNotNull null
            if (seen.add(n)) instantiateNetwork(n, s["proto"].str) else null
        }
    }

    fun getWANNetworks() = wanNets("0.0.0.0")
    fun getWAN6Networks() = wanNets("::")

    fun getWifiDevices(): List<WifiDevice> =
        uci.sections("wireless", "wifi-device").mapNotNull { s -> (s[".name"] as? String)?.let { WifiDevice(this, it) } }

    fun getWifiNetworks(): List<WifiNetwork> =
        wifiIfaces().mapNotNull { s -> (s[".name"] as? String)?.let { lookupWifiNetwork(it) } }
            .sortedWith { a, b -> LuciFmt.naturalCompare.compare(a.netid, b.netid) }

    fun lookupWifiNetwork(netname: String): WifiNetwork {
        var sid: String? = getWifiSidByNetid(netname)
        var netid: String? = null
        var radioname: String? = null
        var radiostate: JsonObject? = null
        var netstate: JsonObject? = null
        if (sid != null) {
            val res = getWifiStateBySid(sid)
            netid = netname; radioname = res?.first; radiostate = res?.second; netstate = res?.third
        } else {
            val r1 = getWifiStateByIfname(netname)
            if (r1 != null) {
                radioname = r1.first; radiostate = r1.second; netstate = r1.third
                sid = netstate["section"].str
                netid = sid?.let { getWifiNetidBySid(it)?.first }
            } else {
                val r2 = getWifiStateBySid(netname)
                if (r2 != null) {
                    radioname = r2.first; radiostate = r2.second; netstate = r2.third
                    sid = netname; netid = getWifiNetidBySid(netname)?.first
                } else {
                    val r3 = getWifiNetidBySid(netname)
                    if (r3 != null) { netid = r3.first; radioname = r3.second; sid = netname }
                }
            }
        }
        val hapd = netstate?.get("ifname").str?.let { hostapd[it] }
        return WifiNetwork(this, sid ?: netname, radioname, radiostate, netid, netstate, hapd)
    }

    fun hostHints() = Hosts(hosts)

    companion object {
        /** network.flushCache() / initNetworkState(true). */
        suspend fun load(ctx: PageContext): NetState {
            val hasWifi = ctx.sysFeature("wifi")
            val res = ctx.client.batch(
                listOf(
                    UbusCall("network.interface", "dump"),
                    UbusCall("luci-rpc", "getBoardJSON"),
                    UbusCall("luci-rpc", "getNetworkDevices"),
                    UbusCall("luci-rpc", "getWirelessDevices"),
                    UbusCall("luci-rpc", "getHostHints"),
                    UbusCall("network", "get_proto_handlers"),
                ),
            )
            resolveDefault(Unit) { ctx.uci.load("network") }
            if (hasWifi) resolveDefault(Unit) { ctx.uci.load("wireless") }
            resolveDefault(Unit) { ctx.uci.load("luci") }

            val ifaces = res[0].getOrNull()["interface"].arr?.mapNotNull { it as? JsonObject } ?: emptyList()
            val board = res[1].getOrNull().obj ?: JsonObject(emptyMap())
            val luciDevs = res[2].getOrNull().obj ?: JsonObject(emptyMap())
            val radios = res[3].getOrNull().obj ?: JsonObject(emptyMap())
            val hosts = res[4].getOrNull().obj ?: JsonObject(emptyMap())
            val protos = LinkedHashMap<String, JsonObject>()
            res[5].getOrNull().obj?.forEach { (k, v) -> if (k != "bonding") protos[k] = v.obj ?: JsonObject(emptyMap()) }
            if (!protos.containsKey("none")) protos["none"] = jsonOf("no_device" to false)
            if (!protos.containsKey("relay") && ctx.sysFeature("relayd")) protos["relay"] = jsonOf("no_device" to true)

            val objects = radios.values.flatMap { r -> r["interfaces"].arr?.mapNotNull { it["ifname"].str } ?: emptyList() }.map { "hostapd.$it" }
            val hostapd = mutableMapOf<String, JsonElement>()
            if (objects.isNotEmpty()) {
                resolveDefault(null) { ctx.client.list(*objects.toTypedArray()) }.obj?.forEach { (k, v) ->
                    Regex("^hostapd\\.(.+)$").find(k)?.let { hostapd[it.groupValues[1]] = v }
                }
            }

            val dsl = board["dsl"]["modem"].obj
            val st = NetState(ctx.uci, ifaces, LinkedHashMap(), radios, hosts, protos, hostapd, dsl)
            for ((name, dev) in luciDevs) {
                if (st.isVirtualIfname(name)) st.isTunnel += name
                if (!st.isTunnel.contains(name) && st.isIgnoredIfname(name)) continue
                if (dev is JsonObject) st.netdevs[name] = dev
            }
            for ((name, dev) in luciDevs) {
                if (dev["bridge"].bool != true) continue
                dev["ports"].strList.forEach { p -> if (st.netdevs.containsKey(p)) st.bridgePorts[p] = name }
                st.isBridge += name
            }
            for ((name, dev) in luciDevs) {
                val parent = dev["parent"].str
                if (parent.isNullOrEmpty() || dev["devtype"].str != "dsa") continue
                st.isSwitch += parent; st.isSwitch += name
            }
            return st
        }
    }
}

/** network.Hosts */
internal class Hosts(private val hosts: JsonObject) {
    private fun addrs(mac: String, a: String, b: String): List<String> {
        val h = hosts[mac] ?: return emptyList()
        val v = h[a] ?: h[b]
        return if (v is JsonArray) v.mapNotNull { it.str } else v.strList
    }

    fun hostnameByMAC(mac: String?): String? = mac?.let { hosts[it]?.get("name").str }
    fun ipByMAC(mac: String?): String? = mac?.let { addrs(it, "ipaddrs", "ipv4").firstOrNull() }
    fun ip6ByMAC(mac: String?): String? = mac?.let { addrs(it, "ip6addrs", "ipv6").firstOrNull() }

    fun macHints(preferIp6: Boolean): List<Pair<String, String?>> =
        hosts.keys.map { mac ->
            val hint = hosts[mac]?.get("name").str
                ?: addrs(mac, if (preferIp6) "ip6addrs" else "ipaddrs", if (preferIp6) "ipv6" else "ipv4").firstOrNull()
                ?: addrs(mac, if (preferIp6) "ipaddrs" else "ip6addrs", if (preferIp6) "ipv4" else "ipv6").firstOrNull()
            mac to hint
        }.sortedWith { a, b -> LuciFmt.naturalCompare.compare(a.first, b.first) }
}

/** network.Protocol (with the registered protocol handler's overrides). */
internal class Protocol(private val st: NetState, val sid: String, private val protoName: String) {
    private val registered: Boolean = st.protos.containsKey(protoName) && ProtoTable.containsKey(protoName)
    private val spec: ProtoSpec? = if (registered) ProtoTable[protoName] else null

    private fun ubus(field: String): JsonElement? = st.ifaces.firstOrNull { it["interface"].str == sid }?.get(field)

    fun getProtocol(): String? = if (registered) protoName else null
    fun getI18n(): String = spec?.i18n ?: when (getProtocol()) {
        "none" -> "Unmanaged"; "static" -> "Static address"; "dhcp" -> "DHCP client"; else -> "Unknown"
    }
    fun isFloating(): Boolean = spec?.floating ?: false
    fun isVirtual(): Boolean = spec?.virtual ?: (registered && st.protos[protoName]?.get("no_device")?.bool == true)
    fun getName() = sid
    fun getType(): String = st.uci.getString("network", sid, "type") ?: ""
    fun isBridge() = !isVirtual() && getType() == "bridge"

    fun getIfname(): String? {
        val ifname = if (isFloating()) ubus("l3_device").str else (ubus("device").str ?: ubus("l3_device").str)
        return ifname ?: st.getWifiNetidByNetname(sid)?.first
    }

    fun getUptime(): Long = ubus("uptime").long ?: 0

    fun getExpiry(): Long {
        fun number(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.double
        val u = number(ubus("uptime"))
        val d = ubus("data")
        val p6 = ubus("ipv6-prefix").arr
        val a6 = ubus("ipv6-address").arr
        if (u != null && d != null && d !is kotlinx.serialization.json.JsonNull) {
            val lt = number(d["leasetime"])
            if (d is JsonObject && lt != null) {
                val r = lt - (u % lt)
                return if (r > 0) r.toLong() else 0
            }
            if (p6 != null || a6 != null) {
                val all = (p6 ?: emptyList()) + (a6 ?: emptyList())
                val r = number(all.firstOrNull()?.get("valid"))
                if (r != null) return if (r > 0) r.toLong() else 0
            }
        }
        return -1
    }

    fun getIPAddrs(): List<String> = ubus("ipv4-address").arr?.map { "${it["address"].str}/${it["mask"].int ?: 0}" } ?: emptyList()

    fun getIP6Addrs(): List<String> {
        val rv = LinkedHashSet<String>()
        ubus("ipv6-address").arr?.forEach { if (it is JsonObject) rv += "${it["address"].str}/${it["mask"].int ?: 0}" }
        ubus("ipv6-prefix-assignment").arr?.forEach {
            val la = it["local-address"]
            if (it is JsonObject && la is JsonObject) rv += "${la["address"].str}/${la["mask"].int ?: 0}"
        }
        return rv.toList()
    }

    private fun gw(target: String): String? =
        ubus("route").arr?.firstOrNull { it is JsonObject && it["target"].str == target && it["mask"].int == 0 }?.get("nexthop").str

    fun getGatewayAddr() = gw("0.0.0.0")
    fun getGateway6Addr() = gw("::")
    fun getDNSAddrs(): List<String> = ubus("dns-server").arr?.mapNotNull { it.str }?.filter { !it.contains(':') } ?: emptyList()
    fun getDNS6Addrs(): List<String> = ubus("dns-server").arr?.mapNotNull { it.str }?.filter { it.contains(':') } ?: emptyList()

    fun getIP6Prefixes(): List<String>? {
        val rv = ubus("ipv6-prefix").arr?.filter { it is JsonObject }?.map { "${it["address"].str}/${it["mask"].int ?: 0}" } ?: emptyList()
        return rv.ifEmpty { null }
    }

    fun getL3Device(): Device? = ubus("l3_device").str?.let { st.instantiateDevice(it) }

    fun getDevice(): Device? {
        if (isVirtual()) {
            val ifname = "${getProtocol()}-$sid"
            st.isTunnel += ifname
            return st.instantiateDevice(ifname)
        }
        if (isBridge()) {
            val ifname = "br-$sid"
            st.isBridge += ifname
            return st.instantiateDevice(ifname)
        }
        val ifnames = UciStore.toArray(st.uci.get("network", sid, "device"))
        for (ifn in ifnames) {
            val m = Regex("^([^:/]+)").find(ifn)
            return m?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }?.let { st.instantiateDevice(it) }
        }
        return st.getWifiNetidByNetname(sid)?.let { st.instantiateDevice(it.first) }
    }
}

/** network.Device */
internal class Device(private val st: NetState, device: String) {
    val wif: WifiNetwork?
    val name: String
    val dev: JsonObject
    private val config: Map<String, Any>

    init {
        val wsid = st.getWifiSidByIfname(device)
        var d: String? = null
        wif = if (wsid != null) {
            val res = st.getWifiStateBySid(wsid)
            val netid = st.getWifiNetidBySid(wsid)
            WifiNetwork(st, wsid, res?.first, res?.second, netid?.first, res?.third, jsonOf("ifname" to device)).also { d = it.getIfname() }
        } else null
        name = d ?: device
        dev = st.netdevs[name] ?: JsonObject(emptyMap())
        config = st.uci.sections("network", "device").lastOrNull { it["name"] == device } ?: emptyMap()
    }

    fun getName(): String = wif?.getIfname() ?: name
    fun getMAC(): String? = dev["mac"].str?.uppercase()

    fun getType(): String = when {
        name.startsWith("@") -> "alias"
        dev["devtype"].str == "wlan" || wif != null || st.isWifiIfname(name) -> "wifi"
        dev["devtype"].str == "bridge" || st.isBridge.contains(name) -> "bridge"
        dev["devtype"].str == "wireguard" -> "wireguard"
        st.isTunnel.contains(name) -> "tunnel"
        dev["devtype"].str == "vlan" || name.contains('.') -> "vlan"
        dev["devtype"].str == "dsa" || st.isSwitch.contains(name) -> "switch"
        config["type"] == "8021q" || config["type"] == "8021ad" -> "vlan"
        config["type"] == "bridge" -> "bridge"
        config["type"] == "vrf" -> "vrf"
        else -> "ethernet"
    }

    fun getTypeI18n(): String = when (getType()) {
        "alias" -> "Alias Interface"
        "wifi" -> "Wireless Adapter"
        "bridge" -> "Bridge"
        "vrf" -> "Virtual Routing and Forwarding (VRF)"
        "switch" -> if (st.netdevs[name]?.get("devtype").str == "dsa") "Switch port" else "Ethernet Switch"
        "vlan" -> if (st.isSwitch.contains(name)) "Switch VLAN" else "Software VLAN"
        "wireguard" -> "WireGuard Interface"
        "tunnel" -> "Tunnel Interface"
        else -> "Ethernet Adapter"
    }

    fun getI18n(): String {
        val w = wif
        if (w != null) return "Wireless Network: ${w.getActiveMode()} \"${w.getActiveSSID() ?: w.getActiveBSSID() ?: w.netid ?: "?"}\""
        return "${getTypeI18n()}: \"${getName()}\""
    }

    fun isUp(): Boolean = dev["flags"]["up"].bool ?: (getType() == "alias")
    val stats: JsonObject get() = dev["stats"].obj ?: JsonObject(emptyMap())
    fun getTXBytes(): Long = dev["stats"]["tx_bytes"].long ?: 0
    fun getRXBytes(): Long = dev["stats"]["rx_bytes"].long ?: 0
    fun getCarrier(): Boolean = dev["link"]["carrier"].bool ?: false
    fun getSpeed(): Long? = dev["link"]["speed"].long?.takeIf { it != 0L }
    fun getDuplex(): String? = dev["link"]["duplex"].str?.takeIf { it != "unknown" }
}

/** network.WifiDevice */
internal class WifiDevice(private val st: NetState, val sid: String) {
    private val radio: JsonElement? get() = st.radios[sid]
    fun getName() = sid
    fun isUp(): Boolean = radio is JsonObject && radio["up"].bool == true
    fun getI18n(): String {
        val hw = radio["iwinfo"]["hardware"]
        var type = (hw as? JsonObject)?.get("name").str
        val modes = radio["iwinfo"]["hwmodes_text"].str
        if (radio["iwinfo"]["type"].str == "wl") type = "Broadcom"
        return "${type ?: "Generic"} ${if (modes != null) "802.11$modes" else "unknown"} Wireless Controller (${getName()})"
    }
}

/** network.WifiNetwork */
internal class WifiNetwork(
    private val st: NetState,
    val sid: String,
    private val radioname: String?,
    private val radiostate: JsonObject?,
    val netid: String?,
    private val netstate: JsonObject?,
    private val hostapd: JsonElement?,
) {
    private fun uciGet(opt: String): String? = st.uci.getString("wireless", sid, opt)
    private fun net(vararg path: String): JsonElement? { var v: JsonElement? = netstate; for (p in path) { if (v !is JsonObject) return null; v = v[p] }; return v }
    private fun devv(vararg path: String): JsonElement? { var v: JsonElement? = radiostate; for (p in path) { if (v !is JsonObject) return null; v = v[p] }; return v }
    private fun truthy(e: JsonElement?): String? = e.str?.takeIf { it.isNotEmpty() && it != "0" && it != "false" }

    fun isDisabled(): Boolean = devv("disabled").bool == true || uciGet("disabled") == "1"
    fun getMode(): String = net("config", "mode").str?.ifEmpty { null } ?: uciGet("mode") ?: "ap"
    fun getBSSID(): String? = net("config", "bssid").str?.ifEmpty { null } ?: uciGet("bssid")
    fun getIfname(): String? {
        var ifname = net("ifname").str?.ifEmpty { null } ?: net("iwinfo", "ifname").str?.ifEmpty { null }
        if (ifname == null || Regex("^(wifi|radio)\\d").containsMatchIn(ifname)) ifname = netid
        return ifname
    }
    fun getWifiDeviceName(): String? = radioname ?: uciGet("device")

    fun getActiveMode(): String = when (val mode = net("iwinfo", "mode").str?.ifEmpty { null } ?: getMode()) {
        "ap" -> "Master"; "sta" -> "Client"; "adhoc" -> "Ad-Hoc"; "mesh" -> "Mesh Point"; "monitor" -> "Monitor"; else -> mode
    }

    fun getActiveModeI18n(): String = when (val mode = getActiveMode()) {
        "Master" -> "Access Point"; "Ad-Hoc" -> "Ad-Hoc"; "Client" -> "Client"; "Monitor" -> "Monitor"
        "Master(VLAN)" -> "Master (VLAN)"; "WDS" -> "WDS"; "Mesh Point" -> "Mesh Point"; "P2P Client" -> "P2P Client"
        "P2P Go" -> "P2P Go"; "Unknown" -> "Unknown"; else -> mode
    }

    fun getActiveSSID(): String? = net("iwinfo", "ssid").str?.ifEmpty { null } ?: net("config", "ssid").str?.ifEmpty { null } ?: uciGet("ssid")
    fun getActiveBSSID(): String? = net("iwinfo", "bssid").str?.ifEmpty { null } ?: net("config", "bssid").str?.ifEmpty { null } ?: uciGet("bssid")
    fun getActiveEncryption(): String = formatWifiEncryption(net("iwinfo", "encryption")) ?: "-"

    fun getFrequency(): String? = net("iwinfo", "frequency").double?.takeIf { it > 0 }?.let { String.format(java.util.Locale.US, "%.03f", it / 1000) }
    fun getBitRate(): Double? = net("iwinfo", "bitrate").double?.takeIf { it > 0 }?.let { it / 1000 }
    fun getChannel(): String? = truthy(net("iwinfo", "channel")) ?: truthy(devv("config", "channel")) ?: uciGet("channel")
    fun getSignal(): Double = net("iwinfo", "signal").double ?: 0.0
    fun getNoise(): Double = net("iwinfo", "noise").double ?: 0.0
    fun getCountryCode(): String = net("iwinfo", "country").str?.ifEmpty { null } ?: devv("config", "country").str?.ifEmpty { null } ?: "00"
    fun getTXPower(): Double? = net("iwinfo", "txpower").double

    fun getSignalPercent(): Int {
        val qc = net("iwinfo", "quality").double ?: 0.0
        val qm = net("iwinfo", "quality_max").double ?: 0.0
        return if (qc > 0 && qm > 0) kotlin.math.floor((100 / qm) * qc).toInt() else 0
    }

    fun getShortName(): String = "${getActiveModeI18n()} \"${getActiveSSID() ?: getActiveBSSID() ?: netid}\""
    fun getI18n(): String = "Wireless Network: ${getActiveModeI18n()} \"${getActiveSSID() ?: getActiveBSSID() ?: netid}\" (${getIfname()})"

    fun getDevice(): Device? = getIfname()?.let { st.instantiateDevice(it) }
    fun isClientDisconnectSupported(): Boolean = hostapd?.get("del_client") is JsonObject

    /** getVlans(): (ifname, vlan) of net.vlans. */
    fun getVlans(): List<WifiVlan> = net("vlans").arr?.map { WifiVlan(it) } ?: emptyList()

    companion object {
        fun formatWifiEncryption(enc: JsonElement?): String? {
            if (enc !is JsonObject) return null
            if (enc["enabled"].bool != true) return "None"
            val ciphers = enc["ciphers"].arr?.mapNotNull { it.str?.uppercase() } ?: listOf("NONE")
            val wep = enc["wep"].arr
            if (wep != null) {
                val open = wep.any { it.str == "open" }; val shared = wep.any { it.str == "shared" }
                return when {
                    open && shared -> "WEP Open/Shared (${ciphers.joinToString(", ")})"
                    open -> "WEP Open System (${ciphers.joinToString(", ")})"
                    shared -> "WEP Shared Auth (${ciphers.joinToString(", ")})"
                    else -> "WEP"
                }
            }
            val wpa = enc["wpa"].arr
            if (wpa != null) {
                val suites = enc["authentication"].arr?.mapNotNull { it.str?.uppercase() } ?: listOf("NONE")
                val versions = wpa.map { v -> if (v.int == 1) "WPA" else "WPA${v.int ?: v.str}" }
                return if (versions.size > 1) "mixed ${versions.joinToString("/")} ${suites.joinToString(", ")} (${ciphers.joinToString(", ")})"
                else "${versions.firstOrNull()} ${suites.joinToString(", ")} (${ciphers.joinToString(", ")})"
            }
            return "Unknown"
        }
    }
}

/** network.WifiVlan */
internal class WifiVlan(v: JsonElement) {
    val ifname: String? = v["ifname"].str
    val vid: String? = v["config"]["vid"].str
    val name: String? = v["config"]["name"].str
    val network: String? = v["config"]["network"].arr?.firstOrNull().str
    fun getI18n(): String = "vlan ${vid?.toDoubleOrNull()?.toLong() ?: 0}" + (if (name != null && name != vid) " ($name)" else "")
}

/** firewall.Zone (name + networks), as firewall.getZones() returns them. */
internal data class FwZone(val name: String?, val networks: List<String>)

internal suspend fun loadZones(ctx: PageContext): List<FwZone> {
    resolveDefault(Unit) { ctx.uci.load("firewall") }
    return ctx.uci.sections("firewall", "zone").map { FwZone(it["name"] as? String, UciStore.toArray(it["network"])) }
        .sortedWith { a, b -> LuciFmt.naturalCompare.compare(a.name ?: "", b.name ?: "") }
}
