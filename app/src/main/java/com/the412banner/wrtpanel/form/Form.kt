package com.the412banner.wrtpanel.form

import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.ui.PageContext

/**
 * A declarative model of LuCI's form.js (Map / sections / options). Pages build a [FormMap]
 * the same way LuCI views do (`m = new form.Map(...); s = m.section(...); o = s.option(...)`),
 * and [FormRuntime] implements LuCI's load / depends / parse / save rules on top of [UciStore].
 */
class FormMap(val config: String, var title: String? = null, var description: String? = null) {
    val sections = mutableListOf<FormSection>()
    val chained = mutableListOf<String>()
    var readonly = false
    /** Map.tabbed: top-level sections are shown as tabs. */
    var tabbed = false
    /** Extra loading before the form is shown (data for choices, other configs). */
    var onLoad: (suspend PageContext.() -> Unit)? = null
    /** Runs after the form values were written to UCI and before `uci save` (handleSave hooks). */
    var beforeStage: (suspend PageContext.(FormRuntime) -> Unit)? = null
    /** Runs after staging (e.g. extra ubus calls a view makes in handleSave). */
    var afterSave: (suspend PageContext.(FormRuntime) -> Unit)? = null
    /** Replaces the Save & Apply / Save / Reset footer (views that set handleSaveApply = null). */
    var hideSaveApply = false
    var hideSave = false
    var hideReset = false

    fun chain(conf: String) { chained += conf }

    fun typed(type: String, title: String? = null, description: String? = null, block: FormSection.() -> Unit = {}): FormSection =
        FormSection(this, SectionKind.Typed, type, null, title, description).also { sections += it; it.block() }

    fun named(name: String, type: String, title: String? = null, description: String? = null, block: FormSection.() -> Unit = {}): FormSection =
        FormSection(this, SectionKind.Named, type, name, title, description).also { sections += it; it.block() }

    fun grid(type: String, title: String? = null, description: String? = null, block: FormSection.() -> Unit = {}): FormSection =
        FormSection(this, SectionKind.Grid, type, null, title, description).also { sections += it; it.block() }

    fun table(type: String, title: String? = null, description: String? = null, block: FormSection.() -> Unit = {}): FormSection =
        FormSection(this, SectionKind.Table, type, null, title, description).also { sections += it; it.block() }

    /** A section that is not backed by UCI (form.JSONMap-like data), see [FormSection.virtualSids]. */
    fun virtual(name: String, title: String? = null, description: String? = null, block: FormSection.() -> Unit = {}): FormSection =
        FormSection(this, SectionKind.Named, "__virtual__", name, title, description).also { it.virtual = true; sections += it; it.block() }

    val configs: List<String> get() = (listOf(config) + chained + sections.flatMap { s -> s.allOptions().mapNotNull { it.uciconfig } }).distinct()
}

enum class SectionKind { Typed, Named, Grid, Table }

data class FormTab(val name: String, val title: String, val description: String? = null)

