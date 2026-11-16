package com.the412banner.wrtpanel.pages.network.interfaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeviceHub
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.core.UciStore
import com.the412banner.wrtpanel.form.ChoiceOption
import com.the412banner.wrtpanel.form.ComboField
import com.the412banner.wrtpanel.form.Datatypes
import com.the412banner.wrtpanel.form.FormOption
import com.the412banner.wrtpanel.form.FormRuntime
import com.the412banner.wrtpanel.form.FormSection
import com.the412banner.wrtpanel.form.ListValueOption
import com.the412banner.wrtpanel.ui.components.htmlToAnnotated
import com.the412banner.wrtpanel.ui.components.stripHtml

/** Error shown under a field: a parse error, or the live validation result once edited. */
fun fieldError(rt: FormRuntime, o: FormOption, sid: String): String? =
    rt.error(o, sid) ?: if (rt.isEdited(o, sid)) rt.validateField(o, sid) else null

@Composable
private fun help(desc: String?, error: String?): (@Composable () -> Unit)? {
    if (error != null) return { Text(error, color = MaterialTheme.colorScheme.error) }
    if (desc.isNullOrBlank()) return null
    return { Text(htmlToAnnotated(desc)) }
}

private fun keyboard(dt: String?): KeyboardOptions = when {
    dt == null -> KeyboardOptions.Default
    Regex("^(u?integer|port|range\\(|min\\(|max\\(|ufloat|float)").containsMatchIn(dt) -> KeyboardOptions(keyboardType = KeyboardType.Number)
    dt.startsWith("ip") || dt.startsWith("cidr") || dt.startsWith("host") -> KeyboardOptions(keyboardType = KeyboardType.Uri)
    else -> KeyboardOptions.Default
}

/** The default text input of an option, with an optional trailing action. */
@Composable
fun OptionTextField(rt: FormRuntime, o: FormOption, sid: String, datatype: String? = o.datatype, trailing: (@Composable () -> Unit)? = null) {
    var show by remember { mutableStateOf(false) }
    val error = fieldError(rt, o, sid)
    val pwIcon: (@Composable () -> Unit)? = if (o.password) {
        { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show password") } }
    } else null
    val title = o.title?.let(::stripHtml)
    val ph = rt.placeholder(o, sid)
    OutlinedTextField(
        value = rt.formString(o, sid),
        onValueChange = { rt.setValue(o, sid, it) },
        label = title?.let { { Text(it) } },
        placeholder = ph?.let { { Text(it) } },
        enabled = !rt.isDisabled(o, sid),
        singleLine = true,
        isError = error != null,
        supportingText = help(o.description, error),
        visualTransformation = if (o.password && !show) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = trailing ?: pwIcon,
        keyboardOptions = keyboard(datatype),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The default combobox of a Value with choices. */
@Composable
fun OptionCombo(rt: FormRuntime, o: ChoiceOption, sid: String) {
    ComboField(
        value = rt.formString(o, sid), choices = o.choicesFor(rt, sid), label = o.title?.let(::stripHtml),
        onChange = { rt.setValue(o, sid, it) }, enabled = !rt.isDisabled(o, sid), supporting = o.description,
        error = fieldError(rt, o, sid), placeholder = rt.placeholder(o, sid),
    )
}

/** A list editor (DynamicList widget) bound to [o]; items are checked against [itemDatatype]. */
@Composable
fun OptionValueList(rt: FormRuntime, o: FormOption, sid: String, itemDatatype: String?, addPlaceholder: String?) {
    val items = rt.formList(o, sid).filter { it.isNotEmpty() }
    val disabled = rt.isDisabled(o, sid)
    var input by remember(sid) { mutableStateOf("") }
    var inputErr by remember(sid) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth()) {
        o.title?.let { Text(stripHtml(it), style = MaterialTheme.typography.bodyLarge) }
        items.forEachIndexed { i, v ->
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(v, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                    if (!disabled) IconButton(onClick = { rt.setValue(o, sid, items.filterIndexed { j, _ -> j != i }) }) {
                        Icon(Icons.Outlined.Delete, "Remove", Modifier.size(18.dp))
                    }
                }
            }
        }
        if (!disabled) {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it; inputErr = null }, singleLine = true,
                    placeholder = { Text(addPlaceholder ?: "Add value") }, isError = inputErr != null,
                    supportingText = inputErr?.let { { Text(it) } }, keyboardOptions = keyboard(itemDatatype), modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    val v = input.trim()
                    if (v.isEmpty()) return@IconButton
                    val e = itemDatatype?.let { Datatypes.validate(it, v) }
                    if (e != null) { inputErr = e; return@IconButton }
                    rt.setValue(o, sid, items + v); input = ""
                }) { Icon(Icons.Outlined.Add, "Add") }
            }
        }
        val err = fieldError(rt, o, sid)
        if (err != null) Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        else if (!o.description.isNullOrBlank()) Text(htmlToAnnotated(o.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
    }
}

