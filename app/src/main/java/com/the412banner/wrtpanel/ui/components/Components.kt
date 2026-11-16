package com.the412banner.wrtpanel.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.the412banner.wrtpanel.ui.DialogHost
import com.the412banner.wrtpanel.ui.theme.CodeStyle
import com.the412banner.wrtpanel.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay

/** Max content width so pages stay readable on tablets / landscape. */
val ContentMaxWidth = 920.dp

@Composable
fun PageColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = { content(); Spacer(Modifier.height(96.dp)) },
        )
    }
}

@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    description: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        if (title != null || description != null) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
                    if (description != null) {
                        Text(
                            description, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                actions()
            }
        }
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun PageHeader(title: String?, description: String?) {
    if (title == null && description == null) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        if (title != null) Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        if (!description.isNullOrBlank()) {
            Text(
                htmlToAnnotated(description.replace(Regex("(<br\\s*/?>\\s*)+$", RegexOption.IGNORE_CASE), "")),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun InfoRow(label: String, value: String?, mono: Boolean = false, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Text(
            label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f).padding(end = 12.dp),
        )
        Box(Modifier.weight(0.58f)) {
            SelectionContainer {
                Text(
                    value?.ifEmpty { "-" } ?: "-",
                    style = if (mono) CodeStyle else MaterialTheme.typography.bodyMedium,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun InfoTable(rows: List<Pair<String, String?>>) {
    Column(Modifier.fillMaxWidth()) {
        rows.forEachIndexed { i, (k, v) ->
            InfoRow(k, v)
            if (i < rows.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }
    }
}

/** A labelled usage bar (memory, storage). */
@Composable
fun UsageBar(label: String, fraction: Float, text: String) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(600), label = "usage")
    val s = LocalStatusColors.current
    val color = when { fraction > 0.9f -> s.bad; fraction > 0.75f -> s.warn; else -> MaterialTheme.colorScheme.primary }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
        LinearProgressIndicator(
            progress = { f },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            drawStopIndicator = {},
        )
    }
}

enum class BadgeKind { Ok, Warn, Bad, Info, Neutral }

@Composable
fun StatusBadge(text: String, kind: BadgeKind = BadgeKind.Neutral) {
    val s = LocalStatusColors.current
    val c = when (kind) {
        BadgeKind.Ok -> s.ok; BadgeKind.Warn -> s.warn; BadgeKind.Bad -> s.bad; BadgeKind.Info -> s.info
        BadgeKind.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = c.copy(alpha = 0.14f), contentColor = c) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier, text: String? = null) {
    Box(modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
            if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)? = null) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onRetry != null) {
                TextButton(onClick = onRetry) { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Retry") }
            }
        }
    }
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Inbox, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(30.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Monospace, selectable, horizontally scrollable text (logs, rules, outputs). */
@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier, maxHeight: Dp? = null) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
    ) {
        val v = rememberScrollState()
        Box(
            Modifier
                .let { if (maxHeight != null) it.heightIn(max = maxHeight).verticalScroll(v) else it }
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            SelectionContainer { Text(text.ifEmpty { " " }, style = CodeStyle) }
        }
    }
}

/**
 * A table that shows real columns when there is room and stacked cards on narrow screens.
 * [rows] cells are plain strings; [cell] can override rendering of a cell.
 */
