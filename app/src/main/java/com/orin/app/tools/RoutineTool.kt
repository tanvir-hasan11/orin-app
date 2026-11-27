package com.orin.app.tools

import android.content.Context
import com.orin.app.agent.OrinTool
import com.orin.app.agent.ToolApprovalRequest
import com.orin.app.security.MemoryRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class OrinRoutine(val name: String, val steps: List<String>)

/** Pure routine lookup & validation rules (ported from JARVIS MemoryRules). */
object RoutineRules {
    const val MAX_ROUTINES = 20
    const val MAX_STEPS = 10

    val GOOD_MORNING = OrinRoutine(
        "Good morning",
        listOf(
            "Check today's calendar",
            "Check missed notifications",
            "Summarize Meta Ads insights for yesterday",
            "List open personal tasks",
        ),
    )

    fun key(name: String): String = name.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    fun find(name: String, all: List<OrinRoutine>): OrinRoutine? {
        val k = key(name)
        if (k.isEmpty()) return null
        return all.firstOrNull { key(it.name) == k }
            ?: all.firstOrNull { key(it.name).contains(k) || k.contains(key(it.name)) }
    }
}

/**
 * Voice/chat routines tool (ported from JARVIS routine_save / routine_run):
 * lets the user save multi-step named routines ("Good morning", "Good night") and run them.
 */
class RoutineTool(private val context: Context) : OrinTool {
    override val name = "routine"
    override val description =
        "Manage and run named multi-step routines: 'run' (returns steps to execute with normal tools), " +
            "'save' (name + steps), 'list', or 'delete'."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string"); put("description", "run | save | list | delete") }
            putJsonObject("name") { put("type", "string"); put("description", "Routine name, e.g. Good morning") }
            putJsonObject("steps") { put("type", "string"); put("description", "Newline or semicolon-separated steps for 'save'") }
        }
    }

    private val prefs by lazy { context.getSharedPreferences("orin_routines", Context.MODE_PRIVATE) }
    private val jsonParser = Json { ignoreUnknownKeys = true }

    override fun approval(args: JsonObject): ToolApprovalRequest? =
        when (args["action"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "save" -> ToolApprovalRequest(name, "Save routine?", args["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "delete" -> ToolApprovalRequest(name, "Delete routine?", args["name"]?.jsonPrimitive?.contentOrNull.orEmpty())
            else -> null
        }

    override suspend fun execute(args: JsonObject): String {
        val act = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        return when (act) {
            "save" -> save(args)
            "run" -> runRoutine(args)
            "delete" -> delete(args)
            else -> list()
        }
    }

    private fun save(args: JsonObject): String {
        val title = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(40)
        if (title.isEmpty()) return "Provide a routine 'name'."
        val rawSteps = args["steps"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val steps = rawSteps.split(Regex("[\n;]+"))
            .map { it.trim().take(120) }
            .filter { it.isNotEmpty() }
        if (steps.isEmpty()) return "Provide 'steps' for the routine."
        if (steps.size > RoutineRules.MAX_STEPS) return "At most ${RoutineRules.MAX_STEPS} steps allowed."
        for (step in steps) {
            MemoryRules.rejectionReason(title, step)?.let { return it }
        }
        val others = saved().filter { RoutineRules.key(it.name) != RoutineRules.key(title) }
        write((others + OrinRoutine(title, steps)).takeLast(RoutineRules.MAX_ROUTINES))
        return "Saved routine \"$title\" with ${steps.size} step(s)."
    }

    private fun runRoutine(args: JsonObject): String {
        val nameArg = args["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val all = allRoutines()
        val match = RoutineRules.find(nameArg, all)
            ?: return "Available routines: ${all.joinToString { it.name }}. Ask which one to run."
        return buildString {
            appendLine("Execute these steps of \"${match.name}\" in order using your tools (keep approvals intact), then give one short summary:")
            match.steps.forEachIndexed { idx, step ->
                appendLine("${idx + 1}. $step")
            }
        }.trimEnd()
    }

    private fun delete(args: JsonObject): String {
        val nameArg = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (nameArg.isEmpty()) return "Provide the routine 'name' to delete."
        val before = saved()
        val after = before.filter { RoutineRules.key(it.name) != RoutineRules.key(nameArg) }
        if (before.size == after.size) return "No saved routine matched \"$nameArg\"."
        write(after)
        return "Deleted routine \"$nameArg\"."
    }

    private fun list(): String =
        allRoutines().joinToString("\n") { r -> "• ${r.name}: ${r.steps.joinToString(" · ")}" }

    private fun allRoutines(): List<OrinRoutine> {
        val savedList = saved()
        val builtIn = RoutineRules.GOOD_MORNING.takeUnless { gm ->
            savedList.any { RoutineRules.key(it.name) == RoutineRules.key(gm.name) }
        }
        return listOfNotNull(builtIn) + savedList
    }

    private fun saved(): List<OrinRoutine> {
        val raw = prefs.getString("list", "[]").orEmpty()
        return runCatching {
            jsonParser.parseToJsonElement(raw).jsonArray.mapNotNull { elem ->
                val obj = elem.jsonObject
                val rName = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val rSteps = obj["steps"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                OrinRoutine(rName, rSteps)
            }
        }.getOrDefault(emptyList())
    }

    private fun write(list: List<OrinRoutine>) {
        val arr = buildJsonArray {
            for (r in list) {
                add(buildJsonObject {
                    put("name", r.name)
                    put("steps", JsonArray(r.steps.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                })
            }
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }
}
