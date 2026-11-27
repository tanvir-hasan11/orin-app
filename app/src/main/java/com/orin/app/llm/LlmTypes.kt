package com.orin.app.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

data class LlmTurnMessage(
    val role: String, // "user" | "assistant" | "tool"
    val content: String,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
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

sealed interface LlmEvent {
    data class Token(val text: String) : LlmEvent
    data class Completed(val response: LlmResponse) : LlmEvent
}

interface LlmClient {
    fun stream(
        provider: String,
        model: String,
        apiKey: String,
        systemPrompt: String,
        messages: List<LlmTurnMessage>,
        tools: List<LlmToolDeclaration> = emptyList(),
        baseUrl: String? = null,
    ): Flow<LlmEvent>
}

/**
 * Accumulates streamed tool-call fragments. OpenAI sends `arguments` as JSON
 * string fragments across chunks; Anthropic sends `input_json_delta` partials.
 * Both are indexed, so one accumulator works for either wire format.
 */
class ToolCallAccumulator {
    private class Partial(var id: String? = null, var name: String? = null) {
        val arguments = StringBuilder()
    }

    private val parts = sortedMapOf<Int, Partial>()

    fun accept(
        index: Int,
        id: String? = null,
        name: String? = null,
        argumentsDelta: String? = null,
    ) {
        val part = parts.getOrPut(index) { Partial() }
        if (!id.isNullOrBlank()) part.id = id
        if (!name.isNullOrBlank()) part.name = name
        if (!argumentsDelta.isNullOrBlank()) part.arguments.append(argumentsDelta)
    }

    fun build(): List<LlmToolCall> = parts.map { (index, part) ->
        LlmToolCall(
            id = part.id ?: "call_$index",
            name = part.name.orEmpty(),
            arguments = parseArguments(part.arguments.toString()),
        )
    }.filter { it.name.isNotBlank() }

    private fun parseArguments(raw: String): JsonObject {
        if (raw.isBlank()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(raw).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }
    }
}
