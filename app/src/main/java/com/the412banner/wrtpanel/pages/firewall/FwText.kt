package com.the412banner.wrtpanel.pages.firewall

import com.the412banner.wrtpanel.pages.firewall.FwTool.Fmt
import com.the412banner.wrtpanel.pages.firewall.FwTool.ZoneBadge
import com.the412banner.wrtpanel.pages.firewall.FwTool.fmt
import com.the412banner.wrtpanel.pages.firewall.FwTool.hex2
import com.the412banner.wrtpanel.pages.firewall.FwTool.jsNumber
import com.the412banner.wrtpanel.pages.firewall.FwTool.jsString
import com.the412banner.wrtpanel.pages.firewall.FwTool.mapInvert
import com.the412banner.wrtpanel.pages.firewall.FwTool.toArray

/** A conntrack helper from luci getConntrackHelpers. */
data class CtHelper(val name: String, val description: String?)

/**
 * The rule_*_txt() functions of view/firewall/forwards.js, rules.js and snats.js. [get] reads an
 * option of the section (uci.get('firewall', s, option)).
 */
object FwText {
    private fun str(v: Any?): String = if (v == null) "" else jsString(v)
    private fun truthy(v: Any?): Boolean = v != null && v != "" && v != false
    private fun num(s: String): Long = jsNumber(s).let { if (it.isNaN()) 0L else Math.floor(it).toLong() }

    /** E('span', { class: 'zonebadge', style }, [ '*' ? em 'any zone' : z ? strong z : em 'this device' ]) */
    fun zoneBadge(z: Any?): ZoneBadge {
        val zs = z?.let { jsString(it) }
        return when {
            zs == "*" -> ZoneBadge("any zone", FwTool.zoneColor(zs), false)
            truthy(z) -> ZoneBadge(zs!!, FwTool.zoneColor(zs), true)
            else -> ZoneBadge("this device", FwTool.zoneColor(null), false)
        }
    }

    private fun protoList(get: (String) -> Any?, withTypes: Boolean): List<Map<String, Any?>> =
        toArray(get("proto")).filter { it != "*" && it != "any" && it != "all" }.map { p ->
            val pr = FwTool.lookupProto(p)!!
            if (withTypes) mapOf("num" to pr.num, "name" to pr.label, "types" to if (pr.num == 1 || pr.num == 58) toArray(get("icmp_type")) else null)
            else mapOf("num" to pr.num, "name" to pr.label)
        }

    private fun helper(get: (String) -> Any?, ct: List<CtHelper>): Map<String, Any?>? {
        val m = Regex("^(!\\s*)?(\\S+)$").find(str(get("helper")).let { if (truthy(get("helper"))) it else "" }) ?: return null
        return mapOf(
            "val" to m.value.uppercase(),
            "inv" to m.groups[1]?.value,
            "name" to ct.firstOrNull { it.name.lowercase() == m.groupValues[2].lowercase() }?.description,
        )
    }

    private fun mark(get: (String) -> Any?): Map<String, Any?>? {
        val m = Regex("^(!\\s*)?(0x[0-9a-f]{1,8}|[0-9]{1,10})(?:/(0x[0-9a-f]{1,8}|[0-9]{1,10}))?$", RegexOption.IGNORE_CASE)
            .find(jsString(get("mark"))) ?: return null
        return mapOf(
            "val" to m.value.uppercase().replace("X", "x"),
            "inv" to m.groups[1]?.value,
            "num" to "0x" + hex2(num(m.groupValues[2])),
            "mask" to m.groups[3]?.value?.let { "0x" + hex2(num(it)) },
        )
    }

    private fun dscp(get: (String) -> Any?): Map<String, Any?>? {
        val m = Regex("^(!\\s*)?(?:(CS[0-7]|BE|AF[1234][123]|EF)|(0x[0-9a-f]{1,2}|[0-9]{1,2}))$").find(jsString(get("dscp"))) ?: return null
        return mapOf(
            "val" to m.value,
            "inv" to m.groups[1]?.value,
            "name" to m.groups[2]?.value,
            "num" to m.groups[3]?.value?.let { "0x" + hex2(num(it)) },
        )
    }

    private fun srcMacs(get: (String) -> Any?, hosts: Map<String, FwTool.Host>): List<Map<String, Any?>> =
        mapInvert(get("src_mac"), "toUpperCase").map { v ->
            val h = hosts[v["val"] as String]
            v + ("hint" to h?.let { mapOf("name" to it.name) })
        }

