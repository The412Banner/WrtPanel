package com.the412banner.wrtpanel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.the412banner.wrtpanel.core.ChangesManager
import com.the412banner.wrtpanel.core.Fs
import com.the412banner.wrtpanel.core.LuciClient
import com.the412banner.wrtpanel.core.Router
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.int
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A LuCI menu node (admin/menu JSON after LuCI's scrubMenu filtering). */
data class MenuNode(
    val name: String,
    val path: String,
    val title: String?,
    val order: Int,
    val actionType: String?,
    val actionPath: String?,
    val readonly: Boolean,
    val satisfied: Boolean,
    val children: List<MenuNode>,
    val firstchildIneligible: Boolean = false,
) {
    /** Visible children in LuCI's order (ui.menu.getChildren). */
    val visibleChildren: List<MenuNode> get() = children.filter { it.title != null && it.satisfied }

    companion object {
        fun parse(json: JsonObject): MenuNode = build("", "", json)

        private fun build(name: String, path: String, j: JsonElement?): MenuNode {
            val kids = j["children"].obj?.entries?.map { (k, v) -> build(k, if (path.isEmpty()) k else "$path/$k", v) }
                ?.sortedWith(compareBy<MenuNode> { it.order }.thenBy { it.name }) ?: emptyList()
            val action = j["action"]
            var satisfied = j["satisfied"].bool ?: true
            // scrubMenu(): a firstchild node without satisfied titled children is unsatisfied.
            if (action["type"].str == "firstchild" && kids.none { it.title != null && it.satisfied && !it.firstchildIneligible }) satisfied = false
            return MenuNode(
                name = name, path = path, title = j["title"].str, order = j["order"].int ?: 1000,
                actionType = action["type"].str, actionPath = action["path"].str,
                readonly = j["readonly"].bool ?: false, satisfied = satisfied, children = kids,
                firstchildIneligible = j["firstchild_ineligible"].bool ?: false,
            )
        }
    }

    fun find(p: String): MenuNode? {
        if (path == p) return this
        for (c in children) c.find(p)?.let { return it }
        return null
    }

    /** Resolves alias / firstchild to the node whose page is shown. */
    fun resolveTarget(root: MenuNode): MenuNode {
        var n = this
        repeat(8) {
            n = when (n.actionType) {
                "alias" -> n.actionPath?.let { root.find(it) } ?: return n
                "firstchild" -> n.visibleChildren.firstOrNull() ?: return n
                else -> return n
            }
        }
        return n
    }
}

/** Board / release info from `system board` (shown in the drawer header). */
data class BoardInfo(val hostname: String, val model: String, val release: String, val target: String, val kernel: String)

/** Everything for one logged-in router. */
class RouterSession(val router: Router, val client: LuciClient) {
    val uci = UciStore(client)
    val fs = Fs(client)
    val changes = ChangesManager(client, uci)
    /** The router's menu; refreshed after installing or removing LuCI apps. */
    var menu by mutableStateOf<MenuNode?>(null)
    var board: BoardInfo? = null
    /** luci getFeatures (e.g. "fw4", "hostapd": {...}). */
    var features: JsonObject = JsonObject(emptyMap())
    /** Paths of installed LuCI protocol handlers etc. are discovered lazily per page. */
    val cache = mutableMapOf<String, Any?>()

    suspend fun loadBasics() {
        menu = MenuNode.parse(client.menu())
        val b = client.callOrNull("system", "board")
        board = BoardInfo(
            hostname = b["hostname"].str ?: router.name,
            model = b["model"].str ?: "",
            release = b["release"]["description"].str ?: "",
            target = b["release"]["target"].str ?: "",
            kernel = b["kernel"].str ?: "",
        )
        features = client.callOrNull("luci", "getFeatures").obj ?: JsonObject(emptyMap())
        changes.refresh()
    }

    /** Reloads admin/menu (LuCI flushes its menu cache after package changes). */
    suspend fun refreshMenu() {
        runCatching { MenuNode.parse(client.menu()) }.getOrNull()?.let { menu = it }
        features = client.callOrNull("luci", "getFeatures").obj ?: features
        cache.clear()
    }

    fun hasFeature(name: String): Boolean = features[name] != null && features[name].bool != false

    /** Installed ubus objects (LuCI's L.hasSystemFeature / rpc.list), cached. */
    suspend fun ubusObjects(): Set<String> {
        @Suppress("UNCHECKED_CAST")
        (cache["ubus.list"] as? Set<String>)?.let { return it }
        val r = runCatching { client.call("session", "access", jsonOf()) }.getOrNull()
        val set = r.obj?.keys ?: emptySet()
        cache["ubus.list"] = set
        return set
    }
}

/** A modal question shown by [DialogHost]. */
data class DialogRequest(
    val title: String,
    val text: String,
    val confirmLabel: String,
    val dismissLabel: String?,
    val destructive: Boolean,
    val result: CompletableDeferred<Boolean>,
)

class DialogHost {
    val current = mutableStateOf<DialogRequest?>(null)
    suspend fun ask(title: String, text: String, confirmLabel: String = "OK", dismissLabel: String? = "Cancel", destructive: Boolean = false): Boolean {
        val d = CompletableDeferred<Boolean>()
        current.value = DialogRequest(title, text, confirmLabel, dismissLabel, destructive, d)
        return try { d.await() } finally { if (current.value?.result === d) current.value = null }
    }
}

interface Navigator {
    /** Opens the page of a menu path like "admin/network/firewall". */
    fun open(path: String)
    fun back(): Boolean
}

/** Passed to every page. */
class PageContext(
    val session: RouterSession,
    val nav: Navigator,
    val readonly: Boolean,
    val scope: CoroutineScope,
    val dialogs: DialogHost,
    val toast: (String) -> Unit,
    val node: MenuNode?,
) {
    val client: LuciClient get() = session.client
    val uci: UciStore get() = session.uci
    val fs: Fs get() = session.fs
    suspend fun call(obj: String, method: String, args: JsonObject = jsonOf()) = client.call(obj, method, args)
    suspend fun callOrNull(obj: String, method: String, args: JsonObject = jsonOf()) = client.callOrNull(obj, method, args)
}

val LocalPageContext = staticCompositionLocalOf<PageContext> { error("No PageContext") }

/** A page of the app, registered by its LuCI view/template path. */
typealias PageContent = @Composable (PageContext) -> Unit
