package com.the412banner.wrtpanel.pages

import com.the412banner.wrtpanel.pages.system.CrontabPage
import com.the412banner.wrtpanel.pages.system.DropbearPage
import com.the412banner.wrtpanel.pages.system.FlashPage
import com.the412banner.wrtpanel.pages.system.LedsPage
import com.the412banner.wrtpanel.pages.system.MountsPage
import com.the412banner.wrtpanel.pages.system.PasswordPage
import com.the412banner.wrtpanel.pages.system.RebootPage
import com.the412banner.wrtpanel.pages.system.RepoKeysPage
import com.the412banner.wrtpanel.pages.system.SshKeysPage
import com.the412banner.wrtpanel.pages.system.StartupPage
import com.the412banner.wrtpanel.pages.system.SystemPage
import com.the412banner.wrtpanel.pages.system.UhttpdPage

/** System pages (LuCI views). */
internal fun registerSystemPages(r: PageRegistry) {
    r.view("system/system") { SystemPage(it) }
    r.view("system/password") { PasswordPage(it) }
    r.view("system/dropbear") { DropbearPage(it) }
    r.view("system/sshkeys") { SshKeysPage(it) }
    r.view("system/uhttpd") { UhttpdPage(it) }
    r.view("system/repokeys") { RepoKeysPage(it) }
    r.view("system/startup") { StartupPage(it) }
    r.view("system/crontab") { CrontabPage(it) }
    r.view("system/mounts") { MountsPage(it) }
    r.view("system/leds") { LedsPage(it) }
    r.view("system/flash") { FlashPage(it) }
    r.view("system/reboot") { RebootPage(it) }
}
