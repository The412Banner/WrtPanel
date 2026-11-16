package com.the412banner.wrtpanel.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A staged change record as `uci changes` reports it: [op, section, (option|type), (value)]. */
typealias ChangeRecord = List<String>

sealed interface ApplyStatus {
    data class Working(val message: String, val secondsLeft: Int? = null) : ApplyStatus
    data class Done(val message: String) : ApplyStatus
    /** Changes touch the interface the app connects through. */
    data class ConnectivityWarning(val iface: String, val rollbackSeconds: Int) : ApplyStatus
    data class RolledBack(val rollbackSeconds: Int) : ApplyStatus
    data object Unreachable : ApplyStatus
    data class Failed(val message: String) : ApplyStatus
}

/**
 * The staged-changes indicator and the Save & Apply / Apply unchecked / Revert flows of
 * LuCI's ui.changes (ui.js), using the same dispatcher endpoints and timings (L.env).
 */
class ChangesManager(private val client: LuciClient, private val uci: UciStore) {
    private val _changes = MutableStateFlow<Map<String, List<ChangeRecord>>>(emptyMap())
    val changes: StateFlow<Map<String, List<ChangeRecord>>> = _changes.asStateFlow()
    private val _status = MutableStateFlow<ApplyStatus?>(null)
    val status: StateFlow<ApplyStatus?> = _status.asStateFlow()
    /** Incremented after a successful apply or revert so open pages reload. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    val count: Int get() = _changes.value.values.sumOf { it.size }

    fun dismiss() { _status.value = null }

    suspend fun refresh() {
        val r = runCatching { client.call("uci", "changes") }.getOrNull()
        val out = LinkedHashMap<String, List<ChangeRecord>>()
        r["changes"].obj?.forEach { (conf, list) ->
            out[conf] = list.arr?.map { rec -> rec.arr?.map { it.str ?: "" } ?: emptyList() } ?: emptyList()
        }
        _changes.value = out
    }

    /** Text lines in the format of LuCI's changes dialog ("uci set network.lan.ipaddr='…'"). */
    fun describe(conf: String, list: List<ChangeRecord>): List<Pair<String, String>> {
        var added: Pair<String, String>? = null
        return list.map { chg ->
            val op = chg.getOrNull(0) ?: ""
            val sec = { s: String -> if (added != null && s == added!!.first) "@${added!!.second}[-1]" else s }
            val q = { s: String -> "'" + s.replace("'", "'\"'\"'") + "'" }
            val line = when ("$op-${chg.size}") {
                "add-3" -> "uci add $conf ${chg[2]} # =${chg[1]}"
                "set-3" -> "uci set $conf.${chg[1]}=${chg[2]}"
                "set-4" -> "uci set $conf.${sec(chg[1])}.${chg[2]}=${q(chg[3])}"
                "remove-2" -> "uci del $conf.${sec(chg[1])}"
                "remove-3" -> "uci del $conf.${sec(chg[1])}.${chg[2]}"
                "order-3" -> "uci reorder $conf.${sec(chg[1])}=${chg[2]}"
                "list-add-4" -> "uci add_list $conf.${sec(chg[1])}.${chg[2]}=${q(chg[3])}"
                "list-del-4" -> "uci del_list $conf.${sec(chg[1])}.${chg[2]}=${q(chg[3])}"
                "rename-3" -> "uci rename $conf.${sec(chg[1])}=${chg[2]}"
                "rename-4" -> "uci rename $conf.${sec(chg[1])}.${chg[2]}=${chg[3]}"
                else -> "uci ${chg.joinToString(" ")}"
            }
            if (op == "add") added = chg[1] to chg[2]
            val kind = when (op) {
                "add" -> "add"; "remove" -> if (chg.size == 2) "remove" else "optremove"
                "list-del" -> "optremove"; else -> "change"
            }
            kind to line
        }
    }

    /** Which interface the app reaches the router through, if a staged change affects it (ui.js checkConnectivityAffected). */
    suspend fun connectivityAffected(): String? {
        val out = runCatching { Fs(client).execDirect("/usr/libexec/luci-peeraddr") }.getOrNull() ?: return null
        val info = runCatching { JsonCodec.parseToJsonElement(out) }.getOrNull() ?: return null
        val ifaces = info["inbound_interfaces"].arr?.mapNotNull { it.str } ?: return null
        val net = _changes.value["network"] ?: return null
        for (iif in ifaces) for (chg in net) {
            if (chg.getOrNull(0) == "set" && chg.getOrNull(1) == iif &&
                ((chg.getOrNull(2) == "disabled" && chg.getOrNull(3) == "1") || chg.getOrNull(2) in setOf("proto", "ipaddr", "netmask"))
            ) return iif
        }
        return null
    }

