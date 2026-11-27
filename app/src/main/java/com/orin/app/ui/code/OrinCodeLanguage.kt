package com.orin.app.ui.code

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Lightweight syntax highlighter for Sora CodeEditor mapped onto Material 3 colors.
 */
class OrinCodeLanguage(private val fileName: String) : EmptyLanguage() {

    private val analyzer = SimpleLineAnalyzer()

    override fun getAnalyzeManager(): AnalyzeManager = analyzer

    private inner class SimpleLineAnalyzer : SimpleAnalyzeManager<Unit>() {
        override fun analyze(text: StringBuilder, delegate: Delegate<Unit>): Styles {
            val builder = MappedSpans.Builder()
            val lines = text.toString().split('\n')
            val hashComments = fileName.endsWith(".py", true) ||
                fileName.endsWith(".sh", true) ||
                fileName.endsWith(".yml", true) ||
                fileName.endsWith(".yaml", true) ||
                fileName.endsWith(".toml", true)

            lines.forEachIndexed { lineIdx, line ->
                if (line.isEmpty()) {
                    builder.add(lineIdx, SpanFactory.obtain(0, style(EditorColorScheme.TEXT_NORMAL)))
                    return@forEachIndexed
                }
                var i = 0
                var lastStyle = -1L
                fun push(col: Int, s: Long) {
                    if (col == 0 || s != lastStyle) {
                        builder.add(lineIdx, SpanFactory.obtain(col, s))
                        lastStyle = s
                    }
                }
                while (i < line.length) {
                    val ch = line[i]
                    if ((ch == '/' && line.getOrNull(i + 1) == '/') ||
                        (hashComments && ch == '#')
                    ) {
                        push(i, style(EditorColorScheme.COMMENT, italic = true))
                        break
                    }
                    if (ch == '"' || ch == '\'') {
                        val quote = ch
                        push(i, style(EditorColorScheme.LITERAL))
                        i++
                        while (i < line.length) {
                            if (line[i] == '\\') { i += 2; continue }
                            if (line[i] == quote) { i++; break }
                            i++
                        }
                        continue
                    }
                    if (ch == '@' && line.getOrNull(i + 1)?.isLetter() == true) {
                        push(i, style(EditorColorScheme.ANNOTATION))
                        i++
                        while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                        continue
                    }
                    if (ch.isDigit()) {
                        push(i, style(EditorColorScheme.ATTRIBUTE_VALUE))
                        while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '.' || line[i] == '_')) i++
                        continue
                    }
                    if (ch.isLetter() || ch == '_') {
                        val start = i
                        while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
                        val word = line.substring(start, i)
                        val slot = when {
                            word in KEYWORDS -> style(EditorColorScheme.KEYWORD, bold = true)
                            word.first().isUpperCase() -> style(EditorColorScheme.IDENTIFIER_NAME)
                            else -> style(EditorColorScheme.TEXT_NORMAL)
                        }
                        push(start, slot)
                        continue
                    }
                    push(i, style(EditorColorScheme.TEXT_NORMAL))
                    i++
                }
            }
            builder.determine((lines.size - 1).coerceAtLeast(0))
            return Styles(builder.build())
        }

        private fun style(id: Int, bold: Boolean = false, italic: Boolean = false): Long =
            TextStyle.makeStyle(id, 0, bold, italic, false)
    }

    companion object {
        private val KEYWORDS = setOf(
            "package", "import", "class", "interface", "object", "data", "sealed", "enum",
            "fun", "val", "var", "return", "if", "else", "when", "for", "while", "do",
            "try", "catch", "finally", "throw", "suspend", "override", "private", "public",
            "internal", "const", "true", "false", "null", "function", "let", "export",
            "async", "await", "def", "from", "as", "with", "pass", "yield", "elif",
            "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "CREATE", "TABLE",
        )

        fun applyMaterialTheme(editor: CodeEditor, scheme: ColorScheme) {
            editor.colorScheme.apply {
                setColor(EditorColorScheme.WHOLE_BACKGROUND, scheme.surface.toArgb())
                setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, scheme.surfaceContainerLow.toArgb())
                setColor(EditorColorScheme.LINE_NUMBER, scheme.onSurfaceVariant.toArgb())
                setColor(EditorColorScheme.TEXT_NORMAL, scheme.onSurface.toArgb())
                setColor(EditorColorScheme.KEYWORD, scheme.primary.toArgb())
                setColor(EditorColorScheme.LITERAL, scheme.tertiary.toArgb())
                setColor(EditorColorScheme.COMMENT, scheme.onSurfaceVariant.copy(alpha = 0.65f).toArgb())
                setColor(EditorColorScheme.ANNOTATION, scheme.secondary.toArgb())
                setColor(EditorColorScheme.IDENTIFIER_NAME, scheme.secondary.toArgb())
                setColor(EditorColorScheme.ATTRIBUTE_VALUE, scheme.tertiary.toArgb())
                setColor(EditorColorScheme.SELECTION_INSERT, scheme.primary.toArgb())
                setColor(EditorColorScheme.SELECTION_HANDLE, scheme.primary.toArgb())
            }
        }
    }
}
