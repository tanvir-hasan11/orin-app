package com.orin.app.tools

import com.orin.app.agent.OrinTool
import com.orin.app.agent.ToolApprovalRequest
import com.orin.app.data.MemoryEntity
import com.orin.app.data.OrinDao
import com.orin.app.security.MemoryRules
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

/** Long-term notes the user explicitly asks Orin to remember. */
class MemoryTool(private val dao: OrinDao) : OrinTool {
    override val name = "memory"
    override val description =
        "Remember a note ('remember'), recall it ('recall'), forget it ('forget'), or list notes ('list'). Secrets are refused."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "remember | recall | forget | list")
            }
            putJsonObject("key") { put("type", "string"); put("description", "Short label for the note") }
            putJsonObject("value") { put("type", "string"); put("description", "What to remember") }
        }
    }

    override fun approval(args: JsonObject): ToolApprovalRequest? = when (action(args)) {
        "remember" -> ToolApprovalRequest(
            name,
            "Save this to memory?",
            "${key(args)}\n\n${value(args).take(400)}",
        )
        "forget" -> ToolApprovalRequest(name, "Forget this note?", key(args))
        else -> null
    }

    override suspend fun execute(args: JsonObject): String = when (action(args)) {
        "remember" -> remember(args)
        "recall" -> recall(args)
        "forget" -> forget(args)
        else -> list()
    }

    private suspend fun remember(args: JsonObject): String {
        val key = key(args)
        val value = value(args)
        if (value.isEmpty()) return "Provide a 'value' to remember."
        MemoryRules.rejectionReason(key, value)?.let { return it }

        val existing = dao.findMemory(key)
        val id = existing?.id ?: UUID.randomUUID().toString()
        dao.upsertMemory(
            MemoryEntity(
                id = id,
                key = key,
                value = value,
                createdAtMs = System.currentTimeMillis(),
            ),
        )
        return if (existing == null) "Remembered: $key" else "Updated memory: $key"
    }

    private suspend fun recall(args: JsonObject): String {
        val query = key(args)
        if (query.isEmpty()) return "Provide a 'key' or query to recall."
        val exact = dao.findMemory(query)
        if (exact != null) return "${exact.key}: ${exact.value}"
        val matches = dao.searchMemories(query)
        return if (matches.isEmpty()) {
            "Nothing in memory matches '$query'."
        } else {
            matches.joinToString("\n") { "• ${it.key}: ${it.value}" }
        }
    }

    private suspend fun forget(args: JsonObject): String {
        val query = key(args)
        if (query.isEmpty()) return "Provide the 'key' to forget."
        val match = dao.findMemory(query) ?: dao.searchMemories(query).firstOrNull()
            ?: return "No memory matching '$query'."
        dao.deleteMemory(match.id)
        return "Forgot: ${match.key}"
    }

    private suspend fun list(): String {
        val notes = dao.observeMemories().first()
        return if (notes.isEmpty()) {
            "No saved memories."
        } else {
            notes.joinToString("\n") { "• ${it.key}: ${it.value.take(120)}" }
        }
    }

    private fun action(args: JsonObject): String =
        args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"

    private fun key(args: JsonObject): String =
        args["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    private fun value(args: JsonObject): String =
        args["value"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
}
