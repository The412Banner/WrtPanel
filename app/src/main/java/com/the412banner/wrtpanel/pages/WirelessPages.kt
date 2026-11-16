package com.the412banner.wrtpanel.pages

import com.the412banner.wrtpanel.pages.network.wireless.WirelessPage

/** Wireless pages (LuCI views). */
internal fun registerWirelessPages(r: PageRegistry) {
    r.view("network/wireless") { WirelessPage(it) }
}
