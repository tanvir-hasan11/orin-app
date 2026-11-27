package com.orin.app.tools

import com.orin.app.agent.OrinTool
import com.orin.app.data.KeyStoreManager
import com.orin.app.data.MetaAdSnapshotEntity
import com.orin.app.data.OrinDao
import com.orin.app.data.TaskEntity
import com.orin.app.notify.NotificationTriage
import com.orin.app.notify.OrinNotificationListener
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
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

data class MetaAdMetrics(
    val datePreset: String,
    val spend: String,
    val spendCents: Long,
    val currency: String,
    val reach: Long,
    val impressions: Long,
    val clicks: Long,
    val ctr: Double,
    val cpc: Double,
    val fetchedAtMs: Long = System.currentTimeMillis(),
) {
    fun formatSummary(): String =
        "Meta Ads ($datePreset): Spend=$$spend | Reach=$reach | Impr=$impressions | Clicks=$clicks | CTR=${"%.2f".format(ctr)}% | CPC=$${"%.2f".format(cpc)}"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun fromSnapshot(s: MetaAdSnapshotEntity): MetaAdMetrics {
            val spendStr = "%.2f".format(s.spendCents / 100.0)
            return MetaAdMetrics(
                datePreset = s.datePreset,
                spend = spendStr,
                spendCents = s.spendCents,
                currency = s.currency,
                reach = s.reach,
                impressions = s.impressions,
                clicks = s.clicks,
                ctr = s.ctr,
                cpc = s.cpc,
                fetchedAtMs = s.fetchedAt,
            )
        }

        fun parseInsightsJson(body: String, datePreset: String): MetaAdMetrics? {
            val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray ?: return null
            if (data.isEmpty()) return null
            val row = data.first().jsonObject
            val spendStr = row["spend"]?.jsonPrimitive?.contentOrNull ?: "0"
            val spendVal = spendStr.toDoubleOrNull() ?: 0.0
            val reach = row["reach"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val impressions = row["impressions"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val clicks = row["clicks"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val ctr = row["ctr"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
            val cpc = row["cpc"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
            val currency = row["account_currency"]?.jsonPrimitive?.contentOrNull ?: "USD"
            return MetaAdMetrics(
                datePreset = datePreset,
                spend = "%.2f".format(spendVal),
                spendCents = (spendVal * 100.0).roundToLong(),
                currency = currency,
                reach = reach,
                impressions = impressions,
                clicks = clicks,
                ctr = ctr,
                cpc = cpc,
            )
        }
    }
}

class MetaAdsTool(
    private val keys: KeyStoreManager,
    private val dao: OrinDao? = null,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build(),
) : OrinTool {
    override val name = "meta_ads"
    override val description = "Inspect Meta (Facebook/Instagram) Ads insights ('insights', date_preset: today/yesterday/last_7d/this_month) or campaigns ('campaigns')."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string"); put("description", "'insights' (default) or 'campaigns'") }
            putJsonObject("date_preset") { put("type", "string"); put("description", "today, yesterday, last_7d, this_month") }
        }
    }
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchInsights(preset: String = "yesterday"): Result<MetaAdMetrics> = withContext(Dispatchers.IO) {
        val token = keys.getSecret("meta_ads_token")
            ?: return@withContext Result.failure(IllegalStateException("Meta Ads token not configured. Add it in Settings → Meta Ads."))
        var accountId = keys.getSecret("meta_ad_account_id")
            ?: return@withContext Result.failure(IllegalStateException("Meta Ad Account ID not configured. Add it in Settings → Meta Ads."))
        if (!accountId.startsWith("act_")) accountId = "act_$accountId"

        val url = "https://graph.facebook.com/v21.0/$accountId/insights" +
            "?fields=spend,reach,impressions,clicks,cpc,ctr,account_currency&date_preset=$preset"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = when (response.code) {
                        401, 403 -> "Meta token is invalid or expired (HTTP ${response.code}). Re-connect it in Settings."
                        429 -> "Meta rate limit reached (HTTP 429). Try again shortly."
                        else -> "Meta API HTTP ${response.code}: ${body.take(250)}"
                    }
                    error(msg)
                }
                val parsed = MetaAdMetrics.parseInsightsJson(body, preset)
                    ?: error("No Meta Ads data for '$preset'.")
                dao?.insertMetaAdSnapshot(
                    MetaAdSnapshotEntity(
                        id = UUID.randomUUID().toString(),
                        accountId = accountId,
                        datePreset = preset,
                        spendCents = parsed.spendCents,
                        currency = parsed.currency,
                        impressions = parsed.impressions,
                        clicks = parsed.clicks,
                        ctr = parsed.ctr,
                        cpc = parsed.cpc,
                        reach = parsed.reach,
                        fetchedAt = parsed.fetchedAtMs,
                    ),
                )
                parsed
            }
        }
    }

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "insights"
        val preset = args["date_preset"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "yesterday"

        if (action != "campaigns") {
            return@withContext fetchInsights(preset).fold(
                onSuccess = { it.formatSummary() },
                onFailure = { err ->
                    val cached = dao?.latestMetaAdSnapshot(preset)?.let { MetaAdMetrics.fromSnapshot(it) }
                    if (cached != null) "${err.message}\n(Cached snapshot: ${cached.formatSummary()})"
                    else err.message ?: "Meta Ads error"
                },
            )
        }

        val token = keys.getSecret("meta_ads_token")
            ?: return@withContext "Meta Ads token not configured. Add it in Settings → Meta Ads."
        var accountId = keys.getSecret("meta_ad_account_id")
            ?: return@withContext "Meta Ad Account ID not configured. Add it in Settings → Meta Ads."
        if (!accountId.startsWith("act_")) accountId = "act_$accountId"

        val url = "https://graph.facebook.com/v21.0/$accountId/campaigns?fields=name,status,daily_budget&limit=25"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@use when (response.code) {
                        401, 403 -> "Meta token is invalid or expired (HTTP ${response.code}). Re-connect it in Settings."
                        429 -> "Meta rate limit reached (HTTP 429). Try again shortly."
                        else -> "Meta API HTTP ${response.code}: ${body.take(250)}"
                    }
                }
                val data = json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
                    ?: return@use "No data returned."
                if (data.isEmpty()) return@use "No Meta Ads campaigns found."
                data.joinToString("\n") { element ->
                    val campaign = element.jsonObject
                    val budget = campaign["daily_budget"]?.jsonPrimitive?.contentOrNull
                        ?.toDoubleOrNull()
                        ?.let { " · daily $${"%.2f".format(it / 100)}" }
                        .orEmpty()
                    "• ${campaign["name"]?.jsonPrimitive?.contentOrNull} " +
                        "[${campaign["status"]?.jsonPrimitive?.contentOrNull}]$budget"
                }
            }
        }.getOrElse { "Meta Ads error: ${it.message}" }
    }
}

