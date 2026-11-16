@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.the412banner.wrtpanel.form

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.EmptyState
import com.the412banner.wrtpanel.ui.components.ErrorBox
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.SectionCard
import com.the412banner.wrtpanel.ui.components.htmlToAnnotated
import com.the412banner.wrtpanel.ui.components.stripHtml
import com.the412banner.wrtpanel.ui.theme.CodeStyle
import kotlinx.coroutines.launch

/**
 * A complete LuCI form page: builds the [FormMap] (fresh UCI state, like a page load),
 * renders it and shows the Save & Apply / Save / Reset footer.
 */
@Composable
fun FormPage(ctx: PageContext, vararg keys: Any?, build: suspend PageContext.() -> FormMap) {
    val generation by ctx.session.changes.generation.collectAsState()
    var reloadTick by remember { mutableIntStateOf(0) }
    var state by remember(generation, reloadTick, *keys) { mutableStateOf<FormLoad>(FormLoad.Loading) }
    LaunchedEffect(generation, reloadTick, *keys) {
        state = FormLoad.Loading
        state = try {
            ctx.uci.unloadAll()
            val map = ctx.build()
            if (ctx.readonly) map.readonly = true
            ctx.uci.load(map.configs)
            map.onLoad?.invoke(ctx)
            val rt = FormRuntime(map, ctx)
            for (o in map.sections.flatMap { it.allOptions() }) o.loadFn?.invoke(ctx, rt)
            FormLoad.Ready(rt)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FormLoad.Failed(e.message ?: e.toString())
        }
    }
    when (val s = state) {
        is FormLoad.Loading -> PageColumn { LoadingBox() }
        is FormLoad.Failed -> PageColumn { ErrorBox(s.message) { reloadTick++ } }
        is FormLoad.Ready -> FormView(s.rt, onReset = { reloadTick++ })
    }
}

sealed interface FormLoad {
    data object Loading : FormLoad
    data class Ready(val rt: FormRuntime) : FormLoad
    data class Failed(val message: String) : FormLoad
}

@Composable
fun FormView(rt: FormRuntime, onReset: () -> Unit, extraTop: (@Composable () -> Unit)? = null, extraBottom: (@Composable () -> Unit)? = null) {
    val map = rt.map
    val ctx = rt.ctx
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    rt.revision.intValue
    Box(Modifier.fillMaxSize()) {
        PageColumn {
            PageHeader(map.title, map.description)
            extraTop?.invoke()
            if (map.tabbed && map.sections.size > 1) {
                var tab by remember { mutableIntStateOf(0) }
                PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    map.sections.forEachIndexed { i, s ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(s.title ?: s.sectionType) })
                    }
                }
                SectionView(rt, map.sections[tab.coerceIn(0, map.sections.size - 1)])
            } else {
                map.sections.forEach { SectionView(rt, it) }
            }
            extraBottom?.invoke()
        }
        if (!(map.readonly || ctx.readonly) && !(map.hideSave && map.hideSaveApply && map.hideReset)) {
            SaveBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                busy = busy,
                showApply = !map.hideSaveApply, showSave = !map.hideSave, showReset = !map.hideReset,
                onReset = { rt.reset(); onReset() },
                onSave = { apply, unchecked ->
                    scope.launch {
                        busy = true
                        val err = try { rt.save() } catch (e: Exception) { e.message ?: e.toString() }
                        busy = false
                        if (err != null) ctx.toast(err)
                        else if (apply) ctx.session.changes.apply(checked = !unchecked)
                        else ctx.toast("Changes saved")
                    }
                },
            )
        }
    }
}

