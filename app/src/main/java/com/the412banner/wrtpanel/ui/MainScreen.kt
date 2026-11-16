@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.the412banner.wrtpanel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.the412banner.wrtpanel.core.ApplyStatus
import com.the412banner.wrtpanel.pages.Pages
import com.the412banner.wrtpanel.ui.components.DialogHostView
import com.the412banner.wrtpanel.ui.theme.CodeStyle
import com.the412banner.wrtpanel.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

/** Top-level groups (admin/<group>) and their pages, as LuCI's menu shows them. */
private fun groups(root: MenuNode): List<MenuNode> =
    root.find("admin")?.visibleChildren?.filter { it.actionType == "firstchild" || it.actionType == "alias" || it.visibleChildren.isNotEmpty() }
        ?.filter { it.visibleChildren.isNotEmpty() } ?: emptyList()

private fun iconFor(path: String): ImageVector = when (path.substringAfterLast('/')) {
    "overview" -> Icons.Outlined.Dashboard
    "routes", "routesj" -> Icons.Outlined.Timeline
    "iptables", "nftables", "firewall" -> Icons.Outlined.Security
    "logs", "syslog", "dmesg" -> Icons.Outlined.Article
    "processes" -> Icons.Outlined.Memory
    "channel_analysis" -> Icons.Outlined.WifiFind
    "realtime" -> Icons.Outlined.ShowChart
    "system" -> Icons.Outlined.Tune
    "admin" -> Icons.Outlined.AdminPanelSettings
    "package-manager", "opkg" -> Icons.Outlined.Widgets
    "startup" -> Icons.Outlined.PlayCircleOutline
    "crontab" -> Icons.Outlined.Schedule
    "mounts" -> Icons.Outlined.Storage
    "leds" -> Icons.Outlined.Lightbulb
    "flash" -> Icons.Outlined.SystemUpdate
    "reboot" -> Icons.Outlined.RestartAlt
    "network" -> Icons.Outlined.SettingsEthernet
    "wireless" -> Icons.Outlined.Wifi
    "switch" -> Icons.Outlined.DeviceHub
    "dhcp" -> Icons.Outlined.Dns
    "dns" -> Icons.Outlined.Language
    "diagnostics" -> Icons.Outlined.NetworkCheck
    "ksmbd", "samba4" -> Icons.Outlined.FolderShared
    "wireguard", "openvpn" -> Icons.Outlined.VpnKey
    else -> Icons.Outlined.Extension
}