class PersonalTrackerTool(private val dao: OrinDao) : OrinTool {
    override val name = "personal_tracker"
    override val description = "Manage the user's personal tasks in the local database ('add_task', 'list_tasks', 'complete_task')."
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
    override val description = "Compile the user's Executive Brief ('run' for Morning Brief, 'evening_wrap' for Evening Wrap-Up)."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string"); put("description", "'run' (default) or 'evening_wrap'") }
        }
    }

    override suspend fun execute(args: JsonObject): String {
        val isEvening = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() == "evening_wrap"
        val calendarText = calendar.execute(
            buildJsonObject { put("action", "list"); put("days", if (isEvening) "2" else "1") },
        )
        val ads = metaAds.execute(
            buildJsonObject { put("action", "insights"); put("date_preset", if (isEvening) "today" else "yesterday") },
        )
        val tasks = tracker.execute(buildJsonObject { put("action", "list_tasks") })
        val recentNotify = OrinNotificationListener.snapshot()

        return buildString {
            appendLine(if (isEvening) "🌙 **Orin Evening Wrap-Up**" else "☀️ **Orin Morning Brief**")
            appendLine()
            appendLine("**Calendar:**")
            appendLine(calendarText)
            appendLine()
            appendLine("**Meta Ads:**")
            appendLine(ads)
            appendLine()
            appendLine("**Open Tasks:**")
            appendLine(tasks)
            if (recentNotify.isNotEmpty()) {
                appendLine()
                appendLine("**Recent Messages:**")
                appendLine(NotificationTriage.summarise(recentNotify, limit = 4))
            }
        }.trimEnd()
    }
}
