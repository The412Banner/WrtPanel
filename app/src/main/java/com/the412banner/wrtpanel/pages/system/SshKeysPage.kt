package com.the412banner.wrtpanel.pages.system

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.ErrorBox
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.SectionCard
import com.the412banner.wrtpanel.ui.theme.CodeStyle
import kotlinx.coroutines.launch

/** A decoded public key (sshkeys.js SSHPubkeyDecoder.decode result). */
internal data class SshPubkey(
    val type: String,
    val bits: Int? = null,
    val curve: String? = null,
    val comment: String,
    val options: Map<String, String>?,
    val fprint: String,
    val src: String,
)

/** sshkeys.js SSHPubkeyDecoder. */
internal object SshPubkeyDecoder {
    private val keyRe = Regex(
        "^((?:(?:^|,)[^ =,]+(?:=(?:[^ \",]+|\"(?:[^\"\\\\]|\\\\.)*\"))?)+ +)?" +
            "(ssh-dss|ssh-rsa|ssh-ed25519|ecdsa-sha2-nistp[0-9]+|sk-ecdsa-sha2-nistp256@openssh\\.com|sk-ssh-ed25519@openssh\\.com) +([^ ]+)( +.*)?$",
    )
    private val optRe = Regex("(?:^|,)([^ =,]+)(?:=(?:([^ \",]+)|\"((?:[^\"\\\\]|\\\\.)*)\"))?")

    private fun lengthDecode(s: String, start: Int): Int {
        var off = start
        fun at(i: Int): Int = if (i < s.length) s[i].code else 0
        val l = (at(off++) shl 24) or (at(off++) shl 16) or (at(off++) shl 8) or at(off++)
        if (l < 0 || off + l > s.length) return -1
        return l
    }

    private fun atob(b64: String): String? = try {
        val bytes = java.util.Base64.getDecoder().decode(b64)
        String(CharArray(bytes.size) { (bytes[it].toInt() and 0xff).toChar() })
    } catch (e: IllegalArgumentException) { null }

    fun decode(input: String): SshPubkey? {
        val s = input
        val parts = keyRe.find(s.trim()) ?: return null
        val g = parts.groupValues
        val key = atob(g[3])
        if (key.isNullOrEmpty()) return null

        var off = 0
        val len = lengthDecode(key, off)
        if (len <= 0) return null
        var type = key.substring(off + 4, (off + 4 + len).coerceAtMost(key.length))
        if (type != g[2]) return null
        off += 4 + len

        var len1 = if (off < key.length) lengthDecode(key, off) else 0
        if (len1 <= 0) return null

        var curve: String? = null
        if (type.startsWith("ecdsa-sha2-")) {
            curve = key.substring(off + 4, (off + 4 + len1).coerceAtMost(key.length))
            if (len1 == 0 || type.substring(11) != curve) return null
            type = "ecdsa-sha2"
            curve = curve.replace(Regex("^nistp(\\d+)$"), "NIST P-$1")
        }
        off += 4 + len1

        var len2 = if (off < key.length) lengthDecode(key, off) else 0
        if (len2 < 0) return null
        if ((len1 and 1) != 0) len1--
        if ((len2 and 1) != 0) len2--

        val comment = g[4].trim()
        val b = g[3]
        val fprint = if (b.length > 68) b.substring(0, 33) + "…" + b.substring(b.length - 34) else b

        var options: MutableMap<String, String>? = null
        optRe.findAll(g[1].trim()).forEach { m ->
            if (m.value.isEmpty()) return@forEach
            val k = m.groupValues[1]
            val v = m.groupValues[3].ifEmpty { m.groupValues[2] }.ifEmpty { "true" }
            val o = options ?: mutableMapOf<String, String>().also { options = it }
            o[k] = if (o.containsKey(k)) o[k] + "," + v else v
        }

        return when (type) {
            "ssh-rsa" -> SshPubkey("RSA", bits = len2 * 8, comment = comment, options = options, fprint = fprint, src = s)
            "ssh-dss" -> SshPubkey("DSA", bits = len1 * 8, comment = comment, options = options, fprint = fprint, src = s)
            "ssh-ed25519" -> SshPubkey("EdDSA", curve = "Curve25519", comment = comment, options = options, fprint = fprint, src = s)
            "ecdsa-sha2" -> SshPubkey("ECDSA", curve = curve, comment = comment, options = options, fprint = fprint, src = s)
            "sk-ecdsa-sha2-nistp256@openssh.com" -> SshPubkey("ECDSA-SK", curve = "NIST P-256", comment = comment, options = options, fprint = fprint, src = s)
            "sk-ssh-ed25519@openssh.com" -> SshPubkey("EdDSA-SK", curve = "Curve25519", comment = comment, options = options, fprint = fprint, src = s)
            else -> null
        }
    }
}

