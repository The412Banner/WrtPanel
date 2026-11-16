package com.the412banner.wrtpanel.pages.system

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.htmlToAnnotated
import com.the412banner.wrtpanel.ui.theme.CodeStyle
import com.the412banner.wrtpanel.ui.theme.LocalStatusColors
import androidx.compose.foundation.BorderStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/** LuCI's `'%1024.2mB'` / `'%1024mB'` format (cbi.js String.format 'm' with base 1024). */
internal fun fmtMiB(bytes: Long?): String {
    val b = bytes ?: 0L
    var v = b.toDouble()
    var i = 0
    val units = listOf(" ", " K", " M", " G", " T", " P", " E")
    while (i < units.size - 1 && v > 1024) { v /= 1024; i++ }
    return if (i > 0) String.format(Locale.US, "%.2f", v) + units[i] + "iB" else "$b B"
}

/** fs.trimmed(): file contents without surrounding whitespace, "" when it cannot be read. */
internal suspend fun PageContext.trimmed(path: String): String = fs.readOrNull(path)?.trim() ?: ""

/** fs.lines(): trimmed lines of a file, empty when it cannot be read. */
internal suspend fun PageContext.lines(path: String): List<String> {
    val s = (fs.readOrNull(path) ?: "").trim()
    return if (s.isEmpty()) emptyList() else s.split('\n').map { it.trim() }
}

internal fun displayName(context: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "upload.bin"
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val si = c.getColumnIndex(OpenableColumns.SIZE)
            if (ni >= 0) c.getString(ni)?.let { name = it }
            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
        }
    }
    return name to size
}

/** Streams a picked document into a multipart upload, reporting sent bytes. */
private class UriRequestBody(
    private val cr: ContentResolver,
    private val uri: Uri,
    private val length: Long,
    private val onProgress: (Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType = "application/octet-stream".toMediaType()
    override fun contentLength(): Long = length
    override fun writeTo(sink: BufferedSink) {
        val input = cr.openInputStream(uri) ?: throw java.io.IOException("Cannot open file")
        input.use { inp ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                sink.write(buf, 0, n)
                total += n
                onProgress(total)
            }
        }
    }
}

/** Reads a picked text document (the drag-and-drop equivalent of the key pages). */
internal suspend fun readText(context: Context, uri: Uri): Pair<String, String> = withContext(Dispatchers.IO) {
    val (name, _) = displayName(context, uri)
    val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: ""
    name to text
}

/** Copies [input] into the document at [uri]. */
internal suspend fun writeToUri(context: Context, uri: Uri, input: InputStream) = withContext(Dispatchers.IO) {
    val out: OutputStream = context.contentResolver.openOutputStream(uri) ?: throw java.io.IOException("Cannot open file")
    out.use { o -> input.use { it.copyTo(o, 64 * 1024) } }
}

/**
 * ui.uploadFile(): pick a file on the phone and upload it through cgi-upload to [remotePath],
 * showing LuCI's "Uploading file…" progress. Returns the launcher; [onUploaded] gets the reply
 * ({ size, checksum, sha256sum }).
 */
@Composable
internal fun rememberUploader(ctx: PageContext, remotePath: String, onUploaded: suspend (JsonElement?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var uploading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) {
            ctx.toast("Upload has been cancelled")
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            uploading = true
            progress = 0f
            var temp: File? = null
            try {
                val (name, size) = withContext(Dispatchers.IO) { displayName(context, uri) }
                val body: RequestBody = if (size >= 0) {
                    UriRequestBody(context.contentResolver, uri, size) { sent ->
                        val p = if (size > 0) sent.toFloat() / size else 1f
                        if (p - progress >= 0.01f || p >= 1f) progress = p
                    }
                } else {
                    val f = withContext(Dispatchers.IO) {
                        File.createTempFile("upload", ".bin", context.cacheDir).also { t ->
                            context.contentResolver.openInputStream(uri)?.use { i -> t.outputStream().use { i.copyTo(it) } }
                        }
                    }
                    temp = f
                    f.asRequestBody("application/octet-stream".toMediaType())
                }
                val reply = ctx.client.upload(remotePath, name.replace(Regex("^.*[\\\\/]"), ""), body)
                uploading = false
                onUploaded(reply)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.toast(e.message ?: e.toString())
            } finally {
                uploading = false
                temp?.delete()
            }
        }
    }
    if (uploading) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Uploading file…") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(String.format(Locale.US, "%.2f%%", progress * 100), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {},
        )
    }
    return { picker.launch(arrayOf("*/*")) }
}

/** A modal that cannot be dismissed, with LuCI's spinning paragraph (ui.showModal + 'spinning'). */
@Composable
internal fun BusyDialog(title: String, text: String, warning: Boolean = false) {
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.widthIn(max = 520.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(
                        htmlToAnnotated(text), style = MaterialTheme.typography.bodyMedium,
                        color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * ui.awaitReconnect(): after 5 s, pings the router every poll interval and, once it answers,
 * signs in again and opens the start page (LuCI navigates to the device root).
 * [unreachableAfterMs] switches to [unreachableText] like reboot.js does after 150 s.
 */
@Composable
internal fun ReconnectDialog(ctx: PageContext, title: String, text: String, unreachableAfterMs: Long? = null, unreachableText: String? = null) {
    var warn by remember { mutableStateOf(false) }
    if (unreachableAfterMs != null) LaunchedEffect(Unit) { delay(unreachableAfterMs); warn = true }
    LaunchedEffect(Unit) {
        delay(5000)
        val interval = (ctx.client.env?.pollInterval ?: 5).coerceAtLeast(1) * 1000L
        while (true) {
            if (ctx.client.ping()) break
            delay(interval)
        }
        ctx.client.logoutLocal()
        ctx.uci.unloadAll()
        try { ctx.session.refreshMenu() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) }
        ctx.nav.open("admin/status/overview")
    }
    BusyDialog(title, if (warn && unreachableText != null) unreachableText else text, warning = warn && unreachableText != null)
}

/** A plain-text file editor (LuCI's full-width textarea). */
@Composable
internal fun FileTextArea(value: String, onChange: (String) -> Unit, rows: Int, enabled: Boolean) {
    OutlinedTextField(
        value = value, onValueChange = onChange, enabled = enabled,
        textStyle = CodeStyle.copy(color = MaterialTheme.colorScheme.onSurface),
        minLines = rows.coerceAtMost(16), maxLines = Int.MAX_VALUE,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** LuCI's `alert-message` paragraph; [danger] for `alert-message danger`, else warning. */
@Composable
internal fun AlertBox(text: String, modifier: Modifier = Modifier, danger: Boolean = false, content: (@Composable () -> Unit)? = null) {
    val s = LocalStatusColors.current
    val c = if (danger) MaterialTheme.colorScheme.error else s.warn
    Surface(
        shape = MaterialTheme.shapes.medium, color = c.copy(alpha = 0.12f), contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, c.copy(alpha = 0.45f)), modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (text.isNotEmpty()) Text(htmlToAnnotated(text), style = MaterialTheme.typography.bodyMedium)
            content?.invoke()
        }
    }
}

/** Value written by LuCI's file editors: trimmed, LF line ends, one trailing newline. */
internal fun editorValue(s: String): String = s.trim().replace("\r\n", "\n") + "\n"
