package com.the412banner.wrtpanel.pages

import com.the412banner.wrtpanel.pages.network.RoutesPage

/** Network pages (LuCI views). */
internal fun registerNetworkPages(r: PageRegistry) {
    r.view("network/routes") { RoutesPage(it) }
}
