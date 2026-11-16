@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.the412banner.wrtpanel.pages.firewall

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.ui.components.decodeEntities
import com.the412banner.wrtpanel.ui.components.htmlToAnnotated

/** Colours used for fmt() text and zone badges (LuCI bootstrap: var = italic blue, zonebadge = zone colour). */
data class FwColors(val dark: Boolean, val varColor: Color, val dim: Color, val text: Color)

@Composable
fun fwColors(): FwColors {
    val cs = MaterialTheme.colorScheme
    return FwColors(cs.surface.luminance() < 0.5f, cs.primary, cs.onSurfaceVariant, cs.onSurface)
}

fun zoneColor(hex: String): Color {
    val (r, g, b) = FwTool.rgb(hex)
    return Color(r, g, b)
}

private val BadgeInk = Color(0xFF1B1B1B)

fun badgeBackground(hex: String, c: FwColors): Color = zoneColor(hex).let { if (c.dark) it.copy(alpha = 0.38f) else it }
fun badgeInk(c: FwColors): Color = if (c.dark) c.text else BadgeInk

fun AnnotatedString.Builder.appendBadge(text: String, hex: String, strong: Boolean, c: FwColors) {
    withStyle(
        SpanStyle(
            background = badgeBackground(hex, c), color = badgeInk(c),
            fontWeight = if (strong) FontWeight.SemiBold else null, fontStyle = if (strong) null else FontStyle.Italic,
        ),
    ) { append(" $text ") }
}

/** Appends fwtool.fmt() HTML: var / strong / em styling, "cbi-tooltip" spans in parentheses, zone badges. */
fun AnnotatedString.Builder.appendFmt(f: FwTool.Fmt, c: FwColors) {
    val html = f.html
    val tag = Regex("<(/?)([a-zA-Z0-9]+)([^>]*)>")
    val stack = ArrayList<String>()
    var pos = 0
    for (m in tag.findAll(html)) {
        if (m.range.first > pos) append(decodeEntities(html.substring(pos, m.range.first)))
        pos = m.range.last + 1
        val closing = m.groupValues[1] == "/"
        val name = m.groupValues[2].lowercase()
        val attrs = m.groupValues[3]
        if (name == "br") { append("\n"); continue }
        if (closing) {
            when (stack.removeLastOrNull()) {
                "style" -> pop()
                "tip" -> { append(")"); pop() }
                else -> {}
            }
            continue
        }
        val classes = Regex("class=\"([^\"]*)\"").find(attrs)?.groupValues?.get(1)?.split(' ') ?: emptyList()
        val ph = Regex("data-fmt-placeholder=\"(\\d+)\"").find(attrs)
        when {
            ph != null -> {
                (f.values.getOrNull(ph.groupValues[1].toInt()) as? FwTool.ZoneBadge)?.let { appendBadge(it.text, it.color, it.strong, c) }
                stack.add("none")
            }
            "cbi-tooltip" in classes -> {
                pushStyle(SpanStyle(color = c.dim, fontStyle = FontStyle.Normal))
                append(" (")
                stack.add("tip")
            }
            name == "var" -> { pushStyle(SpanStyle(fontStyle = FontStyle.Italic, color = c.varColor)); stack.add("style") }
            name == "strong" || name == "b" -> { pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); stack.add("style") }
            name == "em" || name == "i" -> { pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); stack.add("style") }
            else -> stack.add("none")
        }
    }
    if (pos < html.length) append(decodeEntities(html.substring(pos)))
    while (stack.isNotEmpty()) {
        when (stack.removeAt(stack.size - 1)) {
            "style" -> pop()
            "tip" -> { append(")"); pop() }
        }
    }
}

fun fmtLines(lines: List<FwTool.Fmt>, c: FwColors): AnnotatedString = buildAnnotatedString {
    lines.filter { !it.isEmpty }.forEachIndexed { i, f ->
        if (i > 0) append("\n")
        appendFmt(f, c)
    }
}

