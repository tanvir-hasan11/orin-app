package com.orin.app.tools

import com.orin.app.agent.OrinTool
import com.orin.app.data.KeyStoreManager
import com.orin.app.data.OrinDao
import com.orin.app.data.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

class MetaAdsTool(
    private val keys: KeyStoreManager,
    private val http: OkHttpClient = OkHttpClient(),
) : OrinTool {
    override val name = "meta_ads"
    override val description = "Inspect Meta (Facebook/Instagram) Ads insights ('insights', date_preset: today/yesterday/last_7d) or campaigns ('campaigns')."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string"); put("description", "'insights' (default) or 'campaigns'") }
            putJsonObject("date_preset") { put("type", "string"); put("description", "today, yesterday, last_7d, this_month") }
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val token = keys.getSecret("meta_ads_token")
            ?: return@withContext "Meta Ads token not configured. Add it in Settings -> Phone Bridge & Meta Ads."
        var accountId = keys.getSecret("meta_ad_account_id")
            ?: return@withContext "Meta Ad Account ID not configured. Add it in Settings -> Phone Bridge & Meta Ads."
        if (!accountId.startsWith("act_")) accountId = "act_$accountId"

        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "insights"
        val preset = args["date_preset"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "yesterday"

        val url = if (action == "campaigns") {
            "https://graph.facebook.com/v21.0/$accountId/campaigns?fields=name,status,daily_budget&limit=15&access_token=$token"
        } else {
            "https://graph.facebook.com/v21.0/$accountId/insights?fields=spend,reach,impressions,clicks,cpc,ctr&date_preset=$preset&access_token=$token"
        }

        val req = Request.Builder().url(url).get().build()
        runCatching {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use "Meta API HTTP ${resp.code}: ${body.take(250)}"
                val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                    ?: return@use "No data returned."
                if (data.isEmpty()) return@use "No Meta Ads data for '$preset'."
                if (action == "campaigns") {
                    data.joinToString("\n") { el ->
                        val o = el.jsonObject
                        "• ${o["name"]?.jsonPrimitive?.contentOrNull} [${o["status"]?.jsonPrimitive?.contentOrNull}]"
                    }
                } else {
                    val row = data.first().jsonObject
                    val spend = row["spend"]?.jsonPrimitive?.contentOrNull ?: "0"
                    val reach = row["reach"]?.jsonPrimitive?.contentOrNull ?: "0"
                    val clicks = row["clicks"]?.jsonPrimitive?.contentOrNull ?: "0"
                    val ctr = row["ctr"]?.jsonPrimitive?.contentOrNull ?: "0"
                    val cpc = row["cpc"]?.jsonPrimitive?.contentOrNull ?: "0"
                    "Meta Ads ($preset): Spend=$$spend | Reach=$reach | Clicks=$clicks | CTR=$ctr% | CPC=$$cpc"
                }
            }
        }.getOrElse { "Meta Ads error: ${it.message}" }
    }
}

class PersonalTrackerTool(private val dao: OrinDao) : OrinTool {
    override val name = "personal_tracker"
    override val description = "Manage Tanvir's personal tasks in Room DB ('add_task', 'list_tasks', 'complete_task')."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string"); put("description", "add_task, list_tasks, complete_task") }
            putJsonObject("title") { put("type", "string"); put("description", "Task title") }
            putJsonObject("priority") { put("type", "string"); put("description", "high, normal, low") }
        }
    }

    override suspend fun execute(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list_tasks"
        return when (action) {
            "add_task" -> {
                val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (title.isEmpty()) return "Provide 'title'."
                val priority = args["priority"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "normal"
                val task = TaskEntity(id = UUID.randomUUID().toString().take(8), title = title, priority = priority)
                dao.upsertTask(task)
                "Added task: $title ($priority)"
            }
            "complete_task" -> {
                val target = args["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                val match = dao.observeTasks().first().firstOrNull { !it.completed && it.title.contains(target, true) }
                    ?: return "No open task matching '$target'."
                dao.setTaskCompleted(match.id, true)
                "Completed: ${match.title}"
            }
            else -> {
                val tasks = dao.observeTasks().first().filter { !it.completed }
                if (tasks.isEmpty()) "No open personal tasks."
                else tasks.joinToString("\n") { "• [${it.priority.uppercase()}] ${it.title}" }
            }
        }
    }
}

class MorningBriefTool(
    private val calendar: PhoneCalendarTool,
    private val metaAds: MetaAdsTool,
    private val tracker: PersonalTrackerTool,
) : OrinTool {
    override val name = "morning_brief"
    override val description = "Compile Tanvir's Executive Brief ('run' for Morning Brief, 'evening_wrap' for Evening Wrap-Up)."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string"); put("description", "'run' (default) or 'evening_wrap'") }
        }
    }

    override suspend fun execute(args: JsonObject): String {
        val isEvening = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() == "evening_wrap"
        val cal = calendar.execute(buildJsonObject { put("action", "list"); put("days", if (isEvening) "2" else "1") })
        val ads = metaAds.execute(buildJsonObject { put("action", "insights"); put("date_preset", if (isEvening) "today" else "yesterday") })
        val tasks = tracker.execute(buildJsonObject { put("action", "list_tasks") })

        return buildString {
            appendLine(if (isEvening) "🌙 **Orin Evening Wrap-Up**" else "☀️ **Orin Morning Brief**")
            appendLine()
            appendLine("**Calendar:**")
            appendLine(cal)
            appendLine()
            appendLine("**Meta Ads:**")
            appendLine(ads)
            appendLine()
            appendLine("**Open Tasks:**")
            appendLine(tasks)
        }.trimEnd()
    }
}
