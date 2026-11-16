package com.the412banner.wrtpanel.form

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.ui.PageContext

/**
 * Runtime state of a [FormMap]: values being edited, dependency evaluation, validation and
 * LuCI's parse() → uci set/unset rules, then `uci save` through [UciStore.save].
 */
class FormRuntime(val map: FormMap, val ctx: PageContext) {
    val uci: UciStore get() = ctx.session.uci
    private val edits = mutableStateMapOf<String, Any?>()
    private val editedKeys = mutableStateMapOf<String, Boolean>()
    val errors = mutableStateMapOf<String, String>()
    /** Values of virtual (non-UCI) sections. */
    val virtualValues = mutableStateMapOf<String, Any?>()
    /** Free per-page data (loaded lists etc.). */
    val data = mutableMapOf<String, Any?>()
    val revision = mutableIntStateOf(0)

    private fun key(o: FormOption, sid: String) = "${System.identityHashCode(o)}|$sid"

    fun cfgsections(s: FormSection): List<String> {
        uci.revision.intValue // read for recomposition
        s.cfgsectionsOverride?.let { return it(uci) }
        if (s.virtual) return listOfNotNull(s.sectionName)
        return when (s.kind) {
            SectionKind.Named -> {
                val name = s.sectionName ?: return emptyList()
                if (uci.section(s.config, name) != null) listOf(name) else emptyList()
            }
            else -> uci.sections(s.config, s.sectionType).mapNotNull { sec ->
                val sid = sec[".name"] as? String ?: return@mapNotNull null
                if (s.filter?.invoke(sid, sec) == false) null else sid
            }
        }
    }

    fun cfgvalue(o: FormOption, sid: String): Any? {
        uci.revision.intValue
        o.cfgvalueFn?.let { return it(this, sid) }
        if (o.section.virtual) return virtualValues[key(o, sid)]
        if (o is DummyValueOption || o is ButtonOption) {
            return uci.get(o.uciconfig ?: o.section.config, o.ucisection ?: sid, o.ucioption ?: o.option)
        }
        return uci.get(o.uciconfig ?: o.section.config, o.ucisection ?: sid, o.ucioption ?: o.option)
    }

    /** Current widget value: the edit if any, else cfgvalue, else the default. */
    fun formvalue(o: FormOption, sid: String): Any? {
        val k = key(o, sid)
        if (editedKeys[k] == true) return edits[k]
        val cv = cfgvalue(o, sid)
        if (cv != null) return if (o is MultiValueOption && o.asString && cv is String) UciStore.toArray(cv) else cv
        return o.default
    }

    fun formString(o: FormOption, sid: String): String = when (val v = formvalue(o, sid)) {
        null -> ""
        is List<*> -> v.joinToString(" ")
        else -> v.toString()
    }

    fun formList(o: FormOption, sid: String): List<String> = when (val v = formvalue(o, sid)) {
        null -> emptyList()
        is List<*> -> v.mapNotNull { it?.toString() }
        is String -> if (o is DynamicListOption || o is MultiValueOption) UciStore.toArray(v) else listOf(v)
        else -> listOf(v.toString())
    }

    fun isEdited(o: FormOption, sid: String) = editedKeys[key(o, sid)] == true

    fun setValue(o: FormOption, sid: String, v: Any?) {
        val k = key(o, sid)
        edits[k] = v
        editedKeys[k] = true
        errors.remove(k)
        o.onChange?.invoke(this, sid, v)
        revision.intValue++
    }

    fun setVirtual(o: FormOption, sid: String, v: Any?) { virtualValues[key(o, sid)] = v; revision.intValue++ }

    fun error(o: FormOption, sid: String): String? = errors[key(o, sid)]

    /** Finds the option a dependency refers to: "option" in the same section, or "config.section.option". */
    private fun lookup(name: String, sid: String, from: FormOption): Pair<FormOption, String>? {
        if (name.contains('.')) {
            val parts = name.split('.')
            if (parts.size == 3) {
                val (conf, s, opt) = parts
                val o = map.sections.flatMap { it.allOptions() }.firstOrNull { it.option == opt && (it.uciconfig ?: it.section.config) == conf }
                return o?.let { it to s }
            }
        }
        val local = from.section.options.firstOrNull { it.option == name }
            ?: from.section.allOptions().firstOrNull { it.option == name }
            ?: map.sections.flatMap { it.allOptions() }.firstOrNull { it.option == name && it.section.config == from.section.config }
        return local?.let { it to sid }
    }

