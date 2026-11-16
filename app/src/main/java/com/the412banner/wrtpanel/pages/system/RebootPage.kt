package com.the412banner.wrtpanel.pages.system

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.int
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.SectionCard
import kotlinx.coroutines.launch

/** System › Reboot — port of luci-mod-system view/system/reboot.js. */
@Composable
fun RebootPage(ctx: PageContext) {
    val changes by ctx.session.changes.changes.collectAsState()
    var rebooting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    PageColumn {
        PageHeader("Reboot", "Reboots the operating system of your device")
        SectionCard {
            if (changes.values.any { it.isNotEmpty() }) {
                AlertBox("Warning: There are unsaved changes that will get lost on reboot!")
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
            }
            Button(
                enabled = !ctx.readonly && !busy,
                onClick = {
                    scope.launch {
                        if (!ctx.dialogs.ask("Reboot", "Reboots the operating system of your device", "Perform reboot", destructive = true)) return@launch
                        busy = true
                        try {
                            val res = ctx.call("system", "reboot")["result"].int ?: 0
                            if (res != 0) { ctx.toast("The reboot command failed with code $res"); return@launch }
                            rebooting = true
                        } catch (e: Exception) {
                            ctx.toast(e.message ?: e.toString())
                        } finally { busy = false }
                    }
                },
            ) { Text("Perform reboot") }
        }
    }
    if (rebooting) {
        ReconnectDialog(
            ctx, "Rebooting…", "Waiting for device...",
            unreachableAfterMs = 150_000, unreachableText = "Device unreachable! Still waiting for device...",
        )
    }
}
