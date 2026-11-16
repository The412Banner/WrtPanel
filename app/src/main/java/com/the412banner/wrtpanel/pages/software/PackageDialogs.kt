@file:OptIn(ExperimentalLayoutApi::class)

package com.the412banner.wrtpanel.pages.software

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.the412banner.wrtpanel.core.JsonCodec
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.int
import com.the412banner.wrtpanel.core.long
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.CodeBlock
import com.the412banner.wrtpanel.ui.components.htmlToAnnotated
import com.the412banner.wrtpanel.ui.theme.CodeStyle
import com.the412banner.wrtpanel.ui.theme.LocalStatusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException

internal sealed interface PmDialog {
    data class Details(val name: String, val cmd: String) : PmDialog
    data class Remove(val name: String) : PmDialog
    data class Manual(val nameOrUrl: String) : PmDialog
    data object Config : PmDialog
    data object Upload : PmDialog
    data class UploadDone(val path: String, val name: String, val size: Long?, val checksum: String?, val sha256sum: String?) : PmDialog
    /** [result] is null while the command runs. */
    data class Exec(val cmd: String, val result: PmResult?) : PmDialog
}

internal data class PmResult(val code: Int?, val pkmcmd: String?, val stdout: String?, val stderr: String?)

/** handlePkg(): runs package-manager-call through cgi-exec and reports its JSON reply. */
internal fun runPkg(
    ctx: PageContext,
    pm: String,
    cmd: String,
    pkg: String?,
    autoremove: Boolean = false,
    overwrite: Boolean = false,
    i18nPkgs: List<String>? = null,
    setDialog: (PmDialog?) -> Unit,
    onFinally: (suspend () -> Unit)? = null,
) {
    val argv = mutableListOf(cmd)
    if (cmd == "remove") argv += "--force-removal-of-dependent-packages"
    if (autoremove) argv += "--autoremove"
    if (overwrite) argv += "--force-overwrite"
    if (i18nPkgs != null) argv += i18nPkgs
    if (pkg != null) argv += pkg
    ctx.scope.launch {
        try {
            val text = ctx.fs.execDirect(PM_CALL, argv)
            val j = JsonCodec.parseToJsonElement(text)
            val res = PmResult(j["code"].int, j["pkmcmd"].str, j["stdout"].str, j["stderr"].str)
            // ui.menu.flushCache(): newly installed or removed LuCI apps change the menu.
            runCatching { ctx.session.refreshMenu() }
            setDialog(PmDialog.Exec(cmd, res))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ctx.toast("Unable to execute $pm $cmd command: ${e.message ?: e}")
            setDialog(null)
        } finally {
            if (onFinally != null) withContext(NonCancellable) { onFinally() }
        }
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
internal fun PmModal(
    title: AnnotatedString,
    onDismiss: (() -> Unit)?,
    buttons: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = { onDismiss?.invoke() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = onDismiss != null, dismissOnClickOutside = onDismiss != null),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.padding(16.dp).widthIn(max = 680.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Column(
                    Modifier.weight(1f, fill = false).padding(top = 14.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content,
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = buttons,
                )
            }
        }
    }
}

@Composable
private fun LabeledLine(label: String, value: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$label:") }
            append(" ")
            append(value)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun CheckRow(text: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DescriptionBlock(text: String) {
    Column {
        Text("Description", style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun ErrorsList(errors: List<String>) {
    val c = MaterialTheme.colorScheme.error
    Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        errors.forEach { e ->
            Row {
                Text("⚠", color = c, modifier = Modifier.width(22.dp))
                Text(htmlToAnnotated(e), color = c, fontWeight = FontWeight.Bold, fontSize = MaterialTheme.typography.bodyMedium.fontSize * 0.9f)
            }
        }
    }
}

@Composable
private fun ErrorCode(text: String) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(12.dp)) {
            SelectionContainer { Text(text, style = CodeStyle, color = MaterialTheme.colorScheme.onErrorContainer) }
        }
    }
}

@Composable
private fun DepBadge(label: DepLabel) {
    val s = LocalStatusColors.current
    val c = when (label.kind) {
        LabelKind.Plain -> MaterialTheme.colorScheme.onSurfaceVariant
        LabelKind.Notice -> s.info
        LabelKind.Warning -> s.warn
    }
    Surface(shape = RoundedCornerShape(6.dp), color = c.copy(alpha = 0.14f), contentColor = c) {
        Text(label.text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** The nested <ul class="deps"> list, flattened with an indent per level. */
@Composable
private fun DepTree(nodes: List<DepNode>) {
    val flat = remember(nodes) {
        val out = mutableListOf<Pair<Int, DepNode>>()
        fun walk(l: List<DepNode>, depth: Int) { for (n in l) { out += depth to n; walk(n.children, depth + 1) } }
        walk(nodes, 0)
        out
    }
    Column(Modifier.padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        flat.forEach { (depth, n) ->
            Row(Modifier.padding(start = (14 * depth.coerceAtMost(10)).dp), verticalAlignment = Alignment.Top) {
                Text("↳", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(16.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    n.alts.forEachIndexed { i, a ->
                        if (i > 0) Text("|", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Tip(a.tooltip) { Text(a.text, style = MaterialTheme.typography.bodyMedium) }
                            Spacer(Modifier.width(6.dp))
                            Tip(a.label.tooltip) { DepBadge(a.label) }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- handleInstall()

@Composable
internal fun DetailsDialog(
    db: PkgDb,
    pkg: Pkg,
    readonly: Boolean,
    onDismiss: () -> Unit,
    onInstall: (overwrite: Boolean, i18nPkgs: List<String>?) -> Unit,
) {
    val info = remember(db, pkg) { installInfo(db, pkg) }
    var i18nChecked by remember { mutableStateOf(true) }
    var overwrite by remember { mutableStateOf(false) }
    PmModal(
        title = htmlToAnnotated("Details for package <em>${escHtml(pkg.name)}</em>"),
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(
                onClick = { onInstall(overwrite, if (info.i18nPackages.isNotEmpty() && i18nChecked) info.i18nPackages else null) },
                enabled = !readonly,
            ) { Text("Install") }
        },
    ) {
        LabeledLine("Version", pkg.version ?: "")
        LabeledLine("Size", info.sizeText)
        info.deps?.let {
            Text("Dependencies:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            DepTree(it)
        }
        if (info.i18nPackages.isNotEmpty()) {
            Text("Suggested translations:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            info.i18nTree?.let { DepTree(it) }
        }
        pkg.description?.takeIf { it.isNotEmpty() }?.let { DescriptionBlock(it) }
        if (info.errors.isNotEmpty()) {
            ErrorsList(info.errors)
        } else {
            Text(
                "Require approx. ${fmtM(info.totalsize)} size for ${info.totalpkgs} package(s) to install." + " " +
                    (if (info.suggestsize != 0L) "Suggested translations require approx. ${fmtM(info.suggestsize)} additional space." else ""),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        if (info.i18nPackages.isNotEmpty()) {
            CheckRow("Install suggested translation packages as well", i18nChecked, enabled = !readonly) { i18nChecked = it }
        }
        CheckRow("Allow overwriting conflicting package files", overwrite, enabled = !readonly) { overwrite = it }
    }
}

// ---------------------------------------------------------------- handleRemove()

@Composable
internal fun RemoveDialog(db: PkgDb, pkg: Pkg, apk: Boolean, readonly: Boolean, onDismiss: () -> Unit, onRemove: (autoremove: Boolean) -> Unit) {
    val avail = db.available.pkgs[pkg.name]
    val size = avail?.installsize.nz()?.let { "~${fmtM(it)} installed" }
        ?: avail?.size.nz()?.let { "~${fmtM(it)} compressed" }
        ?: "unknown"
    var autoremove by remember { mutableStateOf(true) }
    PmModal(
        title = htmlToAnnotated("Remove package <em>${escHtml(pkg.name)}</em>"),
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(
                onClick = { onRemove(autoremove) }, enabled = !readonly,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Remove") }
        },
    ) {
        LabeledLine("Version", pkg.version ?: "")
        LabeledLine("Size", size)
        avail?.description?.takeIf { it.isNotEmpty() }?.let { DescriptionBlock(it) }
        CheckRow("Automatically remove unused dependencies", autoremove, enabled = !(readonly || apk)) { autoremove = it }
    }
}

// ---------------------------------------------------------------- handleManualInstall()

@Composable
internal fun ManualInstallDialog(nameOrUrl: String, available: Boolean, onDismiss: () -> Unit, onInstall: () -> Unit) {
    val n = escHtml(nameOrUrl)
    val (warning, canInstall) = when {
        nameOrUrl.contains('/') -> "Installing packages from untrusted sources is a potential security risk! Really attempt to install <em>$n</em>?" to true
        !available -> "The package <em>$n</em> is not available in any configured repository." to false
        else -> "Really attempt to install <em>$n</em>?" to true
    }
    PmModal(
        title = AnnotatedString("Manually install package"),
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            if (canInstall) Button(onClick = onInstall) { Text("Install") }
        },
    ) {
        Text(htmlToAnnotated(warning), style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------- handlePkg() output

@Composable
internal fun ExecDialog(pm: String, d: PmDialog.Exec, onDismiss: () -> Unit) {
    val res = d.result
    PmModal(
        title = AnnotatedString("Executing package manager"),
        onDismiss = if (res == null) null else onDismiss,
        buttons = { if (res != null) Button(onClick = onDismiss) { Text("Dismiss") } },
    ) {
        if (res == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(12.dp))
                Text(htmlToAnnotated("Waiting for the <em>$pm ${escHtml(d.cmd)}</em> command to complete…"), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            res.pkmcmd?.takeIf { it.isNotEmpty() }?.let { CodeBlock(it) }
            res.stdout?.takeIf { it.isNotEmpty() }?.let { CodeBlock(it) }
            res.stderr?.takeIf { it.isNotEmpty() }?.let {
                Text("Errors", style = MaterialTheme.typography.titleSmall)
                ErrorCode(it)
            }
            if (res.code != 0) {
                val code = res.code?.let { c -> (c and 0xff).takeIf { it != 0 } } ?: -1
                Text(
                    htmlToAnnotated("The <em>$pm ${escHtml(d.cmd)}</em> command failed with code <code>$code</code>."),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- handleConfig()

@Composable
internal fun ConfigDialog(ctx: PageContext, apk: Boolean, readonly: Boolean, onClose: () -> Unit) {
    val title = AnnotatedString("%s Configuration".format(if (apk) "APK" else "OPKG"))
    var files by remember { mutableStateOf<List<String>?>(null) }
    val values = remember { mutableStateMapOf<String, String>() }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            val base = if (apk) "/etc/apk" else "/etc/opkg"
            val partials = ctx.fs.list(base)
            val list = mutableListOf<String>()
            if (apk) {
                list += "$base/repositories.d/customfeeds.list"
                list += "$base/repositories.d/distfeeds.list"
            } else {
                list += "$base.conf"
            }
            for (p in partials) {
                if (p.type != "file") continue
                if (apk) { if (p.name == "repositories") list += "$base/${p.name}" }
                else if (p.name.endsWith(".conf")) list += "$base/${p.name}"
            }
            val read = coroutineScope { list.map { f -> async { f to runCatching { ctx.fs.read(f) } } }.awaitAll() }
            val failed = read.filter { it.second.isFailure }
            if (failed.isNotEmpty()) {
                failed.forEach { (f, r) -> ctx.toast("Unable to read %s: %s".format(f, r.exceptionOrNull()?.message)) }
                onClose()
                return@LaunchedEffect
            }
            read.forEach { (f, r) -> values[f] = r.getOrDefault("") }
            files = values.keys.sorted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ctx.toast(e.message ?: e.toString())
            onClose()
        }
    }

    val list = files
    if (list == null || saving) {
        PmModal(title, onDismiss = null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.width(12.dp))
                Text(if (saving) "Saving configuration data…" else "Loading configuration data…", style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }

    PmModal(
        title = title,
        onDismiss = onClose,
        buttons = {
            TextButton(onClick = onClose) { Text("Cancel") }
            Button(
                enabled = !readonly,
                onClick = {
                    saving = true
                    scope.launch {
                        val data = values.toMap()
                        coroutineScope {
                            data.map { (f, v) ->
                                async {
                                    try { ctx.fs.write(f, v) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                                        ctx.toast("Unable to save %s: %s".format(f, e.message))
                                    }
                                }
                            }.awaitAll()
                        }
                        onClose()
                    }
                },
            ) { Text("Save") }
        },
    ) {
        Text(
            htmlToAnnotated(
                if (apk) "Below is a listing of the various configuration files used by <em>apk</em>. The configuration in the other files may be changed but is usually not preserved by <em>sysupgrade</em>."
                else "Below is a listing of the various configuration files used by <em>opkg</em>. Use <em>opkg.conf</em> for global settings and <em>customfeeds.conf</em> for custom repository entries. The configuration in the other files may be changed but is usually not preserved by <em>sysupgrade</em>.",
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        list.forEach { f ->
            val initial = remember(f) { values[f] ?: "" }
            val rows = maxOf(minOf(initial.count { it == '\n' }, 10), 3)
            Text(f, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            OutlinedTextField(
                value = values[f] ?: "",
                onValueChange = { values[f] = it },
                textStyle = CodeStyle.copy(fontSize = 12.sp),
                minLines = rows, maxLines = maxOf(rows, 10),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------- handleUpload() / ui.uploadFile()

private class Picked(val uri: Uri, val name: String, val size: Long)

private fun queryPicked(context: Context, uri: Uri): Picked {
    var name = uri.lastPathSegment ?: "upload"
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    return Picked(uri, name.replace(Regex("^.*[\\\\/]"), ""), size)
}

/** Streams a picked document to cgi-upload and reports the bytes sent. */
private class UriBody(private val cr: ContentResolver, private val uri: Uri, private val length: Long, private val onProgress: (Long) -> Unit) : RequestBody() {
    override fun contentType() = "application/octet-stream".toMediaType()
    override fun contentLength() = length
    override fun writeTo(sink: BufferedSink) {
        val input = cr.openInputStream(uri) ?: throw IOException("Unable to open the selected file")
        input.use {
            val buf = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                total += n
                onProgress(total)
            }
        }
    }
}

@Composable
internal fun UploadDialog(ctx: PageContext, path: String, onDismiss: () -> Unit, onUploaded: (PmDialog.UploadDone) -> Unit) {
    val context = LocalContext.current
    var picked by remember { mutableStateOf<Picked?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) picked = runCatching { queryPicked(context, uri) }.getOrNull()
    }

    if (uploading) {
        PmModal(AnnotatedString("Uploading file…"), onDismiss = null) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp), drawStopIndicator = {})
            Text("%.2f%%".format(progress * 100), style = MaterialTheme.typography.bodySmall)
        }
        return
    }

    PmModal(
        title = AnnotatedString("Uploading file…"),
        onDismiss = onDismiss,
        buttons = {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Browse…") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Button(
                enabled = picked != null,
                onClick = {
                    val p = picked ?: return@Button
                    uploading = true
                    progress = 0f
                    scope.launch {
                        try {
                            val body = UriBody(context.contentResolver, p.uri, p.size) { sent -> if (p.size > 0) progress = (sent.toFloat() / p.size).coerceIn(0f, 1f) }
                            val reply = withContext(Dispatchers.IO) { ctx.client.upload(path, p.name, body) }
                            if (reply["failure"] != null) {
                                ctx.toast("Upload request failed: %s".format(reply["message"].str ?: reply["failure"].str))
                                onDismiss()
                            } else {
                                onUploaded(PmDialog.UploadDone(path, p.name, reply["size"].long, reply["checksum"].str, reply["sha256sum"].str))
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ctx.toast("Upload request failed: %s".format(e.message ?: e.toString()))
                            onDismiss()
                        }
                    }
                },
            ) { Text("Upload") }
        },
    ) {
        val p = picked
        if (p == null) {
            Text("Please select the file to upload.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("• Name: ${p.name}", style = MaterialTheme.typography.bodyMedium)
            Text("• Size: ${if (p.size >= 0) fmtM(p.size) else "-"}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun UploadInstallDialog(ctx: PageContext, info: PmDialog.UploadDone, onDismiss: () -> Unit, onInstall: () -> Unit) {
    val cancel = {
        onDismiss()
        ctx.scope.launch { runCatching { ctx.fs.remove(info.path) } }
        Unit
    }
    PmModal(
        title = AnnotatedString("Manually install package"),
        onDismiss = cancel,
        buttons = {
            TextButton(onClick = cancel) { Text("Cancel") }
            Button(onClick = onInstall) { Text("Install") }
        },
    ) {
        Text(
            htmlToAnnotated("Installing packages from untrusted sources is a potential security risk! Really attempt to install <em>${escHtml(info.name)}</em>?"),
            style = MaterialTheme.typography.bodyMedium,
        )
        SelectionContainer {
            Column {
                info.size?.takeIf { it != 0L }?.let { Text("• Size: ${fmtM(it)}", style = MaterialTheme.typography.bodyMedium) }
                info.checksum?.takeIf { it.isNotEmpty() }?.let { Text("• MD5: $it", style = MaterialTheme.typography.bodyMedium) }
                info.sha256sum?.takeIf { it.isNotEmpty() }?.let { Text("• SHA256: $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
