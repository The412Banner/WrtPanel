package com.the412banner.wrtpanel.pages.firewall

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.the412banner.wrtpanel.form.SaveBar
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.SectionCard
import com.the412banner.wrtpanel.ui.theme.CodeStyle
import kotlinx.coroutines.launch

/** Network › Firewall › Custom Rules — port of luci-app-firewall view/firewall/custom.js. */
@Composable
fun FirewallCustomPage(ctx: PageContext) {
    var text by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        text = try { ctx.fs.read("/etc/firewall.user") } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { "" }
    }

    Box(Modifier.fillMaxSize()) {
        PageColumn {
            PageHeader(
                "Firewall - Custom Rules",
                "Custom rules allow you to execute arbitrary iptables commands which are not otherwise covered by the firewall framework. The commands are executed after each firewall restart, right after the default ruleset has been loaded.",
            )
            val t = text
            if (t == null) LoadingBox() else SectionCard {
                OutlinedTextField(
                    value = t, onValueChange = { text = it }, enabled = !ctx.readonly,
                    minLines = 25, textStyle = CodeStyle, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (!ctx.readonly && text != null) {
            SaveBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                busy = busy, showApply = false, showSave = true, showReset = false,
                onReset = {},
                onSave = { _, _ ->
                    scope.launch {
                        busy = true
                        val value = (text ?: "").trim().replace("\r\n", "\n") + "\n"
                        try {
                            ctx.fs.write("/etc/firewall.user", value)
                            text = value
                            ctx.toast("Contents have been saved.")
                            ctx.scope.launch { runCatching { ctx.fs.exec("/etc/init.d/firewall", listOf("restart")) } }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ctx.toast("Unable to save contents: ${e.message}")
                        }
                        busy = false
                    }
                },
            )
        }
    }
}