    /** rule_limit_txt(s), the same in all three views. */
    fun limit(get: (String) -> Any?): Fmt {
        val m = Regex("^(\\d+)/([smhd])\\w*$", RegexOption.IGNORE_CASE).find(jsString(get("limit"))) ?: return FwTool.EmptyFmt
        val units = mapOf("s" to "second", "m" to "minute", "h" to "hour", "d" to "day")
        val l = mapOf("num" to num(m.groupValues[1]), "unit" to units[m.groupValues[2]], "burst" to get("limit_burst"))
        return fmt("Limit matching to <var>%{limit.num}</var> packets per <var>%{limit.unit}</var>%{limit.burst? burst <var>%{limit.burst}</var>}", mapOf("limit" to l))
    }

    // ---- forwards.js ----

    fun forwardProto(get: (String) -> Any?, ct: List<CtHelper>): Fmt {
        val family = str(get("family")).lowercase().replace(Regex("^(?:all|\\*)$"), "any")
        val dip = str(get("dest_ip"))
        return fmt(
            "Incoming %{ipv6?%{ipv4?<var>IPv4</var> and <var>IPv6</var>:<var>IPv6</var>}:<var>IPv4</var>}%{proto?, protocol %{proto#%{next?, }%{item.types?<var class=\"cbi-tooltip-container\">%{item.name}<span class=\"cbi-tooltip\">ICMP with types %{item.types#%{next?, }<var>%{item}</var>}</span></var>:<var>%{item.name}</var>}}}%{mark?, mark <var%{mark.inv? data-tooltip=\"Match fwmarks except %{mark.num}%{mark.mask? with mask %{mark.mask}}.\":%{mark.mask? data-tooltip=\"Mask fwmark value with %{mark.mask} before compare.\"}}>%{mark.val}</var>}%{helper?, helper %{helper.inv?<var data-tooltip=\"Match any helper except &quot;%{helper.name}&quot;\">%{helper.val}</var>:<var data-tooltip=\"%{helper.name}\">%{helper.val}</var>}}",
            mapOf(
                "ipv4" to ((family.isEmpty() && !dip.contains(':')) || family == "any" || (family.isEmpty() && dip.isEmpty()) || family == "ipv4"),
                "ipv6" to ((family.isEmpty() && dip.contains(':')) || family == "any" || family == "ipv6"),
                "proto" to protoList(get, true),
                "helper" to helper(get, ct),
                "mark" to mark(get),
            ),
        )
    }

    fun forwardSrc(get: (String) -> Any?, hosts: Map<String, FwTool.Host>): Fmt = fmt(
        "From %{src}%{src_ip?, IP %{src_ip#%{next?, }<var%{item.inv? data-tooltip=\"Match IP addresses except %{item.val}.\"}>%{item.ival}</var>}}%{src_port?, port %{src_port#%{next?, }<var%{item.inv? data-tooltip=\"Match ports except %{item.val}.\"}>%{item.ival}</var>}}%{src_mac?, MAC %{src_mac#%{next?, }<var%{item.inv? data-tooltip=\"Match MACs except %{item.val}%{item.hint.name? a.k.a. %{item.hint.name}}.\":%{item.hint.name? data-tooltip=\"%{item.hint.name}\"}}>%{item.ival}</var>}}",
        mapOf(
            "src" to zoneBadge(get("src")),
            "src_ip" to mapInvert(get("src_ip"), "toLowerCase"),
            "src_mac" to srcMacs(get, hosts),
            "src_port" to mapInvert(get("src_port")),
        ),
    )

    fun forwardDest(get: (String) -> Any?): Fmt = fmt(
        "To %{dest}%{dest_ip?, IP %{dest_ip#%{next?, }<var%{item.inv? data-tooltip=\"Match IP addresses except %{item.val}.\"}>%{item.ival}</var>}}%{dest_port?, port %{dest_port#%{next?, }<var%{item.inv? data-tooltip=\"Match ports except %{item.val}.\"}>%{item.ival}</var>}}",
        mapOf(
            "dest" to ZoneBadge("this device", FwTool.zoneColor(null), false),
            "dest_ip" to mapInvert(get("src_dip"), "toLowerCase"),
            "dest_port" to mapInvert(get("src_dport")),
        ),
    )

    fun forwardTarget(get: (String) -> Any?): Fmt = fmt(
        "<var data-tooltip=\"DNAT\">Forward</var> to %{dest}%{dest_ip? IP <var>%{dest_ip}</var>}%{dest_port? port <var>%{dest_port}</var>}",
        mapOf(
            "dest" to zoneBadge(get("dest")),
            "dest_ip" to str(get("dest_ip")).lowercase(),
            "dest_port" to get("dest_port"),
        ),
    )

    // ---- rules.js ----

