package com.the412banner.wrtpanel.core

import androidx.compose.runtime.mutableIntStateOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Client-side UCI state, modelled on LuCI's uci.js: configurations are loaded through
 * ubus `uci get`, edits are kept locally (creates / changes / deletes / reorder) and
 * [save] stages them on the router with `uci add/set/delete/order`, exactly like the
 * web interface's Save button. Applying is done by [ChangesManager].
 *
 * Option values are either String or List<String>. Section maps carry the meta keys
 * ".name", ".type", ".anonymous" (Boolean) and ".index" (Int).
 */
class UciStore(private val client: LuciClient) {
    private val loaded = mutableMapOf<String, LinkedHashMap<String, MutableMap<String, Any>>>()
    private val creates = mutableMapOf<String, LinkedHashMap<String, MutableMap<String, Any>>>()
    private val changes = mutableMapOf<String, MutableMap<String, MutableMap<String, Any>>>()
    private val deletes = mutableMapOf<String, MutableMap<String, MutableSet<String>?>>()
    private val order = mutableMapOf<String, MutableList<String>>()
    private var seq = 0

    /** Bumped on every local change so Compose readers recompose. */
    val revision = mutableIntStateOf(0)
    private fun touch() { revision.intValue++ }

    fun isLoaded(conf: String) = loaded.containsKey(conf)

    suspend fun load(vararg configs: String) = load(configs.toList())

    suspend fun load(configs: Collection<String>) {
        val todo = configs.distinct().filter { !loaded.containsKey(it) }
        if (todo.isEmpty()) return
        val res = client.batch(todo.map { UbusCall("uci", "get", jsonOf("config" to it)) })
        todo.forEachIndexed { i, conf ->
            val r = res[i]
            val values = r.getOrNull()?.get("values").obj
            if (r.isFailure) {
                val e = r.exceptionOrNull()
                // A missing config (status 4/5) behaves like an empty one, as resolveDefault() does in views.
                if (e !is UbusException) throw e ?: LuciException("uci get $conf failed")
            }
            loaded[conf] = parseConfig(values)
        }
        touch()
    }

    /** Drops cached state for [configs] (local edits included) so the next [load] refetches. */
    fun unload(vararg configs: String) {
        configs.forEach { loaded.remove(it); creates.remove(it); changes.remove(it); deletes.remove(it); order.remove(it) }
        touch()
    }

    suspend fun reload(vararg configs: String) { unload(*configs); load(*configs) }

    /** Forgets everything, like a fresh LuCI page load. */
    fun unloadAll() {
        loaded.clear(); creates.clear(); changes.clear(); deletes.clear(); order.clear(); touch()
    }

    private fun parseConfig(values: JsonObject?): LinkedHashMap<String, MutableMap<String, Any>> {
        val out = LinkedHashMap<String, MutableMap<String, Any>>()
        val list = values?.entries?.map { (sid, s) ->
            val m = mutableMapOf<String, Any>()
            (s as? JsonObject)?.forEach { (k, v) ->
                when {
                    k == ".anonymous" -> m[k] = v.bool ?: false
                    k == ".index" -> m[k] = v.int ?: 0
                    v is JsonArray -> m[k] = v.mapNotNull { it.str }
                    else -> v.str?.let { m[k] = it }
                }
            }
            m.putIfAbsent(".name", sid)
            sid to m
        } ?: emptyList()
        list.sortedBy { (it.second[".index"] as? Int) ?: 0 }.forEach { out[it.first] = it.second }
        return out
    }

    /** Resolves "@type[index]" (negative index counts from the end) to a section name. */
    fun resolveSid(conf: String, sid: String): String? {
        val m = Regex("^@([a-zA-Z0-9_-]+)\\[(-?[0-9]+)]$").find(sid) ?: return sid
        val type = m.groupValues[1]
        val idx = m.groupValues[2].toInt()
        val list = sections(conf, type)
        val pos = if (idx < 0) list.size + idx else idx
        return list.getOrNull(pos)?.get(".name") as? String
    }

    /** Merged sections of [conf], optionally only those of [type], in order. */
    fun sections(conf: String, type: String? = null): List<Map<String, Any>> {
        val base = loaded[conf] ?: LinkedHashMap()
        val del = deletes[conf]
        val ids = (order[conf]?.toList() ?: (base.keys.toList() + (creates[conf]?.keys?.toList() ?: emptyList())))
            .filter { base.containsKey(it) || creates[conf]?.containsKey(it) == true }
        val out = ArrayList<Map<String, Any>>()
        for (sid in ids) {
            val created = creates[conf]?.get(sid)
            val s: Map<String, Any> = if (created != null) created.toMap() else {
                if (del != null && del.containsKey(sid) && del[sid] == null) continue
                merged(conf, sid) ?: continue
            }
            if (type != null && s[".type"] != type) continue
            out.add(s)
        }
        // Order may miss newly created sections when [order] was set before an add.
        creates[conf]?.forEach { (sid, s) ->
            if (ids.contains(sid)) return@forEach
            if (type == null || s[".type"] == type) out.add(s.toMap())
        }
        return out
    }

