package com.the412banner.wrtpanel.pages.system

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.core.long
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.form.SelectField
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.ErrorBox
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.SectionCard
import com.the412banner.wrtpanel.ui.components.htmlToAnnotated
import com.the412banner.wrtpanel.ui.theme.CodeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.File

/** flash.js findStorageSize(). */
internal fun findStorageSize(procmtd: String, procpart: String): Long {
    var kernsize = 0L
    var rootsize = 0L
    var wholesize = 0L
    val mtdRe = Regex("^mtd\\d+: ([0-9a-f]+) [0-9a-f]+ \"(.+)\"$")
    procmtd.split('\n').forEach { ln ->
        val m = mtdRe.find(ln)
        val size = m?.groupValues?.get(1)?.toLongOrNull(16) ?: 0L
        when (m?.groupValues?.get(2) ?: "") {
            "linux", "firmware" -> if (size > wholesize) wholesize = size
            "kernel", "kernel0" -> kernsize = size
            "rootfs", "rootfs0", "ubi", "ubi0" -> rootsize = size
        }
    }
    if (wholesize > 0) return wholesize
    else if (kernsize > 0 && rootsize > kernsize) return kernsize + rootsize
    val partRe = Regex("^\\s*\\d+\\s+\\d+\\s+(\\d+)\\s+(\\S+)$")
    procpart.split('\n').forEach { ln ->
        val m = partRe.find(ln) ?: return@forEach
        val size = m.groupValues[1].toLongOrNull() ?: 0L
        if (!Regex("\\d").containsMatchIn(m.groupValues[2]) && size > 2048 && wholesize == 0L) wholesize = size * 1024
    }
    return wholesize
}

private data class FlashInfo(
    val hasSysupgrade: Boolean,
    val hostname: String,
    val hasRootfsData: Boolean,
    val storageSize: Long,
    val mtdblocks: List<Pair<String, String>>,
)

private data class ImageCheck(
    val size: Long?,
    val checksum: String?,
    val sha256: String?,
    val valid: Boolean,
    val forceable: Boolean,
    val allowBackup: Boolean,
    val tooBig: Boolean,
    val testCode: Int,
    val testStderr: String,
)