    fun isActive(o: FormOption, sid: String, depth: Int = 0): Boolean {
        if (o.deps.isEmpty() || depth > 12) return true
        var def = false
        for (dep in o.deps) {
            if (dep.isDefault) { def = true; continue }
            var istat = true
            for ((name, expected) in dep.conds) {
                val ref = lookup(name, sid, o)
                val v = if (ref != null && isActive(ref.first, ref.second, depth + 1)) formvalue(ref.first, ref.second) else null
                val eq = if (dep.contains) contains(v, expected) else depEqual(v, expected)
                istat = istat && eq
                if (!istat) break
            }
            if (istat xor dep.reverse) return true
        }
        return def
    }

    private fun depEqual(v: Any?, expected: Any?): Boolean = when (expected) {
        is Regex -> v != null && v !is List<*> && expected.containsMatchIn(v.toString())
        null -> v == null || v == "" || (v is List<*> && v.isEmpty())
        is Boolean -> (v?.toString() in setOf("1", "true", "yes", "on")) == expected
        is List<*> -> v is List<*> && v.map { it.toString() } == expected.map { it.toString() }
        else -> v != null && v !is List<*> && v.toString() == expected.toString() ||
            (expected.toString() == "" && (v == null || v == ""))
    }

    private fun contains(v: Any?, expected: Any?): Boolean = when (v) {
        is List<*> -> v.any { it.toString() == expected.toString() }
        is String -> v.contains(expected.toString())
        else -> false
    }

    fun isDisabled(o: FormOption, sid: String): Boolean =
        map.readonly || ctx.readonly || o.readonly == true || (o.disabledFn?.invoke(this, sid) ?: false)

    /** Field validation as ui.js does it on input; null = valid. */
    fun validateField(o: FormOption, sid: String): String? {
        if (o is DummyValueOption || o is ButtonOption || o is SectionValueOption) return null
        val values: List<String> = when (o) {
            is FlagOption -> return null
            is DynamicListOption, is MultiValueOption -> formList(o, sid)
            else -> listOf(formString(o, sid))
        }
        val empty = values.isEmpty() || values.all { it.isEmpty() }
        if (empty) {
            return if (o.optional || o.rmempty) null else "Expecting: non-empty value"
        }
        val dt = o.datatype
        if (dt != null && o !is ListValueOption) {
            for (v in values) {
                if (v.isEmpty()) continue
                Datatypes.validate(dt, v)?.let { return it }
            }
        }
        o.validate?.let { fn ->
            for (v in values) fn(this, sid, v)?.let { return it }
            if (values.isEmpty()) fn(this, sid, "")?.let { return it }
        }
        return null
    }

    fun textvalue(o: FormOption, sid: String): String {
        o.textvalueFn?.let { return it(this, sid) ?: "" }
        val v = formvalue(o, sid)
        return when (o) {
            is FlagOption -> if ((v?.toString() ?: o.disabled) == o.enabled) "Yes" else "No"
            is ChoiceOption -> {
                val ch = o.choicesFor(this, sid)
                val list = if (v is List<*>) v.map { it.toString() } else listOfNotNull(v?.toString()?.takeIf { it.isNotEmpty() })
                list.joinToString(", ") { k -> ch.firstOrNull { it.first == k }?.second ?: k }
            }
            else -> when (v) { null -> ""; is List<*> -> v.joinToString(", "); else -> v.toString() }
        }
    }

    // ---- parse / write (form.js AbstractValue.parse) ----

    private fun write(o: FormOption, sid: String, v: Any) {
        o.writeFn?.let { it(this, sid, v); return }
        if (o.section.virtual) { virtualValues[key(o, sid)] = v; return }
        uci.set(o.uciconfig ?: o.section.config, o.ucisection ?: sid, o.ucioption ?: o.option, v)
    }

    private fun remove(o: FormOption, sid: String) {
        o.removeFn?.let { it(this, sid); return }
        if (o.section.virtual) { virtualValues.remove(key(o, sid)); return }
        val cfg = o.uciconfig ?: o.section.config
        val s = o.ucisection ?: sid
        val opt = o.ucioption ?: o.option
        // Another active sibling writing the same UCI option wins (form.js remove()).
        for (sib in o.section.options) {
            if (sib === o) continue
            if ((sib.uciconfig ?: sib.section.config) != cfg || (sib.ucisection ?: sid) != s || (sib.ucioption ?: sib.option) != opt) continue
            if (isActive(sib, sid)) return
        }
        uci.unset(cfg, s, opt)
    }

