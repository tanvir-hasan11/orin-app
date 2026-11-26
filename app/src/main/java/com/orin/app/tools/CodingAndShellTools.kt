package com.orin.app.tools

import android.content.Context
import com.orin.app.agent.OrinTool
import com.orin.app.agent.ToolApprovalRequest
import com.orin.app.data.AuditLogEntity
import com.orin.app.data.KeyStoreManager
import com.orin.app.data.OrinDao
import com.orin.app.security.CommandPolicy
import com.orin.app.security.UrlSafety
import com.orin.app.security.WorkspacePaths
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
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
    override val description = "Read, write, or list files in Orin's private workspace ('list', 'read', 'write')."
    override val parameters = toolSchema(
        "action" to "One of: 'list' (default), 'read', or 'write'.",
        "path" to "Relative path inside the workspace.",
        "content" to "File content for 'write'.",
    )

    private val workspaceRoot: File
        get() = File(context.filesDir, "workspace").apply { mkdirs() }

    override fun approval(args: JsonObject): ToolApprovalRequest? {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        if (action != "write") return null
        val path = args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val content = args["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return ToolApprovalRequest(
            toolName = name,
            title = "Write a file?",
            detail = "Path: ${path.ifBlank { "(missing)" }}\n\n${content.take(600)}",
        )
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        val path = args["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        runCatching {
            when (action) {
                "read" -> {
                    if (path.isBlank()) return@runCatching "Provide a 'path' to read."
                    val file = WorkspacePaths.resolve(workspaceRoot, path)
                    when {
                        !file.exists() -> "File not found: $path"
                        !file.isFile -> "Not a file: $path"
                        file.length() > 1_000_000 -> "File is too large to read (${file.length()} bytes)."
                        else -> file.readText().take(12_000)
                    }
                }
                "write" -> {
                    if (path.isBlank()) return@runCatching "Provide a 'path' to write."
                    val file = WorkspacePaths.resolve(workspaceRoot, path)
                    file.parentFile?.mkdirs()
                    val content = args["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    file.writeText(content)
                    "Wrote ${content.length} chars to $path."
                }
                else -> {
                    val dir = if (path.isBlank()) workspaceRoot else WorkspacePaths.resolve(workspaceRoot, path)
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
    private val keys: KeyStoreManager,
) : OrinTool {
    override val name = "shell"
    override val description = "Execute a shell command inside Orin's private workspace. Requires user approval."
    override val parameters = toolSchema("command" to "Shell command to execute.")

    private val workspaceRoot: File
        get() = File(context.filesDir, "workspace").apply { mkdirs() }

    override fun approval(args: JsonObject): ToolApprovalRequest? {
        if (!keys.shellEnabled()) return null
        val command = args["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return ToolApprovalRequest(
            toolName = name,
            title = "Run a shell command?",
            detail = command.ifBlank { "(empty command)" },
        )
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        if (!keys.shellEnabled()) {
            return@withContext "Shell is disabled. Enable it in Settings → Advanced."
        }
        val command = args["command"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        CommandPolicy.rejectionReason(command)?.let { return@withContext it }

        runCatching {
            val tempDir = File(workspaceRoot, ".tmp").apply { mkdirs() }
            val builder = ProcessBuilder("sh", "-c", command)
                .directory(workspaceRoot)
                .redirectErrorStream(true)
            builder.environment()["HOME"] = workspaceRoot.absolutePath
            builder.environment()["TMPDIR"] = tempDir.absolutePath

            val process = builder.start()

            // Drain stdout/stderr concurrently: waiting first can deadlock once
            // the pipe buffer fills up.
            val captured = StringBuilder()
            val drainer = Thread {
                runCatching { captured.append(readCapped(process.inputStream, 8_000)) }
            }.apply {
                isDaemon = true
                name = "orin-shell-drain"
                start()
            }

            val finished = process.waitFor(20, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                drainer.join(1_000)
                dao.insertAuditLog(
                    AuditLogEntity(
                        UUID.randomUUID().toString(), "shell", "exec", "Timeout: $command", false,
                    ),
                )
                return@withContext "Command timed out after 20s."
            }
            drainer.join(2_000)
            val output = captured.toString()
            val code = process.exitValue()
            dao.insertAuditLog(
                AuditLogEntity(
                    UUID.randomUUID().toString(), "shell", "exec", "$command (exit $code)", code == 0,
                ),
            )
            "Exit $code:\n${output.ifBlank { "(no output)" }}"
        }.getOrElse {
            "Shell error: ${it.message}"
        }
    }

    private fun readCapped(stream: InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (output.size() < maxBytes) {
            val read = stream.read(buffer, 0, minOf(buffer.size, maxBytes - output.size()))
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

class WebFetchTool(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
) : OrinTool {
    override val name = "web_fetch"
    override val description = "Fetch text/JSON from a public HTTPS URL."
    override val parameters = toolSchema("url" to "HTTPS URL to fetch.")

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        var current = args["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (current.isEmpty()) return@withContext "Provide a 'url'."

        var hops = 0
        while (hops <= 3) {
            val url = try {
                UrlSafety.validate(current)
            } catch (e: Exception) {
                return@withContext "Blocked URL: ${e.message}"
            }
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Orin/1.0 (Android)")
                .get()
                .build()
            val response = try {
                http.newCall(request).execute()
            } catch (e: Exception) {
                return@withContext "Fetch error: ${e.message}"
            }
            response.use { resp ->
                if (resp.isRedirect) {
                    val location = resp.header("Location")
                    if (location.isNullOrBlank()) return@withContext "Redirect without Location."
                    current = url.toURI().resolve(location).toString()
                    hops++
                } else {
                    if (!resp.isSuccessful) return@withContext "HTTP ${resp.code}."
                    val contentType = resp.header("Content-Type").orEmpty().lowercase()
                    val supported = contentType.isEmpty() ||
                        contentType.startsWith("text/") ||
                        contentType.contains("json") ||
                        contentType.contains("xml")
                    if (!supported) return@withContext "Unsupported content type: $contentType"
                    val body = resp.body ?: return@withContext "Empty response body."
                    val source = body.source()
                    val buffer = okio.Buffer()
                    val maxBytes = 200_000L
                    while (buffer.size < maxBytes) {
                        val read = source.read(buffer, maxBytes - buffer.size)
                        if (read == -1L) break
                    }
                    return@withContext "HTTP ${resp.code}:\n${buffer.readUtf8().take(10_000)}"
                }
            }
        }
        "Too many redirects."
    }
}
