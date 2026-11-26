package com.orin.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.util.concurrent.TimeUnit

data class LlmTurnMessage(
    val role: String, // "user" | "assistant" | "system"
    val content: String,
)

data class LlmToolDeclaration(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
)

data class LlmResponse(
    val text: String,
    val toolCalls: List<LlmToolCall> = emptyList(),
)

/**
 * Clean streaming & tool-calling LLM client supporting Gemini, Anthropic, OpenAI, and DeepSeek.
 */
class OrinLlmClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun chatStream(
        provider: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        messages: List<LlmTurnMessage>,
        tools: List<LlmToolDeclaration> = emptyList(),
        onToken: suspend (String) -> Unit,
    ): Result<LlmResponse> = withContext(Dispatchers.IO) {
        runCatching {
            when (provider.lowercase()) {
                "gemini" -> callGemini(model, apiKey, systemPrompt, messages, tools, onToken)
                "anthropic" -> callAnthropic(model, apiKey, systemPrompt, messages, tools, onToken)
                "deepseek" -> callOpenAiCompatible(
                    url = "https://api.deepseek.com/v1/chat/completions",
                    model = model,
                    apiKey = apiKey,
                    systemPrompt = systemPrompt,
                    messages = messages,
                    tools = tools,
                    onToken = onToken,
                )
                else -> callOpenAiCompatible(
                    url = "https://api.openai.com/v1/chat/completions",
                    model = model,
                    apiKey = apiKey,
                    systemPrompt = systemPrompt,
                    messages = messages,
                    tools = tools,
                    onToken = onToken,
                )
            }
        }
    }

    private suspend fun callGemini(
        model: String,
        apiKey: String,
        systemPrompt: String,
        messages: List<LlmTurnMessage>,
        tools: List<LlmToolDeclaration>,
        onToken: suspend (String) -> Unit,
    ): LlmResponse {
        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", systemPrompt) })
                })
            })
            put("contents", buildJsonArray {
                for (msg in messages) {
                    add(buildJsonObject {
                        put("role", if (msg.role == "assistant") "model" else "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", msg.content) })
                        })
                    })
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            for (t in tools) {
                                add(buildJsonObject {
                                    put("name", t.name)
                                    put("description", t.description)
                                    put("parameters", t.parameters)
                                })
                            }
                        })
                    })
                })
            }
        }

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent?alt=sse&key=$apiKey"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        val textAcc = StringBuilder()
        val toolCalls = mutableListOf<LlmToolCall>()

        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                error("Gemini HTTP ${resp.code}: ${err.take(300)}")
            }
            val reader: BufferedReader = resp.body?.charStream()?.buffered() ?: error("Empty response body")
            reader.forEachLine { line ->
                if (!line.startsWith("data:")) return@forEachLine
                val payload = line.removePrefix("data:").trim()
                if (payload.isEmpty() || payload == "[DONE]") return@forEachLine
                val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return@forEachLine
                val parts = root["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject?.get("parts")?.jsonArray ?: return@forEachLine
                for (partEl in parts) {
                    val part = partEl.jsonObject
                    val chunk = part["text"]?.jsonPrimitive?.contentOrNull
                    if (!chunk.isNullOrEmpty()) {
                        textAcc.append(chunk)
                        kotlinx.coroutines.runBlocking { onToken(chunk) }
                    }
                    val fn = part["functionCall"]?.jsonObject
                    if (fn != null) {
                        val fnName = fn["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val fnArgs = fn["args"]?.jsonObject ?: JsonObject(emptyMap())
                        if (fnName.isNotEmpty()) {
                            toolCalls.add(LlmToolCall(id = "call_${toolCalls.size}", name = fnName, arguments = fnArgs))
                        }
                    }
                }
            }
        }
        return LlmResponse(textAcc.toString(), toolCalls)
    }

    private suspend fun callAnthropic(
        model: String,
        apiKey: String,
        systemPrompt: String,
        messages: List<LlmTurnMessage>,
        tools: List<LlmToolDeclaration>,
        onToken: suspend (String) -> Unit,
    ): LlmResponse {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            put("system", systemPrompt)
            put("stream", true)
            put("messages", buildJsonArray {
                for (msg in messages) {
                    add(buildJsonObject {
                        put("role", if (msg.role == "assistant") "assistant" else "user")
                        put("content", msg.content)
                    })
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (t in tools) {
                        add(buildJsonObject {
                            put("name", t.name)
                            put("description", t.description)
                            put("input_schema", t.parameters)
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

        val textAcc = StringBuilder()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                error("Anthropic HTTP ${resp.code}: ${err.take(300)}")
            }
            val reader = resp.body?.charStream()?.buffered() ?: error("Empty response body")
            reader.forEachLine { line ->
                if (!line.startsWith("data:")) return@forEachLine
                val payload = line.removePrefix("data:").trim()
                val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return@forEachLine
                if (root["type"]?.jsonPrimitive?.contentOrNull == "content_block_delta") {
                    val delta = root["delta"]?.jsonObject
                    val chunk = delta?.get("text")?.jsonPrimitive?.contentOrNull
                    if (!chunk.isNullOrEmpty()) {
                        textAcc.append(chunk)
                        kotlinx.coroutines.runBlocking { onToken(chunk) }
                    }
                }
            }
        }
        return LlmResponse(textAcc.toString())
    }

    private suspend fun callOpenAiCompatible(
        url: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        messages: List<LlmTurnMessage>,
        tools: List<LlmToolDeclaration>,
        onToken: suspend (String) -> Unit,
    ): LlmResponse {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                for (msg in messages) {
                    add(buildJsonObject {
                        put("role", msg.role)
                        put("content", msg.content)
                    })
                }
            })
            if (tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    for (t in tools) {
                        add(buildJsonObject {
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", t.name)
                                put("description", t.description)
                                put("parameters", t.parameters)
                            })
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

        val textAcc = StringBuilder()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string().orEmpty()
                error("OpenAI HTTP ${resp.code}: ${err.take(300)}")
            }
            val reader = resp.body?.charStream()?.buffered() ?: error("Empty response body")
            reader.forEachLine { line ->
                if (!line.startsWith("data:")) return@forEachLine
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]" || payload.isEmpty()) return@forEachLine
                val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return@forEachLine
                val delta = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                val chunk = delta?.get("content")?.jsonPrimitive?.contentOrNull
                if (!chunk.isNullOrEmpty()) {
                    textAcc.append(chunk)
                    kotlinx.coroutines.runBlocking { onToken(chunk) }
                }
            }
        }
        return LlmResponse(textAcc.toString())
    }
}
