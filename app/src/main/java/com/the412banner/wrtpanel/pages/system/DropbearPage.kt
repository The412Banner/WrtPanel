package com.the412banner.wrtpanel.pages.system

import androidx.compose.runtime.Composable
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormPage
import com.the412banner.wrtpanel.form.networkSelect
import com.the412banner.wrtpanel.ui.PageContext

/** System › Administration › SSH Access — port of luci-mod-system view/system/dropbear.js. */
@Composable
fun DropbearPage(ctx: PageContext) = FormPage(ctx) {
    val m = FormMap(
        "dropbear", "SSH Access",
        "Dropbear offers <abbr title=\"Secure Shell\">SSH</abbr> network shell access and an integrated <abbr title=\"Secure Copy\">SCP</abbr> server",
    )
    m.typed("dropbear", "Dropbear Instance") {
        anonymous = true; addremove = true; addbtntitle = "Add instance"

        flag("enable", "Enable Instance", "Enable <abbr title=\"Secure Shell\">SSH</abbr> service instance") { default = enabled }
        flag("_direct", "Bind to Interface") { default = disabled }
        networkSelect("DirectInterface", "Interface", "Listen only on the given interface or, if unspecified, on all", nocreate = true) {
            depends("_direct", "1")
        }
        networkSelect("Interface", "Interface", "Listen on up to 10 IPs on the given interface or, if unspecified, on all interfaces", nocreate = true) {
            depends("_direct", "0")
        }
        value("Port", "Port") { datatype = "port"; placeholder = "22" }
        flag("PasswordAuth", "Password authentication", "Allow <abbr title=\"Secure Shell\">SSH</abbr> password authentication") {
            enabled = "on"; disabled = "off"; default = enabled; rmempty = false
        }
        flag("RootPasswordAuth", "Allow root logins with password", "Allow the <em>root</em> user to log in with password") {
            enabled = "on"; disabled = "off"; default = enabled
        }
        flag("GatewayPorts", "Gateway Ports", "Allow remote hosts to connect to local SSH forwarded ports") {
            enabled = "on"; disabled = "off"; default = disabled
        }
    }
    m
}