@Composable
fun ResponsiveTable(
    headers: List<String>,
    rows: List<List<String>>,
    modifier: Modifier = Modifier,
    weights: List<Float>? = null,
    minWideWidth: Dp = 600.dp,
    empty: String = "No entries",
    primaryColumn: Int = 0,
    cell: (@Composable (row: Int, col: Int, text: String) -> Unit)? = null,
    rowActions: (@Composable RowScope.(row: Int) -> Unit)? = null,
) {
    if (rows.isEmpty()) { EmptyState(empty, modifier); return }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= minWideWidth
        if (wide) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    headers.forEachIndexed { i, h ->
                        Text(
                            h, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(weights?.getOrNull(i) ?: 1f).padding(end = 8.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (rowActions != null) Spacer(Modifier.width(96.dp))
                }
                HorizontalDivider()
                rows.forEachIndexed { r, row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        row.forEachIndexed { c, t ->
                            Box(Modifier.weight(weights?.getOrNull(c) ?: 1f).padding(end = 8.dp)) {
                                if (cell != null) cell(r, c, t) else SelectionContainer { Text(t.ifEmpty { "-" }, style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                        if (rowActions != null) Row(Modifier.width(96.dp), horizontalArrangement = Arrangement.End) { rowActions(r) }
                    }
                    if (r < rows.size - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEachIndexed { r, row ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) {
                                    val t = row.getOrNull(primaryColumn).orEmpty()
                                    if (cell != null) cell(r, primaryColumn, t)
                                    else Text(t.ifEmpty { "-" }, style = MaterialTheme.typography.titleSmall)
                                }
                                if (rowActions != null) Row { rowActions(r) }
                            }
                            row.forEachIndexed { c, t ->
                                if (c == primaryColumn) return@forEachIndexed
                                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                    Text(
                                        headers.getOrNull(c).orEmpty(), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f),
                                    )
                                    Box(Modifier.weight(0.6f)) {
                                        if (cell != null) cell(r, c, t)
                                        else SelectionContainer { Text(t.ifEmpty { "-" }, style = MaterialTheme.typography.bodySmall) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Repeats [block] every [intervalMs] while composed; value keeps the last success. */
@Composable
fun <T> rememberPolled(vararg keys: Any?, intervalMs: Long, block: suspend () -> T): State<PollState<T>> {
    val state = remember(*keys) { mutableStateOf(PollState<T>(null, null, true)) }
    val current by rememberUpdatedState(block)
    LaunchedEffect(*keys) {
        while (true) {
            try {
                val v = current()
                state.value = PollState(v, null, false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = state.value.copy(error = e.message ?: e.toString(), loading = false)
            }
            if (intervalMs <= 0) break
            delay(intervalMs)
        }
    }
    return state
}

data class PollState<T>(val value: T?, val error: String?, val loading: Boolean)

/** Shows the pending [DialogHost] request. */
@Composable
fun DialogHostView(host: DialogHost) {
    val req = host.current.value ?: return
    AlertDialog(
        onDismissRequest = { if (req.dismissLabel != null) { req.result.complete(false); host.current.value = null } },
        title = { Text(req.title) },
        text = { Text(req.text) },
        confirmButton = {
            TextButton(
                onClick = { req.result.complete(true); host.current.value = null },
                colors = if (req.destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) { Text(req.confirmLabel) }
        },
        dismissButton = req.dismissLabel?.let { label ->
            { TextButton(onClick = { req.result.complete(false); host.current.value = null }) { Text(label) } }
        },
    )
}

@Composable
fun FadeIn(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(160))) { content() }
}

@Composable
fun ActionButton(text: String, onClick: () -> Unit, enabled: Boolean = true, destructive: Boolean = false, modifier: Modifier = Modifier) {
    FilledTonalButton(
        onClick = onClick, enabled = enabled, modifier = modifier,
        colors = if (destructive) ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) else ButtonDefaults.filledTonalButtonColors(),
    ) { Text(text) }
}

fun formatBytes(b: Long?, si: Boolean = false): String {
    if (b == null) return "-"
    val unit = if (si) 1000.0 else 1024.0
    val units = if (si) listOf("B", "kB", "MB", "GB", "TB") else listOf("B", "KiB", "MiB", "GiB", "TiB")
    var v = b.toDouble(); var i = 0
    while (v >= unit && i < units.size - 1) { v /= unit; i++ }
    return if (i == 0) "${b} B" else "%.2f %s".format(v, units[i])
}

/** LuCI's '%t' uptime formatting: "1d 2h 3m 4s". */
fun formatUptime(sec: Long?): String {
    if (sec == null) return "-"
    val d = sec / 86400; val h = (sec % 86400) / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    return buildString {
        if (d > 0) append("${d}d ")
        if (d > 0 || h > 0) append("${h}h ")
        if (d > 0 || h > 0 || m > 0) append("${m}m ")
        append("${s}s")
    }
}

val Transparent = Color.Transparent
