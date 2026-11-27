package com.orin.app.mcp

import android.content.Context
import com.orin.app.agent.OrinTool
import com.orin.app.agent.ToolApprovalRequest
import com.orin.app.data.KeyStoreManager
import com.orin.app.security.UrlSafety
import com.orin.app.workspace.WorkspaceManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val transport: String, // "http" | "sse" | "stdio"
    val target: String, // URL or shell command
    val hasAuthHeader: Boolean = false,
)

object McpJsonRpc {
    private val seq = AtomicInteger(1)

    fun buildRequest(method: String, params: JsonObject = JsonObject(emptyMap()), id: Int = seq.getAndIncrement()): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
}

class McpClientManager(
    private val context: Context,
    private val keys: KeyStoreManager,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun listServers(): List<McpServerConfig> {
        val raw = keys.getSecret("mcp_servers_json") ?: return emptyList()
        return runCatching { json.decodeFromString<List<McpServerConfig>>(raw) }.getOrDefault(emptyList())
    }

    fun addServer(name: String, transport: String, target: String, authHeader: String? = null): McpServerConfig {
        val id = UUID.randomUUID().toString().take(8)
        val cleanTransport = when (transport.lowercase()) {
            "stdio" -> "stdio"
            "sse" -> "sse"
            else -> "http"
        }
        val cfg = McpServerConfig(id, name.trim(), cleanTransport, target.trim(), !authHeader.isNullOrBlank())
        if (!authHeader.isNullOrBlank()) {
            keys.putSecret("mcp_auth_$id", authHeader.trim())
        }
        val updated = listServers() + cfg
        keys.putSecret("mcp_servers_json", json.encodeToString(updated))
        return cfg
    }

    fun removeServer(id: String): Boolean {
        val list = listServers()
        val next = list.filterNot { it.id == id }
        if (next.size == list.size) return false
        keys.putSecret("mcp_servers_json", json.encodeToString(next))
        keys.putSecret("mcp_auth_$id", "")
        return true
    }

    suspend fun rpc(server: McpServerConfig, method: String, params: JsonObject = JsonObject(emptyMap())): String =
        withContext(Dispatchers.IO) {
            val reqJson = McpJsonRpc.buildRequest(method, params)
            val auth = keys.getSecret("mcp_auth_${server.id}")
            when (server.transport) {
                "stdio" -> rpcStdio(server.target, reqJson)
                "sse" -> rpcHttpOrSse(server.target, auth, reqJson)
                else -> rpcHttpOrSse(server.target, auth, reqJson)
            }
        }

    private suspend fun rpcHttpOrSse(urlStr: String, authHeader: String?, reqJson: JsonObject): String {
        UrlSafety.validate(urlStr)
        val req = Request.Builder()
            .url(urlStr)
            .header("Accept", "application/json, text/event-stream")
            .apply { if (!authHeader.isNullOrBlank()) header("Authorization", authHeader) }
            .post(reqJson.toString().toRequestBody(jsonMedia))
            .build()

        http.newCall(req).execute().use { resp ->
            val contentType = resp.header("Content-Type").orEmpty().lowercase()
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) error("MCP HTTP ${resp.code}: ${body.take(200)}")
            if (contentType.contains("text/event-stream")) {
                return extractSseData(body)
            }
            return body
        }
    }

    private suspend fun rpcStdio(command: String, reqJson: JsonObject): String = withTimeout(20_000L) {
        val root = WorkspaceManager.activeRoot(context, keys)
        val proc = ProcessBuilder("sh", "-c", command).directory(root).start()
        try {
            val writer = BufferedWriter(OutputStreamWriter(proc.outputStream))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val initReq = McpJsonRpc.buildRequest(
                "initialize",
                buildJsonObject { put("protocolVersion", "2024-11-05") },
                id = 0,
            )
            writer.write(initReq.toString())
            writer.newLine()
            writer.write(reqJson.toString())
            writer.newLine()
            writer.flush()

            val targetId = reqJson["id"]?.jsonPrimitive?.contentOrNull
            while (true) {
                val line = reader.readLine() ?: error("MCP stdio EOF before reply")
                val parsed = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                if (parsed["id"]?.jsonPrimitive?.contentOrNull == targetId) {
                    return@withTimeout line
                }
            }
            @Suppress("UNREACHABLE_CODE")
            ""
        } finally {
            proc.destroyForcibly()
        }
    }

    private fun extractSseData(raw: String): String {
        val dataLines = raw.lineSequence()
            .filter { it.startsWith("data:") }
            .map { it.removePrefix("data:").trim() }
            .filter { it.isNotBlank() && it != "[DONE]" }
            .toList()
        return dataLines.lastOrNull() ?: raw
    }

    @Suppress("unused")
    suspend fun listenSseStream(urlStr: String, authHeader: String?): String = withTimeout(10_000L) {
        val deferred = CompletableDeferred<String>()
        val req = Request.Builder()
            .url(urlStr)
            .apply { if (!authHeader.isNullOrBlank()) header("Authorization", authHeader) }
            .build()
        val es = EventSources.createFactory(http).newEventSource(
            req,
            object : EventSourceListener() {
                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (!deferred.isCompleted) deferred.complete(data)
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    if (!deferred.isCompleted) deferred.completeExceptionally(t ?: RuntimeException("SSE failed"))
                }
            },
        )
        try {
            deferred.await()
        } finally {
            es.cancel()
        }
    }
}

