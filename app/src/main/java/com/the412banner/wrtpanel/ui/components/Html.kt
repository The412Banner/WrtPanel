package com.the412banner.wrtpanel.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

private val entityMap = mapOf(
    "&nbsp;" to " ", "&#160;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"",
    "&#39;" to "'", "&apos;" to "'", "&#8230;" to "…", "&hellip;" to "…", "&ndash;" to "–", "&mdash;" to "—",
)

fun decodeEntities(s: String): String {
    var out = s
    entityMap.forEach { (k, v) -> out = out.replace(k, v) }
    out = Regex("&#(\\d+);").replace(out) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: it.value }
    out = Regex("&#x([0-9a-fA-F]+);").replace(out) { it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: it.value }
    return out
}

/** Plain text of LuCI's small HTML snippets (titles, descriptions). */
fun stripHtml(s: String?): String {
    if (s == null) return ""
    val t = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>|</li>|</div>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "• ")
        .replace(Regex("<[^>]+>"), "")
    return decodeEntities(t).replace(Regex("\n{3,}"), "\n\n").trim()
}

/** Bold / italic / code / link styling for LuCI's description HTML. */
fun htmlToAnnotated(s: String?): AnnotatedString {
    if (s.isNullOrEmpty()) return AnnotatedString("")
    if (!s.contains('<') && !s.contains('&')) return AnnotatedString(s)
    val src = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>|</li>|</div>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<li[^>]*>", RegexOption.IGNORE_CASE), "• ")
    val tag = Regex("<(/?)([a-zA-Z0-9]+)([^>]*)>")
    return buildAnnotatedString {
        var pos = 0
        val stack = ArrayList<String>()
        for (m in tag.findAll(src)) {
            if (m.range.first > pos) append(decodeEntities(src.substring(pos, m.range.first)))
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val style = when (name) {
                "b", "strong" -> SpanStyle(fontWeight = FontWeight.SemiBold)
                "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
                "code", "tt", "kbd", "var", "pre" -> SpanStyle(fontFamily = FontFamily.Monospace)
                "a" -> SpanStyle(textDecoration = TextDecoration.Underline)
                "u", "ins" -> SpanStyle(textDecoration = TextDecoration.Underline)
                "del", "s" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                else -> null
            }
            if (style != null) {
                if (!closing) { pushStyle(style); stack.add(name) }
                else if (stack.lastOrNull() == name) { pop(); stack.removeAt(stack.size - 1) }
            }
            pos = m.range.last + 1
        }
        if (pos < src.length) append(decodeEntities(src.substring(pos)))
        while (stack.isNotEmpty()) { pop(); stack.removeAt(stack.size - 1) }
    }
}
