package com.the412banner.wrtpanel.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.Router
import com.the412banner.wrtpanel.core.normalizeRouterUrl
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutersScreen(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Router?>(null) }
    var askPassword by remember { mutableStateOf<Router?>(null) }
    var untrusted by remember { mutableStateOf<Triple<Router, String, String>?>(null) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    fun connect(r: Router) {
        scope.launch {
            when (val res = vm.connect(r)) {
                is ConnectResult.Ok -> {}
                is ConnectResult.Failed -> snack.showSnackbar(res.message)
                is ConnectResult.Untrusted -> untrusted = Triple(r, res.sha256, res.subject)
            }
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("WrtPanel") },
                actions = {
                    if (vm.availableUpdate != null) IconButton(onClick = { vm.showUpdateRequested = true }) {
                        Icon(Icons.Outlined.SystemUpdate, "Update available", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = { vm.showSettings = true }) { Icon(Icons.Outlined.Settings, "Settings") }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = Router(name = "", url = vm.detectGateway()?.let { "http://$it" } ?: "http://192.168.1.1") },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("Add router") },
            )
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (vm.routers.isEmpty()) {
                    item {
                        WelcomeCard(vm.detectGateway()) { gw ->
                            editing = Router(name = "", url = gw?.let { "http://$it" } ?: "http://192.168.1.1")
                        }
                    }
                }
                items(vm.routers, key = { it.id }) { r ->
                    RouterCard(
                        r, connecting = vm.connectingId == r.id, enabled = vm.connectingId == null,
                        onClick = { if (r.password.isEmpty()) askPassword = r else connect(r) },
                        onEdit = { editing = r },
                        onDelete = { vm.deleteRouter(r.id) },
                    )
                }
            }
        }
    }

    editing?.let { r ->
        RouterEditDialog(
            initial = r,
            onDismiss = { editing = null },
            onSave = { updated, andConnect ->
                editing = null
                vm.saveRouter(updated)
                if (andConnect) {
                    if (updated.password.isEmpty()) askPassword = updated else connect(updated)
                }
            },
        )
    }
    askPassword?.let { r ->
        PasswordDialog(r, onDismiss = { askPassword = null }) { pw, keep ->
            askPassword = null
            val withPw = r.copy(password = pw, remember = keep)
            if (keep) vm.saveRouter(withPw)
            connect(withPw)
        }
    }
    untrusted?.let { (r, sha, subject) ->
        AlertDialog(
            onDismissRequest = { untrusted = null },
            icon = { Icon(Icons.Outlined.Lock, null) },
            title = { Text("Untrusted certificate") },
            text = {
                Column {
                    Text("${r.hostLabel} presented a certificate that is not signed by a known authority (routers usually use a self-signed one).")
                    Spacer(Modifier.height(10.dp))
                    Text("Subject", style = MaterialTheme.typography.labelMedium)
                    Text(subject, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Text("SHA-256 fingerprint", style = MaterialTheme.typography.labelMedium)
                    Text(sha, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(10.dp))
                    Text("Trust it only if this is your router. WrtPanel will warn you if it ever changes.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    untrusted = null
                    val pinned = r.copy(pinnedCertSha256 = sha)
                    vm.saveRouter(pinned)
                    connect(pinned)
                }) { Text("Trust and connect") }
            },
            dismissButton = { TextButton(onClick = { untrusted = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WelcomeCard(gateway: String?, onAdd: (String?) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(22.dp)) {
            Icon(Icons.Outlined.Router, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(12.dp))
            Text("Manage your OpenWrt router", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Sign in with the same user and password as the LuCI web interface.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(14.dp))
            if (gateway != null) Text("Detected router on this network: $gateway", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            TextButton(onClick = { onAdd(gateway) }) { Text(if (gateway != null) "Add $gateway" else "Add a router") }
        }
    }
}

@Composable
private fun RouterCard(r: Router, connecting: Boolean, enabled: Boolean, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(46.dp).clip(CircleShape)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Router, null, tint = MaterialTheme.colorScheme.onPrimaryContainer) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name.ifEmpty { r.hostLabel }, style = MaterialTheme.typography.titleMedium)
                Text("${r.username}@${r.hostLabel}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (r.isHttps) Text(if (r.pinnedCertSha256 != null) "HTTPS · pinned certificate" else "HTTPS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            AnimatedVisibility(connecting) { CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Edit") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun RouterEditDialog(initial: Router, onDismiss: () -> Unit, onSave: (Router, Boolean) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var user by remember { mutableStateOf(initial.username) }
    var pw by remember { mutableStateOf(initial.password) }
    var keepPw by remember { mutableStateOf(initial.remember) }
    var show by remember { mutableStateOf(false) }
    val build = {
        val u = normalizeRouterUrl(url)
        initial.copy(
            name = name.trim(), url = u, username = user.trim().ifEmpty { "root" }, password = pw, remember = keepPw,
            pinnedCertSha256 = if (u != initial.url) null else initial.pinnedCertSha256,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isEmpty() && initial.lastUsed == 0L) "Add router" else "Edit router") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("Address") }, singleLine = true,
                    supportingText = { Text("e.g. 192.168.1.1, https://router.lan or 10.0.0.1:8080") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show password") } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Remember password", modifier = Modifier.weight(1f))
                    Switch(keepPw, { keepPw = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(build(), true) }, enabled = url.isNotBlank()) { Text("Save & connect") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onSave(build(), false) }, enabled = url.isNotBlank()) { Text("Save") }
            }
        },
    )
}

@Composable
private fun PasswordDialog(r: Router, onDismiss: () -> Unit, onConnect: (String, Boolean) -> Unit) {
    var pw by remember { mutableStateOf("") }
    var keepPw by remember { mutableStateOf(r.remember) }
    var show by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in to ${r.name.ifEmpty { r.hostLabel }}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${r.username}@${r.hostLabel}", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show password") } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Remember password", modifier = Modifier.weight(1f))
                    Switch(keepPw, { keepPw = it })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConnect(pw, keepPw) }) { Text("Log in") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