    private fun merged(conf: String, sid: String): Map<String, Any>? {
        val base = loaded[conf]?.get(sid) ?: return null
        val m = base.toMutableMap()
        deletes[conf]?.get(sid)?.forEach { m.remove(it) }
        changes[conf]?.get(sid)?.forEach { (k, v) -> m[k] = v }
        return m
    }

    fun section(conf: String, sid: String): Map<String, Any>? {
        val id = resolveSid(conf, sid) ?: return null
        creates[conf]?.get(id)?.let { return it.toMap() }
        val del = deletes[conf]
        if (del != null && del.containsKey(id) && del[id] == null) return null
        return merged(conf, id)
    }

    fun get(conf: String, sid: String, opt: String): Any? {
        val s = section(conf, sid) ?: return null
        return s[opt]
    }

    fun getString(conf: String, sid: String, opt: String): String? = when (val v = get(conf, sid, opt)) {
        is String -> v
        is List<*> -> v.joinToString(" ")
        else -> null
    }

    fun getList(conf: String, sid: String, opt: String): List<String> = toList(get(conf, sid, opt))

    fun set(conf: String, sid0: String, opt: String, value: Any?) {
        val sid = resolveSid(conf, sid0) ?: return
        val v = normalize(value)
        val created = creates[conf]?.get(sid)
        if (created != null) {
            if (v != null) created[opt] = v else created.remove(opt)
            touch(); return
        }
        val base = loaded[conf]?.get(sid) ?: return
        val del = deletes.getOrPut(conf) { mutableMapOf() }
        if (v != null) {
            if (del.containsKey(sid) && del[sid] == null) return
            val ch = changes.getOrPut(conf) { mutableMapOf() }
            val cur = base[opt]
            del[sid]?.let { set -> set.remove(opt); if (set.isEmpty()) del.remove(sid) }
            if (cur != null && uciEqual(cur, v) && del[sid]?.contains(opt) != true) {
                ch[sid]?.remove(opt); if (ch[sid]?.isEmpty() == true) ch.remove(sid)
            } else {
                ch.getOrPut(sid) { mutableMapOf() }[opt] = v
            }
        } else {
            changes[conf]?.get(sid)?.let { it.remove(opt); if (it.isEmpty()) changes[conf]?.remove(sid) }
            if (base.containsKey(opt)) {
                if (!(del.containsKey(sid) && del[sid] == null)) del.getOrPut(sid) { mutableSetOf() }!!.add(opt)
            }
        }
        touch()
    }

    fun unset(conf: String, sid: String, opt: String) = set(conf, sid, opt, null)

    /** Adds a section; returns its temporary id (replaced by the real name after [save]). */
    fun add(conf: String, type: String, name: String? = null): String {
        val sid = name ?: "new%06x".format((++seq * 7919 + (System.nanoTime() and 0xffff).toInt()) and 0xffffff)
        val m = mutableMapOf<String, Any>(".type" to type, ".name" to sid, ".anonymous" to (name == null))
        if (name != null) m[".create"] = name
        creates.getOrPut(conf) { LinkedHashMap() }[sid] = m
        order[conf]?.add(sid)
        touch()
        return sid
    }

    fun remove(conf: String, sid0: String) {
        val sid = resolveSid(conf, sid0) ?: return
        if (creates[conf]?.remove(sid) != null) {
            order[conf]?.remove(sid); touch(); return
        }
        if (loaded[conf]?.containsKey(sid) != true) return
        changes[conf]?.remove(sid)
        deletes.getOrPut(conf) { mutableMapOf() }[sid] = null
        order[conf]?.remove(sid)
        touch()
    }

    /** Moves [sid1] before [sid2] (or after it when [after]); [sid2] null moves to the end. */
    fun move(conf: String, sid1: String, sid2: String?, after: Boolean = false) {
        val ids = order.getOrPut(conf) {
            ((loaded[conf]?.keys?.toList() ?: emptyList()) + (creates[conf]?.keys?.toList() ?: emptyList())).toMutableList()
        }
        if (!ids.remove(sid1)) return
        if (sid2 == null) ids.add(sid1) else {
            val i = ids.indexOf(sid2)
            if (i < 0) ids.add(sid1) else ids.add(if (after) i + 1 else i, sid1)
        }
        touch()
    }

    fun firstSid(conf: String, type: String): String? = sections(conf, type).firstOrNull()?.get(".name") as? String

    fun getFirst(conf: String, type: String, opt: String): Any? = firstSid(conf, type)?.let { get(conf, it, opt) }

    fun setFirst(conf: String, type: String, opt: String, value: Any?) {
        firstSid(conf, type)?.let { set(conf, it, opt, value) }
    }

    fun getBool(conf: String, type: String, opt: String): Boolean {
        val v = (getFirst(conf, type, opt) as? String)?.lowercase() ?: return false
        return v in setOf("1", "on", "true", "yes", "enabled")
    }

