@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.the412banner.wrtpanel.pages.software

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.double
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.ContentMaxWidth
import com.the412banner.wrtpanel.ui.components.ErrorBox
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal const val PM_CALL = "/usr/libexec/package-manager-call"
private const val PAGE_SIZE = 100

private class MountInfo(val size: Double, val free: Double)

/** fs.exec_direct('/usr/libexec/package-manager-call', [ arg ]), parsed while it streams in. */
private suspend fun downloadList(ctx: PageContext, arg: String): PkgList = withContext(Dispatchers.IO) {
    val (resp, stream) = ctx.client.cgiStream("cgi-exec", mapOf("command" to "$PM_CALL $arg", "stderr" to "0"))
    resp.use { PkgList().also { list -> stream.use { parsePackageData(it, list) } } }
}

/** System › Software — port of luci-app-package-manager view/package-manager.js. */
@Composable
fun PackageManagerPage(ctx: PageContext) {
    val apk = ctx.session.hasFeature("apk")
    val pm = if (apk) "apk" else "opkg"
    val readonly = ctx.readonly || ctx.node?.readonly == true

    var db by remember { mutableStateOf<PkgDb?>(null) }
    var mount by remember { mutableStateOf<MountInfo?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }

    var mode by rememberSaveable { mutableStateOf(PmMode.Available) }
    var filter by rememberSaveable { mutableStateOf("") }
    var shownFilter by remember { mutableStateOf(filter) }
    var i18n by rememberSaveable { mutableStateOf(I18nFilter.Lang) }
    var install by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<PkgRow>?>(null) }
    var offset by remember { mutableIntStateOf(0) }
    var sort by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    var dialog by remember { mutableStateOf<PmDialog?>(null) }
    val listState = rememberLazyListState()

    // updateLists()
    LaunchedEffect(reload) {
        db = null
        rows = null
        loadError = null
        try {
            val (mounts, available, installed) = coroutineScope {
                val mp = async { ctx.call("luci", "getMountPoints")["result"].arr ?: emptyList() }
                val av = async { downloadList(ctx, "list-available") }
                val inst = async { downloadList(ctx, "list-installed") }
                Triple(mp.await(), av.await(), inst.await())
            }
            val m = mounts.firstOrNull { it["mount"].str == "/" || it["mount"].str == "/overlay" }
            mount = MountInfo(m["size"].double ?: 0.0, m["free"].double ?: 0.0)
            val langs = mutableListOf("en")
            for (n in installed.pkgs.keys) if (n.startsWith("luci-i18n-base-")) langs += n.substring(15)
            db = PkgDb(available, installed, langs)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: e.toString()
        }
    }

    // handleInput(): display() 250 ms after typing stops
    LaunchedEffect(filter) {
        if (filter == shownFilter) return@LaunchedEffect
        delay(250)
        shownFilter = filter
    }

    // display()
    LaunchedEffect(db, mode, shownFilter, i18n) {
        val d = db ?: return@LaunchedEffect
        val r = withContext(Dispatchers.Default) { buildRows(d, mode, shownFilter, i18n) }
        rows = r
        offset = 0
    }

    fun displayNow() { shownFilter = filter }
    val pattern = remember(shownFilter) { filterPattern(shownFilter) }

    val pageRows: List<PkgRow> = remember(rows, offset, sort) {
        val all = rows ?: return@remember emptyList()
        val slice = all.subList(offset.coerceAtMost(all.size), (offset + PAGE_SIZE).coerceAtMost(all.size))
        val s = sort ?: return@remember slice
        val cmp = Comparator<PkgRow> { a, b -> NaturalCompare.compare(sortKey(a.keys[s.first]), sortKey(b.keys[s.first])) }
        slice.sortedWith(if (s.second) cmp.reversed() else cmp)
    }

    fun openRow(row: PkgRow) {
        dialog = when (row.btn) {
            RowBtn.Install -> PmDialog.Details(row.name, "install")
            RowBtn.Upgrade -> PmDialog.Details(row.name, "upgrade")
            RowBtn.Remove -> PmDialog.Remove(row.name)
            RowBtn.Installed -> null
        }
    }

    fun manualInstall() {
        if (install.isEmpty()) return
        dialog = PmDialog.Manual(install)
    }

    fun setPage(next: Boolean) {
        val total = rows?.size ?: 0
        if ((next && offset + PAGE_SIZE >= total) || (!next && offset < PAGE_SIZE)) return
        offset += if (next) PAGE_SIZE else -PAGE_SIZE
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = listState,
            modifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") { Header(pm) }
            item(key = "controls") {
                Controls(
                    pm = pm, readonly = readonly, mount = mount,
                    filter = filter, onFilter = { filter = it }, onClear = { filter = ""; displayNow() },
                    install = install, onInstall = { install = it }, onManualInstall = { manualInstall() },
                    onUpdate = { dialog = PmDialog.Exec("update", null); runPkg(ctx, pm, "update", null, setDialog = { dialog = it }) },
                    onUpload = { dialog = PmDialog.Upload },
                    onConfig = { dialog = PmDialog.Config },
                    i18n = i18n, onI18n = { i18n = it; displayNow() },
                )
            }
            item(key = "tabs") {
                PrimaryTabRow(selectedTabIndex = mode.ordinal, containerColor = Color.Transparent) {
                    listOf(PmMode.Available to "Available", PmMode.Installed to "Installed", PmMode.Updates to "Updates").forEach { (m, label) ->
                        Tab(selected = mode == m, onClick = { if (mode != m) { mode = m; displayNow() } }, text = { Text(label) })
                    }
                }
            }
            val all = rows
            when {
                loadError != null -> item(key = "error") { ErrorBox(loadError!!) { reload++ } }
                all == null -> item(key = "loading") { LoadingBox(text = "Loading package information…") }
                else -> {
                    item(key = "pager-top") { Pager(offset, all.size, ::setPage) }
                    item(key = "titles") {
                        SortTitles(apk, sort) { idx ->
                            val cur = sort
                            val desc = cur != null && cur.first == idx && !cur.second
                            sort = idx to desc
                        }
                    }
                    if (all.isEmpty()) {
                        item(key = "placeholder") { Placeholder(filter) { filter = ""; displayNow() } }
                    } else {
                        items(pageRows, key = { it.name }) { row ->
                            PackageRowItem(row, pattern) { openRow(row) }
                        }
                    }
                    item(key = "pager-bottom") { Pager(offset, all.size, ::setPage) }
                }
            }
        }
    }

    LaunchedEffect(offset) { if (offset > 0 || listState.firstVisibleItemIndex > 3) listState.scrollToItem(3) }

    val d = db
    when (val dl = dialog) {
        null -> {}
        is PmDialog.Details -> if (d != null) d.available.pkgs[dl.name]?.let { pkg ->
            DetailsDialog(db = d, pkg = pkg, readonly = readonly, onDismiss = { dialog = null }) { overwrite, i18nPkgs ->
                dialog = PmDialog.Exec(dl.cmd, null)
                runPkg(ctx, pm, dl.cmd, dl.name, overwrite = overwrite, i18nPkgs = i18nPkgs, setDialog = { dialog = it })
            }
        }
        is PmDialog.Remove -> if (d != null) d.installed.pkgs[dl.name]?.let { pkg ->
            RemoveDialog(db = d, pkg = pkg, apk = apk, readonly = readonly, onDismiss = { dialog = null }) { autoremove ->
                dialog = PmDialog.Exec("remove", null)
                runPkg(ctx, pm, "remove", dl.name, autoremove = autoremove, setDialog = { dialog = it })
            }
        }
        is PmDialog.Manual -> ManualInstallDialog(
            nameOrUrl = dl.nameOrUrl,
            available = d?.available?.providers?.containsKey(dl.nameOrUrl) == true,
            onDismiss = { dialog = null },
        ) {
            install = ""
            dialog = PmDialog.Exec("install", null)
            runPkg(ctx, pm, "install", dl.nameOrUrl, setDialog = { dialog = it })
        }
        is PmDialog.Config -> ConfigDialog(ctx, apk, readonly) { dialog = null }
        is PmDialog.Upload -> UploadDialog(
            ctx = ctx,
            path = "/tmp/upload.${if (apk) "apk" else "ipk"}",
            onDismiss = { dialog = null },
            onUploaded = { dialog = it },
        )
        is PmDialog.UploadDone -> UploadInstallDialog(
            ctx = ctx, info = dl, onDismiss = { dialog = null },
        ) {
            dialog = PmDialog.Exec("install", null)
            runPkg(ctx, pm, "install", dl.path, setDialog = { dialog = it }, onFinally = { runCatching { ctx.fs.remove(dl.path) } })
        }
        is PmDialog.Exec -> ExecDialog(pm, dl) {
            dialog = null
            reload++
        }
    }
}

