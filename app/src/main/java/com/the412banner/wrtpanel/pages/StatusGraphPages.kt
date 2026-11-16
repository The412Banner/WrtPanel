package com.the412banner.wrtpanel.pages

import com.the412banner.wrtpanel.pages.status.graphs.BandwidthPage
import com.the412banner.wrtpanel.pages.status.graphs.ChannelAnalysisPage
import com.the412banner.wrtpanel.pages.status.graphs.ConnectionsPage
import com.the412banner.wrtpanel.pages.status.graphs.LoadPage
import com.the412banner.wrtpanel.pages.status.graphs.WirelessPage

/** StatusGraph pages (LuCI views). */
internal fun registerStatusGraphPages(r: PageRegistry) {
    r.view("status/load") { LoadPage(it) }
    r.view("status/bandwidth") { BandwidthPage(it) }
    r.view("status/wireless") { WirelessPage(it) }
    r.view("status/connections") { ConnectionsPage(it) }
    r.view("status/channel_analysis") { ChannelAnalysisPage(it) }
}
