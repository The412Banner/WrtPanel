package com.the412banner.wrtpanel.pages.services

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormOption
import com.the412banner.wrtpanel.form.FormPage
import com.the412banner.wrtpanel.form.FormRuntime
import com.the412banner.wrtpanel.form.networkSelect
import com.the412banner.wrtpanel.ui.PageContext

private const val TEMPLATE = "/etc/ksmbd/ksmbd.conf.template"

/** Services › Network Shares — port of luci-app-ksmbd view/ksmbd.js. */
@Composable
fun KsmbdPage(ctx: PageContext) = FormPage(ctx) {
    val hasBlock = runCatching { fs.stat("/sbin/block") }.getOrNull() != null
    val hasFstab = runCatching { fs.stat("/etc/config/fstab") }.getOrNull() != null
    val mountdVer = runCatching { fs.exec("/usr/sbin/ksmbd.mountd", listOf("-V")).stdout }.getOrNull()
        ?.let { Regex("version : (\\S+)").find(it)?.groupValues?.get(1) }
    val kmodVer = runCatching { fs.exec("/sbin/modinfo", listOf("ksmbd")).stdout }.getOrNull()
        ?.let { Regex("vermagic:\t(\\S+)").find(it)?.groupValues?.get(1) }
    var template = runCatching { fs.read(TEMPLATE) }.getOrDefault("").trim()
    var pendingTemplate: String? = null

    var v = mountdVer?.takeIf { it.isNotEmpty() }?.trim() ?: "?"
    val v2 = kmodVer?.takeIf { it.isNotEmpty() }?.trim() ?: "?"
    if (v != v2) v = "$v/$v2"

    val m = FormMap("ksmbd", "Network Shares")

    m.typed("globals", "Ksmbd/Kmod Version $v") {
        anonymous = true
        tab("general", "General Settings")
        tab("template", "Edit Template", "Edit the template that is used for generating the ksmbd configuration.")

        networkSelect(
            "interface", "Interface", "Listen only on the given interface or, if unspecified, on lan",
            tab = "general", multiple = true, nocreate = false,
        ) {
            cfgvalueFn = { rt, sid -> UciStore.toArray(rt.uci.get("ksmbd", sid, "interface")) }
            writeFn = { rt, sid, value ->
                val oldNetworks = UciStore.toArray(rt.uci.get("ksmbd", sid, "interface")).sorted()
                val newNetworks = UciStore.toArray(value).sorted()
                if (oldNetworks.joinToString(" ") != newNetworks.joinToString(" ")) {
                    rt.uci.set("ksmbd", sid, "interface", newNetworks.joinToString(" "))
                }
            }
        }

        value("workgroup", "Workgroup", tab = "general") { placeholder = "WORKGROUP" }

        value("description", "Description", tab = "general") { placeholder = "Ksmbd on OpenWrt" }

        flag("allow_guest_ipc", "Allow guest on IPC$.", "Add optional guest access to IPC$ share, disabled by default", tab = "general")

        flag(
            "allow_legacy_protocols", "Allow legacy (insecure) protocols/authentication.",
            "Allow legacy smb(v1)/Lanman connections, needed for older devices without smb(v2.1/3) support.", tab = "general",
        )

        text(
            "_tmpl", null,
            "This is the content of the file '/etc/ksmbd/ksmbd.conf.template' from which your ksmbd configuration will be generated. " +
                "Values enclosed by pipe symbols ('|') should not be changed. They get their values from the 'General Settings' tab.",
            tab = "template",
        ) {
            rows = 20
            cfgvalueFn = { _, _ -> template }
            writeFn = { _, _, value -> pendingTemplate = value.toString().trim().replace("\r\n", "\n") + "\n" }
        }
    }

    m.beforeStage = {
        pendingTemplate?.let { data ->
            fs.write(TEMPLATE, data)
            template = data.trim()
            pendingTemplate = null
        }
    }

    m.table("share", "Shared Directories", "Please add directories to share. Each directory refers to a folder on a mounted device.") {
        anonymous = true
        addremove = true

        value("name", "Name")
        value("path", "Path") {
            if (hasBlock && hasFstab) {
                val o = this
                render = { rt, sid -> TitleRefField(rt, o, sid, "admin/system/mounts") }
            }
        }
        flag("browseable", "Browse-able") { enabled = "yes"; disabled = "no"; default = "yes" }
        flag("read_only", "Read-only") { enabled = "yes"; disabled = "no"; default = "no"; rmempty = false }
        flag("force_root", "Force Root")
        value("users", "Allowed users") { rmempty = true }
        flag("guest_ok", "Allow guests") { enabled = "yes"; disabled = "no"; default = "yes"; rmempty = false }
        flag("inherit_owner", "Inherit owner") { enabled = "yes"; disabled = "no"; default = "no" }
        flag("hide_dot_files", "Hide dot files") { enabled = "yes"; disabled = "no"; default = "yes" }
        value("create_mask", "Create mask") {
            default = "0666"; placeholder = "0666"; rmempty = false
            val o = this
            render = { rt, sid -> MaxLengthField(rt, o, sid, 4) }
        }
        value("dir_mask", "Directory mask") {
            default = "0777"; placeholder = "0777"; rmempty = false
            val o = this
            render = { rt, sid -> MaxLengthField(rt, o, sid, 4) }
        }
    }
    m
}

private fun fieldError(rt: FormRuntime, o: FormOption, sid: String): String? =
    rt.error(o, sid) ?: if (rt.isEdited(o, sid)) rt.validateField(o, sid) else null

/** form.Value with o.maxlength. */
@Composable
private fun MaxLengthField(rt: FormRuntime, o: FormOption, sid: String, maxlength: Int) {
    val err = fieldError(rt, o, sid)
    OutlinedTextField(
        value = rt.formString(o, sid),
        onValueChange = { rt.setValue(o, sid, it.take(maxlength)) },
        label = { Text(o.title ?: o.option) },
        placeholder = o.placeholder?.let { p -> { Text(p) } },
        enabled = !rt.isDisabled(o, sid),
        singleLine = true,
        isError = err != null,
        supportingText = err?.let { e -> { Text(e) } },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** form.Value with o.titleref: the title links to another page. */
@Composable
private fun TitleRefField(rt: FormRuntime, o: FormOption, sid: String, path: String) {
    val err = fieldError(rt, o, sid)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = rt.formString(o, sid),
            onValueChange = { rt.setValue(o, sid, it) },
            label = { Text(o.title ?: o.option) },
            placeholder = o.placeholder?.let { p -> { Text(p) } },
            enabled = !rt.isDisabled(o, sid),
            singleLine = true,
            isError = err != null,
            supportingText = err?.let { e -> { Text(e) } },
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { rt.ctx.nav.open(path) }) {
            Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Go to relevant configuration page")
        }
    }
}
