package com.the412banner.wrtpanel.pages.system

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.the412banner.wrtpanel.form.SaveBar
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.ErrorBox
import com.the412banner.wrtpanel.ui.components.LoadingBox
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.SectionCard
import kotlinx.coroutines.launch

/** System › Scheduled Tasks — port of luci-mod-system view/system/crontab.js. */
@Composable
fun CrontabPage(ctx: PageContext) {
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tick) {
        error = null
        try { text = ctx.fs.readOrNull("/etc/crontabs/root") ?: "" } catch (e: Exception) { error = e.message ?: e.toString() }
    }
    Box(Modifier.fillMaxSize()) {
        PageColumn {
            PageHeader("Scheduled Tasks", "This is the system crontab in which scheduled tasks can be defined.")
            val t = text
            when {
                error != null && t == null -> ErrorBox(error!!) { tick++ }
                t == null -> LoadingBox()
                else -> SectionCard { FileTextArea(t, { text = it }, rows = 25, enabled = !ctx.readonly && !busy) }
            }
        }
        if (!ctx.readonly && text != null) {
            SaveBar(
                modifier = Modifier.align(Alignment.BottomCenter), busy = busy,
                showApply = false, showSave = true, showReset = false,
                onReset = {},
                onSave = { _, _ ->
                    val value = editorValue(text ?: "")
                    scope.launch {
                        busy = true
                        try {
                            ctx.fs.write("/etc/crontabs/root", value)
                            text = value
                            ctx.toast("Contents have been saved.")
                            ctx.fs.exec("/etc/init.d/cron", listOf("reload"))
                        } catch (e: Exception) {
                            ctx.toast("Unable to save contents: ${e.message}")
                        } finally { busy = false }
                    }
                },
            )
        }
    }
}
