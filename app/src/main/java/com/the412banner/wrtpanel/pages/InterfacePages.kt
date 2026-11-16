package com.the412banner.wrtpanel.pages

import com.the412banner.wrtpanel.pages.network.interfaces.InterfacesPage

/** Interface pages (LuCI views). */
internal fun registerInterfacePages(r: PageRegistry) {
    r.view("network/interfaces") { InterfacesPage(it) }
}
