package com.orin.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.orin.app.MainActivity
import com.orin.app.OrinApp
import com.orin.app.R
import com.orin.app.data.AuditLogEntity
import com.orin.app.data.AutomationEntity
import com.orin.app.data.KeyStoreManager
import com.orin.app.data.OrinDao
import com.orin.app.data.OrinDatabase
import com.orin.app.notify.NotificationTriage
import com.orin.app.notify.OrinNotificationListener
import com.orin.app.tools.MetaAdsTool
import com.orin.app.tools.MorningBriefTool
import com.orin.app.tools.PersonalTrackerTool
import com.orin.app.tools.PhoneCalendarTool
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.TimeUnit

object BriefNotificationHelper {
    const val CHANNEL_ID = "orin_briefs"
    const val EXTRA_NAVIGATE_OPS = "orin_open_ops"

    fun post(context: Context, id: Int, title: String, body: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Orin Briefs & Automations", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val canPost = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!canPost) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_NAVIGATE_OPS, true)
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body.lines().firstOrNull().orEmpty().take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(1800)))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}

/**
 * Compiles Orin's Executive Morning Brief (Calendar + Meta Ads + Open Tasks + Missed Notifications)
 * and delivers it as a local notification + Ops history log.
 */
class MorningBriefWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = OrinDatabase.get(applicationContext)
        val dao = db.dao()
        val keys = KeyStoreManager(applicationContext)

        val cal = PhoneCalendarTool(applicationContext)
        val meta = MetaAdsTool(keys, dao = dao)
        val tracker = PersonalTrackerTool(dao)
        val briefText = MorningBriefTool(cal, meta, tracker).execute(
            buildJsonObject { put("action", "run") },
        )
        val missed = OrinNotificationListener.snapshot()
        val fullSummary = if (missed.isNotEmpty()) {
            "$briefText\n\nMissed Notifications:\n${NotificationTriage.summarise(missed, limit = 5)}"
        } else {
            briefText
        }

        dao.insertAuditLog(
            AuditLogEntity(
                id = UUID.randomUUID().toString(),
                tool = "morning_brief",
                action = "scheduled_run",
                detail = fullSummary.replace('\n', ' ').take(180),
                ok = true,
            ),
        )
        dao.getAutomation(AutomationManager.DEFAULT_MORNING_BRIEF_ID)?.let { row ->
            val now = System.currentTimeMillis()
            dao.upsertAutomation(
                row.copy(
                    lastRunAt = now,
                    nextRunAt = ScheduleMath.nextRunAt(row.scheduleType, row.scheduleParam, now),
                ),
            )
            if (row.enabled) {
                AutomationManager.scheduleNextMorningBrief(applicationContext, row.scheduleParam)
            }
        }
        BriefNotificationHelper.post(applicationContext, 4001, "Orin Morning Brief", fullSummary)
        return Result.success()
    }
}

/**
 * Periodic WorkManager runner for user-scheduled autonomous routines.
 */
class AutomationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = OrinDatabase.get(applicationContext)
        val dao = db.dao()
        val now = System.currentTimeMillis()
        val due = dao.getDueAutomations(now)
        if (due.isEmpty()) return Result.success()

        val app = applicationContext as? OrinApp
        for (job in due) {
            if (job.id == AutomationManager.DEFAULT_MORNING_BRIEF_ID) {
                val cal = PhoneCalendarTool(applicationContext)
                val meta = MetaAdsTool(KeyStoreManager(applicationContext), dao = dao)
                val tracker = PersonalTrackerTool(dao)
                val summary = MorningBriefTool(cal, meta, tracker).execute(
                    buildJsonObject { put("action", "run") },
                )
                BriefNotificationHelper.post(applicationContext, job.id.hashCode(), job.name, summary)
            } else if (app != null && app.runtime.keys.hasApiKey()) {
                val reply = runCatching {
                    app.runtime.runTurn(
                        conversationId = "automation_${job.id}",
                        userText = job.prompt,
                        onUpdate = {},
                        onApproval = { false }, // Never execute unapproved destructive actions in background.
                    )
                }.getOrElse { "Automation failed: ${it.message}" }
                BriefNotificationHelper.post(applicationContext, job.id.hashCode(), job.name, reply)
            }

            val next = ScheduleMath.nextRunAt(job.scheduleType, job.scheduleParam, System.currentTimeMillis())
            dao.upsertAutomation(job.copy(lastRunAt = now, nextRunAt = next))
            dao.insertAuditLog(
                AuditLogEntity(
                    id = UUID.randomUUID().toString(),
                    tool = "automations",
                    action = "run:${job.name}",
                    detail = job.prompt.take(120),
                    ok = true,
                ),
            )
        }
        return Result.success()
    }
}

object AutomationManager {
    const val DEFAULT_MORNING_BRIEF_ID = "default_morning_brief"
    private const val WORK_MORNING_BRIEF = "orin_morning_brief"
    private const val WORK_PERIODIC = "orin_automations_poll"

    suspend fun ensureDefaultMorningBrief(dao: OrinDao, timeParam: String = "08:00"): AutomationEntity {
        val existing = dao.getAutomation(DEFAULT_MORNING_BRIEF_ID)
        if (existing != null) return existing
        val initial = AutomationEntity(
            id = DEFAULT_MORNING_BRIEF_ID,
            name = "Morning Brief",
            prompt = "Run my morning brief",
            scheduleType = "daily_time",
            scheduleParam = timeParam,
            enabled = false,
            isDaemon = false,
            lastRunAt = null,
            nextRunAt = ScheduleMath.nextRunAt("daily_time", timeParam),
        )
        dao.upsertAutomation(initial)
        return initial
    }

    fun scheduleNextMorningBrief(context: Context, timeParam: String = "08:00") {
        val now = System.currentTimeMillis()
        val delayMs = (ScheduleMath.nextRunAt("daily_time", timeParam, now) - now).coerceAtLeast(1_000L)
        val req = OneTimeWorkRequestBuilder<MorningBriefWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_MORNING_BRIEF,
            ExistingWorkPolicy.REPLACE,
            req,
        )
    }

    fun cancelMorningBrief(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_MORNING_BRIEF)
    }

    suspend fun syncWork(context: Context) {
        val dao = OrinDatabase.get(context).dao()
        val morning = ensureDefaultMorningBrief(dao)
        if (morning.enabled) {
            scheduleNextMorningBrief(context, morning.scheduleParam)
        } else {
            cancelMorningBrief(context)
        }

        val othersEnabled = dao.getAutomations().any { it.enabled && it.id != DEFAULT_MORNING_BRIEF_ID }
        val wm = WorkManager.getInstance(context)
        if (othersEnabled) {
            val req = PeriodicWorkRequestBuilder<AutomationWorker>(15, TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(WORK_PERIODIC)
        }
    }
}
