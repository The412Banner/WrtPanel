package com.the412banner.wrtpanel.pages

import com.the412banner.wrtpanel.pages.services.KsmbdPage

/** Services pages (LuCI views). */
internal fun registerServicesPages(r: PageRegistry) {
    r.view("ksmbd") { KsmbdPage(it) }
}