    /** Returns an error message, or null when the option parsed fine. */
    fun parseOption(o: FormOption, sid: String): String? {
        when (o) {
            is DummyValueOption, is ButtonOption -> return null
            is SectionValueOption -> return parseSection(o.sub, sectionIdsFor(o.sub, sid))
            else -> {}
        }
        val active = isActive(o, sid)
        if (o is FlagOption) {
            if (active) {
                val fval = formString(o, sid).ifEmpty { o.default?.toString() ?: o.disabled }
                if (fval == (o.default?.toString() ?: o.disabled) && (o.optional || o.rmempty)) remove(o, sid)
                else {
                    val cv = cfgvalue(o, sid)?.toString()
                    if (o.forcewrite || cv != fval) write(o, sid, fval)
                }
            } else if (!o.retain) remove(o, sid)
            return null
        }
        if (active) {
            validateField(o, sid)?.let { err ->
                val title = o.title ?: o.option
                return "Option \"$title\" contains an invalid input value. $err"
            }
            val cval = cfgvalue(o, sid)
            val fval: Any? = when (o) {
                is DynamicListOption -> formList(o, sid).filter { it.isNotEmpty() }.ifEmpty { null }
                is MultiValueOption -> formList(o, sid).ifEmpty { null }?.let { if (o.asString) it.joinToString(" ") else it }
                else -> formString(o, sid).ifEmpty { null }
            }
            if (fval == null) {
                if (o.rmempty || o.optional) remove(o, sid)
                else return "Option \"${o.title ?: o.option}\" must not be empty."
            } else if (o.forcewrite || !UciStore.uciEqual(normalizeForCompare(cval, o), fval)) {
                write(o, sid, fval)
            }
        } else if (!o.retain) {
            remove(o, sid)
        }
        return null
    }

    private fun normalizeForCompare(v: Any?, o: FormOption): Any? = when {
        v == null -> null
        o is DynamicListOption && v is String -> UciStore.toArray(v)
        o is MultiValueOption && !o.asString && v is String -> UciStore.toArray(v)
        o is MultiValueOption && o.asString && v is List<*> -> v.joinToString(" ")
        else -> v
    }

    /** Section ids a nested section works on, given its parent row id (SectionValue). */
    fun sectionIdsFor(sub: FormSection, parentSid: String): List<String> =
        if (sub.kind == SectionKind.Named && sub.sectionName == null) listOf(parentSid) else cfgsections(sub)

    /** Parses [s] for [sids]; [modal] = values come from the grid editor (all options shown). */
    fun parseSection(s: FormSection, sids: List<String> = cfgsections(s), modal: Boolean = false): String? {
        for (sid in sids) {
            for (o in s.options) {
                if (!modal && (s.kind == SectionKind.Grid) && (!o.editable || o.modalonly == true)) continue
                if (!modal && s.kind == SectionKind.Table && o.modalonly == true) continue
                if (modal && o.modalonly == false) continue
                val err = parseOption(o, sid)
                if (err != null) { errors[key(o, sid)] = err; return err }
            }
        }
        return null
    }

    /** form.Map.save(): parse everything, stage with `uci save`, refresh the changes indicator. */
    suspend fun save(): String? {
        for (s in map.sections) parseSection(s)?.let { return it }
        map.beforeStage?.invoke(ctx, this)
        uci.save()
        map.afterSave?.invoke(ctx, this)
        clearEdits()
        ctx.session.changes.afterSave()
        return null
    }

    /** Save from a grid editor: parse one section id (all its options) and stage. */
    suspend fun saveModal(s: FormSection, sid: String): String? {
        parseSection(s, listOf(sid), modal = true)?.let { return it }
        uci.save()
        // Created sections got a real name: move pending edits over is unnecessary, they are staged.
        clearEdits()
        ctx.session.changes.afterSave()
        return null
    }

    fun clearEdits() { edits.clear(); editedKeys.clear(); errors.clear(); revision.intValue++ }

    fun reset() { clearEdits() }

    fun hasEdits(): Boolean = editedKeys.isNotEmpty() || uci.hasLocalChanges()

    // ---- sections ----

    fun addSection(s: FormSection, name: String? = null): String {
        val sid = uci.add(s.config, s.sectionType, if (s.kind == SectionKind.Named) s.sectionName else name)
        s.onAdd?.invoke(this, sid)
        revision.intValue++
        return sid
    }

    fun removeSection(s: FormSection, sid: String) {
        uci.remove(s.config, sid)
        revision.intValue++
    }

    fun moveSection(s: FormSection, sid: String, up: Boolean) {
        val ids = uci.sections(s.config).mapNotNull { it[".name"] as? String }
        val mine = cfgsections(s)
        val idx = mine.indexOf(sid)
        val target = if (up) mine.getOrNull(idx - 1) else mine.getOrNull(idx + 1)
        if (target == null || !ids.contains(target)) return
        uci.move(s.config, sid, target, after = !up)
        revision.intValue++
    }

    fun sectionTitle(s: FormSection, sid: String): String? =
        s.sectionTitle?.invoke(sid) ?: if (s.anonymous || uci.section(s.config, sid)?.get(".anonymous") == true) null else sid
}
