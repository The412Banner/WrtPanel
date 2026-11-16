package com.the412banner.wrtpanel.pages.network.wireless

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.UbusCall
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.form.Dep
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormOption
import com.the412banner.wrtpanel.form.FormRuntime
import com.the412banner.wrtpanel.form.FormSection
import com.the412banner.wrtpanel.form.ListValueOption
import com.the412banner.wrtpanel.form.SelectField
import com.the412banner.wrtpanel.form.deviceSelect
import com.the412banner.wrtpanel.form.networkSelect
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.stripHtml
import kotlinx.serialization.json.JsonElement

/** add_dependency_permutations(): one depends() per combination of the listed values. */
internal fun FormOption.perms(vararg deps: Pair<String, List<String>>) {
    var res = listOf(emptyMap<String, Any?>())
    for ((k, list) in deps) res = list.flatMap { v -> res.map { it + (k to v) } }
    res.forEach { this.deps += Dep(it) }
}

private val AP = "mode" to listOf("ap", "ap-wds")
private val STA = "mode" to listOf("sta", "sta-wds")
private val EAP = "encryption" to listOf("wpa", "wpa2", "wpa3", "wpa3-mixed", "wpa3-192")
private val PSK = "encryption" to listOf("psk", "psk2", "psk+psk2", "psk-mixed")

private const val CERT_SYSTEM = "Validate server certificate using built-in system CA bundle,<br />requires the \"ca-bundle\" package"
private const val SUBJECT_DESC = "Certificate constraint substring - e.g. /CN=wifi.mycompany.com<br />See `logread -f` during handshake for actual values"
private const val SAN_DESC = "Certificate constraint(s) via Subject Alternate Name values<br />(supported attributes: EMAIL, DNS, URI) - e.g. DNS:wifi.mycompany.com"
private const val DOMAIN_DESC = "Certificate constraint(s) against DNS SAN values (if available)<br />or Subject CN (exact match)"
private const val WILDCARD_DESC = "Certificate constraint(s) against DNS SAN values (if available)<br />or Subject CN (suffix match)"

/** What the modal needs from the page: live status, the enable/disable action and the QR view. */
internal class EditHooks(
    val liveNet: @Composable () -> WifiNet?,
    val updown: suspend (FormRuntime) -> Unit,
    val showQr: suspend (FormRuntime, String) -> Unit,
)

/** The edit modal of a wireless network: s.addModalOptions() as two named sections (device + interface). */
internal class WifiEditForm(val map: FormMap, val device: FormSection, val iface: FormSection, val radio: String, val sid: String, val title: String)