/** Icons for LuCI's icons/<type>.svg device badges. */
fun deviceIcon(type: String?): ImageVector = when (type) {
    "bridge" -> Icons.Outlined.DeviceHub
    "wifi" -> Icons.Outlined.Wifi
    "vlan" -> Icons.Outlined.Lan
    "switch" -> Icons.Outlined.Router
    "tunnel" -> Icons.Outlined.VpnLock
    "wireguard" -> Icons.Outlined.VpnKey
    "alias" -> Icons.Outlined.Link
    "vrf" -> Icons.AutoMirrored.Outlined.AltRoute
    else -> Icons.Outlined.SettingsEthernet
}

@Composable
fun DeviceGlyph(type: String?, up: Boolean, modifier: Modifier = Modifier) {
    Icon(
        deviceIcon(type), null, modifier = modifier.size(18.dp),
        tint = if (up) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    )
}

fun zoneColor(name: String?): Color = Color(0xff000000 or Firewall.colorForName(name))

@Composable
fun SmallBox(content: @Composable () -> Unit) { Box(Modifier.padding(vertical = 2.dp)) { content() } }

// ---------------------------------------------------------------- widgets.js selectors

/** widgets.DeviceSelect as LuCI configures it in the network views. */
fun FormSection.devSelect(
    name: String, title: String? = null, description: String? = null, tab: String? = null,
    model: NetModel,
    multiple: Boolean = false, noaliases: Boolean = false, nobridges: Boolean = false, nocreate: Boolean = false,
    noinactive: Boolean = false, exclude: String? = null,
    filter: ((rt: FormRuntime, sid: String, value: String) -> Boolean)? = null,
    block: ChoiceOption.() -> Unit = {},
): ChoiceOption {
    val o: ChoiceOption = if (multiple) dynlist(name, title, description, tab) else value(name, title, description, tab)
    o.choicesFn = { rt, sid ->
        val out = ArrayList<Pair<String, String>>()
        if (!multiple && (o.rmempty || o.optional)) out += "" to "unspecified"
        val nets = model.getNetworks()
        for (d in model.devices) {
            val n = d.getName()
            val type = d.getType()
            if (n == "lo" || n == exclude || filter?.invoke(rt, sid, n) == false) continue
            if (noaliases && type == "alias") continue
            if (nobridges && type == "bridge") continue
            if (noinactive && !d.isUp()) continue
            val dnets = nets.filter { it.containsDevice(d.device) || it.getIfname() == d.device }
            out += n to (d.getI18n() + if (dnets.isNotEmpty()) " (${dnets.joinToString(", ") { it.sid }})" else "")
        }
        if (!noaliases) {
            for (net in nets) {
                val dev = model.instantiateDevice("@${net.sid}", net)
                val n = dev.getName()
                if (n == "@loopback" || n == exclude || filter?.invoke(rt, sid, n) == false) continue
                if (noinactive && !net.isUp()) continue
                out += n to dev.getI18n()
            }
        }
        if (!nocreate) {
            val cur = rt.formList(o, sid).filter { it.isNotEmpty() }
            cur.sortedWith { a, b -> naturalCompare(a, b) }.forEach { k ->
                if (out.none { it.first == k }) out += k to "Absent Interface: \"$k\""
            }
        }
        out
    }
    if (o is com.the412banner.wrtpanel.form.ValueOption) o.allowCustom = !nocreate
    o.block()
    return o
}

