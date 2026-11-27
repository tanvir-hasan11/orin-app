package com.orin.app.tools

import android.content.Context
import com.orin.app.agent.OrinTool
import com.orin.app.data.AutomationEntity
import com.orin.app.data.OrinDao
import com.orin.app.work.AutomationManager
import com.orin.app.work.ScheduleMath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class AutomationTool(
    private val context: Context,
    private val dao: OrinDao,
) : OrinTool {
    override val name = "automations"
    override val description = "Schedule or manage recurring autonomous routines ('create', 'list', 'pause', 'resume', 'delete'). Supports daily_time (HH:mm), interval (15m/2h/1d), or 5-field cron."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string"); put("description", "create, list, pause, resume, delete") }
            putJsonObject("id") { put("type", "string"); put("description", "Automation ID for pause/resume/delete") }
            putJsonObject("name") { put("type", "string"); put("description", "Short automation title") }
            putJsonObject("prompt") { put("type", "string"); put("description", "Prompt to run on schedule") }
            putJsonObject("schedule_type") { put("type", "string"); put("description", "daily_time, interval, or cron") }
            putJsonObject("schedule_param") { put("type", "string"); put("description", "e.g. '08:00', '30m', '2h', or '0 8 * * *'") }
        }
    }

    private val timeFmt = DateTimeFormatter.ofPattern("MMM d HH:mm")

    override suspend fun execute(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        return when (action) {
            "create" -> {
                val name = args["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val type = args["schedule_type"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "daily_time"
                val param = args["schedule_param"]?.jsonPrimitive?.contentOrNull?.trim() ?: "08:00"
                if (name.isEmpty() || prompt.isEmpty()) return "Provide 'name' and 'prompt'."
                val next = ScheduleMath.nextRunAt(type, param)
                val entity = AutomationEntity(
                    id = UUID.randomUUID().toString().take(8),
                    name = name,
                    prompt = prompt,
                    scheduleType = type,
                    scheduleParam = param,
                    enabled = true,
                    nextRunAt = next,
                )
                dao.upsertAutomation(entity)
                AutomationManager.syncWork(context)
                "Scheduled '${entity.name}' (${entity.id}): $type=$param, next run ${formatMs(next)}"
            }
            "pause" -> toggle(args["id"]?.jsonPrimitive?.contentOrNull, enabled = false)
            "resume" -> toggle(args["id"]?.jsonPrimitive?.contentOrNull, enabled = true)
            "delete" -> {
                val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (id.isEmpty()) return "Provide 'id'."
                dao.deleteAutomation(id)
                AutomationManager.syncWork(context)
                "Deleted automation $id."
            }
            else -> {
                val list = dao.getAutomations()
                if (list.isEmpty()) "No automations scheduled."
                else list.joinToString("\n") { row ->
                    val status = if (row.enabled) "ON" else "OFF"
                    "• [${row.id}] ${row.name} ($status · ${row.scheduleType}:${row.scheduleParam}) · next ${formatMs(row.nextRunAt)}"
                }
            }
        }
    }

    private suspend fun toggle(rawId: String?, enabled: Boolean): String {
        val id = rawId?.trim().orEmpty()
        if (id.isEmpty()) return "Provide 'id'."
        val existing = dao.getAutomation(id) ?: return "No automation with id '$id'."
        val next = if (enabled) ScheduleMath.nextRunAt(existing.scheduleType, existing.scheduleParam) else existing.nextRunAt
        dao.upsertAutomation(existing.copy(enabled = enabled, nextRunAt = next))
        AutomationManager.syncWork(context)
        return "${existing.name} is now ${if (enabled) "enabled" else "paused"}."
    }

    private fun formatMs(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)
}
