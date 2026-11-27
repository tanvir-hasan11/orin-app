package com.orin.app.tools

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.orin.app.agent.OrinTool
import com.orin.app.agent.ToolApprovalRequest
import com.orin.app.data.AuditLogEntity
import com.orin.app.data.OrinDao
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Everyday phone actions that don't need a full app-opening flow. */
class PhoneBasicsTool(
    private val context: Context,
    private val dao: OrinDao,
) : OrinTool {
    override val name = "phone_basics"
    override val description =
        "Phone basics: set_alarm, set_timer, remind_me, play_music, media_pause, set_volume, toggle_flashlight, web_search, date_time."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string"); put("description", "The basic action to run") }
            putJsonObject("hour") { put("type", "string"); put("description", "Alarm hour") }
            putJsonObject("minute") { put("type", "string"); put("description", "Alarm minute") }
            putJsonObject("seconds") { put("type", "string"); put("description", "Timer length in seconds") }
            putJsonObject("text") { put("type", "string"); put("description", "Reminder text or search query") }
            putJsonObject("time_ms") { put("type", "string"); put("description", "Reminder time, epoch milliseconds") }
            putJsonObject("percent") { put("type", "string"); put("description", "Volume 0..100") }
        }
    }

    override fun approval(args: JsonObject): ToolApprovalRequest? = when (action(args)) {
        "set_alarm", "set_timer", "remind_me", "toggle_flashlight", "set_volume" ->
            ToolApprovalRequest(name, "Do this on your phone?", describe(args))
        else -> null
    }

    override suspend fun execute(args: JsonObject): String {
        val result = when (action(args)) {
            "set_alarm" -> setAlarm(args)
            "set_timer" -> setTimer(args)
            "remind_me" -> setReminder(args)
            "play_music" -> playMusic(args)
            "media_pause" -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            "media_next" -> mediaKey(android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
            "set_volume" -> setVolume(args)
            "toggle_flashlight" -> toggleFlashlight()
            "web_search" -> webSearch(args)
            "date_time" -> dateTime()
            else -> "Unknown action: ${action(args)}"
        }
        dao.insertAuditLog(
            AuditLogEntity(UUID.randomUUID().toString(), name, action(args), result.take(180), true),
        )
        return result
    }

    private fun setAlarm(args: JsonObject): String {
        val hour = args["hour"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val minute = args["minute"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        if (hour == null || hour !in 0..23) return "Provide an 'hour' between 0 and 23."
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Opened the clock app with an alarm at ${"%02d:%02d".format(hour, minute)}. Tap Save."
        } catch (e: Exception) {
            "No clock app available: ${e.message}"
        }
    }

    private fun setTimer(args: JsonObject): String {
        val seconds = args["seconds"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: return "Provide 'seconds' for the timer."
        if (seconds <= 0) return "Timer length must be positive."
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Opened the clock app with a $seconds-second timer. Tap Start."
        } catch (e: Exception) {
            "No clock app available: ${e.message}"
        }
    }

    private fun setReminder(args: JsonObject): String {
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val time = args["time_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        if (text.isEmpty()) return "Provide reminder 'text'."
        if (time == null || time <= System.currentTimeMillis()) {
            return "Provide a future 'time_ms' for the reminder."
        }
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !manager.canScheduleExactAlarms()
        ) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return "Exact alarms need your permission. I opened the setting for you; please allow it, then ask again."
        }
        val id = UUID.randomUUID().hashCode()
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra(EXTRA_TEXT, text)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(context, id, intent, flags)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, pending)
            } else {
                @Suppress("DEPRECATION")
                manager.setExact(AlarmManager.RTC_WAKEUP, time, pending)
            }
        }.onFailure { return "Couldn't schedule the reminder: ${it.message}" }
        return "Reminder set: $text"
    }

    private fun playMusic(args: JsonObject): String {
        val query = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) return "Provide a song or artist in 'text'."
        val uri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Opened a music search for '$query'. Tap play in the app."
        } catch (e: Exception) {
            "No music app available: ${e.message}"
        }
    }

    private fun mediaKey(keyCode: Int): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "Audio service unavailable."
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        audio.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        return "Sent the media key."
    }

    private fun setVolume(args: JsonObject): String {
        val percent = args["percent"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: return "Provide 'percent' (0..100)."
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "Audio service unavailable."
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (max * (percent.coerceIn(0, 100) / 100.0)).toInt().coerceIn(0, max),
            0,
        )
        return "Media volume set to ${percent.coerceIn(0, 100)}%."
    }

    private fun toggleFlashlight(): String {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return "Camera service unavailable."
        val cameraId = runCatching { manager.cameraIdList.firstOrNull() }.getOrNull()
            ?: return "No camera with a torch found."
        return try {
            val enabled = !torchOn
            manager.setTorchMode(cameraId, enabled)
            torchOn = enabled
            if (enabled) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "Couldn't switch the flashlight: ${e.message}"
        }
    }

    private fun webSearch(args: JsonObject): String {
        val query = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (query.isEmpty()) return "Provide a search query in 'text'."
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            "Opened a web search for '$query'."
        } catch (e: Exception) {
            "No browser available: ${e.message}"
        }
    }

    private fun dateTime(): String {
        val now = LocalDateTime.now(ZoneId.systemDefault())
        return "It's ${DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm").format(now)}."
    }

    private fun action(args: JsonObject): String =
        args["action"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()

    private fun describe(args: JsonObject): String = when (action(args)) {
        "set_alarm" -> "${args["hour"]?.jsonPrimitive?.contentOrNull ?: "?"}:${args["minute"]?.jsonPrimitive?.contentOrNull ?: "00"}"
        "set_timer" -> "${args["seconds"]?.jsonPrimitive?.contentOrNull ?: "?"} seconds"
        "remind_me" -> "${args["text"]?.jsonPrimitive?.contentOrNull ?: ""} at ${args["time_ms"]?.jsonPrimitive?.contentOrNull ?: "?"}"
        "set_volume" -> "${args["percent"]?.jsonPrimitive?.contentOrNull ?: "?"}%"
        else -> action(args)
    }

    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_REMINDER) return
            val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
            if (text.isBlank()) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Orin reminders", NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, com.orin.app.MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            manager.notify(
                text.hashCode(),
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(com.orin.app.R.drawable.ic_notification)
                    .setContentTitle("Orin reminder")
                    .setContentText(text)
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    companion object {
        private const val ACTION_REMINDER = "com.orin.app.REMINDER"
        private const val EXTRA_TEXT = "reminder_text"
        private const val CHANNEL_ID = "orin_reminders"
        private var torchOn = false
    }
}
