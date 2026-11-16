@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.the412banner.wrtpanel.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.the412banner.wrtpanel.BuildConfig
import com.the412banner.wrtpanel.ui.components.SectionCard
import com.the412banner.wrtpanel.ui.components.htmlToAnnotated
import com.the412banner.wrtpanel.ui.theme.ThemeMode
import com.the412banner.wrtpanel.update.Updater
import kotlinx.coroutines.launch

@Composable
fun AppRoot(vm: AppViewModel) {
    AnimatedContent(
        targetState = vm.session to vm.showSettings,
        transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(160)) },
        label = "root",
    ) { (s, settings) ->
        when {
            settings -> {
                androidx.activity.compose.BackHandler { vm.showSettings = false }
                SettingsScreen(vm) { vm.showSettings = false }
            }
            s == null -> RoutersScreen(vm)
            else -> MainScreen(vm, s)
        }
    }
    if (vm.showUpdateRequested) UpdateDialog(vm) { vm.showUpdateRequested = false }
}

@Composable
fun SettingsScreen(vm: AppViewModel, onClose: () -> Unit) {
    val s = vm.settings
    val context = LocalContext.current
    run {
        Scaffold(topBar = {
            TopAppBar(
                title = { Text("App settings") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } },
            )
        }) { pad ->
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SectionCard("Appearance") {
                        ThemeMode.entries.forEach { m ->
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = s.themeMode == m, role = Role.RadioButton) { s.updateTheme(m) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = s.themeMode == m, onClick = null)
                                Spacer(Modifier.padding(start = 8.dp))
                                Text(when (m) { ThemeMode.System -> "Follow system"; ThemeMode.Light -> "Light"; ThemeMode.Dark -> "Dark" })
                            }
                        }
                        if (android.os.Build.VERSION.SDK_INT >= 31) SwitchRow("Use wallpaper colours", s.dynamicColor) { s.updateDynamic(it) }
                    }
                    SectionCard("Updates") {
                        SwitchRow("Check for updates automatically", s.autoUpdateCheck) {
                            s.updateAutoCheck(it); Updater.schedule(context, it)
                        }
                        SwitchRow("Include test builds (pre-releases)", s.includePrereleases) { s.updatePrereleases(it) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Installed version", style = MaterialTheme.typography.bodyLarge)
                                Text("${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { vm.checkForUpdate(manual = true) }, enabled = !vm.checkingUpdate) { Text(if (vm.checkingUpdate) "Checking…" else "Check now") }
                        }
                        vm.updateCheckError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    SectionCard("About") {
                        Text("WrtPanel manages OpenWrt routers through the same interfaces the LuCI web interface uses.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Page layouts, labels and help texts follow LuCI (Apache-2.0, © the LuCI contributors). OpenWrt is a registered trademark of Software Freedom Conservancy; WrtPanel is not affiliated with the OpenWrt project.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.UPDATE_REPO}"))) }) { Text("Source code on GitHub") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}

@Composable
fun UpdateDialog(vm: AppViewModel, onClose: () -> Unit) {
    val rel = vm.availableUpdate
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    if (rel == null) {
        AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClick = onClose) { Text("OK") } },
            title = { Text("No update") }, text = { Text(vm.updateCheckError ?: "WrtPanel is up to date.") })
        return
    }
    AlertDialog(
        onDismissRequest = { if (progress == null) onClose() },
        title = { Text("WrtPanel ${rel.tag.removePrefix("v")}") },
        text = {
            Column {
                Text("Installed: ${vm.updater.currentVersion}" + if (rel.prerelease) " · test build" else "", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text(htmlToAnnotated(rel.notes.ifBlank { "No release notes." }.replace(Regex("(?m)^#+ "), "").replace("**", "")), style = MaterialTheme.typography.bodyMedium)
                }
                progress?.let { p ->
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = progress == null, onClick = {
                if (!vm.updater.canInstall()) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    return@TextButton
                }
                scope.launch {
                    error = null
                    progress = 0f
                    try {
                        val f = vm.updater.download(rel) { progress = it }
                        vm.updater.install(f)
                    } catch (e: Exception) {
                        error = e.message ?: "Update failed"
                    }
                    progress = null
                }
            }) { Text(if (vm.updater.canInstall()) "Download & install" else "Allow installs") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = progress == null) { Text("Later") } },
    )
}
