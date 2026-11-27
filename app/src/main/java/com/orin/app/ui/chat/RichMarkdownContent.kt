package com.orin.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class CodeFence(val lang: String, val code: String) : MdBlock
    data class Callout(val kind: String, val text: String) : MdBlock
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock
    data class BulletList(val items: List<String>) : MdBlock
    data class Paragraph(val text: String) : MdBlock
}

object MarkdownBlockParser {
    fun parse(markdown: String): List<MdBlock> {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                i++
                continue
            }
            if (trimmed.startsWith("```")) {
                val lang = trimmed.removePrefix("```").trim()
                val buf = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    buf.add(lines[i])
                    i++
                }
                if (i < lines.size) i++
                out.add(MdBlock.CodeFence(lang, buf.joinToString("\n")))
                continue
            }
            if (trimmed.startsWith("#")) {
                val hashes = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 3)
                out.add(MdBlock.Heading(hashes, trimmed.drop(hashes).trim()))
                i++
                continue
            }
            if (trimmed.startsWith(">")) {
                val quotes = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quotes.add(lines[i].trim().removePrefix(">").trim())
                    i++
                }
                val first = quotes.firstOrNull().orEmpty()
                val tag = Regex("""^\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]""").find(first)?.groupValues?.get(1) ?: "NOTE"
                val cleanText = quotes.joinToString("\n").replace(Regex("""^\[!\w+]\s*"""), "")
                out.add(MdBlock.Callout(tag, cleanText))
                continue
            }
            if (trimmed.contains('|') && i + 1 < lines.size &&
                lines[i + 1].trim().matches(Regex("""^\|?[\s:\-|]+\|?$"""))
            ) {
                val headers = splitPipeRow(trimmed)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().contains('|')) {
                    rows.add(splitPipeRow(lines[i].trim()))
                    i++
                }
                out.add(MdBlock.Table(headers, rows))
                continue
            }
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") ||
                trimmed.matches(Regex("""^\d+\.\s+.*"""))
            ) {
                val items = mutableListOf<String>()
                while (i < lines.size) {
                    val t = lines[i].trim()
                    if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") ||
                        t.matches(Regex("""^\d+\.\s+.*"""))
                    ) {
                        items.add(t)
                        i++
                    } else break
                }
                out.add(MdBlock.BulletList(items))
                continue
            }
            val para = mutableListOf<String>()
            while (i < lines.size) {
                val t = lines[i].trim()
                if (t.isEmpty() || t.startsWith("```") || t.startsWith("#") || t.startsWith(">") ||
                    t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ")
                ) break
                para.add(lines[i])
                i++
            }
            if (para.isNotEmpty()) out.add(MdBlock.Paragraph(para.joinToString("\n")))
        }
        return out
    }

    private fun splitPipeRow(row: String): List<String> =
        row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
}

@Composable
fun CollapsibleToolCard(summary: String) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val firstLine = summary.lineSequence().firstOrNull().orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainerLow)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (expanded) "Tools executed" else firstLine.ifBlank { "Tool activity" },
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RichMarkdownContent(
    markdown: String,
    onPreviewSnippet: (title: String, htmlOrMermaid: String, isMermaid: Boolean) -> Unit = { _, _, _ -> },
) {
    val blocks = remember(markdown) { MarkdownBlockParser.parse(markdown) }
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                        2 -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        else -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    }
                    Text(formatInline(block.text, scheme.primary), style = style, color = scheme.onSurface)
                }
                is MdBlock.CodeFence -> {
                    val langLow = block.lang.lowercase()
                    val previewable = langLow in setOf("html", "htm", "svg", "mermaid")
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(scheme.surfaceContainerHigh)
                            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp)),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(scheme.surfaceContainerLow)
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                block.lang.ifBlank { "code" },
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (previewable) {
                                Row(
                                    modifier = Modifier
                                        .clickable {
                                            onPreviewSnippet("Preview (${block.lang})", block.code, langLow == "mermaid")
                                        }
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Outlined.Visibility, contentDescription = "Preview", tint = scheme.primary, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(3.dp))
                                    Text("Preview", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .clickable { clipboard.setText(AnnotatedString(block.code)) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy", tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(3.dp))
                                Text("Copy", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(10.dp),
                        ) {
                            Text(
                                text = block.code,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
                                color = scheme.onSurface,
                            )
                        }
                    }
                }
                is MdBlock.Callout -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(scheme.primaryContainer.copy(alpha = 0.4f))
                            .border(1.dp, scheme.primary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    ) {
                        Text(block.kind, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = scheme.primary)
                        Spacer(Modifier.height(2.dp))
                        Text(formatInline(block.text, scheme.primary), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    }
                }
                is MdBlock.Table -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp)),
                    ) {
                        Row(Modifier.background(scheme.surfaceContainerHigh).padding(6.dp)) {
                            block.headers.forEach { h ->
                                Text(
                                    h,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = scheme.onSurface,
                                    modifier = Modifier.width(110.dp).padding(horizontal = 4.dp),
                                )
                            }
                        }
                        block.rows.forEach { row ->
                            Row(Modifier.padding(6.dp)) {
                                row.forEach { cell ->
                                    Text(
                                        cell,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = scheme.onSurfaceVariant,
                                        modifier = Modifier.width(110.dp).padding(horizontal = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                is MdBlock.BulletList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        block.items.forEach { item ->
                            Text(
                                formatInline(item, scheme.primary),
                                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp),
                                color = scheme.onSurface,
                            )
                        }
                    }
                }
                is MdBlock.Paragraph -> {
                    Text(
                        formatInline(block.text, scheme.primary),
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                        color = scheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun formatInline(text: String, accent: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end > i + 2) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(text.substring(i + 2, end))
                    pop()
                    i = end + 2
                } else {
                    append(text[i]); i++
                }
            }
            text.startsWith("`", i) -> {
                val end = text.indexOf('`', i + 1)
                if (end > i + 1) {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = accent))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            text.startsWith("*", i) -> {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(text.substring(i + 1, end))
                    pop()
                    i = end + 1
                } else {
                    append(text[i]); i++
                }
            }
            else -> {
                append(text[i])
                i++
            }
        }
    }
}
