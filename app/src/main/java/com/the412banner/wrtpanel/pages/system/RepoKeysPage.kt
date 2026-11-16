package com.the412banner.wrtpanel.pages.system

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.CodeBlock
import com.the412banner.wrtpanel.ui.components.EmptyState
import com.the412banner.wrtpanel.ui.components.ErrorBox
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.SectionCard
import com.the412banner.wrtpanel.ui.theme.CodeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val APK_DIR = "/etc/apk/keys/"
private const val OPKG_DIR = "/etc/opkg/keys/"

private val safeList = listOf("d310c6f2833e97f7", "openwrt-snapshots.pem")

/** repokeys.js isFileInSafeList(). */
private fun isFileInSafeList(file: String): Boolean {
    for (name in safeList) {
        if (file == name) return true
        if (file.lowercase().replace(Regex("^openwrt-[0-9]+\\.[0-9]+", RegexOption.IGNORE_CASE), "") != file) return true
        if (file.lowercase().replace(Regex("^openwrt-snapshots", RegexOption.IGNORE_CASE), "") != file) return true
    }
    return false
}

private fun normalizeKey(s: String?): String? = s?.replace(Regex("\\s+"), " ")?.trim()

private fun isPemFormat(content: String) = Regex("-BEGIN ([A-Z ]+)?PUBLIC KEY-").containsMatchIn(content)

private data class RepoKey(val filename: String, val key: String)

private val fetchHttp by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build() }

/** System › Administration › Repo Public Keys — port of luci-mod-system view/system/repokeys.js. */
@Composable
fun RepoKeysPage(ctx: PageContext) {
    var keyDir by remember { mutableStateOf(APK_DIR) }
    var keyExt by remember { mutableStateOf<String?>(null) }
    var keys by remember { mutableStateOf<List<RepoKey>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    suspend fun listKeyFiles(): List<RepoKey> =
        ctx.fs.list(keyDir).map { e -> RepoKey(e.name, ctx.fs.read(keyDir + e.name)) }

    LaunchedEffect(tick) {
        error = null
        try {
            if (ctx.fs.stat(APK_DIR) != null) { keyDir = APK_DIR; keyExt = ".pem" } else { keyDir = OPKG_DIR; keyExt = null }
            keys = listKeyFiles()
        } catch (e: Exception) { error = e.message ?: e.toString() }
    }

    fun keyEnvironmentCheck(key: String): String? {
        val pem = isPemFormat(key)
        if (keyDir == OPKG_DIR && pem) return "This key appears to be in PEM format, which is not supported in an opkg environment."
        if (keyDir == APK_DIR && !pem) return "This key does not appear to be in PEM format, which is required in an apk environment."
        return null
    }

    suspend fun addKey(fileName: String?, fileContent: String?) {
        val key = fileContent ?: input.trim()
        if (key.isEmpty()) return

        if (fileContent == null && Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(key)) {
            ctx.toast("Fetching key from URL…")
            try {
                val (code, finalUrl, body) = withContext(Dispatchers.IO) {
                    fetchHttp.newCall(Request.Builder().url(key).get().build()).execute().use { r ->
                        Triple(r.code, r.request.url.toString(), r.body?.string())
                    }
                }
                if (code != 200) { ctx.toast("Failed to fetch key: HTTP error $code"); return }
                val fetched = body?.trim()
                if (fetched.isNullOrEmpty() || fetched.length > 8192) { ctx.toast("Key file too large: Fetched content seems too long. Maximum 8192 bytes."); return }
                if (fetched.length < 32) { ctx.toast("Invalid or empty key file: Fetched content seems empty or too short."); return }
                val filename = finalUrl.split('/').last().split('?')[0].split('#')[0]
                addKey(filename.replace(Regex("\\.[^.]+$"), ""), fetched)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ctx.toast("Failed to fetch key: ${e.message}")
            }
            return
        }

        keyEnvironmentCheck(key)?.let { ctx.toast("Invalid key format: $it"); return }

        try {
            val existing = listKeyFiles()
            if (existing.any { normalizeKey(it.key) == normalizeKey(key) }) {
                ctx.toast("Add key: The given software repository public key is already present.")
                return
            }
            input = ""
            val name = fileName?.let { it.split('.')[0] + (keyExt ?: "") } ?: ("key_" + System.currentTimeMillis() + (keyExt ?: ""))
            ctx.fs.write(keyDir + name, key, 384)
            tick++
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ctx.toast(e.message ?: e.toString())
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        scope.launch {
            for (uri in uris) {
                try {
                    val (name, text) = readText(context, uri)
                    addKey(name, text)
                } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) }
            }
        }
    }

    PageColumn {
        val list = keys
        PageHeader(
            "Repository Public Keys",
            if (list == null) null else
                "Each software repository public key (from official or third party repositories) allows packages in lists signed by it to be installed by the package manager.<br/>" +
                    "Each key is stored as a file in <code>$keyDir</code>.",
        )
        when {
            error != null && list == null -> ErrorBox(error!!) { tick++ }
            list == null -> LoadingBox()
            else -> {
                SectionCard {
                    if (list.isEmpty()) EmptyState("This section contains no values yet")
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        list.forEach { k ->
                            Column(Modifier.fillMaxWidth()) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(k.filename, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    val reserved = isFileInSafeList(k.filename)
                                    OutlinedButton(
                                        enabled = !ctx.readonly && !reserved,
                                        onClick = {
                                            scope.launch {
                                                if (isFileInSafeList(k.filename)) { ctx.toast("This key is protected and cannot be deleted."); return@launch }
                                                if (ctx.dialogs.ask("Delete key", "Really delete the following software repository public key?\n\n${k.filename}", "Delete key", destructive = true)) {
                                                    try { ctx.fs.remove(keyDir + k.filename); tick++ } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) }
                                                }
                                            }
                                        },
                                    ) { Text("Delete", color = if (!ctx.readonly && !reserved) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)) }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text("Key", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                CodeBlock(k.key.trimEnd(), maxHeight = 140.dp)
                            }
                        }
                    }
                }
                SectionCard(description = "Add new repository public key by pasting its content, a file, or a URL.") {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it }, enabled = !ctx.readonly,
                        placeholder = { Text("Paste content of a file, or a URL to a key file, or drag and drop here to upload a software repository public key…") },
                        textStyle = CodeStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                        minLines = 5, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !ctx.readonly) {
                            Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Browse…")
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = { scope.launch { addKey(null, null) } }, enabled = !ctx.readonly) { Text("Add key") }
                    }
                }
            }
        }
    }
}