/** System › Backup / Flash Firmware — port of luci-mod-system view/system/flash.js. */
@Composable
fun FlashPage(ctx: PageContext) {
    var info by remember { mutableStateOf<FlashInfo?>(null) }
    var editlist by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var mtdSel by remember { mutableStateOf("") }

    var backupBusy by remember { mutableStateOf(false) }
    var mtdBusy by remember { mutableStateOf(false) }
    var restoreLabel by remember { mutableStateOf("Upload archive...") }
    var flashLabel by remember { mutableStateOf("Flash image...") }
    var listBusy by remember { mutableStateOf(false) }
    var saveBusy by remember { mutableStateOf(false) }

    var restoreList by remember { mutableStateOf<String?>(null) }
    var restoreRunning by remember { mutableStateOf(false) }
    var restoreConfirmLabel by remember { mutableStateOf("Continue") }
    var imageCheck by remember { mutableStateOf<ImageCheck?>(null) }
    var checkingImage by remember { mutableStateOf(false) }
    var backupFiles by remember { mutableStateOf<List<String>?>(null) }
    var reconnect by remember { mutableStateOf<Pair<String, String>?>(null) }

    var pendingBackup by remember { mutableStateOf<File?>(null) }
    var pendingMtd by remember { mutableStateOf<Pair<String, String>?>(null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ro = ctx.readonly

    LaunchedEffect(tick) {
        error = null
        try {
            val stat = ctx.fs.stat("/lib/upgrade/platform.sh")
            val hostname = ctx.trimmed("/proc/sys/kernel/hostname")
            val procmtd = ctx.trimmed("/proc/mtd")
            val procpart = ctx.trimmed("/proc/partitions")
            val procmounts = ctx.trimmed("/proc/mounts")
            val mtdRe = Regex("^mtd(\\d+): .+ \"(.+?)\"$")
            val blocks = procmtd.split('\n').mapNotNull { ln -> mtdRe.find(ln)?.let { it.groupValues[1] to it.groupValues[2] } }
            editlist = ctx.fs.readOrNull("/etc/sysupgrade.conf") ?: ""
            mtdSel = blocks.firstOrNull()?.first ?: ""
            info = FlashInfo(
                hasSysupgrade = stat?.type == "file",
                hostname = hostname,
                hasRootfsData = procmtd.contains("\"rootfs_data\"") || procmounts.contains("overlayfs:/overlay / "),
                storageSize = findStorageSize(procmtd, procpart),
                mtdblocks = blocks,
            )
        } catch (e: Exception) { error = e.message ?: e.toString() }
    }

    val backupSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gzip")) { uri: Uri? ->
        val f = pendingBackup
        pendingBackup = null
        if (uri == null || f == null) { f?.delete(); return@rememberLauncherForActivityResult }
        scope.launch {
            try { writeToUri(context, uri, f.inputStream()) } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) } finally { f.delete() }
        }
    }

    val mtdSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val p = pendingMtd
        pendingMtd = null
        if (uri == null || p == null) return@rememberLauncherForActivityResult
        scope.launch {
            mtdBusy = true
            try {
                val (resp, stream) = ctx.client.download("/dev/mtdblock${p.first}", p.second)
                try { writeToUri(context, uri, stream) } finally { resp.close() }
            } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) } finally { mtdBusy = false }
        }
    }

    val restoreUpload = rememberUploader(ctx, "/tmp/backup.tar.gz") { _ ->
        restoreLabel = "Checking archive…"
        try {
            val res = ctx.fs.exec("/bin/tar", listOf("-tzf", "/tmp/backup.tar.gz"))
            if (res.code != 0) {
                ctx.toast("The uploaded backup archive is not readable")
                runCatching { ctx.fs.remove("/tmp/backup.tar.gz") }
            } else {
                restoreConfirmLabel = "Continue"
                restoreList = res.stdout
            }
        } catch (e: Exception) {
            ctx.toast(e.message ?: e.toString())
        } finally { restoreLabel = "Upload archive..." }
    }

    val firmwareUpload = rememberUploader(ctx, "/tmp/firmware.bin") { reply: JsonElement? ->
        val inf = info ?: return@rememberUploader
        flashLabel = "Checking image…"
        checkingImage = true
        try {
            val v = ctx.call("system", "validate_firmware_image", jsonOf("path" to "/tmp/firmware.bin"))
            val test = ctx.fs.exec("/sbin/sysupgrade", listOf("--test", "/tmp/firmware.bin"))
            val size = reply["size"].long
            imageCheck = ImageCheck(
                size = size,
                checksum = reply["checksum"].str,
                sha256 = reply["sha256sum"].str,
                valid = v["valid"].bool ?: false,
                forceable = v["forceable"].bool ?: false,
                allowBackup = v["allow_backup"].bool ?: false,
                tooBig = inf.storageSize > 0 && (size ?: 0) > inf.storageSize,
                testCode = test.code,
                testStderr = test.stderr,
            )
        } catch (e: Exception) {
            ctx.toast(e.message ?: e.toString())
        } finally {
            checkingImage = false
            flashLabel = "Flash image..."
        }
    }

    fun handleBackup() {
        scope.launch {
            backupBusy = true
            try {
                val (resp, stream) = ctx.client.cgiStream("cgi-backup", emptyMap())
                val name = Regex("filename=\"([^\"]+)\"").find(resp.header("Content-Disposition") ?: "")?.groupValues?.get(1)
                    ?: "backup-${info?.hostname ?: "OpenWrt"}.tar.gz"
                val f = File(context.cacheDir, "cgi-backup.tar.gz")
                withContext(Dispatchers.IO) {
                    try { stream.use { s -> f.outputStream().use { s.copyTo(it) } } } finally { resp.close() }
                }
                pendingBackup = f
                backupSaver.launch(name)
            } catch (e: Exception) {
                ctx.toast(e.message ?: e.toString())
            } finally { backupBusy = false }
        }
    }

    fun handleFirstboot() {
        scope.launch {
            if (!ctx.dialogs.ask("Reset to defaults", "Do you really want to erase all settings?", "OK", destructive = true)) return@launch
            reconnect = "Erasing..." to "The system is erasing the configuration partition now and will reboot itself when finished."
            ctx.scope.launch { runCatching { ctx.fs.exec("/sbin/firstboot", listOf("-r", "-y")) } }
        }
    }

    fun handleBlock() {
        val inf = info ?: return
        val name = inf.mtdblocks.firstOrNull { it.first == mtdSel }?.second ?: return
        val filename = "%s.mtd%s.%s.bin".format(inf.hostname, mtdSel, name.replace(Regex("[^a-zA-Z0-9]+"), "-"))
        pendingMtd = mtdSel to filename
        mtdSaver.launch(filename)
    }

    fun handleBackupList() {
        scope.launch {
            listBusy = true
            try {
                val res = ctx.fs.exec("/sbin/sysupgrade", listOf("--list-backup"))
                if (res.code != 0) {
                    ctx.toast("The sysupgrade command failed with code ${res.code}" + if (res.stderr.isNotEmpty()) "\n${res.stderr}" else "")
                } else {
                    backupFiles = res.stdout.trim().split('\n')
                }
            } catch (e: Exception) { ctx.toast(e.message ?: e.toString()) } finally { listBusy = false }
        }
    }

    fun handleBackupSave() {
        scope.launch {
            saveBusy = true
            try {
                val value = editorValue(editlist)
                ctx.fs.write("/etc/sysupgrade.conf", value)
                editlist = value
                ctx.toast("Contents have been saved.")
            } catch (e: Exception) {
                ctx.toast("Unable to save contents: ${e.message}")
            } finally { saveBusy = false }
        }
    }

    PageColumn {
        PageHeader("Flash operations", null)
        val inf = info
        when {
            error != null && inf == null -> ErrorBox(error!!) { tick++ }
            inf == null -> LoadingBox()
            else -> {
                PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Actions") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Configuration") })
                }
                if (tab == 0) {
                    SectionCard(title = "Backup", description = "Click \"Generate archive\" to download a tar archive of the current configuration files.") {
                        ActionRow("Download backup", null, "Generate archive", enabled = !ro && !backupBusy, primary = true) { handleBackup() }
                    }
                    SectionCard(
                        title = "Restore",
                        description = "To restore configuration files, you can upload a previously generated backup archive here. To reset the firmware to its initial state, click \"Perform reset\" (only possible with squashfs images).",
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (inf.hasRootfsData) {
                                ActionRow("Reset to defaults", null, "Perform reset", enabled = !ro, negative = true) { handleFirstboot() }
                            }
                            ActionRow(
                                "Restore backup", "Custom files (certificates, scripts) may remain on the system. To prevent this, perform a factory-reset first.",
                                restoreLabel, enabled = !ro && restoreLabel == "Upload archive...", primary = true,
                            ) { restoreUpload() }
                        }
                    }
                    if (inf.mtdblocks.isNotEmpty()) {
                        SectionCard(
                            title = "Save mtdblock contents",
                            description = "Click \"Save mtdblock\" to download specified mtdblock file. (NOTE: THIS FEATURE IS FOR PROFESSIONALS! )",
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                SelectField(
                                    value = mtdSel, choices = inf.mtdblocks, label = "Choose mtdblock",
                                    onChange = { mtdSel = it }, enabled = !ro,
                                )
                                ActionRow("Download mtdblock", null, "Save mtdblock", enabled = !ro && !mtdBusy, primary = true) { handleBlock() }
                            }
                        }
                    }
                    SectionCard(
                        title = "Flash new firmware image",
                        description = if (inf.hasSysupgrade) "Upload a sysupgrade-compatible image here to replace the running firmware."
                        else "Sorry, there is no sysupgrade support present; a new firmware image must be flashed manually. Please refer to the wiki for device specific install instructions.",
                    ) {
                        if (inf.hasSysupgrade) {
                            ActionRow("Image", null, flashLabel, enabled = !ro && flashLabel == "Flash image...", primary = true) { firmwareUpload() }
                        }
                    }
                } else {
                    SectionCard(
                        title = "Configuration",
                        description = "This is a list of shell glob patterns for matching files and directories to include during sysupgrade. Modified files in /etc/config/ and certain other configurations are automatically preserved.",
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActionRow("Show current backup file list", null, "Open list...", enabled = !ro && !listBusy, primary = false) { handleBackupList() }
                            FileTextArea(editlist, { editlist = it }, rows = 30, enabled = !ro && !saveBusy)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Button(onClick = { handleBackupSave() }, enabled = !ro && !saveBusy) { Text("Save") }
                            }
                        }
                    }
                }
            }
        }
    }

    restoreList?.let { listing ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("Apply backup?") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("The uploaded backup archive appears to be valid and contains the files listed below. Press \"Continue\" to restore the backup and reboot, or \"Cancel\" to abort the operation.")
                    Spacer(Modifier.padding(top = 8.dp))
                    SelectionContainer { Text(listing, style = CodeStyle) }
                }
            },
            dismissButton = {
                TextButton(enabled = !restoreRunning, onClick = {
                    scope.launch {
                        runCatching { ctx.fs.remove("/tmp/backup.tar.gz") }
                        restoreList = null
                    }
                }) { Text("Cancel") }
            },
            confirmButton = {
                Button(enabled = !restoreRunning, onClick = {
                    scope.launch {
                        restoreRunning = true
                        try {
                            val res = ctx.fs.exec("/sbin/sysupgrade", listOf("--restore-backup", "/tmp/backup.tar.gz"))
                            if (res.code != 0) {
                                ctx.toast("The restore command failed with code ${res.code}" + if (res.stderr.isNotEmpty()) "\n${res.stderr}" else "")
                                return@launch
                            }
                            restoreConfirmLabel = "Rebooting…"
                            val rb = ctx.fs.exec("/sbin/reboot")
                            if (rb.code != 0) {
                                ctx.toast("The reboot command failed with code ${rb.code}")
                                return@launch
                            }
                            restoreList = null
                            reconnect = "Rebooting…" to "The system is rebooting now. If the restored configuration changed the current LAN IP address, you might need to reconnect manually."
                        } catch (e: Exception) {
                            ctx.toast(e.message ?: e.toString())
                        } finally {
                            restoreRunning = false
                            if (restoreList != null) restoreConfirmLabel = "Continue"
                        }
                    }
                }) { Text(restoreConfirmLabel) }
            },
        )
    }

    if (checkingImage) BusyDialog("Checking image…", "Verifying the uploaded image file.")

    imageCheck?.let { chk ->
        FlashConfirmDialog(
            chk, hasRootfsData = info?.hasRootfsData == true,
            onCancel = {
                scope.launch {
                    runCatching { ctx.fs.remove("/tmp/firmware.bin") }
                    imageCheck = null
                }
            },
            onContinue = { args ->
                imageCheck = null
                reconnect = "Flashing…" to "The system is flashing now.<br /> DO NOT POWER OFF THE DEVICE!<br /> Wait a few minutes before you try to reconnect. It might be necessary to renew the address of your computer to reach the device again, depending on your settings."
                ctx.scope.launch { runCatching { ctx.fs.exec("/sbin/sysupgrade", args) } }
            },
        )
    }

    backupFiles?.let { files ->
        AlertDialog(
            onDismissRequest = { backupFiles = null },
            title = { Text("Backup file list") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Below is the determined list of files to backup. It consists of changed configuration files marked by opkg, essential base files and the user defined backup patterns.")
                    Spacer(Modifier.padding(top = 8.dp))
                    SelectionContainer {
                        Column { files.forEach { Text("• $it", style = CodeStyle, modifier = Modifier.padding(vertical = 1.dp)) } }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { backupFiles = null }) { Text("Dismiss") } },
        )
    }

    reconnect?.let { (title, text) -> ReconnectDialog(ctx, title, text) }
}

