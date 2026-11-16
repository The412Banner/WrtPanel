package com.the412banner.wrtpanel.pages

import com.the412banner.wrtpanel.pages.status.DmesgPage
import com.the412banner.wrtpanel.pages.status.IptablesPage
import com.the412banner.wrtpanel.pages.status.NftablesPage
import com.the412banner.wrtpanel.pages.status.OverviewPage
import com.the412banner.wrtpanel.pages.status.ProcessesPage
import com.the412banner.wrtpanel.pages.status.StatusRoutesJsonPage
import com.the412banner.wrtpanel.pages.status.StatusRoutesPage
import com.the412banner.wrtpanel.pages.status.SyslogPage

/** Status pages (LuCI views). */
internal fun registerStatusPages(r: PageRegistry) {
    r.template("admin_status/index") { OverviewPage(it) }
    r.view("status/routes") { StatusRoutesPage(it) }
    r.view("status/routesj") { StatusRoutesJsonPage(it) }
    r.view("status/nftables") { NftablesPage(it) }
    r.view("status/iptables") { IptablesPage(it) }
    r.view("status/syslog") { SyslogPage(it) }
    r.view("status/dmesg") { DmesgPage(it) }
    r.view("status/processes") { ProcessesPage(it) }
}