@Composable
fun MainScreen(vm: AppViewModel, session: RouterSession) {
    val root = session.menu ?: return
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val dialogs = remember { DialogHost() }
    val groups = remember(root) { groups(root) }
    val firstPage = remember(root) { groups.firstOrNull()?.visibleChildren?.firstOrNull()?.path ?: "admin/status/overview" }
    var current by rememberSaveable { mutableStateOf(firstPage) }
    val history = remember { mutableStateListOf<String>() }
    var refreshKey by remember { mutableIntStateOf(0) }
    var showChanges by remember { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val changes by session.changes.changes.collectAsState()
    val changeCount = changes.values.sumOf { it.size }
    val applyStatus by session.changes.status.collectAsState()

    val navigator = remember {
        object : Navigator {
            override fun open(path: String) {
                val target = root.find(path)?.resolveTarget(root)?.path ?: path
                if (target != current) { history.add(current); current = target }
                scope.launch { drawer.close() }
            }
            override fun back(): Boolean {
                if (history.isEmpty()) return false
                current = history.removeAt(history.size - 1); return true
            }
        }
    }
    LaunchedEffect(vm.openRequest) {
        vm.openRequest?.let { navigator.open(it); vm.openRequest = null }
    }
    BackHandler(enabled = drawer.isOpen || history.isNotEmpty()) {
        if (drawer.isOpen) scope.launch { drawer.close() } else navigator.back()
    }

    // Node layout: admin/<group>/<page>[/<tab>]
    val segs = current.split('/')
    val pagePath = segs.take(3).joinToString("/")
    val pageNode = root.find(pagePath)
    val groupNode = root.find(segs.take(2).joinToString("/"))
    val tabs = pageNode?.visibleChildren ?: emptyList()
    val shownNode = root.find(current)?.resolveTarget(root) ?: pageNode

    val drawerContent: @Composable () -> Unit = {
        DrawerContent(
            session = session, groups = groups, current = current,
            onOpen = { navigator.open(it) },
            onSettings = { vm.showSettings = true },
            onSwitch = { vm.disconnect(logout = false) },
            onLogout = { vm.disconnect(logout = true) },
            updateAvailable = vm.availableUpdate != null,
            onUpdate = { vm.showUpdateRequested = true },
        )
    }

    val content: @Composable (Boolean) -> Unit = { wide ->
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(pageNode?.title ?: "WrtPanel", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(groupNode?.title, session.board?.hostname).joinToString(" · "),
                                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        },
                        navigationIcon = {
                            if (!wide) IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "Menu") }
                        },
                        actions = {
                            if (changeCount > 0) {
                                TextButton(onClick = { showChanges = true }) {
                                    BadgedBox(badge = { Badge { Text("$changeCount") } }) { Text("Unsaved Changes") }
                                }
                            }
                            IconButton(onClick = { refreshKey++ }) { Icon(Icons.Outlined.Refresh, "Reload") }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    )
                    if (tabs.size > 1) {
                        val idx = tabs.indexOfFirst { current == it.path || current.startsWith(it.path + "/") }.coerceAtLeast(0)
                        PrimaryScrollableTabRow(selectedTabIndex = idx, edgePadding = 12.dp) {
                            tabs.forEachIndexed { i, t ->
                                Tab(selected = i == idx, onClick = { navigator.open(t.path) }, text = { Text(t.title ?: t.name) })
                            }
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snack) },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                val ctx = remember(shownNode?.path, refreshKey) {
                    PageContext(
                        session = session, nav = navigator, readonly = false,
                        scope = scope, dialogs = dialogs, toast = { m -> scope.launch { snack.showSnackbar(m) } }, node = shownNode,
                    )
                }
                AnimatedContent(
                    targetState = Triple(shownNode?.path, refreshKey, ctx),
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                    label = "page",
                ) { (_, _, c) ->
                    CompositionLocalProvider(LocalPageContext provides c) {
                        Pages.Render(c, shownNode)
                    }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        if (wide) {
            PermanentNavigationDrawer(drawerContent = { PermanentDrawerSheet(Modifier.width(300.dp)) { drawerContent() } }) { content(true) }
        } else {
            ModalNavigationDrawer(drawerState = drawer, drawerContent = { ModalDrawerSheet(Modifier.width(310.dp)) { drawerContent() } }) { content(false) }
        }
    }

    if (showChanges) ChangesSheet(session, onDismiss = { showChanges = false })
    applyStatus?.let { ApplyStatusDialog(session, it) }
    DialogHostView(dialogs)
}