    /** True when edits are pending that [save] would stage. */
    fun hasLocalChanges(): Boolean =
        creates.values.any { it.isNotEmpty() } || changes.values.any { it.isNotEmpty() } ||
            deletes.values.any { it.isNotEmpty() } || order.isNotEmpty()

    fun discardLocal() {
        creates.clear(); changes.clear(); deletes.clear(); order.clear(); touch()
    }

    /**
     * Stages local edits on the router (ubus uci delete/add/set/order) and reloads the
     * touched configs. Returns the touched config names. Temporary section ids of created
     * sections map to their real names in [lastCreatedNames].
     */
    val lastCreatedNames = mutableMapOf<String, String>()

    suspend fun save(): List<String> {
        lastCreatedNames.clear()
        val pkgs = linkedSetOf<String>()
        val calls = ArrayList<UbusCall>()
        deletes.forEach { (conf, m) ->
            m.forEach { (sid, opts) ->
                calls += if (opts == null) UbusCall("uci", "delete", jsonOf("config" to conf, "section" to sid))
                else UbusCall("uci", "delete", jsonOf("config" to conf, "section" to sid, "options" to opts.toList()))
            }
            if (m.isNotEmpty()) pkgs += conf
        }
        if (calls.isNotEmpty()) checkAll(client.batch(calls))
        calls.clear()

        val createdList = ArrayList<Pair<String, String>>()
        creates.forEach { (conf, m) ->
            m.forEach { (sid, s) ->
                val values = s.filterKeys { !it.startsWith(".") }
                val args = mutableMapOf<String, Any?>("config" to conf, "type" to s[".type"], "values" to values)
                (s[".create"] as? String)?.let { args["name"] = it }
                calls += UbusCall("uci", "add", jsonOf(*args.map { it.key to it.value }.toTypedArray()))
                createdList += conf to sid
            }
            if (m.isNotEmpty()) pkgs += conf
        }
        if (calls.isNotEmpty()) {
            val res = client.batch(calls)
            checkAll(res)
            res.forEachIndexed { i, r ->
                val real = r.getOrNull()?.get("section").str
                if (real != null) {
                    lastCreatedNames[createdList[i].second] = real
                    order[createdList[i].first]?.let { ids -> val j = ids.indexOf(createdList[i].second); if (j >= 0) ids[j] = real }
                }
            }
        }
        calls.clear()

        changes.forEach { (conf, m) ->
            m.forEach { (sid, opts) ->
                calls += UbusCall("uci", "set", jsonOf("config" to conf, "section" to sid, "values" to opts))
            }
            if (m.isNotEmpty()) pkgs += conf
        }
        if (calls.isNotEmpty()) checkAll(client.batch(calls))
        calls.clear()

        order.forEach { (conf, ids) ->
            val del = deletes[conf]
            val keep = ids.filter {
                !(del != null && del.containsKey(it) && del[it] == null) &&
                    (loaded[conf]?.containsKey(it) == true || lastCreatedNames.containsValue(it))
            }
            calls += UbusCall("uci", "order", jsonOf("config" to conf, "sections" to keep))
            pkgs += conf
        }
        if (calls.isNotEmpty()) checkAll(client.batch(calls))

        val touched = pkgs.toList()
        touched.forEach { loaded.remove(it); creates.remove(it); changes.remove(it); deletes.remove(it); order.remove(it) }
        load(touched)
        touch()
        return touched
    }

    private fun checkAll(res: List<Result<JsonElement?>>) {
        res.firstOrNull { it.isFailure }?.exceptionOrNull()?.let { throw it }
    }

    companion object {
        fun normalize(value: Any?): Any? = when (value) {
            null -> null
            is String -> value.ifEmpty { null }
            is List<*> -> value.mapNotNull { it?.toString() }.filter { it.isNotEmpty() }.ifEmpty { null }
            is Boolean -> if (value) "1" else "0"
            is Number -> value.toString()
            else -> value.toString().ifEmpty { null }
        }

        fun toList(v: Any?): List<String> = when (v) {
            null -> emptyList()
            is List<*> -> v.mapNotNull { it?.toString() }
            is String -> if (v.isEmpty()) emptyList() else listOf(v)
            else -> listOf(v.toString())
        }

        /** LuCI's L.toArray: strings are split on whitespace. */
        fun toArray(v: Any?): List<String> = when (v) {
            null -> emptyList()
            is List<*> -> v.mapNotNull { it?.toString() }
            is String -> v.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            else -> listOf(v.toString())
        }

        fun uciEqual(a: Any?, b: Any?): Boolean = when {
            a is List<*> && b is List<*> -> a.map { it.toString() } == b.map { it.toString() }
            a is List<*> || b is List<*> -> false
            else -> a?.toString() == b?.toString()
        }
    }
}

/** A JSON value to UCI (String / List<String>). */
fun JsonElement?.toUciValue(): Any? = when (this) {
    is JsonArray -> mapNotNull { it.str }
    is JsonPrimitive -> str
    else -> null
}
