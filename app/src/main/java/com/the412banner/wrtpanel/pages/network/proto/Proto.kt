package com.the412banner.wrtpanel.pages.network.proto

import com.the412banner.wrtpanel.form.FormRuntime
import com.the412banner.wrtpanel.form.FormSection
import com.the412banner.wrtpanel.form.ValueOption
import com.the412banner.wrtpanel.pages.network.interfaces.NetIface
import com.the412banner.wrtpanel.pages.network.interfaces.NetModel
import com.the412banner.wrtpanel.ui.PageContext

/**
 * A LuCI protocol handler (network.registerProtocol): name, label, device semantics and the
 * renderFormOptions() options added to the interface edit dialog.
 */
interface ProtoHandler {
    val name: String
    val i18n: String
    /** isFloating() */
    val floating: Boolean get() = false
    /** isVirtual() override; null = netifd's no_device flag. */
    val virtual: Boolean? get() = null
    /** getIfname() override: l3_device or "<prefix>-<interface>". */
    val ifnamePrefix: String? get() = null
    /** network.registerPatternVirtual() of the protocol script. */
    val virtualPattern: Regex? get() = null
    fun renderFormOptions(s: FormSection, p: ProtoForm) {}
}

/** What renderFormOptions() can reach: the page, the network state and the interface (this). */
class ProtoForm(val ctx: PageContext, val model: NetModel, val iface: NetIface) {
    val sid: String get() = iface.sid
    fun hasFeature(name: String) = model.hasFeature(name)
}

/** A protocol netifd supports and LuCI has a view for, without a native port (label = name). */
class GenericProto(override val name: String) : ProtoHandler {
    override val i18n: String get() = name
}

object ProtoRegistry {
    val handlers: Map<String, ProtoHandler> = listOf(
        NoneProto, StaticProto, DhcpProto,
        Dhcpv6Proto, Xlat464Proto, SixIn4Proto, SixRdProto, SixToFourProto, DsliteProto, Ipip6Proto, MapProto,
        PppProto, PppoeProto, PppoaProto, PptpProto, L2tpProto,
    ).associateBy { it.name }
}

/** write_keepalive() of the PPP protocol scripts. */
internal fun writeKeepalive(rt: FormRuntime, sid: String, failure: ValueOption, interval: ValueOption) {
    var i = rt.formString(interval, sid).trim().let { Regex("^-?\\d+").find(it)?.value?.toIntOrNull() }
    val f0 = rt.formString(failure, sid).trim().let { Regex("^-?\\d+").find(it)?.value?.toIntOrNull() }
    if (i == null) i = 1
    val f = f0 ?: if (i == 1) null else 5
    if (f != null) rt.uci.set("network", sid, "keepalive", "$f $i") else rt.uci.unset("network", sid, "keepalive")
}
