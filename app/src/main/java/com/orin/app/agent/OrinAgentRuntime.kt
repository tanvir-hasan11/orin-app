package com.orin.app.agent

import android.content.Context
import com.orin.app.data.ConversationEntity
import com.orin.app.data.KeyStoreManager
import com.orin.app.data.MessageEntity
import com.orin.app.data.OrinDatabase
import com.orin.app.llm.LlmToolDeclaration
import com.orin.app.llm.LlmTurnMessage
import com.orin.app.llm.OrinLlmClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import java.util.UUID

interface OrinTool {
    val name: String
    val description: String
    val parameters: JsonObject
    suspend fun execute(args: JsonObject): String
}

/**
 * Clean autonomous agent loop for Orin (`orin-app`).
 * Streams tokens from Gemini / Anthropic / OpenAI / DeepSeek, executes tools, and persists to Room.
 */
class OrinAgentRuntime(
    context: Context,
    val keys: KeyStoreManager = KeyStoreManager(context),
    val db: OrinDatabase = OrinDatabase.get(context),
    private val llm: OrinLlmClient = OrinLlmClient(),
) {
    private val dao = db.dao()
    private val tools = mutableMapOf<String, OrinTool>()

    fun registerTool(tool: OrinTool) {
        tools[tool.name] = tool
    }

    fun observeConversations(): Flow<List<ConversationEntity>> = dao.observeConversations()

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> = dao.observeMessages(conversationId)

    suspend fun runTurn(
        conversationId: String,
        userText: String,
        onStreamChunk: suspend (String) -> Unit,
    ): String {
        val provider = keys.activeProviderId()
        val model = keys.activeModel()
        val apiKey = keys.getSecret("api_key_$provider")
            ?: return "No API key configured for $provider. Open Settings -> Models & API Keys to add your key."

        val title = userText.trim().take(48).ifEmpty { "New chat" }
        dao.upsertConversation(ConversationEntity(id = conversationId, title = title, updatedAtMs = System.currentTimeMillis()))
        dao.insertMessage(MessageEntity(id = UUID.randomUUID().toString(), conversationId = conversationId, isUser = true, text = userText))

        val history = dao.getMessages(conversationId).map {
            LlmTurnMessage(role = if (it.isUser) "user" else "assistant", content = it.text)
        }

        val declarations = tools.values.map {
            LlmToolDeclaration(name = it.name, description = it.description, parameters = it.parameters)
        }

        val systemPrompt = """
            You are Orin, Tanvir's personal J.A.R.V.I.S.-class AI assistant running natively on Android.
            Be sharp, calm, concise, and helpful. Never invent tool outputs.
        """.trimIndent()

        val result = llm.chatStream(
            provider = provider,
            model = model,
            apiKey = apiKey,
            systemPrompt = systemPrompt,
            messages = history,
            tools = declarations,
            onToken = onStreamChunk,
        )

        val reply = if (result.isSuccess) {
            val resp = result.getOrThrow()
            if (resp.toolCalls.isEmpty()) {
                resp.text.ifBlank { "(Empty response)" }
            } else {
                val outputs = mutableListOf<String>()
                for (call in resp.toolCalls) {
                    val tool = tools[call.name]
                    val out = tool?.execute(call.arguments) ?: "Unknown tool '${call.name}'."
                    outputs.add("[${call.name}]\n$out")
                }
                val toolText = outputs.joinToString("\n\n")
                if (resp.text.isNotBlank()) "${resp.text}\n\n$toolText" else toolText
            }
        } else {
            val err = result.exceptionOrNull()
            "Error ($provider / $model): ${err?.message}"
        }

        dao.insertMessage(MessageEntity(id = UUID.randomUUID().toString(), conversationId = conversationId, isUser = false, text = reply))
        return reply
    }
}