/** Plain text of fmt() lines (read-only grid rows). */
fun fmtPlain(lines: List<FwTool.Fmt>): String =
    lines.filter { !it.isEmpty }.joinToString("\n") { f ->
        val withBadges = Regex("<span data-fmt-placeholder=\"(\\d+)\"></span>").replace(f.html) { m ->
            (f.values.getOrNull(m.groupValues[1].toInt()) as? FwTool.ZoneBadge)?.text ?: ""
        }
        com.the412banner.wrtpanel.ui.components.stripHtml(withBadges.replace(Regex("<span class=\"cbi-tooltip\">"), " (").replace("</span></var>", ")</var>"))
    }

/** A grid cell like LuCI's `<small>` match / action columns: a small caption and the styled lines. */
@Composable
fun RichCell(title: String?, lines: List<FwTool.Fmt>) {
    val c = fwColors()
    Column(Modifier.fillMaxWidth()) {
        if (title != null) Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(fmtLines(lines, c), style = MaterialTheme.typography.bodyMedium)
    }
}

/** `<span class="zonebadge">` */
@Composable
fun ZoneBadge(text: String, hex: String, strong: Boolean = true, suffix: String? = null) {
    val c = fwColors()
    Surface(shape = RoundedCornerShape(4.dp), color = badgeBackground(hex, c), contentColor = badgeInk(c)) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = if (strong) FontWeight.SemiBold else null, fontStyle = if (strong) null else FontStyle.Italic)) { append(text) }
                if (suffix != null) append(suffix)
            },
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/** `<label class="zonebadge zonebadge-empty">` */
@Composable
fun EmptyZoneBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp), color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), contentColor = MaterialTheme.colorScheme.outline,
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontStyle = FontStyle.Italic, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

/** One entry of a ZoneSelect: '' (device / unspecified), '*' (any zone) or a zone with its networks. */
data class ZoneChoice(val key: String, val text: String, val strong: Boolean, val suffix: String, val color: String, val ifaces: String?)

private fun ZoneChoice.annotated(c: FwColors, withIfaces: Boolean): AnnotatedString = buildAnnotatedString {
    appendBadge(text, color, strong, c)
    if (suffix.isNotEmpty()) withStyle(SpanStyle(color = badgeInk(c), background = badgeBackground(color, c))) { append(suffix.trimEnd() + " ") }
    if (withIfaces && ifaces != null) withStyle(SpanStyle(color = c.dim)) { append("  $ifaces") }
}