@Composable
private fun ActionRow(
    title: String,
    description: String?,
    label: String,
    enabled: Boolean,
    primary: Boolean = false,
    negative: Boolean = false,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!description.isNullOrBlank()) Text(htmlToAnnotated(description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when {
            negative -> Button(
                onClick = onClick, enabled = enabled,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text(label) }
            primary -> Button(onClick = onClick, enabled = enabled) { Text(label) }
            else -> FilledTonalButton(onClick = onClick, enabled = enabled) { Text(label) }
        }
    }
}

@Composable
private fun CheckRow(checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Checkbox) { onChange(!checked) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) { content() }
    }
}

/** The "Flash image?" modal of flash.js handleSysupgrade(). [onContinue] gets the sysupgrade arguments. */
@Composable
private fun FlashConfirmDialog(
    chk: ImageCheck,
    hasRootfsData: Boolean,
    onCancel: () -> Unit,
    onContinue: (args: List<String>) -> Unit,
) {
    var keep by remember { mutableStateOf(chk.allowBackup) }
    var force by remember { mutableStateOf(false) }
    var skipOrig by remember { mutableStateOf(false) }
    var backupPkgs by remember { mutableStateOf(false) }
    val showForce = (!chk.valid || chk.tooBig || chk.testCode != 0) && chk.forceable

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Flash image?") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(htmlToAnnotated("The flash image was uploaded. Below is the checksum and file size listed, compare them with the original file to ensure data integrity. <br /> Click 'Continue' below to start the flash procedure."))
                SelectionContainer {
                    Column {
                        if (chk.size != null && chk.size != 0L) Text("• Size: ${fmtMiB(chk.size)}")
                        if (!chk.checksum.isNullOrEmpty()) Text("• MD5: ${chk.checksum}", style = CodeStyle)
                        if (!chk.sha256.isNullOrEmpty()) Text("• SHA256: ${chk.sha256}", style = CodeStyle)
                    }
                }
                CheckRow(keep, enabled = chk.allowBackup, onChange = { keep = it }) { Text("Keep settings and retain the current configuration") }
                if (!chk.valid || chk.tooBig) HorizontalDivider()
                if (chk.tooBig) AlertBox("It appears that you are trying to flash an image that does not fit into the flash memory, please verify the image file!")
                if (!chk.valid) {
                    AlertBox(
                        (if (chk.testStderr.isNotEmpty()) chk.testStderr.replace("\n", "<br />") + "<br /><br />" else "") +
                            "The uploaded image file does not contain a supported format. Make sure that you choose the generic image format for your platform.",
                    )
                }
                if (!chk.allowBackup) {
                    AlertBox("The uploaded firmware does not allow keeping current configuration.")
                } else {
                    if (hasRootfsData) {
                        CheckRow(skipOrig, enabled = keep, onChange = { skipOrig = it }) { Text("Skip from backup files that are equal to those in /rom") }
                    }
                    CheckRow(backupPkgs, enabled = keep, onChange = { backupPkgs = it }) { Text("Include in backup a list of current installed packages at /etc/backup/installed_packages.txt") }
                }
                if (chk.testCode != 0) {
                    AlertBox("Image check failed:<br /><br />" + chk.testStderr.replace("\n", "<br />"), danger = true)
                }
                if (showForce) {
                    AlertBox("", danger = true) {
                        CheckRow(force, enabled = true, onChange = { force = it }) {
                            Text("Force upgrade", style = MaterialTheme.typography.titleSmall)
                            Text("Select 'Force upgrade' to flash the image even if the image format check fails. Use only if you are sure that the firmware is correct and meant for your device!", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        confirmButton = {
            Button(
                enabled = !showForce || force,
                onClick = {
                    val args = ArrayList<String>()
                    if (!keep) args += "-n"
                    if (force) args += "--force"
                    if (skipOrig) args += "-u"
                    if (backupPkgs) args += "-k"
                    args += "/tmp/firmware.bin"
                    onContinue(args)
                },
            ) { Text("Continue") }
        },
    )
}