/** System › Administration › SSH-Keys — port of luci-mod-system view/system/sshkeys.js. */
@Composable
fun SshKeysPage(ctx: PageContext) {
    var keys by remember { mutableStateOf<List<SshPubkey>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(tick) {
        error = null
        try { keys = ctx.lines("/etc/dropbear/authorized_keys").mapNotNull { SshPubkeyDecoder.decode(it) } } catch (e: Exception) { error = e.message ?: e.toString() }
    }

    suspend fun saveKeys(list: List<String>) {
        try {
            ctx.fs.write("/etc/dropbear/authorized_keys", list.joinToString("\n") + "\n", 384)
            keys = list.mapNotNull { SshPubkeyDecoder.decode(it) }
        } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) }
    }

    suspend fun addKey(raw: String) {
        val key = raw.trim()
        if (key.isEmpty()) return
        val current = keys?.map { it.src } ?: emptyList()
        val pubkey = SshPubkeyDecoder.decode(key)
        when {
            key in current -> ctx.dialogs.ask("Add key", "The given SSH public key has already been added.", "Close", dismissLabel = null)
            pubkey == null -> ctx.dialogs.ask("Add key", "The given SSH public key is invalid. Please supply proper public RSA, ED25519 or ECDSA keys.", "Close", dismissLabel = null)
            else -> {
                input = ""
                busy = true
                try { saveKeys(current + key) } finally { busy = false }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try { addKey(readText(context, uri).second.trim()) } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) }
        }
    }

    PageColumn {
        PageHeader(
            "SSH-Keys",
            "Public keys allow for passwordless SSH logins with higher security than plain passwords. In order to upload a new key to the device, paste an OpenSSH-compatible public key or drag a <code>.pub</code> file into the input field.",
        )
        val list = keys
        when {
            error != null && list == null -> ErrorBox(error!!) { tick++ }
            list == null -> LoadingBox()
            else -> SectionCard {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (list.isEmpty()) {
                        Text("No public keys present yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    list.forEach { k ->
                        KeyItem(k, enabled = !ctx.readonly && !busy) {
                            scope.launch {
                                val rest = list.map { it.src }.filter { it != k.src }
                                if (ctx.dialogs.ask("Delete key", "Do you really want to delete the following SSH key?\n\n${k.src}", "Delete key", destructive = true)) {
                                    busy = true
                                    try { saveKeys(rest) } finally { busy = false }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it }, singleLine = true,
                            enabled = !ctx.readonly && !busy,
                            placeholder = { Text("Paste or drag SSH key file…") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { scope.launch { addKey(input) } }),
                            trailingIcon = {
                                IconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !ctx.readonly && !busy) { Icon(Icons.Outlined.FileOpen, "Browse…") }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(onClick = { scope.launch { addKey(input) } }, enabled = !ctx.readonly && !busy) { Text("Add key") }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyItem(k: SshPubkey, enabled: Boolean, onDelete: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(k.comment.ifEmpty { "Unnamed key" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                val info = "%s, %s".format(k.type, k.curve ?: "%d Bit".format(k.bits ?: 0)) +
                    (k.options?.let { " / Options: " + it.keys.sorted().joinToString(", ") } ?: "")
                Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(k.fprint, style = CodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (enabled) IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete key") }
        }
    }
}