@Composable
private fun ZoneChoiceItem(z: ZoneChoice) {
    val c = fwColors()
    Column {
        Text(buildAnnotatedString { appendBadge(z.text, z.color, z.strong, c); if (z.suffix.isNotEmpty()) append(z.suffix) }, style = MaterialTheme.typography.bodyLarge)
        if (z.ifaces != null) Text(z.ifaces, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun fieldSupport(desc: String?, error: String?): (@Composable () -> Unit)? {
    if (error != null) return { Text(error, color = MaterialTheme.colorScheme.error) }
    if (desc.isNullOrBlank()) return null
    return { Text(htmlToAnnotated(desc)) }
}

@Composable
private fun HelpLine(desc: String?, error: String?) {
    if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
    else if (!desc.isNullOrBlank()) Text(htmlToAnnotated(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
}

/** widgets.ZoneSelect, single value: a dropdown showing zone badges with their networks. */
@Composable
fun ZoneSelectField(
    label: String?, value: String, choices: List<ZoneChoice>, enabled: Boolean, description: String?, error: String?,
    onChange: (String) -> Unit,
) {
    val c = fwColors()
    var expanded by remember { mutableStateOf(false) }
    val cur = choices.firstOrNull { it.key == value }
    val shown: AnnotatedString = when {
        cur != null -> cur.annotated(c, withIfaces = true)
        value.isNotEmpty() -> AnnotatedString(value)
        else -> AnnotatedString("unspecified", SpanStyle(fontStyle = FontStyle.Italic, color = c.dim))
    }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = shown.text, onValueChange = {}, readOnly = true, enabled = enabled, singleLine = true,
            label = label?.let { { Text(it) } }, isError = error != null,
            supportingText = fieldSupport(description, error),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            visualTransformation = VisualTransformation { TransformedText(shown, OffsetMapping.Identity) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { z ->
                DropdownMenuItem(
                    text = { ZoneChoiceItem(z) },
                    onClick = { onChange(z.key); expanded = false },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

/** widgets.ZoneSelect with multiple = true: zone badge chips plus a checklist. */
@Composable
fun ZoneMultiSelectField(
    label: String?, values: List<String>, choices: List<ZoneChoice>, enabled: Boolean, description: String?, error: String?,
    onChange: (List<String>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (label != null) Text(label, style = MaterialTheme.typography.bodyLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            values.forEach { k ->
                val z = choices.firstOrNull { it.key == k } ?: ZoneChoice(k, k, true, "", FwTool.zoneColor(k), null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZoneBadge(z.text, z.color, z.strong, z.suffix.ifEmpty { null })
                    if (enabled) IconButton(onClick = { onChange(values - k) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Outlined.Close, "Remove", Modifier.size(16.dp)) }
                }
            }
            if (values.isEmpty()) Text("unspecified", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { open = true }, enabled = enabled) { Icon(Icons.Outlined.Add, null); Text(if (values.isEmpty()) "Select" else "Change") }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    choices.forEach { z ->
                        val on = z.key in values
                        DropdownMenuItem(
                            text = { ZoneChoiceItem(z) },
                            leadingIcon = { Checkbox(checked = on, onCheckedChange = null) },
                            onClick = { onChange(if (on) values - z.key else values + z.key) },
                        )
                    }
                }
            }
        }
        HelpLine(description, error)
    }
}

/** fwtool.CBIProtocolSelect: protocol names, "Any" clears the others, free entry of protocol names / numbers. */
@Composable
fun ProtoSelectField(
    label: String?, values: List<String>, choices: List<Pair<String, String>>, enabled: Boolean, error: String?,
    validate: (String) -> String?, onChange: (List<String>) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf("") }
    var customErr by remember { mutableStateOf<String?>(null) }
    fun toggle(k: String) {
        val on = k in values
        onChange(
            when {
                k == "all" -> if (on) emptyList() else listOf("all")
                on -> values - k
                else -> (values - "all") + k
            },
        )
    }
    Column(Modifier.fillMaxWidth()) {
        if (label != null) Text(label, style = MaterialTheme.typography.bodyLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp),
        ) {
            values.forEach { k ->
                InputChip(
                    selected = true, enabled = enabled, onClick = { onChange(values - k) },
                    label = { Text(choices.firstOrNull { it.first == k }?.second ?: FwTool.lookupProto(k)?.label ?: k) },
                    trailingIcon = { Icon(Icons.Outlined.Close, "Remove", Modifier.size(16.dp)) },
                )
            }
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { open = true }, enabled = enabled) { Icon(Icons.Outlined.Add, null); Text(if (values.isEmpty()) "Select" else "Change") }
                DropdownMenu(open, onDismissRequest = { open = false }) {
                    choices.forEach { (k, l) ->
                        DropdownMenuItem(
                            text = { Text(l) },
                            leadingIcon = { Checkbox(checked = k in values, onCheckedChange = null) },
                            onClick = { toggle(k) },
                        )
                    }
                    HorizontalDivider()
                    Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            custom, { custom = it; customErr = null }, singleLine = true, label = { Text("Custom") },
                            isError = customErr != null, supportingText = customErr?.let { { Text(it) } }, modifier = Modifier.width(180.dp),
                        )
                        IconButton(onClick = {
                            val v = custom.trim()
                            if (v.isEmpty()) return@IconButton
                            val e = validate(v)
                            if (e != null) { customErr = e; return@IconButton }
                            if (v !in values) toggle(v)
                            custom = ""
                        }) { Icon(Icons.Outlined.Add, "Add") }
                    }
                }
            }
        }
        HelpLine(null, error)
    }
}

/** zones.js widgets.ZoneForwards: source zone ⇒ destination zones and the default forward policy. */
@Composable
fun ZoneForwardsView(src: ZoneChoice, dests: List<ZoneChoice>, policy: String) {
    Column(Modifier.fillMaxWidth()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ZoneBadge(src.text, src.color, true)
            Text("⇒", style = MaterialTheme.typography.titleMedium)
            dests.forEach { ZoneBadge(it.text, it.color, true) }
            EmptyZoneBadge(if (dests.isEmpty()) policy else "$policy all others")
        }
        if (src.ifaces != null) {
            Spacer(Modifier.size(2.dp))
            Text(src.ifaces, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