class FormSection(
    val map: FormMap,
    val kind: SectionKind,
    val sectionType: String,
    val sectionName: String?,
    var title: String?,
    var description: String?,
) {
    val options = mutableListOf<FormOption>()
    val tabs = mutableListOf<FormTab>()
    var anonymous = false
    var addremove = false
    var sortable = false
    var nodescriptions = false
    var addbtntitle: String? = null
    var uciconfig: String? = null
    /** Section is only a container for values kept outside UCI (no uci reads/writes). */
    var virtual = false
    var parentOption: FormOption? = null
    /** Max columns a grid shows (rest only in the editor). */
    var maxCols: Int? = null
    var filter: ((sid: String, section: Map<String, Any>) -> Boolean)? = null
    var sectionTitle: ((sid: String) -> String?)? = null
    var modalTitle: ((sid: String) -> String)? = null
    /** Overrides which sections are shown (LuCI cfgsections overrides). */
    var cfgsectionsOverride: ((UciStore) -> List<String>)? = null
    /** Called after a section was added (LuCI handleAdd overrides): may set defaults. */
    var onAdd: ((rt: FormRuntime, sid: String) -> Unit)? = null
    /** Name validation for named adds (renderSectionAdd validators); null = uciname. */
    var addNameValidator: ((String) -> String?)? = null
    /** For named adds: offered choices instead of free text. */
    var addNameChoices: List<Pair<String, String>>? = null
    /** Shown when there are no sections ("This section contains no values yet"). */
    var placeholder: String? = null
    /** Section-level custom render below the options (e.g. status tables). */
    var footerText: ((sid: String) -> String?)? = null
    /** Extra row actions for grid sections (e.g. "Restart" on interfaces). */
    val rowActions = mutableListOf<RowAction>()
    /** Hides the edit button / modal for grid rows. */
    var noEdit = false
    var noDelete: ((sid: String) -> Boolean)? = null

    val config: String get() = uciconfig ?: map.config

    fun tab(name: String, title: String, description: String? = null) { tabs += FormTab(name, title, description) }

    fun <T : FormOption> option(o: T, block: T.() -> Unit = {}): T { o.block(); options += o; return o }

    fun allOptions(): List<FormOption> = options + options.filterIsInstance<SectionValueOption>().flatMap { it.sub.allOptions() }

    // Builders mirroring s.option(form.X, name, title) / s.taboption(tab, form.X, name, title)
    fun value(name: String, title: String? = null, description: String? = null, tab: String? = null, block: ValueOption.() -> Unit = {}) =
        option(ValueOption(this, name, title).also { it.description = description; it.tab = tab }, block)
    fun list(name: String, title: String? = null, description: String? = null, tab: String? = null, block: ListValueOption.() -> Unit = {}) =
        option(ListValueOption(this, name, title).also { it.description = description; it.tab = tab }, block)
    fun flag(name: String, title: String? = null, description: String? = null, tab: String? = null, block: FlagOption.() -> Unit = {}) =
        option(FlagOption(this, name, title).also { it.description = description; it.tab = tab }, block)
    fun multi(name: String, title: String? = null, description: String? = null, tab: String? = null, block: MultiValueOption.() -> Unit = {}) =
        option(MultiValueOption(this, name, title).also { it.description = description; it.tab = tab }, block)
    fun dynlist(name: String, title: String? = null, description: String? = null, tab: String? = null, block: DynamicListOption.() -> Unit = {}) =
        option(DynamicListOption(this, name, title).also { it.description = description; it.tab = tab }, block)
    fun text(name: String, title: String? = null, description: String? = null, tab: String? = null, block: TextValueOption.() -> Unit = {}) =
        option(TextValueOption(this, name, title).also { it.description = description; it.tab = tab }, block)
    fun dummy(name: String, title: String? = null, description: String? = null, tab: String? = null, block: DummyValueOption.() -> Unit = {}) =
        option(DummyValueOption(this, name, title).also { it.description = description; it.tab = tab }, block)
    fun button(name: String, title: String? = null, description: String? = null, tab: String? = null, block: ButtonOption.() -> Unit = {}) =
        option(ButtonOption(this, name, title).also { it.description = description; it.tab = tab }, block)
    fun hidden(name: String, tab: String? = null, block: HiddenValueOption.() -> Unit = {}) =
        option(HiddenValueOption(this, name).also { it.tab = tab }, block)
    fun file(name: String, title: String? = null, description: String? = null, tab: String? = null, block: FileUploadOption.() -> Unit = {}) =
        option(FileUploadOption(this, name, title).also { it.description = description; it.tab = tab }, block)
    fun subsection(name: String, sub: FormSection, title: String? = null, tab: String? = null, block: SectionValueOption.() -> Unit = {}) =
        option(SectionValueOption(this, name, title, sub).also { it.tab = tab; sub.parentOption = it }, block)

    /** A nested section for SectionValue (LuCI form.SectionValue). Not added to the map's top level. */
    fun nested(kind: SectionKind, type: String, title: String? = null, description: String? = null, block: FormSection.() -> Unit = {}): FormSection =
        FormSection(map, kind, type, null, title, description).also { it.block() }
}

data class RowAction(val label: String, val icon: String? = null, val destructive: Boolean = false, val visible: (String) -> Boolean = { true }, val run: suspend PageContext.(sid: String) -> Unit)

/** A dependency: all keys must match (LuCI depends({a: x, b: y})). Values: String, Regex, null (= empty / unset) or Boolean. */
data class Dep(val conds: Map<String, Any?>, val reverse: Boolean = false, val contains: Boolean = false, val isDefault: Boolean = false)

