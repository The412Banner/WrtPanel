package com.the412banner.wrtpanel.pages.firewall

import androidx.compose.runtime.Composable
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormPage
import com.the412banner.wrtpanel.ui.PageContext

/** Network › Firewall › IP Sets — port of luci-app-firewall view/firewall/ipsets.js. */
@Composable
fun FirewallIpsetsPage(ctx: PageContext) = FormPage(ctx) {
    uci.load(FW)
    val haveFw4 = session.hasFeature("firewall4")

    val m = FormMap(
        FW, "Firewall - IP sets",
        "firewall4 supports referencing and creating IP sets to simplify matching of large address lists without the need to create one rule per item to match. Port ranges in ipsets are unsupported by firewall4.<br />",
    )

    m.virtual("fwver", null, if (haveFw4) "Your device runs firewall4." else "Your device does not run firewall4.")

    m.grid("ipset", "IP Sets") {
        addremove = true
        anonymous = true
        sortable = true
        nodescriptions = true
        cloneable()
        modalTitle = { _ -> "Firewall - IP sets" }

        val name = value("name", "Name") {
            if (haveFw4) {
                optional = false
                rmempty = false
                validate = { _, _, v -> if (!Regex("^[a-zA-Z_.][a-zA-Z0-9/_.-]*$").containsMatchIn(v)) "Invalid set name" else null }
            } else {
                depends("external", "")
            }
        }
        name.placeholder = "Unnamed set"

        value("comment", "Comment") {
            placeholder = "Comment"
            modalonly = true
            rmempty = true
        }

        list("family", "Family") {
            value("any", "IPv4 and IPv6")
            value("ipv4", "IPv4")
            value("ipv6", "IPv6")
            default = "ipv4"
        }

        dynlist(
            "match", "Packet Field Match",
            "Packet fields to match upon.<br />" +
                "Syntax: <em>direction_datatype</em>. e.g.: <code>src_port, dest_net</code>.<br />" +
                "Directions: <code>src, dst</code>. Datatypes: <code>ip, port, mac, net, set</code>.<br />" +
                "Direction prefixes are optional.<br />" +
                "*Note: datatype <code>set</code> is unsupported in fw4.",
        ) {
            value("ip", "ip: IP addr")
            value("port", "port: Port")
            value("mac", "mac: MAC addr")
            value("net", "net: (sub)net")
            if (!haveFw4) value("set", "set: ipset*")
            value("src_ip", "src_ip: Source IP")
            value("src_port", "src_port: Source Port")
            value("src_mac", "src_mac: Source MAC addr")
            value("src_net", "src_net: Source (sub)net")
            if (!haveFw4) value("src_set", "src_Set: Source ipset*")
            value("dest_ip", "dest_ip: Destination IP")
            value("dest_port", "dest_port: Destination Port")
            value("dest_mac", "dest_mac: Destination MAC addr")
            value("dest_net", "dest_net: Destination (sub)net")
            if (!haveFw4) value("dest_set", "dest_set: Destination ipset*")
            optional = false
            rmempty = false
            textvalueFn = { rt, sid -> UciStore.toArray(rt.uci.get(FW, sid, "match")).joinToString(", ") }
        }

        if (haveFw4) {
            dynlist("entry", "IPs/Networks/MACs", "macaddr|ip[/cidr]<br />") {
                datatype = "or(ipaddr,macaddr)"
                rmempty = true
            }
            value("maxelem", "Max Entries", "up to 65536 entries.") {
                datatype = "port"
                modalonly = true
                rmempty = true
            }
        } else {
            value("external", "Refer To External Set") {
                rmempty = true
                optional = true
            }
            list("storage", "Storage Method") {
                value("bitmap", "bitmap")
                value("hash", "hash")
                value("list", "list")
                validate = { rt, sid, v ->
                    val fam = section.options.firstOrNull { it.option == "family" }?.let { rt.formString(it, sid) } ?: ""
                    if (Regex("bitmap").containsMatchIn(v) && !Regex("ipv4").containsMatchIn(fam)) "bitmap is ipv4 only" else null
                }
            }
            value("iprange", "IP (range)", "ip[/cidr]<br />For use with Match datatypes: <code>*_ip</code>.") {
                datatype = "ipaddr"
                depends("family" to "ipv4", "storage" to "bitmap", "match" to Regex("_ip|_mac"))
                depends("storage" to "hash", "match" to Regex("_ip"))
            }
            dynlist("entry", "IPs/Networks", "ip[/cidr]<br />") {
                datatype = "or(ipaddr,macaddr)"
                depends("storage" to "hash", "match" to Regex("_ip|_net|_mac"))
            }
            value("portrange", "Port range", "fromport-toport") {
                datatype = "neg(portrange)"
                depends("family" to "ipv4", "storage" to "bitmap", "match" to Regex("_port"))
                depends("family" to "ipv4", "storage" to "hash", "match" to Regex("_port"))
                depends("family" to "ipv6", "storage" to "hash", "match" to Regex("_port"))
            }
            value("netmask", "Netmask") {
                datatype = "or(ip4prefix,ip6prefix)"
                depends("family" to "ipv4", "storage" to "bitmap", "match" to Regex("_ip"))
                depends("storage" to "hash", "match" to Regex("_ip"))
            }
            value("maxelem", "Max Length", "up to 65536 entries.") {
                datatype = "port"
                depends("storage", "hash")
                depends("storage", "list")
                modalonly = true
            }
            value("hashsize", "Initial Hash Size") {
                depends("storage", "hash")
                placeholder = "1024"
                modalonly = true
            }
        }

        file("loadfile", "Include File", "Path to file of CIDRs, subnets, host IPs, etc.<br />") {
            rootDirectory = "/etc/luci-uploads"
            enableRemove = true
            enableUpload = true
            datatype = "file"
            rmempty = true
        }

        value(
            "timeout", "Timeout",
            "Unit: seconds. Default <code>0</code> means the entry is added permanently to the set.<br />" +
                "Max: 2147483 seconds.",
        ) {
            placeholder = "0"
            modalonly = true
            rmempty = true
        }

        flag("counters", "Counters", "Enables packet and byte count tracking for the set.") {
            modalonly = true
            rmempty = true
            default = disabled
        }

        flag("enabled", "Enabled") {
            default = enabled
            editable = true
            modalonly = false
        }
    }
    m
}

private const val FW = "firewall"
