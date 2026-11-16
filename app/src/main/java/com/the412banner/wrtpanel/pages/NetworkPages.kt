package com.the412banner.wrtpanel.pages

import com.the412banner.wrtpanel.pages.network.RoutesPage
import com.the412banner.wrtpanel.pages.network.dhcp.DhcpPage
import com.the412banner.wrtpanel.pages.network.diag.DiagnosticsPage
import com.the412banner.wrtpanel.pages.network.dns.DnsPage

/** Network pages (LuCI views). */
internal fun registerNetworkPages(r: PageRegistry) {
    r.view("network/routes") { RoutesPage(it) }
    r.view("network/dhcp") { DhcpPage(it) }
    r.view("network/dns") { DnsPage(it) }
    r.view("network/diagnostics") { DiagnosticsPage(it) }
}