internal suspend fun PageContext.buildWifiEdit(snap: WifiSnapshot, sid: String, hooks: EditHooks): WifiEditForm {
    val st = snap.st
    val radioNet = st.lookupNet(sid)
    val radio = radioNet.wifiDeviceName ?: ""
    val hwtype = uci.getString("wireless", radio, "type")
    val mac80211 = hwtype == "mac80211"
    val haveMesh = feature("hostapd", "mesh") || feature("wpasupplicant", "mesh")
    val title = snap.nets.firstOrNull { it.sid == sid }?.i18n ?: "Edit wireless network"

    val res = client.batch(
        listOf(
            UbusCall("iwinfo", "freqlist", jsonOf("device" to radio)),
            UbusCall("iwinfo", "txpowerlist", jsonOf("device" to radio)),
            UbusCall("iwinfo", "countrylist", jsonOf("device" to radio)),
        ),
    )
    val results = { i: Int -> res.getOrNull(i)?.getOrNull()["results"].arr ?: emptyList<JsonElement>() }
    val freqModel = buildFreqModel(WifiRadio(st, radio), results(0))
    val pwrlist = results(1)
    val countrylist = results(2)

    val m = FormMap("wireless")
    m.chain("network")
    m.chain("firewall")

    // ---------------- Device Configuration
    lateinit var freqOpt: FormOption
    val dev = m.named(radio, "wifi-device", "Device Configuration") {
        tab("general", "General Setup")
        tab("advanced", "Advanced Settings")

        val isDisabled = radioNet.get("disabled") == "1" || uci.getString("wireless", radio, "disabled") == "1"

        dummy("_wifistat_modal", "Status", tab = "general") {
            render = { _, _ -> ModalStatusView(hooks.liveNet()) }
        }
        button("_toggle", if (isDisabled) "Wireless network is disabled" else "Wireless network is enabled", tab = "general") {
            inputstyle = if (isDisabled) "apply" else "reset"
            inputtitle = { _, _ -> if (isDisabled) "Enable" else "Disable" }
            onClick = { rt, _ -> hooks.updown(rt) }
        }
        freqOpt = value(
            "_freq", "<br />Operating frequency",
            "Some channels may be restricted to Indoor Only use by your Regulatory Domain. Make sure to follow this advice if a channel is reported as such.",
            tab = "general",
        ) { wifiFrequency(freqModel) }

        if (mac80211) {
            flag(
                "legacy_rates", "Allow legacy 802.11b rates",
                "Legacy or badly behaving devices may require legacy 802.11b rates to interoperate. Airtime efficiency may be significantly reduced where these are used. It is recommended to not allow 802.11b rates where possible.",
                tab = "general",
            ) { dependsContains("_freq", "2g") }

            list(
                "txpower", "Maximum transmit power",
                "Specifies the maximum transmit power the wireless radio may use. Depending on regulatory requirements and wireless usage, the actual transmit power may be reduced by the driver.",
                tab = "general",
            ) {
                value("", "driver default")
                for (p in pwrlist) {
                    val dbm = p["dbm"].str ?: continue
                    value(dbm, "$dbm dBm (${p["mw"].str} mW)")
                }
                val powerval = radioNet.txPower
                val poweroff = radioNet.txPowerOffset
                render = { rt, s -> TxPowerField(rt, this, s, powerval, poweroff) }
            }

            val countryValidate = { _: FormRuntime, _: String, v: String ->
                if (v.isNotEmpty() && !Regex("^[A-Z0-9][A-Z0-9]$").matches(v)) "Use ISO/IEC 3166 alpha2 country codes." else null
            }
            if (countrylist.isNotEmpty()) {
                list("country", "Country Code", tab = "general") {
                    value("", "driver default")
                    for (c in countrylist) {
                        val iso = c["iso3166"].str ?: continue
                        value(iso, "$iso - ${c["country"].str}")
                    }
                    validate = countryValidate
                }
            } else {
                value("country", "Country Code", tab = "general") { validate = countryValidate }
            }

            list(
                "cell_density", "Coverage cell density",
                "Configures data rates based on the coverage cell density. Normal configures basic rates to 6, 12, 24 Mbps if legacy 802.11b rates are not used else to 5.5, 11 Mbps. High configures basic rates to 12, 24 Mbps if legacy 802.11b rates are not used else to the 11 Mbps rate. Very High configures 24 Mbps as the basic rate. Supported rates lower than the minimum basic rate are not offered.",
                tab = "advanced",
            ) {
                value("0", "Disabled"); value("1", "Normal"); value("2", "High"); value("3", "Very High")
                default = "0"
            }
            value("distance", "Distance Optimization", "Distance to farthest network member in meters. Set only for distances above one kilometer; otherwise it is harmful.", tab = "advanced") {
                datatype = "or(range(0,114750),\"auto\")"; placeholder = "auto"
            }
            value("frag", "Fragmentation Threshold", tab = "advanced") { datatype = "min(256)"; placeholder = "off" }
            value("rts", "RTS/CTS Threshold", tab = "advanced") { datatype = "uinteger"; placeholder = "off" }
            flag("noscan", "Force 40MHz mode", "Always use 40MHz channels even if the secondary channel overlaps. Using this option does not comply with IEEE 802.11n-2009!", tab = "advanced") {
                rmempty = true
            }
            value("beacon_int", "Beacon Interval", tab = "advanced") { datatype = "range(15,65535)"; placeholder = "100"; rmempty = true }
            flag("rxldpc", "Rx LDPC", "Low-Density Parity-Check", tab = "advanced") { default = "1" }
            flag("ldpc", "Tx LDPC", tab = "advanced") { depends("rxldpc", "1"); default = "1" }
        }
    }

    // ---------------- Interface Configuration
    val iface = m.named(sid, "wifi-iface", "Interface Configuration") {
        tab("general", "General Setup")
        tab("encryption", "Wireless Security")
        tab("macfilter", "MAC-Filter")
        tab("advanced", "Advanced Settings")
        tab("roaming", "WLAN roaming", "Settings for assisting wireless clients in roaming between multiple APs: 802.11r, 802.11k and 802.11v")

        val mode = list(
            "mode", "Mode",
            if (!haveMesh) "<a id=\"installmesh\" href=\"%s\" target=\"_blank\" rel=\"noreferrer\">%s</a>".format(client.luciUrl("admin", "system", "package-manager") + "?query=wpad-mesh", "802.11s? Install mesh wpad") else "",
            tab = "general",
        ) {
            value("ap", "Access Point"); value("sta", "Client"); value("adhoc", "Ad-Hoc")
            default = "ap"
        }
        value("mesh_id", "Mesh Id", tab = "general") { depends("mode", "mesh") }
        flag("mesh_fwding", "Forward mesh peer traffic", tab = "advanced") { rmempty = false; default = "1"; depends("mode", "mesh") }
        value(
            "mesh_rssi_threshold", "RSSI threshold for joining mesh",
            "0 = not using RSSI threshold, 1 = do not change driver default Units: dBm. Where -255 is weakest, and -10 is strong.",
            tab = "advanced",
        ) { rmempty = false; default = "0"; datatype = "range(-255,1)"; depends("mode", "mesh") }
        value("ssid", "<abbr title=\"Extended Service Set Identifier\">ESSID</abbr>", tab = "general") {
            datatype = "maxlength(32)"
            listOf("ap", "sta", "adhoc", "ahdemo", "monitor", "ap-wds", "sta-wds", "wds").forEach { depends("mode", it) }
        }
        val bssid = value("bssid", "<abbr title=\"Basic Service Set Identifier\">BSSID</abbr>", tab = "general") { datatype = "macaddr" }
        networkSelect(
            "network", "Network",
            "Choose the network(s) you want to attach to this wireless interface or fill out the <em>custom</em> field to define a new network.",
            tab = "general", multiple = true, nocreate = false, novirtual = true,
        ) {
            rmempty = true
            writeFn = { _, s, v -> writeWifiNetworks(st, s, v) }
        }

        if (mac80211) {
            if (haveMesh) mode.value("mesh", "802.11s")
            mode.value("ahdemo", "Pseudo Ad-Hoc (ahdemo)")
            mode.value("monitor", "Monitor")
            bssid.depends("mode", "adhoc"); bssid.depends("mode", "sta"); bssid.depends("mode", "sta-wds")

            list("macfilter", "MAC Address Filter", tab = "macfilter") {
                depends("mode", "ap"); depends("mode", "ap-wds")
                value("", "disable"); value("allow", "Allow listed only"); value("deny", "Allow all except listed")
            }
            dynlist("maclist", "MAC-List", tab = "macfilter") {
                datatype = "macaddr"; retain = true
                depends("macfilter", "allow"); depends("macfilter", "deny")
                st.macHints().forEach { (mac, hint) -> value(mac, if (hint != null) "%s (%s)".format(mac, hint) else mac) }
            }

            mode.value("ap-wds", "%s (%s)".format("Access Point", "WDS"))
            mode.value("sta-wds", "%s (%s)".format("Client", "WDS"))
            mode.writeFn = { rt, s, v ->
                when (v.toString()) {
                    "ap-wds" -> { rt.uci.set("wireless", s, "mode", "ap"); rt.uci.set("wireless", s, "wds", "1") }
                    "sta-wds" -> { rt.uci.set("wireless", s, "mode", "sta"); rt.uci.set("wireless", s, "wds", "1") }
                    else -> { rt.uci.set("wireless", s, "mode", v); rt.uci.unset("wireless", s, "wds") }
                }
            }
            mode.cfgvalueFn = { rt, s ->
                val md = rt.uci.getString("wireless", s, "mode")
                val wds = truthy(rt.uci.getString("wireless", s, "wds"))
                when {
                    md == "ap" && wds != null -> "ap-wds"
                    md == "sta" && wds != null -> "sta-wds"
                    else -> md
                }
            }

            flag("hidden", "Hide <abbr title=\"Extended Service Set Identifier\">ESSID</abbr>", "Where the ESSID is hidden, clients may fail to roam and airtime efficiency may be significantly reduced.", tab = "general") {
                depends("mode", "ap"); depends("mode", "ap-wds")
            }
            flag("wmm", "WMM Mode", "Where Wi-Fi Multimedia (WMM) Mode QoS is disabled, clients may be limited to 802.11a/802.11g rates.", tab = "general") {
                depends("mode", "ap"); depends("mode", "ap-wds"); default = enabled
            }
            flag(
                "multicast_to_unicast_all", "Multi To Unicast",
                "ARP, IPv4 and IPv6 (even 802.1Q) with multicast destination MACs are unicast to the STA MAC address. Note: This is not Directed Multicast Service (DMS) in 802.11v. Note: might break receiver STA multicast expectations.",
                tab = "advanced",
            ) { rmempty = true }
            flag("isolate", "Isolate Clients", "Prevents client-to-client communication", tab = "advanced") {
                depends("mode", "ap"); depends("mode", "ap-wds")
            }
            flag(
                "bridge_isolate", "Isolate Bridge Port",
                "Prevents communication only with targets on isolated bridge ports (while allowing it with targets on non-isolated ones). This also prevents client-to-client communication on the same interface when the WiFi device is in AP mode.",
                tab = "advanced",
            )
            value("ifname", "Interface name", "Override default interface name", tab = "advanced") {
                optional = true; datatype = "netdevname"
                placeholder = radioNet.ifname?.takeIf { !Regex("^radio\\d+\\.network").containsMatchIn(it) }
            }
            val macaddr = uci.getString("wireless", radioNet.name, "macaddr")
            value("macaddr", "MAC address", "Override default MAC address - the range of usable addresses might be limited by the driver", tab = "advanced") {
                value("", "driver default (%s)".format(if (macaddr == null) (radioNet.activeBSSID ?: "undefined") else "no override"))
                value("random", "randomly generated")
                datatype = "or('random',macaddr)"
            }
            flag("short_preamble", "Short Preamble", tab = "advanced") { default = enabled }
            value("dtim_period", "DTIM Interval", "Delivery Traffic Indication Message Interval", tab = "advanced") {
                optional = true; placeholder = "2"; datatype = "range(1,255)"
            }
            value("wpa_group_rekey", "Time interval for rekeying GTK", "sec", tab = "advanced") {
                optional = true; placeholder = "600"; datatype = "uinteger"
            }
            flag("skip_inactivity_poll", "Disable Inactivity Polling", tab = "advanced") { optional = true; datatype = "uinteger" }
            value("max_inactivity", "Station inactivity limit", "802.11v: BSS Max Idle. Units: seconds.", tab = "advanced") {
                optional = true; placeholder = "300"; datatype = "uinteger"
            }
            value("max_listen_interval", "Maximum allowed Listen Interval", tab = "advanced") {
                optional = true; placeholder = "65535"; datatype = "uinteger"
            }
            flag("disassoc_low_ack", "Disassociate On Low Acknowledgement", "Allow AP mode to disconnect STAs based on low ACK condition", tab = "advanced") {
                default = enabled
            }
        }

        val encr = list("encryption", "Encryption", tab = "encryption") {
            listOf("ap", "sta", "adhoc", "ahdemo", "ap-wds", "sta-wds", "mesh").forEach { depends("mode", it) }
            cfgvalueFn = { rt, s ->
                rt.uci.getString("wireless", s, "encryption")?.let { v ->
                    when {
                        v == "wep" -> "wep-open"
                        v.contains('+') -> v.replace(Regex("\\+.+$"), "")
                        else -> v
                    }
                }
            }
        }
        val cipher = list("cipher", "Cipher", tab = "encryption") {
            listOf("wpa", "wpa2", "wpa3", "wpa3-mixed", "wpa3-192", "psk", "sae", "psk2", "wpa-mixed", "psk-mixed").forEach { depends("encryption", it) }
            value("auto", "auto")
            value("ccmp", "Force CCMP (AES)")
            value("ccmp256", "Force CCMP-256 (AES)")
            value("gcmp", "Force GCMP (AES)")
            value("gcmp256", "Force GCMP-256 (AES)")
            value("tkip", "Force TKIP")
            value("tkip+ccmp", "Force TKIP and CCMP (AES)")
            cfgvalueFn = { rt, s ->
                var v = rt.uci.getString("wireless", s, "encryption") ?: "undefined"
                if (v.contains('+')) {
                    v = v.replace(Regex("^[^+]+\\+"), "")
                    if (v == "aes") v = "ccmp"
                    else if (v == "tkip+aes" || v == "aes+tkip" || v == "ccmp+tkip") v = "tkip+ccmp"
                }
                if (choices.any { it.first == v }) v else "auto"
            }
        }
        val encWrite = { rt: FormRuntime, s: String, value: Any ->
            var e = rt.formString(encr, s)
            val c = rt.formString(cipher, s)
            if (value.toString() in setOf("wpa", "wpa2", "wpa3", "wpa3-mixed", "wpa3-192")) rt.uci.unset("wireless", s, "key")
            if (rt.isActive(cipher, s) && e.isNotEmpty() && c in setOf("tkip", "ccmp", "ccmp256", "gcmp", "gcmp256", "tkip+ccmp")) e += "+$c"
            rt.uci.set("wireless", s, "encryption", e)
        }
        encr.writeFn = encWrite
        cipher.writeFn = encWrite

        val cryptoModes = ArrayList<Triple<String, String, Int>>()
        val is6ghz = uci.getString("wireless", radio, "band") == "6g"

        if (mac80211) {
            val hasSupplicant = feature("wpasupplicant")
            val hasHostapd = feature("hostapd")
            val hasApEap = feature("hostapd", "eap"); val hasStaEap = feature("wpasupplicant", "eap")
            val hasApSae = feature("hostapd", "sae"); val hasStaSae = feature("wpasupplicant", "sae")
            val hasApOwe = feature("hostapd", "owe"); val hasStaOwe = feature("wpasupplicant", "owe")
            val hasApEap192 = feature("hostapd", "suiteb192"); val hasStaEap192 = feature("wpasupplicant", "suiteb192")
            val hasApWep = feature("hostapd", "wep"); val hasStaWep = feature("wpasupplicant", "wep")

            if (hasHostapd || hasSupplicant) {
                if (!is6ghz) {
                    cryptoModes += Triple("psk2", "WPA2-PSK", 35)
                    cryptoModes += Triple("psk-mixed", "WPA-PSK/WPA2-PSK Mixed Mode", 22)
                    cryptoModes += Triple("psk", "WPA-PSK", 12)
                }
            } else {
                encr.description = "WPA-Encryption requires wpa_supplicant (for client mode) or hostapd (for AP and ad-hoc mode) to be installed."
            }
            if (hasApSae || hasStaSae) {
                cryptoModes += Triple("sae", "WPA3-SAE", 31)
                if (!is6ghz) cryptoModes += Triple("sae-mixed", "WPA2-PSK/WPA3-SAE Mixed Mode", 30)
            }
            if (hasApSae && !is6ghz) cryptoModes += Triple("sae-compat", "WPA2-PSK/WPA3-SAE Compatibility Mode", 30)
            if (!is6ghz && (hasApWep || hasStaWep)) {
                cryptoModes += Triple("wep-open", "WEP Open System", 11)
                cryptoModes += Triple("wep-shared", "WEP Shared Key", 10)
            }
            if (hasApEap || hasStaEap) {
                if (hasApEap192 || hasStaEap192) {
                    cryptoModes += Triple("wpa3", "WPA3-EAP", 33)
                    if (!is6ghz) cryptoModes += Triple("wpa3-mixed", "WPA2-EAP/WPA3-EAP Mixed Mode", 32)
                    cryptoModes += Triple("wpa3-192", "WPA3-EAP 192-bit Mode", 36)
                }
                if (!is6ghz) {
                    cryptoModes += Triple("wpa2", "WPA2-EAP", 34)
                    cryptoModes += Triple("wpa", "WPA-EAP", 20)
                }
            }
            if (hasApOwe || hasStaOwe) cryptoModes += Triple("owe", "OWE", 1)

            fun req(ok: Boolean, msg: String): Any = if (ok) true else msg
            val ap = mapOf(
                "wep-open" to req(hasApWep, "Requires hostapd with WEP support"),
                "wep-shared" to req(hasApWep, "Requires hostapd with WEP support"),
                "psk" to req(hasHostapd, "Requires hostapd"),
                "psk2" to req(hasHostapd, "Requires hostapd"),
                "psk-mixed" to req(hasHostapd, "Requires hostapd"),
                "sae" to req(hasApSae, "Requires hostapd with SAE support"),
                "sae-mixed" to req(hasApSae, "Requires hostapd with SAE support"),
                "sae-compat" to req(hasApSae, "Requires hostapd with SAE support"),
                "wpa" to req(hasApEap, "Requires hostapd with EAP support"),
                "wpa2" to req(hasApEap, "Requires hostapd with EAP support"),
                "wpa3" to req(hasApEap192, "Requires hostapd with EAP Suite-B support"),
                "wpa3-mixed" to req(hasApEap192, "Requires hostapd with EAP Suite-B support"),
                "wpa3-192" to req(hasApEap192, "Requires hostapd with EAP Suite-B support"),
                "owe" to req(hasApOwe, "Requires hostapd with OWE support"),
            )
            val sta = mapOf(
                "wep-open" to req(hasStaWep, "Requires wpa-supplicant with WEP support"),
                "wep-shared" to req(hasStaWep, "Requires wpa-supplicant with WEP support"),
                "psk" to req(hasSupplicant, "Requires wpa-supplicant"),
                "psk2" to req(hasSupplicant, "Requires wpa-supplicant"),
                "psk-mixed" to req(hasSupplicant, "Requires wpa-supplicant"),
                "sae" to req(hasStaSae, "Requires wpa-supplicant with SAE support"),
                "sae-mixed" to req(hasStaSae, "Requires wpa-supplicant with SAE support"),
                "wpa" to req(hasStaEap, "Requires wpa-supplicant with EAP support"),
                "wpa2" to req(hasStaEap, "Requires wpa-supplicant with EAP support"),
                "wpa3" to req(hasStaEap192, "Requires wpa-supplicant with EAP Suite-B support"),
                "wpa3-mixed" to req(hasStaEap192, "Requires wpa-supplicant with EAP Suite-B support"),
                "wpa3-192" to req(hasStaEap192, "Requires wpa-supplicant with EAP Suite-B support"),
                "owe" to req(hasStaOwe, "Requires wpa-supplicant with OWE support"),
            )
            val support: Map<String, Map<String, Any>> = mapOf(
                "ap" to ap,
                "sta" to sta,
                "adhoc" to mapOf(
                    "wep-open" to true, "wep-shared" to true,
                    "psk" to req(hasSupplicant, "Requires wpa-supplicant"),
                    "psk2" to req(hasSupplicant, "Requires wpa-supplicant"),
                    "psk-mixed" to req(hasSupplicant, "Requires wpa-supplicant"),
                ),
                "mesh" to mapOf("sae" to req(hasStaSae, "Requires wpa-supplicant with SAE support")),
                "ahdemo" to mapOf("wep-open" to true, "wep-shared" to true),
                "wds" to mapOf("wep-open" to true, "wep-shared" to true),
                "ap-wds" to ap,
                "sta-wds" to sta,
            )
            encr.validate = { rt, s, value ->
                val modeval = rt.formString(mode, s)
                val modetitle = mode.choicesFor(rt, s).firstOrNull { it.first == modeval }?.second ?: "undefined"
                val enctitle = encr.choicesFor(rt, s).firstOrNull { it.first == value }?.second ?: "undefined"
                when {
                    value == "none" -> null
                    support[modeval]?.containsKey(value) != true -> "The selected %s mode is incompatible with %s encryption".format(modetitle, enctitle)
                    else -> support[modeval]!![value] as? String
                }
            }
        } else if (hwtype == "broadcom") {
            cryptoModes += Triple("psk2", "WPA2-PSK", 33)
            cryptoModes += Triple("psk+psk2", "WPA-PSK/WPA2-PSK Mixed Mode", 22)
            cryptoModes += Triple("psk", "WPA-PSK", 12)
            cryptoModes += Triple("wep-open", "WEP Open System", 11)
            cryptoModes += Triple("wep-shared", "WEP Shared Key", 10)
        }
        if (!is6ghz) cryptoModes += Triple("none", "No Encryption", 0)
        cryptoModes.sortByDescending { it.third }
        for ((k, label, level) in cryptoModes) {
            val sec = when {
                level >= 30 -> "strong security"
                level >= 20 -> "medium security"
                level >= 10 -> "weak security"
                else -> "open network"
            }
            encr.value(k, "%s (%s)".format(label, sec))
        }
        encr.default = cryptoModes.firstOrNull()?.first

        dummy("_qrops", "QR Code", "SSID and passwords with URIencoded sequences (e.g. %20) may not work.", tab = "encryption") {
            modalonly = true
            render = { rt, s -> QrButton(rt, s, hooks) }
        }

        flag("ppsk", "Enable Private PSK (PPSK)", "Private Pre-Shared Key (PPSK) allows the use of different Pre-Shared Key for each STA MAC address. Private MAC PSKs are stored on the RADIUS server.", tab = "encryption") {
            perms(AP, PSK)
        }
        val radiusDeps: FormOption.() -> Unit = { perms(AP, EAP); perms(AP, PSK, "ppsk" to listOf("1")) }
        value("auth_server", "RADIUS Authentication Server", tab = "encryption") { this.radiusDeps(); rmempty = true; datatype = "host(0)" }
        value("auth_port", "RADIUS Authentication Port", tab = "encryption") { this.radiusDeps(); rmempty = true; datatype = "port"; placeholder = "1812" }
        value("auth_secret", "RADIUS Authentication Secret", tab = "encryption") { this.radiusDeps(); rmempty = true; password = true }
        value("acct_server", "RADIUS Accounting Server", tab = "encryption") { perms(AP, EAP); rmempty = true; datatype = "host(0)" }
        value("acct_port", "RADIUS Accounting Port", tab = "encryption") { perms(AP, EAP); rmempty = true; datatype = "port"; placeholder = "1813" }
        value("acct_secret", "RADIUS Accounting Secret", tab = "encryption") { perms(AP, EAP); rmempty = true; password = true }

        val attrValidate = { _: FormRuntime, _: String, v: String ->
            if (v.isEmpty() || Regex("^[0-9]+(:s:.+|:d:[0-9]+|:x:([0-9a-zA-Z]{2})+)?$").matches(v)) null
            else "Must be in %s format.".format("<attr_id>[:<syntax:value>]")
        }
        val reqAttrSyntax = "Format:" + "<code>&lt;attr_id&gt;[:&lt;syntax:value&gt;]</code>" + "<br />" +
            "<code>syntax: s = %s; ".format("string (UTF-8)") + "d = %s; ".format("integer") + "x = %s</code>".format("octet string")
        dynlist("radius_auth_req_attr", "RADIUS Access-Request attributes", "Attributes to add/replace in each request." + "<br />" + reqAttrSyntax, tab = "encryption") {
            perms(AP, EAP); rmempty = true; validate = attrValidate; placeholder = "126:s:Operator"
        }
        dynlist("radius_acct_req_attr", "RADIUS Accounting-Request attributes", "Attributes to add/replace in each request." + "<br />" + reqAttrSyntax, tab = "encryption") {
            perms(AP, EAP); rmempty = true; validate = attrValidate; placeholder = "77:x:74657374696e67"
        }
        list("dynamic_vlan", "RADIUS Dynamic VLAN Assignment", "Required: Rejects auth if RADIUS server does not provide appropriate VLAN attributes.", tab = "encryption") {
            this.radiusDeps()
            value("0", "Disabled"); value("1", "Optional"); value("2", "Required")
            default = "0"
            writeFn = { rt, s, v -> rt.uci.set("wireless", s, "dynamic_vlan", if (v.toString() == "0") null else v) }
        }
        flag("per_sta_vif", "RADIUS Per STA VLAN", "Each STA is assigned its own AP_VLAN interface.", tab = "encryption") { this.radiusDeps() }
        flag("vlan_naming", "RADIUS VLAN Naming", "Off: <code>vlanXXX</code>, e.g., <code>vlan1</code>. On: <code>vlan_tagged_interface.XXX</code>, e.g. <code>eth0.1</code>.", tab = "encryption") {
            this.radiusDeps(); enabled = "1"; disabled = "0"; default = enabled
        }
        deviceSelect("vlan_tagged_interface", "RADIUS VLAN Tagged Interface", "E.g. eth0, eth1", tab = "encryption", multiple = false, nocreate = true, noaliases = true, noinactive = true) {
            this.radiusDeps(); rmempty = true
        }
        value("vlan_bridge", "RADIUS VLAN Bridge Naming Scheme", "E.g. <code>br-vlan</code> or <code>brvlan</code>.", tab = "encryption") { this.radiusDeps(); rmempty = true }
        value("dae_client", "DAE-Client", "Dynamic Authorization Extension client.", tab = "encryption") { perms(AP, EAP); rmempty = true; datatype = "host(0)" }
        value("dae_port", "DAE-Port", "Dynamic Authorization Extension port.", tab = "encryption") { perms(AP, EAP); rmempty = true; datatype = "port"; placeholder = "3799" }
        value("dae_secret", "DAE-Secret", "Dynamic Authorization Extension secret.", tab = "encryption") { perms(AP, EAP); rmempty = true; password = true }
        flag(
            "rsn_preauth", "RSN Preauth",
            "Robust Security Network (RSN): Allow roaming preauth for WPA2-EAP networks (and advertise it in WLAN beacons). Only works if the specified network interface is a bridge. Shortens the time-critical reassociation process.",
            tab = "encryption",
        ) { perms(AP, "encryption" to listOf("wpa2", "wpa3", "wpa3-mixed")) }

        value("_wpa_key", "Key", tab = "encryption") {
            perms(AP, PSK, "ppsk" to listOf("0"))
            perms("mode" to listOf("sta", "adhoc", "mesh", "sta-wds"), PSK)
            depends("encryption", "sae"); depends("encryption", "sae-mixed"); depends("encryption", "sae-compat")
            datatype = "wpakey"; rmempty = true; password = true
            cfgvalueFn = { rt, s -> rt.uci.getString("wireless", s, "key")?.takeIf { !Regex("^[1234]$").matches(it) } }
            writeFn = { rt, s, v ->
                rt.uci.set("wireless", s, "key", v)
                for (k in 1..4) rt.uci.unset("wireless", s, "key$k")
            }
        }
        list("_wep_key", "Used Key Slot", tab = "encryption") {
            depends("encryption", "wep-open"); depends("encryption", "wep-shared")
            for (k in 1..4) value("$k", "Key #%d".format(k))
            default = "1"
            cfgvalueFn = { rt, s ->
                val slot = rt.uci.getString("wireless", s, "key")?.trim()?.toIntOrNull()
                if (slot != null && slot in 1..4) "$slot" else null
            }
            writeFn = { rt, s, v -> rt.uci.set("wireless", s, "key", v) }
        }
        for (slot in 1..4) {
            value("key$slot", "Key #%d".format(slot), tab = "encryption") {
                depends("encryption", "wep-open"); depends("encryption", "wep-shared")
                datatype = "wepkey"; rmempty = true; password = true
                writeFn = { rt, s, v0 ->
                    val v = v0.toString()
                    rt.uci.set("wireless", s, "key$slot", if (v.length == 5 || v.length == 13) "s:%s".format(v) else v)
                }
            }
        }

        if (mac80211) {
            val has80211r = feature("hostapd", "11r") || feature("hostapd", "eap")
            val r = "ieee80211r" to "1"

            flag("ieee80211r", "802.11r Fast Transition", "Enables fast roaming among access points that belong to the same Mobility Domain", tab = "roaming") {
                perms(AP, "encryption" to listOf("wpa2", "wpa3", "wpa3-mixed", "wpa3-192"))
                if (has80211r) perms(AP, "encryption" to listOf("psk2", "psk-mixed", "sae", "sae-mixed", "sae-compat"))
                rmempty = true
            }
            value("nasid", "NAS ID", "Used for two different purposes: RADIUS NAS ID and 802.11r R0KH-ID. Not needed with normal WPA(2)-PSK.", tab = "roaming") {
                perms(AP, EAP); depends(r); rmempty = true
            }
            value("mobility_domain", "Mobility Domain", "4-character hexadecimal ID", tab = "roaming") {
                depends(r); placeholder = "automatically derived from SSID"; datatype = "and(hexstring,length(4))"; rmempty = true
            }
            value("reassociation_deadline", "Reassociation Deadline", "time units (TUs / 1.024 ms) [1000-65535]", tab = "roaming") {
                depends(r); placeholder = "20000"; datatype = "range(1000,65535)"; rmempty = true
            }
            list("ft_over_ds", "FT protocol", tab = "roaming") {
                depends(r); value("0", "FT over the Air"); value("1", "FT over DS"); rmempty = true; default = "0"
            }
            flag(
                "ft_psk_generate_local", "Generate PMK locally",
                "When using a PSK, the PMK can be automatically generated. When enabled, the R0/R1 key options below are not applied. Disable this to use the R0 and R1 key options.",
                tab = "roaming",
            ) {
                perms("ieee80211r" to listOf("1"), AP, "encryption" to listOf("psk2", "psk-mixed"))
                default = enabled; rmempty = false
            }
            value("r0_key_lifetime", "R0 Key Lifetime", "minutes", tab = "roaming") {
                depends(r); placeholder = "10000"; datatype = "uinteger"; rmempty = true
            }
            value("r1_key_holder", "R1 Key Holder", "6-octet identifier as a hex string - no colons", tab = "roaming") {
                depends(r); placeholder = "automatically derived from Mobility Domain and PSK"; datatype = "and(hexstring,length(12))"; rmempty = true
            }
            flag("pmk_r1_push", "PMK R1 Push", tab = "roaming") { depends(r); placeholder = "0"; rmempty = true }
            dynlist(
                "r0kh", "External R0 Key Holder List",
                "List of R0KHs in the same Mobility Domain. <br />Format: MAC-address,NAS-Identifier,256-bit key as hex string. <br />This list is used to map R0KH-ID (NAS Identifier) to a destination MAC address when requesting PMK-R1 key from the R0KH that the STA used during the Initial Mobility Domain Association.",
                tab = "roaming",
            ) { depends(r); rmempty = true }
            dynlist(
                "r1kh", "External R1 Key Holder List",
                "List of R1KHs in the same Mobility Domain. <br />Format: MAC-address,R1KH-ID as 6 octets with colons,256-bit key as hex string. <br />This list is used to map R1KH-ID to a destination MAC address when sending PMK-R1 key from the R0KH. This is also the list of authorized R1KHs in the MD that can request PMK-R1 keys.",
                tab = "roaming",
            ) { depends(r); rmempty = true }

            if (feature("hostapd", "eap")) {
                flag("ieee80211k", "802.11k RRM", "Radio Resource Measurement - Sends beacons to assist roaming. Not all clients support this.", tab = "roaming") {
                    depends("mode", "ap"); depends("mode", "ap-wds")
                }
                flag("rrm_neighbor_report", "Neighbour Report", "802.11k: Enable neighbor report via radio measurements.", tab = "roaming") {
                    depends("ieee80211k" to "1"); default = enabled
                }
                flag("rrm_beacon_report", "Beacon Report", "802.11k: Enable beacon report via radio measurements.", tab = "roaming") {
                    depends("ieee80211k" to "1"); default = enabled
                }
                list("time_advertisement", "Time advertisement", "802.11v: Time Advertisement in management frames.", tab = "roaming") {
                    value("0", "Disabled"); value("2", "Enabled")
                    default = "0"
                    writeFn = { rt, s, v -> rt.uci.set("wireless", s, "time_advertisement", if (v.toString() == "2") v else null) }
                }
                val tz = uci.getString("system", "@system[0]", "timezone")
                value("time_zone", "Time zone", "802.11v: Local Time Zone Advertisement in management frames.", tab = "roaming") {
                    if (tz != null) value(tz)
                    rmempty = true
                }
                flag("wnm_sleep_mode", "WNM Sleep Mode", "802.11v: Wireless Network Management (WNM) Sleep Mode (extended sleep mode for stations).", tab = "roaming") { rmempty = true }
                flag("wnm_sleep_mode_no_keys", "WNM Sleep Mode Fixes", "802.11v: Wireless Network Management (WNM) Sleep Mode Fixes: Prevents reinstallation attacks.", tab = "roaming") { rmempty = true }
                flag("bss_transition", "BSS Transition", "802.11v: Basic Service Set (BSS) transition management.", tab = "roaming") { rmempty = true }
                flag("proxy_arp", "ProxyARP", "802.11v: Proxy ARP enables non-AP STA to remain in power-save for longer.", tab = "roaming") { rmempty = true }
            }
        }

        if (mac80211) {
            list("eap_type", "EAP-Method", tab = "encryption") {
                value("tls", "TLS"); value("ttls", "TTLS"); value("peap", "PEAP"); value("fast", "FAST")
                default = "tls"
                perms(STA, EAP)
            }
            val cabundle = { _: FormRuntime, _: String, v: String ->
                if (v == "1" && !feature("cabundle")) "This option cannot be used because the ca-bundle package is not installed." else null
            }
            flag("ca_cert_usesystem", "Use system certificates", CERT_SYSTEM, tab = "encryption") {
                enabled = "1"; disabled = "0"; default = disabled
                perms(STA, EAP)
                validate = cabundle
            }
            file("ca_cert", "Path to CA-Certificate", tab = "encryption") { perms(STA, EAP, "ca_cert_usesystem" to listOf("0")) }
            value("subject_match", "Certificate constraint (Subject)", SUBJECT_DESC, tab = "encryption") { perms(STA, EAP) }
            dynlist("altsubject_match", "Certificate constraint (SAN)", SAN_DESC, tab = "encryption") { perms(STA, EAP) }
            dynlist("domain_match", "Certificate constraint (Domain)", DOMAIN_DESC, tab = "encryption") { perms(STA, EAP) }
            dynlist("domain_suffix_match", "Certificate constraint (Wildcard)", WILDCARD_DESC, tab = "encryption") { perms(STA, EAP) }
            val tls = "eap_type" to listOf("tls")
            file("client_cert", "Path to Client-Certificate", tab = "encryption") { perms(STA, EAP, tls) }
            file("private_key", "Path to Private Key", tab = "encryption") { perms(STA, EAP, tls) }
            value("private_key_passwd", "Password of Private Key", tab = "encryption") { perms(STA, EAP, tls); password = true }


            val eapType = options.first { it.option == "eap_type" }
            list("auth", "Authentication", tab = "encryption") {
                value("PAP", "PAP"); value("CHAP", "CHAP"); value("MSCHAP", "MSCHAP"); value("MSCHAPV2", "MSCHAPv2")
                value("EAP-GTC", "EAP-GTC"); value("EAP-MD5", "EAP-MD5"); value("EAP-MSCHAPV2", "EAP-MSCHAPv2"); value("EAP-TLS", "EAP-TLS")
                default = "PAP"
                perms(STA, EAP, "eap_type" to listOf("fast", "peap", "ttls"))
                validate = { rt, s, v ->
                    val ev = rt.formString(eapType, s)
                    if (ev != "ttls" && v in setOf("PAP", "CHAP", "MSCHAP", "MSCHAPV2")) "This authentication type is not applicable to the selected EAP method." else null
                }
            }
            val inner = "auth" to listOf("EAP-TLS")
            flag("ca_cert2_usesystem", "Use system certificates for inner-tunnel", CERT_SYSTEM, tab = "encryption") {
                enabled = "1"; disabled = "0"; default = disabled
                perms(STA, EAP, inner)
                validate = cabundle
            }
            file("ca_cert2", "Path to inner CA-Certificate", tab = "encryption") { perms(STA, EAP, inner, "ca_cert2_usesystem" to listOf("0")) }
            value("subject_match2", "Inner certificate constraint (Subject)", SUBJECT_DESC, tab = "encryption") { perms(STA, EAP, inner) }
            dynlist("altsubject_match2", "Inner certificate constraint (SAN)", SAN_DESC, tab = "encryption") { perms(STA, EAP, inner) }
            dynlist("domain_match2", "Inner certificate constraint (Domain)", DOMAIN_DESC, tab = "encryption") { perms(STA, EAP, inner) }
            dynlist("domain_suffix_match2", "Inner certificate constraint (Wildcard)", WILDCARD_DESC, tab = "encryption") { perms(STA, EAP, inner) }
            file("client_cert2", "Path to inner Client-Certificate", tab = "encryption") { perms(STA, EAP, inner) }
            file("private_key2", "Path to inner Private Key", tab = "encryption") { perms(STA, EAP, inner) }
            value("private_key2_passwd", "Password of inner Private Key", tab = "encryption") { perms(STA, EAP, inner); password = true }
            value("identity", "Identity", tab = "encryption") { perms(STA, EAP, "eap_type" to listOf("fast", "peap", "tls", "ttls")) }
            value("anonymous_identity", "Anonymous Identity", tab = "encryption") { perms(STA, EAP, "eap_type" to listOf("fast", "peap", "tls", "ttls")) }
            value("password", "Password", tab = "encryption") { perms(STA, EAP, "eap_type" to listOf("fast", "peap", "ttls")); password = true }

            val encActive = { rt: FormRuntime, s: String -> if (rt.isActive(encr, s)) rt.formString(encr, s) else null }
            list("ieee80211w", "802.11w Management Frame Protection", "Note: Some wireless drivers do not fully support 802.11w. E.g. mwlwifi may have problems", tab = "encryption") {
                value("0", "Disabled"); value("1", "Optional"); value("2", "Required")
                perms("mode" to listOf("ap", "ap-wds", "sta", "sta-wds"), "encryption" to listOf("owe", "psk2", "psk-mixed", "sae", "sae-mixed", "wpa2", "wpa3", "wpa3-mixed"))
                defaultFn = { rt, s ->
                    when (encActive(rt, s)) {
                        "sae-mixed" -> "1"
                        "sae", "owe", "wpa3", "wpa3-mixed" -> "2"
                        else -> "0"
                    }
                }
                writeFn = { rt, s, v ->
                    if (v.toString() != rt.defaultOf(this, s)?.toString()) rt.uci.set("wireless", s, "ieee80211w", v)
                    else rt.uci.unset("wireless", s, "ieee80211w")
                }
            }
            value("ieee80211w_max_timeout", "802.11w maximum timeout", "802.11w Association SA Query maximum timeout", tab = "encryption") {
                depends("ieee80211w", "1"); depends("ieee80211w", "2"); datatype = "uinteger"; placeholder = "1000"; rmempty = true
            }
            value("ieee80211w_retry_timeout", "802.11w retry timeout", "802.11w Association SA Query retry timeout", tab = "encryption") {
                depends("ieee80211w", "1"); depends("ieee80211w", "2"); datatype = "uinteger"; placeholder = "201"; rmempty = true
            }
            if (feature("hostapd", "ocv") || feature("wpasupplicant", "ocv")) {
                list("ocv", "Operating Channel Validation", "Note: Workaround mode allows a STA that claims OCV capability to connect even if the STA doesn't send OCI or negotiate PMF.", tab = "encryption") {
                    value("0", "Disabled"); value("1", "Enabled"); value("2", "Enabled (workaround mode)")
                    default = "0"
                    depends("ieee80211w", "1"); depends("ieee80211w", "2")
                    validate = { rt, s, v ->
                        val modeval = rt.formString(mode, s)
                        if (v == "2" && (modeval == "sta" || modeval == "sta-wds")) "Workaround mode can only be used when acting as an access point." else null
                    }
                }
            }
            flag(
                "wpa_disable_eapol_key_retries", "Enable key reinstallation (KRACK) countermeasures",
                "Complicates key reinstallation attacks on the client side by disabling retransmission of EAPOL-Key frames that are used to install keys. This workaround might cause interoperability issues and reduced robustness of key negotiation especially in environments with heavy traffic load.",
                tab = "encryption",
            ) { perms(AP, "encryption" to listOf("psk2", "psk-mixed", "sae", "sae-mixed", "sae-compat", "wpa2", "wpa3", "wpa3-mixed")) }

            val ehtDefault = { rt: FormRuntime, s: String ->
                val d = rt.uci.getString("wireless", s, "device")
                var htmode = d?.let { rt.uci.getString("wireless", it, "htmode") }
                if (d != null && d == radio) htmode = splitFreq(rt.formString(freqOpt, d))[0]
                if (htmode != null && htmode.startsWith("EHT")) {
                    when (encActive(rt, s)) {
                        "sae", "sae-mixed" -> "0"
                        "sae-compat" -> "1"
                        else -> null
                    }
                } else "0"
            }
            flag(
                "gcmp256", "GCMP-256 pairwise cipher",
                "Advertise the GCMP-256 pairwise cipher. Mandatory for Wi-Fi 7 (EHT) and recommended otherwise, but some clients and chipsets fail to associate when it is offered. Enabled by default only in Compatibility Mode on a Wi-Fi 7 (EHT) radio, disabled otherwise.",
                tab = "encryption",
            ) { perms(AP, "encryption" to listOf("sae", "sae-mixed", "sae-compat")); defaultFn = ehtDefault }
            flag(
                "sae_ext_key", "SAE-EXT-KEY (SAE-GDH)",
                "Advertise the SAE-EXT-KEY AKM (SAE using a group-dependent hash). Mandatory for Wi-Fi 7 (EHT) and recommended otherwise, but some clients misbehave when it is offered, in particular together with 802.11r Fast Transition. Enabled by default only in Compatibility Mode on a Wi-Fi 7 (EHT) radio, disabled otherwise.",
                tab = "encryption",
            ) { perms(AP, "encryption" to listOf("sae", "sae-mixed", "sae-compat")); defaultFn = ehtDefault }
            flag(
                "transition_disable", "Transition Disable",
                "Signal Transition Disable (WPA3 Specification v3.5 section 13) so that a client which has connected once no longer downgrades to a weaker security mode for this SSID. The advertised bitmap is derived from the encryption mode.",
                tab = "encryption",
            ) {
                enabled = "on"
                perms(AP, "encryption" to listOf("sae", "wpa3", "wpa3-192", "owe"))
                cfgvalueFn = { rt, s ->
                    val v = UciStore.toArray(rt.uci.get("wireless", s, "transition_disable")).firstOrNull()
                    if (v != null && v != "off" && v != "0") "on" else "0"
                }
            }
            if (feature("hostapd", "wps") && feature("wpasupplicant")) {
                flag("wps_pushbutton", "Enable WPS pushbutton, requires WPA(2)-PSK/WPA3-SAE", tab = "encryption") {
                    enabled = "1"; disabled = "0"; default = disabled
                    listOf("psk", "psk2", "psk-mixed", "sae", "sae-mixed", "sae-compat").forEach { depends("encryption", it) }
                }
            }
        }
    }
    return WifiEditForm(m, dev, iface, radio, sid, title)
}

/** CBIWifiTxPowerValue: the select plus the current power read from iwinfo. */
@Composable
private fun TxPowerField(rt: FormRuntime, o: ListValueOption, sid: String, powerval: Double?, poweroff: Double) {
    rt.revision.intValue
    Column(Modifier.fillMaxWidth()) {
        SelectField(
            value = rt.formString(o, sid), choices = o.choicesFor(rt, sid), label = o.title?.let(::stripHtml),
            onChange = { rt.setValue(o, sid, it) }, enabled = !rt.isDisabled(o, sid), supporting = o.description,
            optional = true,
        )
        val cur = powerval?.let { "${jsNum(it)} dBm" } ?: "unknown"
        val off = if (poweroff != 0.0) " + ${jsNum(poweroff)} dB offset = ${powerval?.let { jsNum(it + poweroff) } ?: "?"} dBm" else ""
        Text(
            "Current power: $cur$off", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 2.dp),
        )
    }
}