    fun ruleProto(get: (String) -> Any?, ct: List<CtHelper>): Fmt {
        val f = str(get("family")).lowercase().replace(Regex("^(?:any|\\*)$"), "")
        return fmt(
            "%{src?%{dest?Forwarded:Incoming}:Outgoing} %{ipv6?%{ipv4?<var>IPv4</var> and <var>IPv6</var>:<var>IPv6</var>}:<var>IPv4</var>}%{proto?, protocol %{proto#%{next?, }%{item.types?<var class=\"cbi-tooltip-container\">%{item.name}<span class=\"cbi-tooltip\">ICMP with types %{item.types#%{next?, }<var>%{item}</var>}</span></var>:<var>%{item.name}</var>}}}%{mark?, mark <var%{mark.inv? data-tooltip=\"Match fwmarks except %{mark.num}%{mark.mask? with mask %{mark.mask}}.\":%{mark.mask? data-tooltip=\"Mask fwmark value with %{mark.mask} before compare.\"}}>%{mark.val}</var>}%{dscp?, DSCP %{dscp.inv?<var data-tooltip=\"Match DSCP classifications except %{dscp.num?:%{dscp.name}}\">%{dscp.val}</var>:<var>%{dscp.val}</var>}}%{helper?, helper %{helper.inv?<var data-tooltip=\"Match any helper except &quot;%{helper.name}&quot;\">%{helper.val}</var>:<var data-tooltip=\"%{helper.name}\">%{helper.val}</var>}}",
            mapOf(
                "ipv4" to (f.isEmpty() || f == "ipv4"),
                "ipv6" to (f.isEmpty() || f == "ipv6"),
                "src" to get("src"),
                "dest" to get("dest"),
                "proto" to protoList(get, true),
                "helper" to helper(get, ct),
                "mark" to mark(get),
                "dscp" to dscp(get),
            ),
        )
    }

    fun ruleSrc(get: (String) -> Any?, hosts: Map<String, FwTool.Host>): Fmt = fmt(
        "From %{src}%{src_device?, interface <var>%{src_device}</var>}%{src_ip?, IP %{src_ip#%{next?, }<var%{item.inv? data-tooltip=\"Match IP addresses except %{item.val}.\"}>%{item.ival}</var>}}%{src_port?, port %{src_port#%{next?, }<var%{item.inv? data-tooltip=\"Match ports except %{item.val}.\"}>%{item.ival}</var>}}%{src_mac?, MAC %{src_mac#%{next?, }<var%{item.inv? data-tooltip=\"Match MACs except %{item.val}%{item.hint.name? a.k.a. %{item.hint.name}}.\":%{item.hint.name? data-tooltip=\"%{item.hint.name}\"}}>%{item.ival}</var>}}",
        mapOf(
            "src" to zoneBadge(get("src")),
            "src_ip" to mapInvert(get("src_ip"), "toLowerCase"),
            "src_mac" to srcMacs(get, hosts),
            "src_port" to mapInvert(get("src_port")),
            "src_device" to if (get("direction") == "in") get("device") else null,
        ),
    )

    fun ruleDest(get: (String) -> Any?): Fmt = fmt(
        "To %{dest}%{dest_device?, interface <var>%{dest_device}</var>}%{dest_ip?, IP %{dest_ip#%{next?, }<var%{item.inv? data-tooltip=\"Match IP addresses except %{item.val}.\"}>%{item.ival}</var>}}%{dest_port?, port %{dest_port#%{next?, }<var%{item.inv? data-tooltip=\"Match ports except %{item.val}.\"}>%{item.ival}</var>}}",
        mapOf(
            "dest" to zoneBadge(get("dest")),
            "dest_ip" to mapInvert(get("dest_ip"), "toLowerCase"),
            "dest_port" to mapInvert(get("dest_port")),
            "dest_device" to if (get("direction") == "out") get("device") else null,
        ),
    )