/** widgets.ZoneSelect for the interface firewall-zone assignment (free text creates a zone). */
fun FormSection.ifZoneSelect(name: String, title: String?, description: String?, tab: String?, model: NetModel, block: ChoiceOption.() -> Unit = {}): ChoiceOption {
    val o = value(name, title, description, tab)
    o.choicesFn = { rt, _ ->
        val out = ArrayList<Pair<String, String>>()
        if (o.rmempty || o.optional) out += "" to "unspecified"
        Firewall.zones(rt.uci).forEach { (zsid, zn) ->
            val n = zn ?: return@forEach
            val nets = Firewall.zoneNetworks(rt.uci, zsid)
            out += n to if (nets.isEmpty()) n else "$n: ${nets.joinToString(", ")}"
        }
        out
    }
    o.datatype = if (model.hasFeature("firewall4")) "ucifw4zonename" else "and(ucifw4zonename,maxlength(11))"
    o.block()
    return o
}

/** tools/network.js cbiFlagTristate. [sysfs] "!" prefix inverts; [flagDefault] = dev.flags value (null = unknown). */
class Tristate(val option: ListValueOption, val sysfs: String?) {
    var invert = false
    var path: String? = null
    /** null = LuCI's sysfs_default === null (plain "automatic"). */
    var sysDefined = true
    var sysDefault = false

    init {
        if (sysfs != null) {
            if (sysfs.startsWith("!")) { invert = true; path = sysfs.substring(1) } else path = sysfs
        }
    }

    fun applyRead(content: String?) {
        when ((content ?: "").trim()) {
            "0" -> sysDefault = invert
            "1" -> sysDefault = !invert
        }
    }

    val autoKey: String get() = if (!sysDefined) "" else if (sysDefault) "1" else "0"
    val autoLabel: String get() = if (!sysDefined) "automatic" else if (sysDefault) "automatic (enabled)" else "automatic (disabled)"
}

fun FormSection.tristate(
    name: String, title: String?, description: String? = null, tab: String?,
    sysfs: String? = null, flagDefault: Boolean? = null, flagKnown: Boolean = true,
    registry: MutableList<Tristate>,
    block: ListValueOption.() -> Unit = {},
): ListValueOption {
    val o = list(name, title, description, tab)
    val t = Tristate(o, sysfs)
    if (sysfs == null) {
        t.sysDefined = flagKnown
        t.sysDefault = flagDefault == true
    }
    o.choicesFn = { _, _ -> listOf(t.autoKey to t.autoLabel, "0!" to "disabled", "1!" to "enabled") }
    o.defaultFn = { _, _ -> t.autoKey }
    o.cfgvalueFn = { rt, sid -> rt.uci.getString("network", sid, o.ucioption ?: o.option)?.takeIf { it.isNotEmpty() }?.let { "$it!" } }
    o.writeFn = { rt, sid, v ->
        val opt = o.ucioption ?: o.option
        when (v.toString()) {
            "1!" -> rt.uci.set("network", sid, opt, "1")
            "0!" -> rt.uci.set("network", sid, opt, "0")
            else -> rt.uci.unset("network", sid, opt)
        }
    }
    registry += t
    o.block()
    return o
}

fun uciList(v: Any?): List<String> = UciStore.toArray(v)
