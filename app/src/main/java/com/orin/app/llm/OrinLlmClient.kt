package com.orin.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
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
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Streaming LLM client with real tool-calling support for Gemini, Anthropic,
 * OpenAI and OpenAI-compatible providers (DeepSeek).
 */
class OrinLlmClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build(),
) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    override fun stream(
        provider: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        messages: List<LlmTurnMessage>,
        tools: List<LlmToolDeclaration>,
        baseUrl: String?,
    ): Flow<LlmEvent> = channelFlow {
        val cancelHandle = currentCoroutineContext().job.invokeOnCompletion {
            httpClient.dispatcher.cancelAll()
        }
        val channel = this
        try {
            val response = withContext(Dispatchers.IO) {
                when (provider.lowercase()) {
                    "gemini" -> channel.callGemini(model, apiKey, systemPrompt, messages, tools)
                    "anthropic" -> channel.callAnthropic(model, apiKey, systemPrompt, messages, tools)
                    "custom" -> {
                        val root = baseUrl?.trim().orEmpty()
                        if (root.isEmpty()) error("Add a Custom API base URL in Settings → Models & API Keys.")
                        val parsed = com.orin.app.security.UrlSafety.validateSyntax(root)
                        check(parsed.path.trim('/').isEmpty() || parsed.path.endsWith("/v1")) {
                            "Use the provider's base URL (for example https://api.example.com/v1)."
                        }
                        channel.callOpenAiCompatible(
                            url = "$root/chat/completions",
                            model = model,
                            apiKey = apiKey,
                            systemPrompt = systemPrompt,
                            messages = messages,
                            tools = tools,
                        )
                    }
                    "deepseek" -> channel.callOpenAiCompatible(
                        url = "https://api.deepseek.com/v1/chat/completions",
                        model = model,
                        apiKey = apiKey,
                        systemPrompt = systemPrompt,
                        messages = messages,
                        tools = tools,
                    )
                    else -> channel.callOpenAiCompatible(
                        url = "https://api.openai.com/v1/chat/completions",
                        model = model,
                        apiKey = apiKey,
                        systemPrompt = systemPrompt,
                        messages = messages,
                        tools = tools,
                    )
                }
            }
            send(LlmEvent.Completed(response))
        } finally {
            cancelHandle.dispose()
        }
    }

    // ------------------------------------------------------------------ Gemini

    private suspend fun SendChannel<LlmEvent>.callGemini(
        model: String,
        apiKey: String,
        systemPrompt: String,
        messages: List<LlmTurnMessage>,
        tools: List<LlmToolDeclaration>,
    ): LlmResponse {
        val modelId = model.removePrefix("models/")
        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemPrompt) })
                })
            })
            put("contents", buildJsonArray {
                for (msg in messages) {
                    when (msg.role) {
                        "tool" -> add(buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray {
                                add(buildJsonObject {
                                    putJsonObject("functionResponse") {
                                        put("name", msg.toolName.orEmpty())
                                        putJsonObject("response") {
                                            put("result", msg.content)
                                        }
                                    }
                                })
                            })
                        })
                        "assistant" -> {
                            val parts = buildJsonArray {
                                if (msg.content.isNotBlank()) {
                                    add(buildJsonObject { put("text", msg.content) })
                                }
                                for (call in msg.toolCalls) {
                                    add(buildJsonObject {
                                        putJsonObject("functionCall") {
                                            put("name", call.name)
                                            put("args", call.arguments)
                                        }
                                    })
                                }
                            }
                            if (parts.isNotEmpty()) {
                                add(buildJsonObject {
                                    put("role", "model")
                                    put("parts", parts)
                                })
                            }
                        }
                        else -> add(buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", msg.content) })
                                for (att in msg.attachments) {
                                    add(buildJsonObject {
                                        putJsonObject("inlineData") {
                                            put("mimeType", att.mimeType)
                                            put("data", att.base64Data)
                                        }
                                    })
                                }
                            })
                        })
                    }
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            for (tool in tools) {
                                add(buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.parameters)
                                })
                            }
                        })
                    })
                })
            }
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$modelId:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        val text = StringBuilder()
        val calls = ToolCallAccumulator()
        var callIndex = 0

        executeWithRetry(request).use { response ->
            if (!response.isSuccessful) error(httpError("Gemini", response))
            val source = response.body?.source() ?: error("Gemini returned an empty response body.")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                root["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull?.let {
                    error("Gemini blocked the request: $it")
                }
                val candidate = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: continue
                for (partElement in parts) {
                    val part = partElement.jsonObject
                    val chunk = part["text"]?.jsonPrimitive?.contentOrNull
                    if (!chunk.isNullOrEmpty()) {
                        text.append(chunk)
                        send(LlmEvent.Token(chunk))
                    }
                    val function = part["functionCall"]?.jsonObject
                    if (function != null) {
                        val name = function["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val args = function["args"]?.jsonObject ?: JsonObject(emptyMap())
                        if (name.isNotEmpty()) {
                            calls.accept(
                                index = callIndex,
                                id = "call_$callIndex",
                                name = name,
                                argumentsDelta = args.toString(),
                            )
                            callIndex++
                        }
                    }
                }
            }
        }
        return LlmResponse(text.toString(), calls.build())
    }

    // --------------------------------------------------------------- Anthropic

    private suspend fun SendChannel<LlmEvent>.callAnthropic(
        model: String,
        apiKey: String,
        systemPrompt: String,
        messages: List<LlmTurnMessage>,
        tools: List<LlmToolDeclaration>,
    ): LlmResponse {
        val anthropicMessages = mutableListOf<JsonObject>()
        var i = 0
        while (i < messages.size) {
            val message = messages[i]
            when (message.role) {
                "tool" -> {
                    val blocks = mutableListOf<JsonObject>()
                    while (i < messages.size && messages[i].role == "tool") {
                        val toolMessage = messages[i]
                        blocks += buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", toolMessage.toolCallId.orEmpty())
                            put("content", toolMessage.content)
                        }
                        i++
                    }
                    anthropicMessages += buildJsonObject {
                        put("role", "user")
                        put("content", JsonArray(blocks))
                    }
                }
                "assistant" -> {
                    val content = buildJsonArray {
                        if (message.content.isNotBlank()) {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", message.content)
                            })
                        }
                        for (call in message.toolCalls) {
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", call.id)
                                put("name", call.name)
                                put("input", call.arguments)
                            })
                        }
                    }
                    if (content.isNotEmpty()) {
                        anthropicMessages += buildJsonObject {
                            put("role", "assistant")
                            put("content", content)
                        }
                    }
                    i++
                }
                else -> {
                    if (message.attachments.isEmpty()) {
                        anthropicMessages += buildJsonObject {
                            put("role", "user")
                            put("content", message.content)
                        }
                    } else {
                        anthropicMessages += buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject { put("type", "text"); put("text", message.content) })
                                for (att in message.attachments) {
                                    val blockType = if (att.mimeType.startsWith("image/")) "image" else "document"
                                    add(buildJsonObject {
                                        put("type", blockType)
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", att.mimeType)
                                            put("data", att.base64Data)
                                        }
                                    })
                                }
                            })
                        }
                    }
                    i++
                }
            }
        }

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            put("system", systemPrompt)
            put("stream", true)
            put("messages", JsonArray(anthropicMessages))
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (tool in tools) {
                        add(buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("input_schema", tool.parameters)
                        })
                    }
                })
            }
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        val text = StringBuilder()
        val calls = ToolCallAccumulator()

        executeWithRetry(request).use { response ->
            if (!response.isSuccessful) error(httpError("Anthropic", response))
            val source = response.body?.source() ?: error("Anthropic returned an empty response body.")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                when (root["type"]?.jsonPrimitive?.contentOrNull) {
                    "content_block_start" -> {
                        val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val block = root["content_block"]?.jsonObject ?: continue
                        when (block["type"]?.jsonPrimitive?.contentOrNull) {
                            "tool_use" -> calls.accept(
                                index = index,
                                id = block["id"]?.jsonPrimitive?.contentOrNull,
                                name = block["name"]?.jsonPrimitive?.contentOrNull,
                            )
                            "text" -> block["text"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { text.append(it); send(LlmEvent.Token(it)) }
                        }
                    }
                    "content_block_delta" -> {
                        val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                        val delta = root["delta"]?.jsonObject ?: continue
                        when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                            "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { text.append(it); send(LlmEvent.Token(it)) }
                            "input_json_delta" -> calls.accept(
                                index = index,
                                argumentsDelta = delta["partial_json"]?.jsonPrimitive?.contentOrNull,
                            )
                        }
                    }
                    "error" -> {
                        val message = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                        error("Anthropic error: ${message ?: "unknown"}")
                    }
                }
            }
        }
        return LlmResponse(text.toString(), calls.build())
    }

    // ------------------------------------------------------ OpenAI / DeepSeek

    private suspend fun SendChannel<LlmEvent>.callOpenAiCompatible(
        url: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        messages: List<LlmTurnMessage>,
        tools: List<LlmToolDeclaration>,
    ): LlmResponse {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                for (message in messages) {
                    when (message.role) {
                        "tool" -> add(buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", message.toolCallId.orEmpty())
                            put("content", message.content)
                        })
                        "assistant" -> add(buildJsonObject {
                            if (message.content.isBlank()) put("content", JsonNull)
                            else put("content", message.content)
                            if (message.toolCalls.isNotEmpty()) {
                                put("tool_calls", buildJsonArray {
                                    for (call in message.toolCalls) {
                                        add(buildJsonObject {
                                            put("id", call.id)
                                            put("type", "function")
                                            putJsonObject("function") {
                                                put("name", call.name)
                                                put("arguments", call.arguments.toString())
                                            }
                                        })
                                    }
                                })
                            }
                        })
                        else -> add(buildJsonObject {
                            put("role", "user")
                            val imgAtts = message.attachments.filter { it.mimeType.startsWith("image/") }
                            if (imgAtts.isEmpty()) {
                                val note = if (message.attachments.isNotEmpty()) {
                                    "\n[Attached files: ${message.attachments.joinToString { it.name }}]"
                                } else ""
                                put("content", message.content + note)
                            } else {
                                put("content", buildJsonArray {
                                    add(buildJsonObject { put("type", "text"); put("text", message.content) })
                                    for (att in imgAtts) {
                                        add(buildJsonObject {
                                            put("type", "image_url")
                                            putJsonObject("image_url") {
                                                put("url", "data:${att.mimeType};base64,${att.base64Data}")
                                            }
                                        })
                                    }
                                })
                            }
                        })
                    }
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (tool in tools) {
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            }
                        })
                    }
                })
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        val text = StringBuilder()
        val calls = ToolCallAccumulator()

        executeWithRetry(request).use { response ->
            if (!response.isSuccessful) error(httpError("OpenAI-compatible", response))
            val source = response.body?.source() ?: error("Provider returned an empty response body.")
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") continue
                val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                val delta = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("delta")?.jsonObject ?: continue
                delta["content"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { text.append(it); send(LlmEvent.Token(it)) }
                val toolCalls = delta["tool_calls"]?.jsonArray ?: continue
                for (toolCallElement in toolCalls) {
                    val toolCall = toolCallElement.jsonObject
                    val index = toolCall["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val function = toolCall["function"]?.jsonObject
                    calls.accept(
                        index = index,
                        id = toolCall["id"]?.jsonPrimitive?.contentOrNull,
                        name = function?.get("name")?.jsonPrimitive?.contentOrNull,
                        argumentsDelta = function?.get("arguments")?.jsonPrimitive?.contentOrNull,
                    )
                }
            }
        }
        return LlmResponse(text.toString(), calls.build())
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Retries only before any response body is consumed, so a partially
     * streamed answer is never duplicated.
     */
    private suspend fun executeWithRetry(request: Request): Response {
        var attempt = 0
        while (true) {
            val call = httpClient.newCall(request)
            try {
                val response = call.execute()
                val retryable = response.code == 429 || response.code in 500..599
                if (retryable && attempt < 2) {
                    val waitSeconds = response.header("Retry-After")
                        ?.toLongOrNull()
                        ?.coerceIn(1, 15)
                        ?: (1L shl attempt)
                    response.close()
                    attempt++
                    delay(waitSeconds * 1000)
                    continue
                }
                return response
            } catch (e: IOException) {
                if (attempt < 2) {
                    attempt++
                    delay(500L * attempt)
                    continue
                }
                throw e
            }
        }
    }

    private fun httpError(provider: String, response: Response): String {
        val detail = runCatching { response.body?.string().orEmpty().take(300) }.getOrDefault("")
        return when (response.code) {
            401, 403 -> "$provider authentication failed (HTTP ${response.code}). Check your API key."
            404 -> "$provider model or endpoint not found (HTTP 404). Check the model ID."
            429 -> "$provider rate limit reached (HTTP 429). Try again shortly."
            in 500..599 -> "$provider is temporarily unavailable (HTTP ${response.code})."
            else -> "$provider HTTP ${response.code}: $detail"
        }
    }
}