abstract class FormOption(val section: FormSection, val option: String, var title: String?) {
    var description: String? = null
    var tab: String? = null
    var default: Any? = null
    var rmempty = true
    var optional = false
    var retain = false
    var readonly: Boolean? = null
    var datatype: String? = null
    var placeholder: String? = null
    var password = false
    /** true = only in the grid editor, false = only in the grid row. */
    var modalonly: Boolean? = null
    /** Grid: value editable directly in the row. */
    var editable = false
    var width: String? = null
    var forcewrite = false
    var uciconfig: String? = null
    var ucisection: String? = null
    var ucioption: String? = null
    val deps = mutableListOf<Dep>()
    /** Custom validation like LuCI's o.validate: return null when OK, else the error text. */
    var validate: ((rt: FormRuntime, sid: String, value: String) -> String?)? = null
    var cfgvalueFn: ((rt: FormRuntime, sid: String) -> Any?)? = null
    var writeFn: ((rt: FormRuntime, sid: String, value: Any) -> Unit)? = null
    var removeFn: ((rt: FormRuntime, sid: String) -> Unit)? = null
    var textvalueFn: ((rt: FormRuntime, sid: String) -> String?)? = null
    /** Default that depends on other values (form.js o.defaults / updateDefaultValue); null = [default]. */
    var defaultFn: ((rt: FormRuntime, sid: String) -> Any?)? = null
    /** Async per-option load (LuCI o.load overrides that fetch data): runs during map load. */
    var loadFn: (suspend PageContext.(rt: FormRuntime) -> Unit)? = null
    /** Visible but greyed out while this returns true. */
    var disabledFn: ((rt: FormRuntime, sid: String) -> Boolean)? = null
    /** Value change hook (onchange in LuCI). */
    var onChange: ((rt: FormRuntime, sid: String, value: Any?) -> Unit)? = null
    /** Custom widget (LuCI renderWidget overrides); replaces the default field UI. */
    var render: (@androidx.compose.runtime.Composable (rt: FormRuntime, sid: String) -> Unit)? = null

    fun depends(opt: String, value: Any?) { deps += Dep(mapOf(opt to value)) }
    fun depends(vararg conds: Pair<String, Any?>) { deps += Dep(conds.toMap()) }
    fun dependsNot(vararg conds: Pair<String, Any?>) { deps += Dep(conds.toMap(), reverse = true) }
    fun dependsContains(opt: String, value: String) { deps += Dep(mapOf(opt to value), contains = true) }
    fun dependsDefault() { deps += Dep(emptyMap(), isDefault = true) }

    open val isList: Boolean get() = false
}

/** Choice support shared by Value / ListValue / MultiValue / DynamicList (o.value(key, label)). */
abstract class ChoiceOption(section: FormSection, option: String, title: String?) : FormOption(section, option, title) {
    val choices = mutableListOf<Pair<String, String>>()
    /** Optional per-choice description (RichListValue). */
    val choiceDescriptions = mutableMapOf<String, String>()
    /** Choices computed at render time (depend on other values). */
    var choicesFn: ((rt: FormRuntime, sid: String) -> List<Pair<String, String>>)? = null
    fun value(key: String, label: String = key) { choices.removeAll { it.first == key }; choices += key to label }
    fun value(key: String, label: String, desc: String) { value(key, label); choiceDescriptions[key] = desc }
    fun clearChoices() { choices.clear() }
    fun choicesFor(rt: FormRuntime, sid: String): List<Pair<String, String>> = choicesFn?.invoke(rt, sid) ?: choices
}

class ValueOption(section: FormSection, option: String, title: String?) : ChoiceOption(section, option, title) {
    /** With choices: free text allowed besides the suggestions (LuCI combobox). */
    var allowCustom = true
}

class ListValueOption(section: FormSection, option: String, title: String?) : ChoiceOption(section, option, title) {
    /** "select" or "radio" */
    var widget = "select"
    var orientation = "vertical"
}

class FlagOption(section: FormSection, option: String, title: String?) : FormOption(section, option, title) {
    var enabled = "1"
    var disabled = "0"
    init { default = disabled }
}

class MultiValueOption(section: FormSection, option: String, title: String?) : ChoiceOption(section, option, title) {
    /** Stored as a single space-separated string instead of a list. */
    var asString = false
    var create = false
    override val isList: Boolean get() = !asString
}

class DynamicListOption(section: FormSection, option: String, title: String?) : ChoiceOption(section, option, title) {
    override val isList: Boolean get() = true
}

class TextValueOption(section: FormSection, option: String, title: String?) : FormOption(section, option, title) {
    var rows = 6
    var monospace = false
    var wrap = true
}

class DummyValueOption(section: FormSection, option: String, title: String?) : FormOption(section, option, title) {
    var href: ((FormRuntime, String) -> String?)? = null
    init { rmempty = true; modalonly = null }
}

class ButtonOption(section: FormSection, option: String, title: String?) : FormOption(section, option, title) {
    var inputtitle: ((FormRuntime, String) -> String)? = null
    /** "action", "positive", "negative", "reset", "apply", "save", "remove" */
    var inputstyle = "action"
    var onClick: (suspend PageContext.(rt: FormRuntime, sid: String) -> Unit)? = null
}

class HiddenValueOption(section: FormSection, option: String) : FormOption(section, option, null)

class FileUploadOption(section: FormSection, option: String, title: String?) : FormOption(section, option, title) {
    var rootDirectory = "/etc/luci-uploads"
    var showHidden = false
    var enableUpload = true
    var enableRemove = true
    var browser = false
}

class SectionValueOption(section: FormSection, option: String, title: String?, val sub: FormSection) : FormOption(section, option, title)
