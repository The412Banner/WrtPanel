package com.the412banner.wrtpanel.ui.components

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.FsEntry
import com.the412banner.wrtpanel.ui.PageContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/** LuCI's FileUpload browser: list router directories under [root], pick a file, upload one from the phone. */
@Composable
fun RouterFileBrowser(
    ctx: PageContext,
    root: String,
    initial: String,
    allowUpload: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val startDir = if (initial.startsWith(root)) initial.substringBeforeLast('/').ifEmpty { root } else root
    var dir by remember { mutableStateOf(startDir) }
    var entries by remember { mutableStateOf<List<FsEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(dir, tick) {
        entries = null; error = null
        try {
            entries = ctx.fs.list(dir).sortedWith(compareBy<FsEntry> { !it.isDir }.thenBy { it.name.lowercase() })
        } catch (e: Exception) {
            // The upload directory may not exist yet.
            entries = emptyList(); error = e.message
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    val cr = context.contentResolver
                    var n = "upload.bin"
                    cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) n = c.getString(0) ?: n }
                    n to (cr.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0))
                }
                val target = "${dir.trimEnd('/')}/$name"
                ctx.client.upload(target, name, bytes.toRequestBody("application/octet-stream".toMediaType()))
                ctx.toast("Uploaded $name")
                tick++
            } catch (e: Exception) { ctx.toast(e.message ?: "Upload failed") }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(dir, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                if (dir != root && dir.length > root.length) {
                    Row(Modifier.fillMaxWidth().clickable { dir = dir.substringBeforeLast('/').ifEmpty { "/" } }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ArrowUpward, null); Spacer(Modifier.width(12.dp)); Text("..")
                    }
                }
                val list = entries
                when {
                    list == null -> LoadingBox()
                    list.isEmpty() -> EmptyState(if (error != null) "Directory not present" else "No files")
                    else -> LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(list, key = { it.name }) { e ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    val p = "${dir.trimEnd('/')}/${e.name}"
                                    if (e.isDir) dir = p else onPick(p)
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(if (e.isDir) Icons.Outlined.Folder else Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(e.name)
                                    if (!e.isDir) Text(formatBytes(e.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!e.isDir) IconButton(onClick = {
                                    scope.launch {
                                        if (ctx.dialogs.ask("Delete file", "Do you really want to delete \"${e.name}\" ?", "Delete", destructive = true)) {
                                            try { ctx.fs.remove("${dir.trimEnd('/')}/${e.name}"); tick++ } catch (x: Exception) { ctx.toast(x.message ?: "Failed") }
                                        }
                                    }
                                }) { Icon(Icons.Outlined.Delete, "Delete") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (allowUpload) TextButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Outlined.Upload, null); Spacer(Modifier.width(6.dp)); Text("Upload file…") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
