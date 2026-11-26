package com.orin.app.tools

import android.content.Context
import com.orin.app.agent.OrinTool
import com.orin.app.data.AuditLogEntity
import com.orin.app.data.OrinDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

private fun toolSchema(vararg props: Pair<String, String>): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        for ((k, desc) in props) {
            putJsonObject(k) {
                put("type", "string")
                put("description", desc)
            }
        }
    }
}

class FileOpsTool(private val context: Context) : OrinTool {
    override val name = "file_ops"
    override val description = "Read, write, or list files in Orin's workspace ('list', 'read', 'write')."
    override val parameters = toolSchema(
        "action" to "One of: 'list' (default), 'read', or 'write'.",
        "path" to "Relative path inside workspace.",
        "content" to "File content for 'write'.",
    )

    private val workspaceRoot: File
        get() = File(context.filesDir, "workspace").apply { mkdirs() }

    private fun resolveSafe(rel: String): File {
        val clean = rel.trim().removePrefix("/")
        val target = File(workspaceRoot, clean).canonicalFile
        val root = workspaceRoot.canonicalFile
        require(target.path.startsWith(root.path)) { "Path escapes workspace." }
        return target
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        val path = args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        runCatching {
            when (action) {
                "read" -> {
                    val f = resolveSafe(path)
                    if (!f.exists() || !f.isFile) "File not found: $path"
                    else f.readText().take(12_000)
                }
                "write" -> {
                    val f = resolveSafe(path)
                    f.parentFile?.mkdirs()
                    val content = args["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    f.writeText(content)
                    "Wrote ${content.length} chars to $path."
                }
                else -> {
                    val dir = if (path.isBlank()) workspaceRoot else resolveSafe(path)
                    val entries = dir.listFiles()?.sortedBy { it.name }.orEmpty()
                    if (entries.isEmpty()) "Workspace is empty."
                    else entries.joinToString("\n") {
                        if (it.isDirectory) "📁 ${it.name}/" else "📄 ${it.name} (${it.length()} B)"
                    }
                }
            }
        }.getOrElse { "File error: ${it.message}" }
    }
}

class WorkspaceShellTool(
    private val context: Context,
    private val dao: OrinDao,
) : OrinTool {
    override val name = "shell"
    override val description = "Execute a shell command in Orin's workspace."
    override val parameters = toolSchema("command" to "Shell command to execute.")

    private val workspaceRoot: File
        get() = File(context.filesDir, "workspace").apply { mkdirs() }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val cmd = args["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (cmd.isEmpty()) return@withContext "Provide 'command'."

        val blocked = listOf("rm -rf /", ":(){ :|:& };:")
        if (blocked.any { cmd.contains(it) }) {
            return@withContext "Blocked dangerous command."
        }

        runCatching {
            val proc = ProcessBuilder("sh", "-c", cmd)
                .directory(workspaceRoot)
                .redirectErrorStream(true)
                .start()
            val finished = proc.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                dao.insertAuditLog(AuditLogEntity(UUID.randomUUID().toString(), "shell", "exec", "Timeout: $cmd", false))
                return@withContext "Command timed out after 20s."
            }
            val out = proc.inputStream.bufferedReader().readText().take(8_000)
            val code = proc.exitValue()
            dao.insertAuditLog(AuditLogEntity(UUID.randomUUID().toString(), "shell", "exec", "$cmd (exit $code)", code == 0))
            "Exit $code:\n${out.ifBlank { "(no output)" }}"
        }.getOrElse {
            "Shell error: ${it.message}"
        }
    }
}

class WebFetchTool(
    private val http: OkHttpClient = OkHttpClient(),
) : OrinTool {
    override val name = "web_fetch"
    override val description = "Fetch text/JSON from a public HTTPS URL."
    override val parameters = toolSchema("url" to "HTTPS URL to fetch.")

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val url = args["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (!url.startsWith("http")) return@withContext "Provide a valid http/https URL."
        runCatching {
            http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
                val body = resp.body?.string().orEmpty().take(10_000)
                "HTTP ${resp.code}:\n$body"
            }
        }.getOrElse { "Fetch error: ${it.message}" }
    }
}
