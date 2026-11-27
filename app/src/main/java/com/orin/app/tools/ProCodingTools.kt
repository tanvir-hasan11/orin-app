package com.orin.app.tools

import android.content.Context
import com.orin.app.agent.OrinTool
import com.orin.app.agent.ToolApprovalRequest
import com.orin.app.data.FileHistoryManager
import com.orin.app.data.KeyStoreManager
import com.orin.app.security.WorkspacePaths
import com.orin.app.workspace.WorkspaceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Computes a compact unified-diff style preview between [before] and [after].
 */
object TextDiff {
    fun summarize(path: String, before: String, after: String, maxLines: Int = 60): String {
        val a = before.lines()
        val b = after.lines()
        val n = a.size
        val m = b.size
        if (n * m > 250_000) {
            return "--- a/$path\n+++ b/$path\n(changed ${a.size} lines -> ${b.size} lines)"
        }
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val ops = mutableListOf<String>()
        var i = 0
        var j = 0
        var added = 0
        var removed = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    i++; j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    ops.add("- ${a[i]}"); removed++; i++
                }
                else -> {
                    ops.add("+ ${b[j]}"); added++; j++
                }
            }
        }
        while (i < n) { ops.add("- ${a[i]}"); removed++; i++ }
        while (j < m) { ops.add("+ ${b[j]}"); added++; j++ }

        return buildString {
            appendLine("--- a/$path (+$added / -$removed)")
            ops.take(maxLines).forEach { appendLine(it) }
            if (ops.size > maxLines) appendLine("... (${ops.size - maxLines} more diff lines)")
        }.trimEnd()
    }
}

object ExactOrWhitespaceEdit {
    sealed class Result {
        data class Ok(val newText: String, val fuzzy: Boolean) : Result()
        data class Ambiguous(val count: Int) : Result()
        data class NotFound(val reason: String) : Result()
    }

    fun apply(content: String, target: String, replacement: String, replaceAll: Boolean): Result {
        if (target.isEmpty()) return Result.NotFound("target cannot be empty")
        val normalizedContent = content.replace("\r\n", "\n")
        val normalizedTarget = target.replace("\r\n", "\n")
        val normalizedReplacement = replacement.replace("\r\n", "\n")

        val exactMatches = countOccurrences(normalizedContent, normalizedTarget)
        if (exactMatches == 1 || (exactMatches > 1 && replaceAll)) {
            val out = if (replaceAll) {
                normalizedContent.replace(normalizedTarget, normalizedReplacement)
            } else {
                replaceSingle(normalizedContent, normalizedTarget, normalizedReplacement)
            }
            return Result.Ok(restoreCrLf(out, content), fuzzy = false)
        }
        if (exactMatches > 1) return Result.Ambiguous(exactMatches)

        // Trailing-whitespace-tolerant line match
        val fileLines = normalizedContent.split('\n')
        val targetLines = normalizedTarget.split('\n')
        if (targetLines.size <= fileLines.size) {
            val hitIndices = mutableListOf<Int>()
            for (idx in 0..(fileLines.size - targetLines.size)) {
                val matches = targetLines.indices.all { offset ->
                    fileLines[idx + offset].trimEnd() == targetLines[offset].trimEnd()
                }
                if (matches) hitIndices.add(idx)
            }
            if (hitIndices.size == 1 || (hitIndices.size > 1 && replaceAll)) {
                val mutable = fileLines.toMutableList()
                val repLines = normalizedReplacement.split('\n')
                for (hit in hitIndices.asReversed()) {
                    repeat(targetLines.size) { mutable.removeAt(hit) }
                    mutable.addAll(hit, repLines)
                }
                return Result.Ok(restoreCrLf(mutable.joinToString("\n"), content), fuzzy = true)
            }
            if (hitIndices.size > 1) return Result.Ambiguous(hitIndices.size)
        }
        return Result.NotFound("Target block not found")
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            val found = haystack.indexOf(needle, idx)
            if (found < 0) break
            count++
            idx = found + needle.length
        }
        return count
    }

    private fun replaceSingle(haystack: String, needle: String, rep: String): String {
        val idx = haystack.indexOf(needle)
        return haystack.substring(0, idx) + rep + haystack.substring(idx + needle.length)
    }

    private fun restoreCrLf(text: String, original: String): String =
        if (original.contains("\r\n")) text.replace("\n", "\r\n") else text
}

