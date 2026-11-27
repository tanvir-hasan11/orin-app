package com.orin.app.tools

import com.orin.app.agent.OrinAgentRuntime
import com.orin.app.agent.OrinTool
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Spawns up to 4 isolated read-only or workspace-scoped child subagents in parallel.
 */
class SubagentTool(
    private val runtimeProvider: () -> OrinAgentRuntime,
) : OrinTool {
    override val name = "subagent"
    override val description = "Run an isolated read-only subagent worker ('task') or parallel list ('tasks', max 4) to investigate code, web search, or summarize data."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("task") { put("type", "string"); put("description", "Single subagent task description") }
            putJsonObject("tasks") {
                put("type", "array")
                put("description", "Optional list of up to 4 parallel subagent tasks")
                putJsonObject("items") { put("type", "string") }
            }
        }
    }

    override suspend fun execute(args: JsonObject): String {
        val batch = args["tasks"]?.let { el ->
            (el as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        }.orEmpty()
        val single = args["task"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val workItems = if (batch.isNotEmpty()) batch.take(4) else listOfNotNull(single.takeIf { it.isNotEmpty() })
        if (workItems.isEmpty()) return "Provide 'task' or 'tasks'."

        val runtime = runtimeProvider()
        return if (workItems.size == 1) {
            runtime.runSubagentTurn(workItems.first())
        } else {
            val results = coroutineScope {
                workItems.mapIndexed { idx, prompt ->
                    async {
                        val out = runtime.runSubagentTurn(prompt)
                        "--- Worker ${idx + 1} (${prompt.take(50)}) ---\n$out"
                    }
                }.awaitAll()
            }
            results.joinToString("\n\n")
        }
    }
}
