package com.orin.app.tools

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.orin.app.agent.OrinTool
import com.orin.app.data.AuditLogEntity
import com.orin.app.data.OrinDao
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import java.util.UUID

private fun objSchema(vararg props: Pair<String, String>): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        for ((k, desc) in props) {
            putJsonObject(k) {
                put("type", "string")
                put("description", desc)
            }
        }
    }
}

class PhoneCalendarTool(private val context: Context) : OrinTool {
    override val name = "phone_calendar"
    override val description = "Read upcoming calendar events ('list', optional days) or create an event ('create', title, start_ms, end_ms)."
    override val parameters = objSchema(
        "action" to "One of: 'list' (default) or 'create'.",
        "days" to "Number of days ahead to list (default 2).",
        "title" to "Event title for 'create'.",
        "description" to "Optional event description.",
    )

    override suspend fun execute(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "READ_CALENDAR permission not granted. Please allow Calendar access in Settings."
        }
        return if (action == "create") createEvent(args) else listEvents(args)
    }

    private fun listEvents(args: JsonObject): String {
        val days = (args["days"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 2).coerceIn(1, 30)
        val now = System.currentTimeMillis()
        val end = now + days * 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(now.toString())
            .appendPath(end.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.EVENT_LOCATION,
        )
        val fmt = DateTimeFormatter.ofPattern("EEE MMM d, HH:mm").withZone(ZoneId.systemDefault())
        val lines = mutableListOf<String>()
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext() && lines.size < 25) {
                val title = c.getString(0) ?: "(No title)"
                val begin = c.getLong(1)
                val loc = c.getString(2)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                lines.add("• ${fmt.format(Instant.ofEpochMilli(begin))} — $title$loc")
            }
        }
        return if (lines.isEmpty()) "No calendar events in the next $days day(s)." else lines.joinToString("\n")
    }

    private fun createEvent(args: JsonObject): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "WRITE_CALENDAR permission not granted."
        }
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (title.isEmpty()) return "Event 'title' is required."
        val startMs = System.currentTimeMillis() + 3_600_000L
        val endMs = startMs + 3_600_000L
        val calId = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.VISIBLE} = 1",
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: 1L

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, args["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, endMs)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val inserted = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return if (inserted != null) "Created calendar event: '$title'." else "Failed to create calendar event."
    }
}

class PhoneContactsTool(private val context: Context) : OrinTool {
    override val name = "phone_contacts"
    override val description = "Search phone contacts by name or phone number."
    override val parameters = objSchema("query" to "Name or number to search.")

    override suspend fun execute(args: JsonObject): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "READ_CONTACTS permission not granted."
        }
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val out = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 20) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                if (query.isEmpty() || name.lowercase().contains(query) || num.contains(query)) {
                    out.add("• $name: $num")
                }
            }
        }
        return if (out.isEmpty()) "No matching contacts found." else out.joinToString("\n")
    }
}

class PhoneSmsTool(private val context: Context, private val dao: OrinDao) : OrinTool {
    override val name = "phone_sms"
    override val description = "Read recent SMS ('list', optional query) or send an SMS ('send', recipient, text)."
    override val parameters = objSchema(
        "action" to "One of: 'list' (default) or 'send'.",
        "query" to "Filter text or sender for 'list'.",
        "recipient" to "Destination phone number for 'send'.",
        "text" to "SMS body for 'send'.",
    )

    override suspend fun execute(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        return if (action == "send") sendSms(args) else listSms(args)
    }

    private fun listSms(args: JsonObject): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "READ_SMS permission not granted."
        }
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
        val fmt = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault())
        val out = mutableListOf<String>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < 15) {
                val addr = c.getString(0) ?: "Unknown"
                val body = c.getString(1) ?: ""
                val date = fmt.format(Instant.ofEpochMilli(c.getLong(2)))
                if (query.isNullOrEmpty() || addr.lowercase().contains(query) || body.lowercase().contains(query)) {
                    out.add("• [$date] $addr: ${body.take(140)}")
                }
            }
        }
        return if (out.isEmpty()) "No SMS messages found." else out.joinToString("\n")
    }

    private suspend fun sendSms(args: JsonObject): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "SEND_SMS permission not granted."
        }
        val recipient = args["recipient"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (recipient.isEmpty() || text.isEmpty()) return "'recipient' and 'text' are required."

        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = smsManager.divideMessage(text)
            if (parts.size > 1) smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
            else smsManager.sendTextMessage(recipient, null, text, null, null)

            dao.insertAuditLog(AuditLogEntity(UUID.randomUUID().toString(), "phone_sms", "send", "To $recipient", true))
            "SMS sent to $recipient."
        } catch (e: Exception) {
            dao.insertAuditLog(AuditLogEntity(UUID.randomUUID().toString(), "phone_sms", "send", "To $recipient: ${e.message}", false))
            "Failed to send SMS: ${e.message}"
        }
    }
}

class DeviceControlTool(private val context: Context, private val dao: OrinDao) : OrinTool {
    override val name = "device_control"
    override val description = "Launch any installed app ('launch_app', query), list apps ('list_apps'), open settings ('open_settings', target), or set volume ('set_volume', percent)."
    override val parameters = objSchema(
        "action" to "One of: 'launch_app', 'list_apps', 'open_settings', 'set_volume'.",
        "query" to "App name or package for launch_app / list_apps.",
        "target" to "Settings target: wifi, bluetooth, hotspot, display, sound, notifications.",
        "percent" to "Volume 0..100 for set_volume.",
    )

    override suspend fun execute(args: JsonObject): String {
        return when (args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list_apps") {
            "launch_app" -> launchApp(args["query"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "open_settings" -> openSettings(args["target"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "set_volume" -> setVolume(args["percent"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 50)
            else -> listApps(args["query"]?.jsonPrimitive?.contentOrNull)
        }
    }

    private fun apps(): List<Pair<String, String>> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(pm).toString().trim().ifEmpty { pkg }
            label to pkg
        }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
    }

    private fun listApps(query: String?): String {
        val q = query?.trim()?.lowercase()
        val list = apps().filter { q.isNullOrEmpty() || it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
        return if (list.isEmpty()) "No matching apps." else list.take(30).joinToString("\n") { "• ${it.first} (${it.second})" }
    }

    private suspend fun launchApp(query: String): String {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return "Provide app name or package."
        val match = apps().firstOrNull { it.first.equals(q, true) || it.second.equals(q, true) }
            ?: apps().firstOrNull { it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
            ?: return "No app matching '$query'."
        val intent = context.packageManager.getLaunchIntentForPackage(match.second)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return "Cannot launch ${match.first}."
        context.startActivity(intent)
        dao.insertAuditLog(AuditLogEntity(UUID.randomUUID().toString(), "device_control", "launch_app", match.first, true))
        return "Launched ${match.first}."
    }

    private suspend fun openSettings(target: String): String {
        val action = when (target.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "notifications" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        dao.insertAuditLog(AuditLogEntity(UUID.randomUUID().toString(), "device_control", "open_settings", target, true))
        return "Opened $target settings."
    }

    private suspend fun setVolume(percent: Int): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "AudioManager unavailable."
        val p = percent.coerceIn(0, 100)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * (p / 100.0)).toInt().coerceIn(0, max), 0)
        dao.insertAuditLog(AuditLogEntity(UUID.randomUUID().toString(), "device_control", "set_volume", "$p%", true))
        return "Set media volume to $p%."
    }
}