@Composable
fun SaveBar(
    modifier: Modifier,
    busy: Boolean,
    showApply: Boolean = true,
    showSave: Boolean = true,
    showReset: Boolean = true,
    onReset: () -> Unit,
    onSave: (apply: Boolean, unchecked: Boolean) -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.navigationBarsPadding().imePadding().widthIn(max = 920.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) Text("Saving…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
            if (showReset) TextButton(onClick = onReset, enabled = !busy) { Text("Reset") }
            if (showSave) OutlinedButton(onClick = { onSave(false, false) }, enabled = !busy) { Text("Save") }
            if (showApply) {
                var menu by remember { mutableStateOf(false) }
                Row {
                    Button(onClick = { onSave(true, false) }, enabled = !busy) { Text("Save & Apply") }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Outlined.ArrowDropDown, "More apply options") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Apply unchecked") }, onClick = { menu = false; onSave(true, true) })
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- sections

@Composable
fun SectionView(rt: FormRuntime, s: FormSection, parentSid: String? = null) {
    rt.revision.intValue
    when (s.kind) {
        SectionKind.Grid, SectionKind.Table -> GridSectionView(rt, s)
        else -> TypedSectionView(rt, s, parentSid)
    }
}

@Composable
private fun TypedSectionView(rt: FormRuntime, s: FormSection, parentSid: String?) {
    val sids = if (parentSid != null) rt.sectionIdsFor(s, parentSid) else rt.cfgsections(s)
    val ro = rt.map.readonly || rt.ctx.readonly
    if (s.kind == SectionKind.Named && sids.isEmpty()) {
        SectionCard(title = s.title?.let(::stripHtml), description = s.description?.let(::stripHtml)) {
            if (s.addremove && !ro) {
                Text("This section contains no values yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { rt.addSection(s) }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text(s.addbtntitle ?: "Add") }
            } else EmptyState(s.placeholder ?: "This section contains no values yet")
        }
        return
    }
    val single = sids.size == 1 && (s.kind == SectionKind.Named || !s.addremove)
    if (sids.isEmpty()) {
        SectionCard(title = s.title?.let(::stripHtml), description = s.description?.let(::stripHtml)) {
            EmptyState(s.placeholder ?: "This section contains no values yet")
            if (s.addremove && !ro) AddSectionRow(rt, s)
        }
        return
    }
    if (single) {
        val sid = sids.first()
        SectionCard(
            title = s.title?.let(::stripHtml),
            description = if (s.nodescriptions) null else s.description?.let(::stripHtml),
            actions = {
                if (s.addremove && !ro) IconButton(onClick = { rt.removeSection(s, sid) }) { Icon(Icons.Outlined.Delete, "Delete") }
            },
        ) { SectionBody(rt, s, sid) }
        if (s.addremove && !ro && s.kind != SectionKind.Named) AddSectionRow(rt, s)
        return
    }
    if (s.title != null || s.description != null) PageHeaderSmall(s.title, s.description)
    sids.forEachIndexed { idx, sid ->
        SectionCard(
            title = rt.sectionTitle(s, sid),
            actions = {
                if (s.sortable && !ro) {
                    IconButton(onClick = { rt.moveSection(s, sid, true) }, enabled = idx > 0) { Icon(Icons.Outlined.ArrowUpward, "Move up") }
                    IconButton(onClick = { rt.moveSection(s, sid, false) }, enabled = idx < sids.size - 1) { Icon(Icons.Outlined.ArrowDownward, "Move down") }
                }
                if (s.addremove && !ro) IconButton(onClick = { rt.removeSection(s, sid) }) { Icon(Icons.Outlined.Delete, "Delete") }
            },
        ) { SectionBody(rt, s, sid) }
    }
    if (s.addremove && !ro) AddSectionRow(rt, s)
}

@Composable
private fun PageHeaderSmall(title: String?, description: String?) {
    Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
        if (title != null) Text(stripHtml(title), style = MaterialTheme.typography.titleMedium)
        if (description != null) Text(htmlToAnnotated(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Tabs (if any) and the option fields of one section id. */
@Composable
fun SectionBody(rt: FormRuntime, s: FormSection, sid: String, modal: Boolean = false) {
    rt.revision.intValue
    val opts = s.options.filter { o ->
        if (modal) o.modalonly != false else !(s.kind == SectionKind.Grid && o.modalonly == true)
    }
    val usedTabs = s.tabs.filter { t -> opts.any { it.tab == t.name && rt.isActive(it, sid) } }
    if (usedTabs.size > 1) {
        var tab by remember(sid) { mutableIntStateOf(0) }
        val cur = usedTabs[tab.coerceIn(0, usedTabs.size - 1)]
        PrimaryScrollableTabRow(selectedTabIndex = tab.coerceIn(0, usedTabs.size - 1), edgePadding = 0.dp, containerColor = androidx.compose.ui.graphics.Color.Transparent) {
            usedTabs.forEachIndexed { i, t -> Tab(selected = i == tab, onClick = { tab = i }, text = { Text(t.title) }) }
        }
        Spacer(Modifier.height(8.dp))
        cur.description?.let { Text(htmlToAnnotated(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp)) }
        OptionList(rt, opts.filter { it.tab == cur.name || (it.tab == null && cur == usedTabs.first()) }, sid)
    } else {
        OptionList(rt, opts, sid)
    }
    s.footerText?.invoke(sid)?.let { Text(htmlToAnnotated(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
}

@Composable
private fun OptionList(rt: FormRuntime, opts: List<FormOption>, sid: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        opts.forEach { o ->
            if (o is HiddenValueOption) return@forEach
            val active = rt.isActive(o, sid)
            AnimatedVisibility(active, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Box(Modifier.padding(vertical = 6.dp)) { OptionField(rt, o, sid) }
            }
        }
    }
}

@Composable
private fun AddSectionRow(rt: FormRuntime, s: FormSection) {
    var name by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val needsName = !s.anonymous && s.kind != SectionKind.Named
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (needsName) {
            val choices = s.addNameChoices
            if (choices != null) {
                SelectField(value = name, choices = choices, label = "Name", onChange = { name = it }, modifier = Modifier.weight(1f))
            } else {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; err = null }, singleLine = true, label = { Text("New section name") },
                    isError = err != null, supportingText = err?.let { { Text(it) } }, modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.width(8.dp))
        } else Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = {
            if (needsName) {
                val e = s.addNameValidator?.invoke(name) ?: if (name.isEmpty()) "Expecting: non-empty value" else Datatypes.validate("uciname", name)
                if (e == null && rt.uci.section(s.config, name) != null) { err = "Expecting: unique value"; return@FilledTonalButton }
                if (e != null) { err = e; return@FilledTonalButton }
                rt.addSection(s, name); name = ""
            } else rt.addSection(s)
        }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text(s.addbtntitle ?: "Add") }
    }
}

// ---------------------------------------------------------------- grid sections

@Composable
private fun GridSectionView(rt: FormRuntime, s: FormSection) {
    val sids = rt.cfgsections(s)
    val ro = rt.map.readonly || rt.ctx.readonly
    var editing by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    val scope = rememberCoroutineScope()
    SectionCard(title = s.title?.let(::stripHtml), description = if (s.nodescriptions) null else s.description?.let(::stripHtml)) {
        if (sids.isEmpty()) EmptyState(s.placeholder ?: "This section contains no values yet")
        val cols = s.options.filter { it.modalonly != true && it !is HiddenValueOption }.let { l -> s.maxCols?.let { l.take(it) } ?: l }
        sids.forEachIndexed { idx, sid ->
            GridRow(rt, s, sid, idx, sids.size, cols, ro, onEdit = { editing = sid to false })
            if (idx < sids.size - 1) HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        if (s.addremove && !ro) {
            Spacer(Modifier.height(8.dp))
            GridAddRow(rt, s) { sid -> if (!(s.noEdit || s.options.none { it.modalonly != false })) editing = sid to true }
        }
    }
    editing?.let { (sid, isNew) ->
        GridEditor(
            rt, s, sid,
            onDismiss = {
                if (isNew) rt.removeSection(s, sid)
                rt.clearEdits()
                editing = null
            },
            onSave = {
                scope.launch {
                    val err = try { rt.saveModal(s, sid) } catch (e: Exception) { e.message }
                    if (err != null) rt.ctx.toast(err) else editing = null
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GridRow(
    rt: FormRuntime, s: FormSection, sid: String, idx: Int, count: Int, cols: List<FormOption>, ro: Boolean, onEdit: () -> Unit,
) {
    val title = rt.sectionTitle(s, sid)
    Column(Modifier.fillMaxWidth().clickable(enabled = !s.noEdit && !ro, onClick = onEdit).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    cols.forEach { o ->
                        if ((o.editable || s.kind == SectionKind.Table) && o !is DummyValueOption && !ro && rt.isActive(o, sid)) return@forEach
                        if (!rt.isActive(o, sid) && o !is DummyValueOption) return@forEach
                        val t = rt.textvalue(o, sid)
                        if (t.isEmpty() && o.title == null) return@forEach
                        Column(Modifier.padding(top = 2.dp)) {
                            if (o.title != null) Text(stripHtml(o.title), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stripHtml(t).ifEmpty { "-" }, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            RowMenu(rt, s, sid, idx, count, ro, onEdit)
        }
        cols.filter { (it.editable || s.kind == SectionKind.Table) && it !is DummyValueOption && !ro && rt.isActive(it, sid) }.forEach { o ->
            Box(Modifier.padding(top = 6.dp)) { OptionField(rt, o, sid, compact = true) }
        }
    }
}

@Composable
private fun RowMenu(rt: FormRuntime, s: FormSection, sid: String, idx: Int, count: Int, ro: Boolean, onEdit: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val actions = s.rowActions.filter { it.visible(sid) }
    if (!s.noEdit && !ro) IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit") }
    if (ro && actions.isEmpty()) return
    if (actions.isEmpty() && !s.sortable && !(s.addremove && !ro)) return
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Outlined.MoreVert, "More") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(text = { Text(a.label, color = if (a.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }, onClick = {
                    open = false
                    scope.launch { try { a.run(rt.ctx, sid) } catch (e: Exception) { rt.ctx.toast(e.message ?: e.toString()) } }
                })
            }
            if (s.sortable && !ro) {
                DropdownMenuItem(text = { Text("Move up") }, enabled = idx > 0, onClick = { open = false; rt.moveSection(s, sid, true) },
                    leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null) })
                DropdownMenuItem(text = { Text("Move down") }, enabled = idx < count - 1, onClick = { open = false; rt.moveSection(s, sid, false) },
                    leadingIcon = { Icon(Icons.Outlined.ArrowDownward, null) })
            }
            if (s.addremove && !ro && s.noDelete?.invoke(sid) != true) {
                DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, onClick = { open = false; rt.removeSection(s, sid) },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) })
            }
        }
    }
}

@Composable
private fun GridAddRow(rt: FormRuntime, s: FormSection, onAdded: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val needsName = !s.anonymous
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (needsName) {
            val choices = s.addNameChoices
            if (choices != null) SelectField(name, choices, "Name", { name = it }, Modifier.weight(1f))
            else OutlinedTextField(
                value = name, onValueChange = { name = it; err = null }, singleLine = true, label = { Text("Name") },
                isError = err != null, supportingText = err?.let { { Text(it) } }, modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
        } else Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = {
            if (needsName) {
                val e = s.addNameValidator?.invoke(name) ?: if (name.isEmpty()) "Expecting: non-empty value" else Datatypes.validate("uciname", name)
                if (e == null && rt.uci.section(s.config, name) != null) { err = "Expecting: unique value"; return@FilledTonalButton }
                if (e != null) { err = e; return@FilledTonalButton }
            }
            val sid = rt.addSection(s, if (needsName) name else null)
            name = ""
            onAdded(sid)
        }) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(6.dp)); Text(s.addbtntitle ?: "Add") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GridEditor(rt: FormRuntime, s: FormSection, sid: String, onDismiss: () -> Unit, onSave: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(s.modalTitle?.invoke(sid) ?: listOfNotNull(s.title?.let(::stripHtml), rt.sectionTitle(s, sid)).joinToString(" » ").ifEmpty { "Edit" },
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Dismiss") } },
                    actions = {
                        if (!(rt.map.readonly || rt.ctx.readonly)) TextButton(onClick = onSave) { Text("Save") }
                    },
                )
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 920.dp).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                ) {
                    SectionBody(rt, s, sid, modal = true)
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }
}

// ---------------------------------------------------------------- option fields

@Composable
fun OptionField(rt: FormRuntime, o: FormOption, sid: String, compact: Boolean = false) {
    rt.revision.intValue
    val disabled = rt.isDisabled(o, sid)
    val title = o.title?.let(::stripHtml)
    val desc = o.description
    val error = rt.error(o, sid) ?: liveError(rt, o, sid)
    o.render?.let { custom -> custom(rt, sid); return }
    when (o) {
        is FlagOption -> {
            val checked = (rt.formString(o, sid).ifEmpty { o.default?.toString() ?: o.disabled }) == o.enabled
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !disabled, role = Role.Switch) {
                    rt.setValue(o, sid, if (checked) o.disabled else o.enabled)
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(title ?: o.option, style = MaterialTheme.typography.bodyLarge)
                    if (!desc.isNullOrBlank() && !compact) Text(htmlToAnnotated(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = checked, onCheckedChange = { rt.setValue(o, sid, if (it) o.enabled else o.disabled) }, enabled = !disabled)
            }
        }
        is ListValueOption -> {
            val choices = o.choicesFor(rt, sid)
            val v = rt.formString(o, sid)
            if (o.widget == "radio") {
                Column {
                    if (title != null) Text(title, style = MaterialTheme.typography.bodyLarge)
                    choices.forEach { (k, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = v == k, enabled = !disabled, role = Role.RadioButton) { rt.setValue(o, sid, k) }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = v == k, onClick = null, enabled = !disabled)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(stripHtml(label))
                                o.choiceDescriptions[k]?.let { Text(stripHtml(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                    Help(desc, error)
                }
            } else {
                SelectField(
                    value = v, choices = choices, label = title, onChange = { rt.setValue(o, sid, it) },
                    enabled = !disabled, supporting = desc, error = error,
                    placeholder = o.placeholder, optional = o.optional || o.rmempty, descriptions = o.choiceDescriptions,
                )
            }
        }
        is MultiValueOption -> MultiSelectField(rt, o, sid, title, desc, error, disabled)
        is DynamicListOption -> DynamicListField(rt, o, sid, title, desc, error, disabled)
        is TextValueOption -> {
            OutlinedTextField(
                value = rt.formString(o, sid), onValueChange = { rt.setValue(o, sid, it) },
                label = title?.let { { Text(it) } }, enabled = !disabled, isError = error != null,
                minLines = o.rows.coerceIn(2, 14), maxLines = 30,
                textStyle = if (o.monospace) CodeStyle else MaterialTheme.typography.bodyLarge,
                placeholder = o.placeholder?.let { { Text(it) } },
                supportingText = helpText(desc, error), modifier = Modifier.fillMaxWidth(),
            )
        }
        is DummyValueOption -> {
            Column {
                if (title != null) Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(htmlToAnnotated(rt.textvalue(o, sid).ifEmpty { "-" }), style = MaterialTheme.typography.bodyLarge) }
                if (!desc.isNullOrBlank()) Text(htmlToAnnotated(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is ButtonOption -> {
            val scope = rememberCoroutineScope()
            var running by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    if (title != null) Text(title, style = MaterialTheme.typography.bodyLarge)
                    if (!desc.isNullOrBlank()) Text(htmlToAnnotated(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val label = o.inputtitle?.invoke(rt, sid) ?: title ?: o.option
                val click: () -> Unit = {
                    scope.launch {
                        running = true
                        try { o.onClick?.invoke(rt.ctx, rt, sid) } catch (e: Exception) { rt.ctx.toast(e.message ?: e.toString()) }
                        running = false
                    }
                }
                when (o.inputstyle) {
                    "negative", "remove", "reset" -> OutlinedButton(onClick = click, enabled = !disabled && !running) { Text(label, color = MaterialTheme.colorScheme.error) }
                    "positive", "apply", "save" -> Button(onClick = click, enabled = !disabled && !running) { Text(label) }
                    else -> FilledTonalButton(onClick = click, enabled = !disabled && !running) { Text(label) }
                }
            }
        }
        is FileUploadOption -> FileField(rt, o, sid, title, desc, error, disabled)
        is SectionValueOption -> {
            Column {
                if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 6.dp))
                SectionView(rt, o.sub, sid)
            }
        }
        is ValueOption -> {
            val choices = o.choicesFor(rt, sid)
            if (choices.isNotEmpty()) {
                ComboField(
                    value = rt.formString(o, sid), choices = choices, label = title, onChange = { rt.setValue(o, sid, it) },
                    enabled = !disabled, supporting = desc, error = error, placeholder = o.placeholder,
                    keyboard = keyboardFor(o.datatype),
                )
            } else {
                TextField(rt, o, sid, title, desc, error, disabled)
            }
        }
        is HiddenValueOption -> {}
        else -> TextField(rt, o, sid, title, desc, error, disabled)
    }
}

/** Shows datatype errors once the user typed something (ui.js validates on input). */
private fun liveError(rt: FormRuntime, o: FormOption, sid: String): String? =
    if (rt.isEdited(o, sid)) rt.validateField(o, sid) else null

private fun keyboardFor(dt: String?): KeyboardOptions {
    val d = dt ?: return KeyboardOptions.Default
    return when {
        Regex("^(u?integer|port|range\\(|min\\(|max\\(|ufloat|float|uinteger)").containsMatchIn(d) -> KeyboardOptions(keyboardType = KeyboardType.Number)
        d.startsWith("ip4addr") || d.startsWith("ipaddr") || d.startsWith("cidr4") -> KeyboardOptions(keyboardType = KeyboardType.Uri)
        d.startsWith("hostname") || d.startsWith("host") -> KeyboardOptions(keyboardType = KeyboardType.Uri)
        else -> KeyboardOptions.Default
    }
}

@Composable
private fun helpText(desc: String?, error: String?): (@Composable () -> Unit)? {
    if (error != null) return { Text(error, color = MaterialTheme.colorScheme.error) }
    if (desc.isNullOrBlank()) return null
    return { Text(htmlToAnnotated(desc)) }
}

@Composable
private fun Help(desc: String?, error: String?) {
    if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
    else if (!desc.isNullOrBlank()) Text(htmlToAnnotated(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
}

@Composable
private fun TextField(rt: FormRuntime, o: FormOption, sid: String, title: String?, desc: String?, error: String?, disabled: Boolean) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = rt.formString(o, sid),
        onValueChange = { rt.setValue(o, sid, it) },
        label = title?.let { { Text(it) } },
        placeholder = o.placeholder?.let { { Text(it) } },
        enabled = !disabled,
        singleLine = true,
        isError = error != null,
        supportingText = helpText(desc, error),
        visualTransformation = if (o.password && !show) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (o.password) {
            { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show password") } }
        } else null,
        keyboardOptions = if (o.password) KeyboardOptions(keyboardType = KeyboardType.Password) else keyboardFor(o.datatype),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A read-only dropdown (LuCI ListValue select). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectField(
    value: String,
    choices: List<Pair<String, String>>,
    label: String?,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    supporting: String? = null,
    error: String? = null,
    placeholder: String? = null,
    optional: Boolean = false,
    descriptions: Map<String, String> = emptyMap(),
) {
    var expanded by remember { mutableStateOf(false) }
    val current = choices.firstOrNull { it.first == value }?.second?.let(::stripHtml) ?: value
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }, modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = label?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it) } },
            isError = error != null,
            supportingText = helpText(supporting, error),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            singleLine = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { (k, l) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stripHtml(l))
                            descriptions[k]?.let { Text(stripHtml(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    },
                    onClick = { onChange(k); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** Free text with suggestions (LuCI Value with o.value() choices = ui.Combobox). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComboField(
    value: String,
    choices: List<Pair<String, String>>,
    label: String?,
    onChange: (String) -> Unit,
    enabled: Boolean = true,
    supporting: String? = null,
    error: String? = null,
    placeholder: String? = null,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it); expanded = true },
            enabled = enabled,
            label = label?.let { { Text(it) } },
            placeholder = placeholder?.let { { Text(it) } },
            isError = error != null,
            singleLine = true,
            keyboardOptions = keyboard,
            supportingText = helpText(supporting, error),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable, enabled).fillMaxWidth(),
        )
        val filtered = choices.filter { value.isEmpty() || it.first.contains(value, true) || it.second.contains(value, true) || choices.any { c -> c.first == value } }
        if (filtered.isNotEmpty()) {
            ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                filtered.forEach { (k, l) ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stripHtml(l))
                                if (l != k) Text(k, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        onClick = { onChange(k); expanded = false },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MultiSelectField(rt: FormRuntime, o: MultiValueOption, sid: String, title: String?, desc: String?, error: String?, disabled: Boolean) {
    val choices = o.choicesFor(rt, sid)
    val selected = rt.formList(o, sid)
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        if (title != null) Text(title, style = MaterialTheme.typography.bodyLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            selected.forEach { k ->
                InputChip(
                    selected = true, enabled = !disabled,
                    onClick = { rt.setValue(o, sid, selected - k) },
                    label = { Text(stripHtml(choices.firstOrNull { it.first == k }?.second ?: k)) },
                    trailingIcon = { Icon(Icons.Outlined.Close, "Remove", Modifier.size(16.dp)) },
                )
            }
            Box {
                TextButton(onClick = { open = true }, enabled = !disabled) { Icon(Icons.Outlined.Add, null); Text(if (selected.isEmpty()) "Select" else "Change") }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    choices.forEach { (k, l) ->
                        val on = k in selected
                        DropdownMenuItem(
                            text = { Text(stripHtml(l)) },
                            leadingIcon = { Checkbox(checked = on, onCheckedChange = null) },
                            onClick = { rt.setValue(o, sid, if (on) selected - k else selected + k) },
                        )
                    }
                    if (o.create) {
                        HorizontalDivider()
                        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(custom, { custom = it }, singleLine = true, label = { Text("Custom") }, modifier = Modifier.width(180.dp))
                            IconButton(onClick = { if (custom.isNotBlank()) { rt.setValue(o, sid, selected + custom.trim()); custom = "" } }) { Icon(Icons.Outlined.Add, "Add") }
                        }
                    }
                }
            }
        }
        if (selected.isEmpty() && o.placeholder != null) Text(o.placeholder!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Help(desc, error)
    }
}

@Composable
private fun DynamicListField(rt: FormRuntime, o: DynamicListOption, sid: String, title: String?, desc: String?, error: String?, disabled: Boolean) {
    val items = rt.formList(o, sid)
    val choices = o.choicesFor(rt, sid)
    var input by remember(sid) { mutableStateOf("") }
    var inputErr by remember(sid) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth()) {
        if (title != null) Text(title, style = MaterialTheme.typography.bodyLarge)
        items.forEachIndexed { i, v ->
            Surface(
                shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val label = choices.firstOrNull { it.first == v }?.second?.let(::stripHtml)
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(label ?: v, style = MaterialTheme.typography.bodyMedium, fontFamily = if (label == null) FontFamily.Monospace else null)
                        if (label != null && label != v) Text(v, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!disabled) {
                        if (i > 0) IconButton(onClick = { val l = items.toMutableList(); l.add(i - 1, l.removeAt(i)); rt.setValue(o, sid, l) }) { Icon(Icons.Outlined.ArrowUpward, "Move up", Modifier.size(18.dp)) }
                        IconButton(onClick = { rt.setValue(o, sid, items.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Outlined.Delete, "Remove", Modifier.size(18.dp)) }
                    }
                }
            }
        }
        if (!disabled) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (choices.isNotEmpty()) {
                        ComboField(input, choices.filter { it.first !in items }, null, { input = it; inputErr = null },
                            error = inputErr, placeholder = o.placeholder ?: "Add value", keyboard = keyboardFor(o.datatype))
                    } else {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it; inputErr = null }, singleLine = true,
                            placeholder = { Text(o.placeholder ?: "Add value") }, isError = inputErr != null,
                            supportingText = inputErr?.let { { Text(it) } }, keyboardOptions = keyboardFor(o.datatype),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                IconButton(onClick = {
                    val v = input.trim()
                    if (v.isEmpty()) return@IconButton
                    val e = o.datatype?.let { Datatypes.validate(it, v) } ?: o.validate?.invoke(rt, sid, v)
                    if (e != null) { inputErr = e; return@IconButton }
                    rt.setValue(o, sid, items + v); input = ""
                }) { Icon(Icons.Outlined.Add, "Add") }
            }
        }
        Help(desc, error)
    }
}

@Composable
private fun FileField(rt: FormRuntime, o: FileUploadOption, sid: String, title: String?, desc: String?, error: String?, disabled: Boolean) {
    var browse by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = rt.formString(o, sid), onValueChange = { rt.setValue(o, sid, it) }, singleLine = true,
                label = title?.let { { Text(it) } }, enabled = !disabled, isError = error != null,
                supportingText = helpText(desc, error), modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { browse = true }, enabled = !disabled) { Icon(Icons.Outlined.FolderOpen, "Browse") }
        }
    }
    if (browse) {
        com.the412banner.wrtpanel.ui.components.RouterFileBrowser(
            ctx = rt.ctx,
            root = o.rootDirectory,
            initial = rt.formString(o, sid).ifEmpty { o.rootDirectory },
            allowUpload = o.enableUpload,
            onPick = { rt.setValue(o, sid, it); browse = false },
            onDismiss = { browse = false },
        )
    }
}