@Composable
private fun DrawerContent(
    session: RouterSession,
    groups: List<MenuNode>,
    current: String,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onSwitch: () -> Unit,
    onLogout: () -> Unit,
    updateAvailable: Boolean,
    onUpdate: () -> Unit,
) {
    val expanded = remember { mutableStateListOf<String>().apply { groups.firstOrNull { current.startsWith(it.path + "/") }?.let { add(it.path) } } }
    // Opening a page elsewhere expands its group once; afterwards the user can collapse any group.
    LaunchedEffect(current) {
        groups.firstOrNull { current.startsWith(it.path + "/") }?.let { if (it.path !in expanded) expanded.add(it.path) }
    }
    Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(16.dp))
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Router, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(34.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(session.board?.hostname ?: session.router.hostLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    session.board?.model?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                    session.board?.release?.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        groups.forEach { g ->
            val open = g.path in expanded
            Row(
                Modifier.fillMaxWidth().clickable { if (g.path in expanded) expanded.remove(g.path) else expanded.add(g.path) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(g.title ?: g.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) {
                g.visibleChildren.forEach { item ->
                    val selected = current == item.path || current.startsWith(item.path + "/")
                    NavigationDrawerItem(
                        label = { Text(item.title ?: item.name) },
                        icon = { Icon(iconFor(item.path), null) },
                        selected = selected,
                        onClick = { onOpen(item.path) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).height(48.dp),
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        if (updateAvailable) {
            NavigationDrawerItem(label = { Text("Update WrtPanel") }, icon = { Icon(Icons.Outlined.SystemUpdate, null) }, selected = false, onClick = onUpdate,
                badge = { Badge { Text("new") } })
        }
        NavigationDrawerItem(label = { Text("App settings") }, icon = { Icon(Icons.Outlined.Settings, null) }, selected = false, onClick = onSettings)
        NavigationDrawerItem(label = { Text("Switch router") }, icon = { Icon(Icons.Outlined.SwapHoriz, null) }, selected = false, onClick = onSwitch)
        NavigationDrawerItem(label = { Text("Log out") }, icon = { Icon(Icons.AutoMirrored.Outlined.Logout, null) }, selected = false, onClick = onLogout)
        Spacer(Modifier.height(24.dp))
    }
}

/** LuCI's "Configuration / Changes" dialog. */
@Composable
private fun ChangesSheet(session: RouterSession, onDismiss: () -> Unit) {
    val changes by session.changes.changes.collectAsState()
    val scope = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val s = LocalStatusColors.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Configuration / Changes", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                changes.forEach { (conf, list) ->
                    Text("# /etc/config/$conf", style = CodeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                    session.changes.describe(conf, list).forEach { (kind, line) ->
                        val c = when (kind) { "add" -> s.ok; "remove", "optremove" -> s.bad; else -> MaterialTheme.colorScheme.onSurface }
                        Text(line, style = CodeStyle, color = c, modifier = Modifier.padding(vertical = 1.dp))
                    }
                }
                if (changes.isEmpty()) Text("There are no pending changes.", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { onDismiss(); scope.launch { session.changes.revert() } }, enabled = changes.isNotEmpty()) { Text("Revert") }
                OutlinedButton(onClick = { onDismiss(); scope.launch { session.changes.apply(checked = false) } }, enabled = changes.isNotEmpty()) { Text("Apply unchecked") }
                Button(onClick = { onDismiss(); scope.launch { session.changes.apply(checked = true) } }, enabled = changes.isNotEmpty()) { Text("Save & Apply") }
            }
        }
    }
}

@Composable
private fun ApplyStatusDialog(session: RouterSession, st: ApplyStatus) {
    val scope = rememberCoroutineScope()
    val m = session.changes
    when (st) {
        is ApplyStatus.Working -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            confirmButton = {},
            title = { Text("Applying") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(st.message + (st.secondsLeft?.let { " ${it}s" } ?: ""))
                }
            },
        )
        is ApplyStatus.Done -> AlertDialog(onDismissRequest = { m.dismiss() }, confirmButton = { TextButton(onClick = { m.dismiss() }) { Text("OK") } }, text = { Text(st.message) })
        is ApplyStatus.Failed -> AlertDialog(onDismissRequest = { m.dismiss() }, title = { Text("Apply failed") }, text = { Text(st.message) },
            confirmButton = { TextButton(onClick = { m.dismiss() }) { Text("Dismiss") } })
        is ApplyStatus.ConnectivityWarning -> AlertDialog(
            onDismissRequest = { m.dismiss() },
            title = { Text("Connectivity change") },
            text = { Text("Changes have been made to the existing connection via \"${st.iface}\". This could inhibit access to this device. Any IP change requires connecting to the new IP within ${st.rollbackSeconds} seconds to retain the changes.") },
            confirmButton = {
                Row {
                    TextButton(onClick = { scope.launch { m.apply(checked = true, skipConnectivityCheck = true) } }) { Text("Apply checked") }
                    TextButton(onClick = { scope.launch { m.apply(checked = false) } }) { Text("Apply unchecked", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(onClick = { m.dismiss() }) { Text("Cancel") } },
        )
        is ApplyStatus.RolledBack -> AlertDialog(
            onDismissRequest = { m.dismiss() },
            title = { Text("Configuration changes have been rolled back!") },
            text = { Text("The device could not be reached within ${st.rollbackSeconds} seconds after applying the pending changes, which caused the configuration to be rolled back for safety reasons. If you believe that the configuration changes are correct nonetheless, perform an unchecked configuration apply. Alternatively, you can dismiss this warning and edit changes before attempting to apply again, or revert all pending changes to keep the currently working configuration state.") },
            confirmButton = {
                Row {
                    TextButton(onClick = { scope.launch { m.revert() } }) { Text("Revert changes") }
                    TextButton(onClick = { scope.launch { m.apply(checked = false) } }) { Text("Apply unchecked", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(onClick = { m.dismiss() }) { Text("Dismiss") } },
        )
        is ApplyStatus.Unreachable -> AlertDialog(
            onDismissRequest = { m.dismiss() },
            title = { Text("Device unreachable!") },
            text = { Text("Could not regain access to the device after applying the configuration changes. You might need to reconnect if you modified network related settings such as the IP address or wireless security credentials.") },
            confirmButton = { TextButton(onClick = { m.dismiss() }) { Text("OK") } },
        )
    }
}