    fun ruleTarget(get: (String) -> Any?, ct: List<CtHelper>): Fmt {
        val t = get("target")
        val h = str(get("set_helper")).uppercase()
        val s = mapOf(
            "target" to t,
            "src" to get("src"),
            "dest" to get("dest"),
            "set_helper" to h,
            "set_mark" to get("set_mark"),
            "set_xmark" to get("set_xmark"),
            "set_dscp" to get("set_dscp"),
            "helper_name" to ct.firstOrNull { it.name.uppercase() == h }?.description,
        )
        return when (t) {
            "DROP" -> fmt("<var data-tooltip=\"DROP\">Drop</var> %{src?%{dest?forward:input}:output}", s)
            "ACCEPT" -> fmt("<var data-tooltip=\"ACCEPT\">Accept</var> %{src?%{dest?forward:input}:output}", s)
            "REJECT" -> fmt("<var data-tooltip=\"REJECT\">Reject</var> %{src?%{dest?forward:input}:output}", s)
            "NOTRACK" -> fmt("<var data-tooltip=\"NOTRACK\">Do not track</var> %{src?%{dest?forward:input}:output}", s)
            "HELPER" -> fmt("<var data-tooltip=\"HELPER\">Assign conntrack</var> helper <var%{helper_name? data-tooltip=\"%{helper_name}\"}>%{set_helper}</var>", s)
            "MARK" -> fmt("<var data-tooltip=\"MARK\">%{set_mark?Assign:XOR}</var> firewall mark <var>%{set_mark?:%{set_xmark}}</var>", s)
            "DSCP" -> fmt("<var data-tooltip=\"DSCP\">Assign DSCP</var> classification <var>%{set_dscp}</var>", s)
            else -> FwTool.plain(t?.let { jsString(it) } ?: "")
        }
    }

    // ---- snats.js ----

    fun natProto(get: (String) -> Any?): Fmt {
        val family = str(get("family")).lowercase().replace(Regex("^(?:all|\\*)$"), "any")
        val sip = str(get("src_ip"))
        val dip = str(get("dest_ip"))
        val rwip = str(get("snat_ip"))
        return fmt(
            "Forwarded %{ipv6?%{ipv4?<var>IPv4</var> and <var>IPv6</var>:<var>IPv6</var>}:<var>IPv4</var>}%{proto?, protocol %{proto#%{next?, }<var>%{item.name}</var>}}%{mark?, mark <var%{mark.inv? data-tooltip=\"Match fwmarks except %{mark.num}%{mark.mask? with mask %{mark.mask}}.\":%{mark.mask? data-tooltip=\"Mask fwmark value with %{mark.mask} before compare.\"}}>%{mark.val}</var>}",
            mapOf(
                "ipv4" to (family == "ipv4" || family == "any" || (family.isEmpty() && !sip.contains(':') && !dip.contains(':') && !rwip.contains(':'))),
                "ipv6" to (family == "ipv6" || family == "any" || (family.isEmpty() && (sip.contains(':') || dip.contains(':') || rwip.contains(':')))),
                "proto" to protoList(get, false),
                "mark" to mark(get),
            ),
        )
    }

    fun natSrc(get: (String) -> Any?): Fmt = fmt(
        "From %{src}%{src_device?, interface <var>%{src_device}</var>}%{src_ip?, IP %{src_ip#%{next?, }<var%{item.inv? data-tooltip=\"Match IP addresses except %{item.val}.\"}>%{item.ival}</var>}}%{src_port?, port %{src_port#%{next?, }<var%{item.inv? data-tooltip=\"Match ports except %{item.val}.\"}>%{item.ival}</var>}}",
        mapOf(
            "src" to ZoneBadge("any zone", FwTool.zoneColor(null), false),
            "src_ip" to mapInvert(get("src_ip"), "toLowerCase"),
            "src_port" to mapInvert(get("src_port")),
        ),
    )

    fun natDest(get: (String) -> Any?): Fmt = fmt(
        "To %{dest}%{dest_device?, via interface <var>%{dest_device}</var>}%{dest_ip?, IP %{dest_ip#%{next?, }<var%{item.inv? data-tooltip=\"Match IP addresses except %{item.val}.\"}>%{item.ival}</var>}}%{dest_port?, port %{dest_port#%{next?, }<var%{item.inv? data-tooltip=\"Match ports except %{item.val}.\"}>%{item.ival}</var>}}",
        mapOf(
            "dest" to zoneBadge(get("src")),
            "dest_ip" to mapInvert(get("dest_ip"), "toLowerCase"),
            "dest_port" to mapInvert(get("dest_port")),
            "dest_device" to get("device"),
        ),
    )

    fun natTarget(get: (String) -> Any?): Fmt {
        val t = get("target")
        val s = mapOf("target" to t, "snat_ip" to get("snat_ip"), "snat_port" to get("snat_port"))
        return when (t) {
            "SNAT" -> fmt("<var data-tooltip=\"SNAT\">Statically rewrite</var> to source %{snat_ip?IP <var>%{snat_ip}</var>} %{snat_port?port <var>%{snat_port}</var>}", s)
            "MASQUERADE" -> fmt("<var data-tooltip=\"MASQUERADE\">Automatically rewrite</var> source IP")
            "ACCEPT" -> fmt("<var data-tooltip=\"ACCEPT\">Prevent source rewrite</var>")
            else -> FwTool.plain(t?.let { jsString(it) } ?: "")
        }
    }
}
