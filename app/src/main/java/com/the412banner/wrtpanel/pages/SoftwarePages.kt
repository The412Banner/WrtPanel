package com.the412banner.wrtpanel.pages

import com.the412banner.wrtpanel.pages.software.PackageManagerPage

/** Software pages (LuCI views). */
internal fun registerSoftwarePages(r: PageRegistry) {
    r.view("package-manager") { PackageManagerPage(it) }
}
