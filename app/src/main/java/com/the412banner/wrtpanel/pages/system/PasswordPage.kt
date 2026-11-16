package com.the412banner.wrtpanel.pages.system

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.bool
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.jsonOf
import com.the412banner.wrtpanel.form.SaveBar
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.PageColumn
import com.the412banner.wrtpanel.ui.components.PageHeader
import com.the412banner.wrtpanel.ui.components.SectionCard
import com.the412banner.wrtpanel.ui.theme.LocalStatusColors
import kotlinx.coroutines.launch

private enum class Strength { More, Strong, Medium, Weak }

/** password.js checkPassword(). */
private fun strengthOf(v: String): Strength {
    val strong = Regex("^(?=.{8,})(?=.*[A-Z])(?=.*[a-z])(?=.*[0-9])(?=.*\\W).*$")
    val medium = Regex("^(?=.{7,})(((?=.*[A-Z])(?=.*[a-z]))|((?=.*[A-Z])(?=.*[0-9]))|((?=.*[a-z])(?=.*[0-9]))).*$")
    val enough = Regex("(?=.{6,}).*")
    return when {
        !enough.containsMatchIn(v) -> Strength.More
        strong.containsMatchIn(v) -> Strength.Strong
        medium.containsMatchIn(v) -> Strength.Medium
        else -> Strength.Weak
    }
}

/** System › Administration › Router Password — port of luci-mod-system view/system/password.js. */
@Composable
fun PasswordPage(ctx: PageContext) {
    var pw1 by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var show1 by remember { mutableStateOf(false) }
    var show2 by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val status = LocalStatusColors.current

    fun save() {
        if (busy) return
        if (pw1.isEmpty()) return
        if (pw1 != pw2) { ctx.toast("Given password confirmation did not match, password not changed!"); return }
        scope.launch {
            busy = true
            try {
                val ok = ctx.call("luci", "setPassword", jsonOf("username" to "root", "password" to pw1))["result"].bool ?: false
                ctx.toast(if (ok) "The system password has been successfully changed." else "Failed to change the system password.")
                pw1 = ""; pw2 = ""
            } catch (e: Exception) {
                ctx.toast(e.message ?: e.toString())
            } finally { busy = false }
        }
    }

    val strength = if (pw1.isNotEmpty()) strengthOf(pw1) else null
    Box(Modifier.fillMaxSize()) {
        PageColumn {
            PageHeader("Router Password", "Changes the administrator password for accessing the device")
            SectionCard {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = pw1, onValueChange = { pw1 = it }, label = { Text("Password") }, singleLine = true,
                        enabled = !ctx.readonly,
                        visualTransformation = if (show1) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                        trailingIcon = { IconButton(onClick = { show1 = !show1 }) { Icon(if (show1) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Reveal/hide password") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = pw2, onValueChange = { pw2 = it }, label = { Text("Confirmation") }, singleLine = true,
                        enabled = !ctx.readonly,
                        visualTransformation = if (show2) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        trailingIcon = { IconButton(onClick = { show2 = !show2 }) { Icon(if (show2) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Reveal/hide password") } },
                        supportingText = strength?.let { st ->
                            {
                                val (label, color: Color) = when (st) {
                                    Strength.More -> "More Characters" to status.bad
                                    Strength.Strong -> "Strong" to status.ok
                                    Strength.Medium -> "Medium" to status.warn
                                    Strength.Weak -> "Weak" to status.bad
                                }
                                Text(buildAnnotatedString {
                                    append("Password strength: ")
                                    withStyle(SpanStyle(color = color)) { append(label) }
                                })
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        if (!ctx.readonly) {
            SaveBar(
                modifier = Modifier.align(Alignment.BottomCenter), busy = busy,
                showApply = false, showSave = true, showReset = false,
                onReset = {}, onSave = { _, _ -> save() },
            )
        }
    }
}
