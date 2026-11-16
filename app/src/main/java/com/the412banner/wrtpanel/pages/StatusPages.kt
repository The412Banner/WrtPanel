package com.the412banner.wrtpanel.pages

import com.the412banner.wrtpanel.pages.status.ProcessesPage

/** Status pages (LuCI views). */
internal fun registerStatusPages(r: PageRegistry) {
    r.view("status/processes") { ProcessesPage(it) }
}
