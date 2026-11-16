package com.the412banner.wrtpanel.pages.network.proto

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.form.Datatypes
import com.the412banner.wrtpanel.form.FormSection
import com.the412banner.wrtpanel.form.ValueOption
import com.the412banner.wrtpanel.pages.common.NetTools
import com.the412banner.wrtpanel.pages.network.interfaces.OptionCombo
import com.the412banner.wrtpanel.pages.network.interfaces.OptionTextField
import com.the412banner.wrtpanel.pages.network.interfaces.OptionValueList

/** protocol/none.js */
object NoneProto : ProtoHandler {
    override val name = "none"
    override val i18n = "Unmanaged"
}

/** protocol/static.js */
object StaticProto : ProtoHandler {
    override val name = "static"
    override val i18n = "Static address"

    private val cidrRe = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}/(\\d{1,2}|\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})$")
    fun isCIDR(v: Any?): Boolean = v is List<*> || (v is String && cidrRe.matches(v))

    /** calculateBroadcast() */
    private fun broadcast(addrs: List<String>, mask: String?): String? {
        val first = if (addrs.isNotEmpty() && addrs[0].isNotEmpty() && !mask.isNullOrEmpty()) "${addrs[0]}/$mask"
        else addrs.firstOrNull { it.indexOf('/') > 0 } ?: return null
        val parts = first.split('/')
        val addr = Datatypes.parseIPv4(parts[0]) ?: return null
        val ms = parts.getOrNull(1) ?: return null
        val m = if (ms.toDoubleOrNull() != null) NetTools.prefixToMask(ms.toDouble().toInt(), false)?.let { Datatypes.parseIPv4(it) } else Datatypes.parseIPv4(ms)
        if (m == null) return null
        return (0 until 4).joinToString(".") { i -> (addr[i] or (m[i].inv() and 255)).toString() }
    }

    override fun renderFormOptions(s: FormSection, p: ProtoForm) {
        val cidr = mutableStateOf(isCIDR(p.ctx.uci.get("network", p.sid, "ipaddr")))
        lateinit var ipOpt: ValueOption
        lateinit var maskOpt: ValueOption

        ipOpt = s.value("ipaddr", "IPv4 address", tab = "general") {
            validate = { rt, sid, _ ->
                if (cidr.value) rt.formList(ipOpt, sid).firstNotNullOfOrNull { a -> if (a.isEmpty()) null else Datatypes.validate("or(cidr4,ipmask4)", a) }
                else rt.formString(ipOpt, sid).takeIf { it.isNotEmpty() }?.let { Datatypes.validate("ip4addr(\"nomask\")", it) }
            }
            writeFn = { rt, sid, _ ->
                rt.uci.set("network", sid, "ipaddr", if (cidr.value) rt.formList(ipOpt, sid).filter { it.isNotEmpty() } else rt.formString(ipOpt, sid))
            }
            render = { rt, sid ->
                if (cidr.value) {
                    OptionValueList(rt, ipOpt, sid, "or(cidr4,ipmask4)", "Add IPv4 address…")
                } else {
                    OptionTextField(rt, ipOpt, sid, datatype = "ip4addr", trailing = {
                        TextButton(onClick = {
                            if (rt.validateField(ipOpt, sid) != null) return@TextButton
                            val maskval = rt.formString(maskOpt, sid)
                            val addrval = rt.formString(ipOpt, sid)
                            val prefix = if (maskval.isNotEmpty()) NetTools.maskToPrefix(maskval, false) else 32
                            if (prefix == null) return@TextButton
                            cidr.value = true
                            rt.setValue(ipOpt, sid, if (addrval.isNotEmpty()) listOf("$addrval/$prefix") else emptyList<String>())
                            rt.setValue(maskOpt, sid, null)
                        }, enabled = !rt.isDisabled(ipOpt, sid)) { Text("…") }
                    })
                }
            }
        }
        maskOpt = s.value("netmask", "IPv4 netmask", tab = "general") {
            datatype = "ip4addr(\"true\")"
            value("255.255.255.0"); value("255.255.0.0"); value("255.0.0.0")
            cfgvalueFn = { rt, sid -> if (cidr.value) null else rt.uci.get("network", sid, "netmask") }
            render = { rt, sid -> if (!cidr.value) OptionCombo(rt, maskOpt, sid) }
        }
        s.value("gateway", "IPv4 gateway", tab = "general") {
            datatype = "ip4addr(\"nomask\")"
            val wans = p.model.getWANNetworks()
            if (wans.size == 1) {
                val gw = wans[0].getGatewayAddr()
                placeholder = if (gw != null) "$gw (${wans[0].sid})" else ""
            }
            validate = { rt, sid, v ->
                val addrs = UciStore.toArray(rt.cfgvalue(ipOpt, sid))
                if (addrs.any { it.split('/')[0] == v }) "The gateway address must not be a local IP address" else null
            }
        }
        s.value("broadcast", "IPv4 broadcast", tab = "general") {
            datatype = "ip4addr(\"nomask\")"
            placeholderFn = { rt, sid -> broadcast(UciStore.toArray(rt.formvalue(ipOpt, sid)), rt.formString(maskOpt, sid).takeIf { !cidr.value }) }
        }
        s.dynlist("ip6addr", "IPv6 address", tab = "general") { datatype = "ip6addr"; placeholder = "Add IPv6 address…" }
        s.value("ip6gw", "IPv6 gateway", tab = "general") { datatype = "ip6addr(\"nomask\")" }
        s.value("ip6prefix", "IPv6 routed prefix", "Public prefix routed to this device for distribution to clients.", tab = "general") { datatype = "ip6addr" }
    }
}

/** protocol/dhcp.js */
object DhcpProto : ProtoHandler {
    override val name = "dhcp"
    override val i18n = "DHCP client"

    override fun renderFormOptions(s: FormSection, p: ProtoForm) {
        s.value("hostname", "Hostname to send when requesting DHCP", tab = "general") {
            default = ""
            value("", "Send the hostname of this device")
            value("*", "Do not send a hostname")
            datatype = "or(hostname, \"*\")"
            loadFn = { _ -> placeholder = fs.readOrNull("/proc/sys/kernel/hostname")?.trim() }
        }
        s.flag("broadcast", "Use broadcast flag", "Required for certain ISPs, e.g. Charter with DOCSIS 3", tab = "advanced")
        s.list(
            "sendclientid", "Preferred client ID",
            "Selects the client identifier (DHCP option 61) to send when requesting DHCP.<br />" +
                "<em>Automatic</em> uses the client ID configured below, else the default DUID of this device, else the MAC address of this interface.<br />" +
                "<em>Default DUID</em> always uses the default DUID of this device, even if a client ID is configured below.<br />" +
                "<em>Hardware address</em> always uses the MAC address of this interface.<br />" +
                "<em>None</em> sends no client identifier at all.",
            tab = "advanced",
        ) {
            value("auto", "Automatic"); value("global", "Default DUID"); value("hardware", "Hardware address"); value("none", "None")
            default = "auto"
        }
        s.value("clientid", "Client ID to send when requesting DHCP", tab = "advanced") {
            datatype = "hexstring"
            depends("sendclientid", "auto")
        }
        s.value("vendorid", "Vendor Class to send when requesting DHCP", tab = "advanced")
    }
}
