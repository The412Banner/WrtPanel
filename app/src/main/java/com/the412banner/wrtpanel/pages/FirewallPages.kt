package com.the412banner.wrtpanel.pages

import com.the412banner.wrtpanel.pages.firewall.FirewallCustomPage
import com.the412banner.wrtpanel.pages.firewall.FirewallForwardsPage
import com.the412banner.wrtpanel.pages.firewall.FirewallIpsetsPage
import com.the412banner.wrtpanel.pages.firewall.FirewallRulesPage
import com.the412banner.wrtpanel.pages.firewall.FirewallSnatsPage
import com.the412banner.wrtpanel.pages.firewall.FirewallZonesPage

/** Firewall pages (LuCI views). */
internal fun registerFirewallPages(r: PageRegistry) {
    r.view("firewall/zones") { FirewallZonesPage(it) }
    r.view("firewall/forwards") { FirewallForwardsPage(it) }
    r.view("firewall/rules") { FirewallRulesPage(it) }
    r.view("firewall/snats") { FirewallSnatsPage(it) }
    r.view("firewall/ipsets") { FirewallIpsetsPage(it) }
    r.view("firewall/custom") { FirewallCustomPage(it) }
}