class McpTool(private val manager: McpClientManager) : OrinTool {
    override val name = "mcp"
    override val description = "Connect to and call configured MCP (Model Context Protocol) servers: list_servers, list_tools, call_tool, add_server, remove_server."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string"); put("description", "list_servers, list_tools, call_tool, add_server, remove_server") }
            putJsonObject("server_id") { put("type", "string"); put("description", "Server ID or name") }
            putJsonObject("tool_name") { put("type", "string"); put("description", "MCP tool name for call_tool") }
            putJsonObject("arguments") { put("type", "object"); put("description", "JSON object arguments for call_tool") }
            putJsonObject("name") { put("type", "string"); put("description", "Display name for add_server") }
            putJsonObject("transport") { put("type", "string"); put("description", "http, sse, or stdio") }
            putJsonObject("target") { put("type", "string"); put("description", "Endpoint URL or stdio command") }
            putJsonObject("auth_header") { put("type", "string"); put("description", "Optional Authorization header") }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun approval(args: JsonObject): ToolApprovalRequest? {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: return null
        if (action in setOf("list_servers", "list_tools")) return null
        return ToolApprovalRequest(
            toolName = name,
            title = "MCP: $action",
            detail = args.toString().take(500),
        )
    }

    override suspend fun execute(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list_servers"
        return when (action) {
            "add_server" -> {
                val n = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val t = args["transport"]?.jsonPrimitive?.contentOrNull ?: "http"
                val tgt = args["target"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (n.isBlank() || tgt.isBlank()) return "Provide 'name' and 'target'."
                val auth = args["auth_header"]?.jsonPrimitive?.contentOrNull
                val cfg = manager.addServer(n, t, tgt, auth)
                "Added MCP server [${cfg.id}] ${cfg.name} (${cfg.transport})"
            }
            "remove_server" -> {
                val id = args["server_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (manager.removeServer(id)) "Removed MCP server $id." else "Server '$id' not found."
            }
            "list_tools" -> {
                val srv = findServer(args["server_id"]?.jsonPrimitive?.contentOrNull)
                    ?: return "Specify valid 'server_id' (run list_servers first)."
                runCatching {
                    val raw = manager.rpc(srv, "tools/list")
                    val tools = json.parseToJsonElement(raw).jsonObject["result"]
                        ?.jsonObject?.get("tools")?.jsonArray
                    if (tools == null) raw.take(2000)
                    else tools.joinToString("\n") { t ->
                        val obj = t.jsonObject
                        "• ${obj["name"]?.jsonPrimitive?.contentOrNull}: ${obj["description"]?.jsonPrimitive?.contentOrNull?.take(100).orEmpty()}"
                    }
                }.getOrElse { "MCP list_tools failed: ${it.message}" }
            }
            "call_tool" -> {
                val srv = findServer(args["server_id"]?.jsonPrimitive?.contentOrNull)
                    ?: return "Specify valid 'server_id'."
                val toolName = args["tool_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (toolName.isBlank()) return "Provide 'tool_name'."
                val toolArgs = args["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                val params = buildJsonObject {
                    put("name", toolName)
                    put("arguments", toolArgs)
                }
                runCatching { manager.rpc(srv, "tools/call", params).take(8_000) }
                    .getOrElse { "MCP call_tool failed: ${it.message}" }
            }
            else -> {
                val list = manager.listServers()
                if (list.isEmpty()) "No MCP servers configured. Add one in Settings → MCP Servers or use action='add_server'."
                else list.joinToString("\n") { "• [${it.id}] ${it.name} (${it.transport}): ${it.target}" }
            }
        }
    }

    private fun findServer(key: String?): McpServerConfig? {
        if (key.isNullOrBlank()) return null
        return manager.listServers().firstOrNull {
            it.id.equals(key, true) || it.name.equals(key, true)
        }
    }
}
