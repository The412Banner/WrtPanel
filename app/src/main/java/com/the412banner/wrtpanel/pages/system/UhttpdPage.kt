package com.the412banner.wrtpanel.pages.system

import androidx.compose.runtime.Composable
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormPage
import com.the412banner.wrtpanel.ui.PageContext

/** System › Administration › HTTP(S) Access — port of luci-mod-system view/system/uhttpd.js. */
@Composable
fun UhttpdPage(ctx: PageContext) = FormPage(ctx) {
    val m = FormMap(
        "uhttpd", "HTTP(S) Access",
        "uHTTPd offers <abbr title=\"Hypertext Transfer Protocol\">HTTP</abbr> or <abbr title=\"Hypertext Transfer Protocol Secure\">HTTPS</abbr> network access.",
    )
    m.named("main", "uhttpd", "Settings") {
        addremove = false
        flag(
            "redirect_https", "Redirect to HTTPS",
            "Enable automatic redirection of <abbr title=\"Hypertext Transfer Protocol\">HTTP</abbr> requests to <abbr title=\"Hypertext Transfer Protocol Secure\">HTTPS</abbr> port.",
        ) { rmempty = false }
    }
    m
}