/** Precision targeted replacement inside a workspace file with diff preview & backup snapshot. */
class EditFileTool(
    private val context: Context,
    private val keys: KeyStoreManager,
    private val history: FileHistoryManager,
) : OrinTool {
    override val name = "edit_file"
    override val description = "Replace an exact target block in a workspace file (requires unique match unless replace_all=true). Saves backup snapshot."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string"); put("description", "Relative workspace file path") }
            putJsonObject("target") { put("type", "string"); put("description", "Exact existing text to replace") }
            putJsonObject("replacement") { put("type", "string"); put("description", "New text to insert") }
            putJsonObject("replace_all") { put("type", "boolean"); put("description", "Replace all occurrences (default false)") }
        }
    }

    override fun approval(args: JsonObject): ToolApprovalRequest? {
        val path = args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val target = args["target"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val rep = args["replacement"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return ToolApprovalRequest(
            toolName = name,
            title = "Edit $path?",
            detail = TextDiff.summarize(path.ifBlank { "file" }, target, rep, maxLines = 24),
        )
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val target = args["target"]?.jsonPrimitive?.contentOrNull ?: return@withContext "Provide 'target'."
        val rep = args["replacement"]?.jsonPrimitive?.contentOrNull ?: return@withContext "Provide 'replacement'."
        val replaceAll = args["replace_all"]?.jsonPrimitive?.booleanOrNull ?: false
        if (path.isEmpty()) return@withContext "Provide 'path'."

        val root = WorkspaceManager.activeRoot(context, keys)
        val file = runCatching { WorkspacePaths.resolve(root, path) }.getOrElse { return@withContext "Invalid path: ${it.message}" }
        if (!file.exists() || !file.isFile) return@withContext "File not found: $path"

        val before = file.readText()
        when (val res = ExactOrWhitespaceEdit.apply(before, target, rep, replaceAll)) {
            is ExactOrWhitespaceEdit.Result.NotFound -> "Edit failed in $path: ${res.reason}"
            is ExactOrWhitespaceEdit.Result.Ambiguous ->
                "Edit ambiguous in $path: matched ${res.count} times. Include more surrounding lines or set replace_all=true."
            is ExactOrWhitespaceEdit.Result.Ok -> {
                history.record(root, path, "before_edit_file")
                file.writeText(res.newText)
                history.record(root, path, "after_edit_file")
                val fuzzyNote = if (res.fuzzy) " (whitespace-tolerant)" else ""
                "Edited $path$fuzzyNote:\n${TextDiff.summarize(path, before, res.newText)}"
            }
        }
    }
}

/**
 * Unified diff parser & two-phase atomic patcher (validates all hunks before writing anything).
 */
object UnifiedPatchEngine {
    data class Hunk(val oldStart: Int, val rawLines: List<String>)
    data class FilePatch(val path: String, val isCreate: Boolean, val isDelete: Boolean, val hunks: List<Hunk>)

    fun parse(patchText: String): List<FilePatch> {
        val lines = patchText.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<FilePatch>()
        var idx = 0
        while (idx < lines.size) {
            val line = lines[idx]
            if (!line.startsWith("--- ")) {
                idx++
                continue
            }
            val oldHeader = line.removePrefix("--- ").trim().substringBefore('\t')
            if (idx + 1 >= lines.size || !lines[idx + 1].startsWith("+++ ")) {
                idx++
                continue
            }
            val newHeader = lines[idx + 1].removePrefix("+++ ").trim().substringBefore('\t')
            val isCreate = oldHeader == "/dev/null"
            val isDelete = newHeader == "/dev/null"
            val path = (if (isDelete) oldHeader else newHeader)
                .removePrefix("a/")
                .removePrefix("b/")
                .trim()
            idx += 2

            val hunks = mutableListOf<Hunk>()
            while (idx < lines.size && !lines[idx].startsWith("--- ")) {
                val hLine = lines[idx]
                if (hLine.startsWith("@@")) {
                    val m = Regex("""@@ -(\d+)""").find(hLine)
                    val oldStart = (m?.groupValues?.get(1)?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    idx++
                    val body = mutableListOf<String>()
                    while (idx < lines.size && !lines[idx].startsWith("@@") && !lines[idx].startsWith("--- ")) {
                        val l = lines[idx]
                        if (l.startsWith(" ") || l.startsWith("+") || l.startsWith("-")) {
                            body.add(l)
                        } else if (l.startsWith("\\ No newline")) {
                            // marker ignored
                        } else if (l.isNotEmpty()) {
                            break
                        }
                        idx++
                    }
                    hunks.add(Hunk(oldStart, body))
                } else {
                    idx++
                }
            }
            if (path.isNotBlank()) out.add(FilePatch(path, isCreate, isDelete, hunks))
        }
        return out
    }

    fun applyToText(original: String, hunks: List<Hunk>, path: String): String {
        val normalized = original.replace("\r\n", "\n")
        val hadTrailingNewline = normalized.endsWith("\n")
        val fileLines = if (normalized.isEmpty()) mutableListOf() else normalized.removeSuffix("\n").split('\n').toMutableList()
        var lineShift = 0

        hunks.forEachIndexed { hunkIdx, hunk ->
            val expectedOld = hunk.rawLines.filter { !it.startsWith("+") }.map { it.drop(1) }
            val replacement = hunk.rawLines.filter { !it.startsWith("-") }.map { it.drop(1) }

            val hintPos = (hunk.oldStart - 1 + lineShift).coerceIn(0, fileLines.size)
            val foundAt = findMatchPosition(fileLines, expectedOld, hintPos)
                ?: throw IllegalStateException("$path hunk #${hunkIdx + 1}: context/removed lines did not match current file.")

            repeat(expectedOld.size) { fileLines.removeAt(foundAt) }
            fileLines.addAll(foundAt, replacement)
            lineShift += replacement.size - expectedOld.size
        }

        val joined = fileLines.joinToString("\n")
        val finalText = if (hadTrailingNewline || fileLines.isEmpty()) {
            if (joined.isEmpty()) "" else "$joined\n"
        } else {
            joined
        }
        return if (original.contains("\r\n")) finalText.replace("\n", "\r\n") else finalText
    }

    private fun findMatchPosition(fileLines: List<String>, needle: List<String>, hint: Int): Int? {
        if (needle.isEmpty()) return hint.coerceIn(0, fileLines.size)
        val maxStart = fileLines.size - needle.size
        if (maxStart < 0) return null
        fun matchesAt(pos: Int): Boolean =
            needle.indices.all { i -> fileLines[pos + i].trimEnd() == needle[i].trimEnd() }
        if (hint <= maxStart && matchesAt(hint)) return hint
        for (delta in 1..maxOf(hint, maxStart - hint)) {
            if (hint - delta >= 0 && matchesAt(hint - delta)) return hint - delta
            if (hint + delta <= maxStart && matchesAt(hint + delta)) return hint + delta
        }
        return null
    }
}

class ApplyPatchTool(
    private val context: Context,
    private val keys: KeyStoreManager,
    private val history: FileHistoryManager,
) : OrinTool {
    override val name = "apply_patch"
    override val description = "Atomically apply a unified diff across one or more workspace files (supports --- /dev/null create, +++ /dev/null delete, and dry_run=true)."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("patch") { put("type", "string"); put("description", "Unified diff patch text") }
            putJsonObject("dry_run") { put("type", "boolean"); put("description", "Validate without writing") }
        }
    }

    override fun approval(args: JsonObject): ToolApprovalRequest? {
        if (args["dry_run"]?.jsonPrimitive?.booleanOrNull == true) return null
        val patch = args["patch"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return ToolApprovalRequest(
            toolName = name,
            title = "Apply unified diff patch?",
            detail = patch.take(700),
        )
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val patchText = args["patch"]?.jsonPrimitive?.contentOrNull ?: return@withContext "Provide 'patch'."
        val dryRun = args["dry_run"]?.jsonPrimitive?.booleanOrNull ?: false
        val root = WorkspaceManager.activeRoot(context, keys)
        val patches = UnifiedPatchEngine.parse(patchText)
        if (patches.isEmpty()) return@withContext "No valid file sections found in patch."

        data class Plan(val file: File, val relPath: String, val op: String, val nextContent: String?)
        val plans = mutableListOf<Plan>()
        val errors = mutableListOf<String>()

        for (fp in patches) {
            val resolved = runCatching { WorkspacePaths.resolve(root, fp.path) }
                .getOrElse { errors.add("${fp.path}: ${it.message}"); continue }
            when {
                fp.isCreate -> {
                    if (resolved.exists()) {
                        errors.add("${fp.path}: already exists")
                    } else {
                        val content = fp.hunks.flatMap { h -> h.rawLines.filter { it.startsWith("+") }.map { it.drop(1) } }
                            .joinToString("\n") + "\n"
                        plans.add(Plan(resolved, fp.path, "create", content))
                    }
                }
                fp.isDelete -> {
                    if (!resolved.exists() || !resolved.isFile) errors.add("${fp.path}: missing for delete")
                    else plans.add(Plan(resolved, fp.path, "delete", null))
                }
                else -> {
                    if (!resolved.exists() || !resolved.isFile) {
                        errors.add("${fp.path}: file not found")
                    } else {
                        runCatching {
                            UnifiedPatchEngine.applyToText(resolved.readText(), fp.hunks, fp.path)
                        }.onSuccess { updated ->
                            plans.add(Plan(resolved, fp.path, "patch", updated))
                        }.onFailure { err ->
                            errors.add(err.message ?: "${fp.path}: hunk mismatch")
                        }
                    }
                }
            }
        }

        if (errors.isNotEmpty()) {
            return@withContext "Patch NOT applied (atomic rollback; 0 files modified):\n" +
                errors.joinToString("\n") { "• $it" }
        }
        if (dryRun) {
            return@withContext "Dry-run OK (${plans.size} file(s) validated):\n" +
                plans.joinToString("\n") { "• ${it.op}: ${it.relPath}" }
        }

        plans.forEach { p ->
            when (p.op) {
                "create" -> {
                    p.file.parentFile?.mkdirs()
                    p.file.writeText(p.nextContent.orEmpty())
                    history.record(root, p.relPath, "apply_patch_create")
                }
                "delete" -> {
                    history.record(root, p.relPath, "before_apply_patch_delete")
                    p.file.delete()
                }
                "patch" -> {
                    history.record(root, p.relPath, "before_apply_patch")
                    p.file.writeText(p.nextContent.orEmpty())
                    history.record(root, p.relPath, "after_apply_patch")
                }
            }
        }
        "Applied patch atomically to ${plans.size} file(s):\n" +
            plans.joinToString("\n") { "• ${it.op}: ${it.relPath}" }
    }
}

class SearchFilesTool(
    private val context: Context,
    private val keys: KeyStoreManager,
) : OrinTool {
    override val name = "search_files"
    override val description = "Search workspace filenames ('glob') or text/regex content ('grep') with pagination."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("mode") { put("type", "string"); put("description", "'grep' (content regex) or 'glob' (filename wildcard)") }
            putJsonObject("pattern") { put("type", "string"); put("description", "Regex pattern for grep or glob e.g. '**/*.kt'") }
            putJsonObject("offset") { put("type", "integer"); put("description", "Result offset for pagination (default 0)") }
            putJsonObject("limit") { put("type", "integer"); put("description", "Max results (default 40)") }
        }
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val mode = args["mode"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "grep"
        val pattern = args["pattern"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 40).coerceIn(1, 120)
        if (pattern.isEmpty()) return@withContext "Provide 'pattern'."

        val root = WorkspaceManager.activeRoot(context, keys)
        val files = WorkspaceManager.listRelativeFiles(root, limit = 800)

        if (mode == "glob") {
            val regex = globToRegex(pattern)
            val hits = files.filter { regex.matches(it) }
            val page = hits.drop(offset).take(limit)
            return@withContext if (page.isEmpty()) {
                "No files matched glob '$pattern'."
            } else {
                "Matched ${hits.size} file(s) (showing ${offset + 1}..${offset + page.size}):\n" +
                    page.joinToString("\n")
            }
        }

        val regex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }
            .getOrElse { return@withContext "Invalid regex '$pattern': ${it.message}" }
        val hits = mutableListOf<String>()
        for (rel in files) {
            val f = File(root, rel)
            if (!f.isFile || f.length() > 512_000) continue
            runCatching {
                f.readLines().forEachIndexed { lineIdx, line ->
                    if (regex.containsMatchIn(line)) {
                        hits.add("$rel:${lineIdx + 1}: ${line.trim().take(160)}")
                    }
                }
            }
            if (hits.size >= offset + limit) break
        }
        val page = hits.drop(offset).take(limit)
        if (page.isEmpty()) "No content matches for '$pattern'."
        else "Matches (${offset + 1}..${offset + page.size}):\n" + page.joinToString("\n")
    }

    companion object {
        fun globToRegex(glob: String): Regex {
            val clean = glob.trim().replace('\\', '/').removePrefix("./")
            val sb = StringBuilder("^")
            var i = 0
            while (i < clean.length) {
                val c = clean[i]
                when {
                    c == '*' && i + 1 < clean.length && clean[i + 1] == '*' -> {
                        if (i + 2 < clean.length && clean[i + 2] == '/') {
                            sb.append("(?:.*/)?")
                            i += 3
                        } else {
                            sb.append(".*")
                            i += 2
                        }
                    }
                    c == '*' -> { sb.append("[^/]*"); i++ }
                    c == '?' -> { sb.append("[^/]"); i++ }
                    c in ".+()|^$\\[]{}" -> { sb.append('\\').append(c); i++ }
                    else -> { sb.append(c); i++ }
                }
            }
            sb.append("$")
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}

class FileHistoryTool(
    private val context: Context,
    private val keys: KeyStoreManager,
    private val history: FileHistoryManager,
) : OrinTool {
    override val name = "file_history"
    override val description = "Inspect snapshot versions of a workspace file ('list') or undo changes ('restore' by version_id)."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string"); put("description", "'list' or 'restore'") }
            putJsonObject("path") { put("type", "string"); put("description", "File path for 'list'") }
            putJsonObject("version_id") { put("type", "string"); put("description", "Snapshot ID for 'restore'") }
        }
    }

    private val fmt = DateTimeFormatter.ofPattern("MMM d HH:mm:ss")

    override fun approval(args: JsonObject): ToolApprovalRequest? {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        if (action != "restore") return null
        val vid = args["version_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return ToolApprovalRequest(
            toolName = name,
            title = "Restore file snapshot?",
            detail = "Restore snapshot ID: $vid (overwrites current workspace file; current state is backed up first).",
        )
    }

    override suspend fun execute(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        val root = WorkspaceManager.activeRoot(context, keys)
        return when (action) {
            "restore" -> {
                val vid = args["version_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (vid.isEmpty()) return "Provide 'version_id'."
                runCatching { history.restore(root, vid) }
                    .getOrElse { "Restore failed: ${it.message}" }
            }
            else -> {
                val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (path.isEmpty()) return "Provide 'path'."
                val versions = history.versions(root, path)
                if (versions.isEmpty()) "No snapshots recorded yet for '$path'."
                else versions.joinToString("\n") { v ->
                    val ts = Instant.ofEpochMilli(v.createdAt).atZone(ZoneId.systemDefault()).format(fmt)
                    "• [${v.id}] $ts · ${v.bytes} B · ${v.reason} · sha256=${v.sha256.take(10)}"
                }
            }
        }
    }
}