    /** Starts Save & Apply. With [checked] the router rolls back unless the app confirms within apply_rollback seconds. */
    suspend fun apply(checked: Boolean, skipConnectivityCheck: Boolean = false) {
        val env = client.env ?: return
        _status.value = ApplyStatus.Working("Starting configuration apply…")
        refresh()
        if (checked && !skipConnectivityCheck) {
            connectivityAffected()?.let { iface ->
                _status.value = ApplyStatus.ConnectivityWarning(iface, env.applyRollback)
                return
            }
        }
        val path = if (checked) "admin/uci/apply_rollback" else "admin/uci/apply_unchecked"
        val (code, text) = try {
            client.dispatcherPost(path)
        } catch (e: Exception) {
            _status.value = ApplyStatus.Failed("Apply request failed: ${e.message}"); return
        }
        if (code == (if (checked) 200 else 204)) {
            var confirmToken: String? = null
            if (checked) confirmToken = runCatching { JsonCodec.parseToJsonElement(text)["token"].str }.getOrNull()
            confirm(checked, System.currentTimeMillis() + env.applyRollback * 1000L, confirmToken)
        } else if (checked && code == 204) {
            _status.value = ApplyStatus.Done("There are no changes to apply")
            delay((env.applyDisplay * 1000).toLong()); if (_status.value is ApplyStatus.Done) _status.value = null
        } else {
            _status.value = ApplyStatus.Failed("Apply request failed with status $code ${text.take(120)}")
        }
    }

    private suspend fun confirm(checked: Boolean, deadline: Long, token: String?) {
        val env = client.env ?: return
        val started = System.currentTimeMillis()
        // Wait a few seconds for the settings to become effective (apply_holdoff).
        while (System.currentTimeMillis() - started < env.applyHoldoff * 1000L) {
            _status.value = ApplyStatus.Working("Applying configuration changes…", secondsLeft(deadline))
            delay(250)
        }
        while (true) {
            if (System.currentTimeMillis() >= deadline) { rollback(checked); return }
            _status.value = ApplyStatus.Working("Applying configuration changes…", secondsLeft(deadline))
            val t0 = System.currentTimeMillis()
            val code = try {
                client.dispatcherPost(
                    "admin/uci/confirm",
                    if (token != null) mapOf("token" to token) else emptyMap(),
                    withSession = false,
                    timeoutMs = env.applyTimeout * 1000L,
                ).first
            } catch (e: Exception) { 0 }
            if (code == 200 || code == 204) {
                _status.value = ApplyStatus.Done("Configuration changes applied.")
                uci.discardLocal()
                reloadAfterApply()
                delay((env.applyDisplay * 1000).toLong())
                if (_status.value is ApplyStatus.Done) _status.value = null
                return
            }
            val took = System.currentTimeMillis() - t0
            delay((1000 - took).coerceAtLeast(0))
        }
    }

    private suspend fun rollback(checked: Boolean) {
        val env = client.env ?: return
        if (!checked) { _status.value = ApplyStatus.Unreachable; return }
        _status.value = ApplyStatus.Working("Failed to confirm apply within ${env.applyRollback}s, waiting for rollback…")
        while (true) {
            val t0 = System.currentTimeMillis()
            val code = try {
                client.dispatcherPost("admin/uci/confirm", emptyMap(), withSession = true, timeoutMs = env.applyTimeout * 1000L).first
            } catch (e: Exception) { 0 }
            if (code == 204) {
                _status.value = ApplyStatus.RolledBack(env.applyRollback)
                runCatching { refresh() }
                return
            }
            delay((1000 - (System.currentTimeMillis() - t0)).coerceAtLeast(0))
        }
    }

    suspend fun revert() {
        val env = client.env ?: return
        _status.value = ApplyStatus.Working("Reverting configuration…")
        val (code, text) = try { client.dispatcherPost("admin/uci/revert") } catch (e: Exception) { 0 to (e.message ?: "") }
        if (code == 200) {
            _status.value = ApplyStatus.Done("Changes have been reverted.")
            uci.discardLocal()
            reloadAfterApply()
            delay((env.applyDisplay * 1000).toLong())
            if (_status.value is ApplyStatus.Done) _status.value = null
        } else {
            _status.value = ApplyStatus.Failed("Revert request failed with status $code ${text.take(120)}")
        }
    }

    private suspend fun reloadAfterApply() {
        uci.unloadAll()
        runCatching { refresh() }
        _generation.value++
    }

    /** Called by pages after a uci save so the indicator updates. */
    suspend fun afterSave() { refresh() }

    private fun secondsLeft(deadline: Long) = ((deadline - System.currentTimeMillis()) / 1000).toInt().coerceAtLeast(0)
}
