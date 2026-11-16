package com.the412banner.wrtpanel.pages

import androidx.compose.runtime.Composable
import com.the412banner.wrtpanel.ui.MenuNode
import com.the412banner.wrtpanel.ui.PageContent
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.WebPage
import com.the412banner.wrtpanel.ui.components.EmptyState
import com.the412banner.wrtpanel.ui.components.PageColumn

/** Registry of native pages by LuCI action path ("view:status/routes", "template:admin_status/index"). */
class PageRegistry {
    internal val pages = mutableMapOf<String, PageContent>()
    fun view(path: String, content: PageContent) { pages["view:$path"] = content }
    fun template(path: String, content: PageContent) { pages["template:$path"] = content }
}

object Pages {
    private val registry = PageRegistry().apply {
        registerStatusPages(this)
        registerSystemPages(this)
        registerNetworkPages(this)
        registerFirewallPages(this)
        registerServicesPages(this)
    }

    fun has(node: MenuNode?): Boolean = node != null && registry.pages.containsKey("${node.actionType}:${node.actionPath}")

    @Composable
    fun Render(ctx: PageContext, node: MenuNode?) {
        if (node == null) { PageColumn { EmptyState("This page is not available") }; return }
        val content = registry.pages["${node.actionType}:${node.actionPath}"]
        if (content != null) content(ctx) else WebPage(ctx, node)
    }
}
