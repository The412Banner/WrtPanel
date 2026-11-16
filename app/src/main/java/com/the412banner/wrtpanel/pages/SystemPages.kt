package com.the412banner.wrtpanel.pages

import com.the412banner.wrtpanel.pages.system.SystemPage

/** System pages (LuCI views). */
internal fun registerSystemPages(r: PageRegistry) {
    r.view("system/system") { SystemPage(it) }
}
