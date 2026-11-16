package com.the412banner.wrtpanel.pages.system

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.arr
import com.the412banner.wrtpanel.core.get
import com.the412banner.wrtpanel.core.long
import com.the412banner.wrtpanel.core.obj
import com.the412banner.wrtpanel.core.str
import com.the412banner.wrtpanel.form.FormMap
import com.the412banner.wrtpanel.form.FormPage
import com.the412banner.wrtpanel.ui.PageContext
import com.the412banner.wrtpanel.ui.components.ResponsiveTable
import kotlinx.coroutines.launch
import java.util.Locale

private data class BlockDev(val dev: String, val uuid: String?, val label: String?, val type: String?, val size: Long?)

private data class MountInfo(val device: String, val mount: String, val size: Long, val avail: Long, val free: Long)

/** System › Mount Points — port of luci-mod-system view/system/mounts.js. */
@Composable
fun MountsPage(ctx: PageContext) {
    var reload by remember { mutableIntStateOf(0) }
    FormPage(ctx, reload) {
        val devicesJson = call("luci", "getBlockDevices").obj ?: emptyMap()
        val devices: List<BlockDev> = devicesJson.values.map { d ->
            BlockDev(d["dev"].str ?: "", d["uuid"].str, d["label"].str, d["type"].str, d["size"].long)
        }
        val devsSorted = devicesJson.keys.sorted().mapNotNull { k -> devicesJson[k]?.let { d -> BlockDev(d["dev"].str ?: "", d["uuid"].str, d["label"].str, d["type"].str, d["size"].long) } }
        val filesystems = sortedSetOf<String>()
        lines("/proc/filesystems").forEach { p -> if (Regex("\\S").containsMatchIn(p) && !Regex("^nodev\\t").containsMatchIn(p)) filesystems += p.trim() }
        lines("/etc/filesystems").forEach { e -> if (Regex("\\S").containsMatchIn(e)) filesystems += e.trim() }
        uci.load("fstab")
        if (uci.sections("fstab", "global").isEmpty()) uci.add("fstab", "global")

        fun matchDevice(sid: String): Pair<String, BlockDev?>? {
            val uuid = (uci.getString("fstab", sid, "uuid") ?: "").lowercase()
            if (uuid.isNotEmpty()) return "uuid" to devices.firstOrNull { (it.uuid ?: "-").lowercase() == uuid }
            val label = uci.getString("fstab", sid, "label")
            if (!label.isNullOrEmpty()) return "label" to devices.firstOrNull { it.label == label }
            val dev = uci.getString("fstab", sid, "device")
            if (!dev.isNullOrEmpty()) return "device" to devices.firstOrNull { it.dev == dev }
            return null
        }

        fun deviceText(sid: String): String? {
            val (kind, e) = matchDevice(sid) ?: return null
            return when (kind) {
                "uuid" -> {
                    val v = (uci.getString("fstab", sid, "uuid") ?: "").lowercase()
                    when {
                        e != null && e.size != null && e.size != 0L -> "UUID: $v (${e.dev}, ${fmtMiB(e.size)})"
                        e != null -> "UUID: $v (${e.dev})"
                        else -> "UUID: $v (<em>not present</em>)"
                    }
                }
                "label" -> {
                    val v = uci.getString("fstab", sid, "label")
                    when {
                        e != null && e.size != null && e.size != 0L -> "Label: $v (${e.dev}, ${fmtMiB(e.size)})"
                        e != null -> "Label: $v (${e.dev})"
                        else -> "Label: $v (<em>not present</em>)"
                    }
                }
                else -> {
                    val v = uci.getString("fstab", sid, "device")
                    when {
                        e != null && e.size != null && e.size != 0L -> "$v (${fmtMiB(e.size)})"
                        e != null -> "$v"
                        else -> "$v (<em>not present</em>)"
                    }
                }
            }
        }

        val m = FormMap("fstab", "Mount Points")

        m.typed("global", "Global Settings") {
            addremove = false; anonymous = true

            button("_detect", "Generate Config", "Find all currently attached filesystems and swap and replace configuration with defaults based on what was detected") {
                inputstyle = "reload"
                onClick = { _, _ ->
                    call("luci", "setBlockDetect")
                    reload++
                }
            }
            button("_mountall", "Mount attached devices", "Attempt to enable configured mount points for attached devices") {
                inputstyle = "reload"
                onClick = { _, _ ->
                    val res = fs.exec("/sbin/block", listOf("mount"))
                    if (res.code != 0) toast("The block mount command failed with code ${res.code}")
                    reload++
                }
            }
            flag("anon_swap", "Anonymous Swap", "Mount swap not specifically configured") { default = disabled; rmempty = false }
            flag("anon_mount", "Anonymous Mount", "Mount filesystems not specifically configured") { default = disabled; rmempty = false }
            flag("auto_swap", "Automount Swap", "Automatically mount swap on hotplug") { default = enabled; rmempty = false }
            flag("auto_mount", "Automount Filesystem", "Automatically mount filesystems on hotplug") { default = enabled; rmempty = false }
            flag("check_fs", "Check filesystems before mount", "Automatically check filesystem for errors before mounting") { default = disabled; rmempty = false }

            dummy("_mtab") {
                loadFn = { rt ->
                    rt.data["mounts"] = (call("luci", "getMountPoints")["result"].arr ?: emptyList()).map {
                        MountInfo(it["device"].str ?: "", it["mount"].str ?: "", it["size"].long ?: 0, it["avail"].long ?: 0, it["free"].long ?: 0)
                    }
                }
                render = { rt, _ ->
                    @Suppress("UNCHECKED_CAST")
                    val mounts = rt.data["mounts"] as? List<MountInfo> ?: emptyList()
                    val scope = rememberCoroutineScope()
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("Mounted file systems", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                        ResponsiveTable(
                            headers = listOf("Filesystem", "Mount Point", "Available", "Used", "Unmount"),
                            rows = mounts.map { tm ->
                                val used = tm.size - tm.free
                                val pct = if (tm.size == 0L) 0.0 else 100.0 / tm.size * used
                                listOf(
                                    tm.device, tm.mount,
                                    "${fmtMiB(tm.avail)} / ${fmtMiB(tm.size)}",
                                    String.format(Locale.US, "%.2f%% (%s)", pct, fmtMiB(used)),
                                    "",
                                )
                            },
                            weights = listOf(1.4f, 1.4f, 1.3f, 1.1f, 0.9f),
                            primaryColumn = 1,
                            empty = "Unable to obtain mount information",
                            cell = { r, c, t ->
                                if (c == 4) {
                                    val tm = mounts[r]
                                    val canUmount = !Regex("^/(overlay|rom|tmp(?:/.+)?|dev(?:/.+)?|)$").matches(tm.mount)
                                    if (canUmount) {
                                        OutlinedButton(
                                            enabled = !rt.isDisabled(this@dummy, ""),
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        rt.ctx.fs.exec("/bin/umount", listOf(tm.mount))
                                                        reload++
                                                    } catch (e: Exception) { rt.ctx.toast(e.message ?: e.toString()) }
                                                }
                                            },
                                        ) { Text("Unmount", color = MaterialTheme.colorScheme.error) }
                                    } else Text("-", style = MaterialTheme.typography.bodyMedium)
                                } else {
                                    Text(t.ifEmpty { "-" }, style = if (c == 1) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium)
                                }
                            },
                        )
                    }
                }
            }
        }

        m.grid("mount", "Mount Points", "Mount Points define at which point a memory device will be attached to the filesystem") {
            modalTitle = { "Mount Points - Mount Entry" }
            anonymous = true; addremove = true; sortable = true
            tab("general", "General Settings")
            tab("advanced", "Advanced Settings")

            flag("enabled", "Enabled", tab = "general") { rmempty = false; default = enabled; editable = true }
            dummy("_device", "Device", tab = "general") {
                modalonly = false
                textvalueFn = { _, sid -> deviceText(sid) }
            }
            value("uuid", "UUID", "If specified, mount the device by its UUID instead of a fixed device node", tab = "general") {
                modalonly = true
                value("", "-- match by uuid --")
                devsSorted.forEach { d ->
                    if (d.uuid != null && d.size != null && d.size != 0L) value(d.uuid, "${d.uuid} (${d.dev}, ${fmtMiB(d.size)})")
                    else if (d.uuid != null) value(d.uuid, "${d.uuid} (${d.dev})")
                }
            }
            value("label", "Label", "If specified, mount the device by the partition label instead of a fixed device node", tab = "general") {
                modalonly = true
                depends("uuid", "")
                value("", "-- match by label --")
                devsSorted.forEach { d ->
                    if (d.label != null && d.size != null && d.size != 0L) value(d.label, "${d.label} (${d.dev}, ${fmtMiB(d.size)})")
                    else if (d.label != null) value(d.label, "${d.label} (${d.dev})")
                }
            }
            value("device", "Device", "The device file of the memory or partition (<abbr title=\"for example\">e.g.</abbr> <code>/dev/sda1</code>)", tab = "general") {
                modalonly = true
                depends("uuid" to "", "label" to "")
                devsSorted.forEach { d ->
                    if (d.size != null && d.size != 0L) value(d.dev, "${d.dev} (${fmtMiB(d.size)})") else value(d.dev)
                }
            }
            value("target", "Mount point", "Specifies the directory the device is attached to", tab = "general") {
                value("/", "Use as root filesystem (/)")
                value("/overlay", "Use as external overlay (/overlay)")
                rmempty = false
            }
            dummy("__notice", "Root preparation", tab = "general") {
                depends("target", "/")
                modalonly = true
                default = "<p>Make sure to clone the root filesystem using something like the commands below:</p>" +
                    "<pre>" +
                    "mkdir -p /tmp/introot\n" +
                    "mkdir -p /tmp/extroot\n" +
                    "mount --bind / /tmp/introot\n" +
                    "mount /dev/sda1 /tmp/extroot\n" +
                    "tar -C /tmp/introot -cvf - . | tar -C /tmp/extroot -xf -\n" +
                    "umount /tmp/introot\n" +
                    "umount /tmp/extroot\n" +
                    "</pre>"
            }
            list("fstype", "Filesystem", tab = "advanced") {
                textvalueFn = { rt, sid ->
                    val dev = matchDevice(sid)?.second
                    var text = rt.uci.getString("fstab", sid, "fstype")?.ifEmpty { null } ?: "auto"
                    if (dev?.type != null && dev.type != text) text += " (${dev.type})"
                    text
                }
                value("", "auto")
                filesystems.forEach { value(it) }
            }
            value("options", "Mount options", "See \"mount\" manpage for details", tab = "advanced") {
                textvalueFn = { rt, sid -> rt.uci.getString("fstab", sid, "options")?.ifEmpty { null } ?: "defaults" }
                placeholder = "defaults"
            }
        }

        m.grid(
            "swap", "SWAP",
            "If your physical memory is insufficient unused data can be temporarily swapped to a swap-device resulting in a higher amount of usable <abbr title=\"Random Access Memory\">RAM</abbr>. Be aware that swapping data is a very slow process as the swap-device cannot be accessed with the high datarates of the <abbr title=\"Random Access Memory\">RAM</abbr>.",
        ) {
            modalTitle = { "Mount Points - Swap Entry" }
            anonymous = true; addremove = true; sortable = true
            val swapDevs = devsSorted.filter { !Regex("^/dev/(mtdblock|ubi|ubiblock)\\d").containsMatchIn(it.dev) }

            flag("enabled", "Enabled") { rmempty = false; editable = true }
            dummy("_device", "Device") {
                modalonly = false
                textvalueFn = { _, sid -> deviceText(sid) }
            }
            value("uuid", "UUID", "If specified, mount the device by its UUID instead of a fixed device node") {
                modalonly = true
                value("", "-- match by uuid --")
                swapDevs.forEach { d ->
                    if (d.uuid != null && d.size != null && d.size != 0L) value(d.uuid, "${d.uuid} (${d.dev}, ${fmtMiB(d.size)})")
                    else if (d.uuid != null) value(d.uuid, "${d.uuid} (${d.dev})")
                }
            }
            value("label", "Label", "If specified, mount the device by the partition label instead of a fixed device node") {
                modalonly = true
                depends("uuid", "")
                value("", "-- match by label --")
                swapDevs.forEach { d ->
                    if (d.label != null && d.size != null && d.size != 0L) value(d.label, "${d.label} (${d.dev}, ${fmtMiB(d.size)})")
                    else if (d.label != null) value(d.label, "${d.label} (${d.dev})")
                }
            }
            value("device", "Device", "The device file of the memory or partition (<abbr title=\"for example\">e.g.</abbr> <code>/dev/sda1</code>)") {
                modalonly = true
                depends("uuid" to "", "label" to "")
                swapDevs.forEach { d ->
                    if (d.size != null && d.size != 0L) value(d.dev, "${d.dev} (${fmtMiB(d.size)})") else value(d.dev)
                }
            }
        }
        m
    }
}