@Composable
private fun Header(pm: String) {
    val link = MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text("Software", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        val text = remember(pm, link) {
            buildAnnotatedString {
                append("Install additional software and upgrade existing packages with $pm.\n")
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("Warning!") }
                append(" Package operations can ")
                withLink(LinkAnnotation.Url("https://openwrt.org/meta/infobox/upgrade_packages_warning", TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) {
                    append("break your system")
                }
                append(".")
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Controls(
    pm: String,
    readonly: Boolean,
    mount: MountInfo?,
    filter: String,
    onFilter: (String) -> Unit,
    onClear: () -> Unit,
    install: String,
    onInstall: (String) -> Unit,
    onManualInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUpload: () -> Unit,
    onConfig: () -> Unit,
    i18n: I18nFilter,
    onI18n: (I18nFilter) -> Unit,
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text("Disk space:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val fraction: Float
                val title: String
                if (mount == null) {
                    fraction = 0f; title = "unknown"
                } else {
                    val pct = Math.floor(if (mount.size != 0.0) (100 / mount.size) * (mount.size - mount.free) else 100.0).toInt()
                    fraction = pct / 100f
                    title = "$pct% used (${fmtM(mount.size - mount.free)} used of ${fmtM(mount.size)}, ${fmtM(mount.free)} free)"
                }
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    drawStopIndicator = {},
                )
                Text(title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = filter, onValueChange = onFilter, singleLine = true,
                    label = { Text("Filter") }, placeholder = { Text("Type to filter…") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onClear) { Text("Clear") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = install, onValueChange = onInstall, singleLine = true, enabled = !readonly,
                    label = { Text("Download and install package") }, placeholder = { Text("Package name or URL…") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { onManualInstall() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = onManualInstall, enabled = !readonly) { Text("OK") }
            }

            Column {
                Text("Actions:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Button(onClick = onUpdate, enabled = !readonly) { Text("Update lists…") }
                    FilledTonalButton(onClick = onUpload, enabled = !readonly) { Text("Upload Package…") }
                    OutlinedButton(onClick = onConfig) { Text("Configure $pm") }
                }
            }

            Column {
                Text("Display LuCI translation packages:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(
                        Triple(I18nFilter.Lang, "filtered", "Display base translation packages and translation packages for already installed languages only"),
                        Triple(I18nFilter.All, "all", "Display all available translation packages"),
                        Triple(I18nFilter.None, "none", "Hide all translation packages"),
                    ).forEach { (v, label, tip) ->
                        Tip(tip) {
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).selectable(selected = i18n == v, role = Role.RadioButton) { onI18n(v) }
                                    .padding(end = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = i18n == v, onClick = null, modifier = Modifier.padding(8.dp))
                                Text(label)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Tip(text: String?, content: @Composable () -> Unit) {
    if (text.isNullOrEmpty()) { content(); return }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
    ) { content() }
}

@Composable
private fun Pager(offset: Int, total: Int, onPage: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        IconButton(onClick = { onPage(false) }, enabled = offset >= PAGE_SIZE) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Previous page") }
        Text(
            if (total > 0) "Displaying %d-%d of %d".format(1 + offset, minOf(offset + PAGE_SIZE, total), total) else "No packages",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp),
        )
        IconButton(onClick = { onPage(true) }, enabled = offset + PAGE_SIZE < total) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Next page") }
    }
}

@Composable
private fun SortTitles(apk: Boolean, sort: Pair<Int, Boolean>?, onSort: (Int) -> Unit) {
    val titles = listOf("Package name", "Version", "Size (%s)".format(if (apk) ".apk" else ".ipk"), "Description")
    Column {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            titles.forEachIndexed { i, t ->
                val active = sort?.first == i
                TextButton(onClick = { onSort(i) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(t, style = MaterialTheme.typography.labelLarge, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (active) Icon(if (sort!!.second) Icons.Outlined.ArrowDownward else Icons.Outlined.ArrowUpward, null, Modifier.padding(start = 4.dp).size(16.dp))
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun Placeholder(filter: String, onReset: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (filter.isNotEmpty()) {
            Text(
                buildAnnotatedString {
                    append("No packages matching \"")
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(filter) }
                    append("\".")
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onReset) { Text("Reset") }
        } else {
            Text("No information available", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun highlight(text: String, pattern: Regex?, bg: Color): AnnotatedString = buildAnnotatedString {
    if (pattern == null) { append(text); return@buildAnnotatedString }
    var pos = 0
    for (m in pattern.findAll(text)) {
        if (m.value.isEmpty()) continue
        append(text.substring(pos, m.range.first))
        withStyle(SpanStyle(background = bg, fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.Underline)) { append(m.value) }
        pos = m.range.last + 1
    }
    append(text.substring(pos))
}

@Composable
private fun PackageRowItem(row: PkgRow, pattern: Regex?, onAction: () -> Unit) {
    val bg = MaterialTheme.colorScheme.primaryContainer
    val name = remember(row, pattern, bg) { highlight(row.name, pattern, bg) }
    val desc = remember(row, pattern, bg) { highlight(row.desc, pattern, bg) }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 640.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(2.2f).padding(end = 8.dp))
                    Text(row.version, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.6f).padding(end = 8.dp))
                    Text(row.sizeText, style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f).padding(end = 8.dp))
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(4f).padding(end = 8.dp))
                    RowButton(row.btn, onAction)
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${row.version}  ·  ${row.sizeText}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        RowButton(row.btn, onAction)
                    }
                    if (row.desc != "-") Text(desc, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp, end = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun RowButton(btn: RowBtn, onClick: () -> Unit) {
    val pad = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
    when (btn) {
        RowBtn.Install -> FilledTonalButton(onClick = onClick, contentPadding = pad) { Text("Install…") }
        RowBtn.Upgrade -> Button(onClick = onClick, contentPadding = pad) { Text("Upgrade…") }
        RowBtn.Remove -> OutlinedButton(
            onClick = onClick, contentPadding = pad,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Remove…") }
        RowBtn.Installed -> OutlinedButton(onClick = {}, enabled = false, contentPadding = pad) { Text("Installed") }
    }
}
