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
        "start_ms" to "Event start time in epoch milliseconds (optional, defaults to one hour from now).",
        "end_ms" to "Event end time in epoch milliseconds (optional, defaults to start + one hour).",
    )

    override fun approval(args: JsonObject): ToolApprovalRequest? {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        if (action != "create") return null
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val start = startMillis(args)
        val end = endMillis(args, start)
        val formatter = eventFormatter()
        return ToolApprovalRequest(
            toolName = name,
            title = "Create a calendar event?",
            detail = "${title.ifBlank { "(no title)" }}\n" +
                "${formatter.format(Instant.ofEpochMilli(start))} – ${formatter.format(Instant.ofEpochMilli(end))}",
        )
    }

    override suspend fun execute(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "READ_CALENDAR permission not granted. Open Settings → Permissions to allow Calendar access."
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
        val formatter = eventFormatter()
        val lines = mutableListOf<String>()
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cursor ->
            while (cursor.moveToNext() && lines.size < 25) {
                val title = cursor.getString(0) ?: "(No title)"
                val begin = cursor.getLong(1)
                val location = cursor.getString(2)?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""
                lines.add("• ${formatter.format(Instant.ofEpochMilli(begin))} — $title$location")
            }
        }
        return if (lines.isEmpty()) "No calendar events in the next $days day(s)." else lines.joinToString("\n")
    }

    private fun createEvent(args: JsonObject): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return "WRITE_CALENDAR permission not granted. Open Settings → Permissions to allow Calendar access."
        }
        val title = args["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (title.isEmpty()) return "Event 'title' is required."
        val start = startMillis(args)
        val end = endMillis(args, start)

        val calendarId = writableCalendarId()
            ?: return "No writable calendar found. Add a calendar account or choose a writable calendar in the Calendar app."

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, args["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        val inserted = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        return if (inserted != null) "Created calendar event: '$title'." else "Failed to create calendar event."
    }

    private fun writableCalendarId(): Long? = context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        arrayOf(CalendarContract.Calendars._ID),
        "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
        arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
        "${CalendarContract.Calendars.IS_PRIMARY} DESC",
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

    private fun startMillis(args: JsonObject): Long =
        args["start_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            ?: (System.currentTimeMillis() + 3_600_000L)

    private fun endMillis(args: JsonObject, start: Long): Long {
        val requested = args["end_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        return if (requested != null && requested > start) requested else start + 3_600_000L
    }

    private fun eventFormatter(): DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE MMM d, HH:mm").withZone(ZoneId.systemDefault())
}

class PhoneContactsTool(private val context: Context) : OrinTool {
    override val name = "phone_contacts"
    override val description = "Search phone contacts by name or phone number."
    override val parameters = objSchema("query" to "Name or number to search.")

    override suspend fun execute(args: JsonObject): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "READ_CONTACTS permission not granted. Open Settings → Permissions to allow Contacts access."
        }
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val selection = if (query.isEmpty()) null else
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
        val selectionArgs = if (query.isEmpty()) null else arrayOf("%$query%", "%$query%")

        val out = mutableListOf<String>()
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
        )?.use { cursor ->
            while (cursor.moveToNext() && out.size < 20) {
                val name = cursor.getString(0) ?: continue
                val number = cursor.getString(1) ?: continue
                out.add("• $name: $number")
            }
        }
        return if (out.isEmpty()) "No matching contacts found." else out.joinToString("\n")
    }
}

class PhoneSmsTool(private val context: Context, private val dao: OrinDao) : OrinTool {
    override val name = "phone_sms"
    override val description = "Read recent SMS ('list', optional query) or send an SMS ('send', recipient, text). Sending always requires approval."
    override val parameters = objSchema(
        "action" to "One of: 'list' (default) or 'send'.",
        "query" to "Filter text or sender for 'list'.",
        "recipient" to "Destination phone number for 'send'.",
        "text" to "SMS body for 'send'.",
    )

    override fun approval(args: JsonObject): ToolApprovalRequest? {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        if (action != "send") return null
        val recipient = args["recipient"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return ToolApprovalRequest(
            toolName = name,
            title = "Send this SMS?",
            detail = "To: ${recipient.ifBlank { "(missing number)" }}\n\n${text.ifBlank { "(empty message)" }}",
        )
    }

    override suspend fun execute(args: JsonObject): String {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list"
        return if (action == "send") sendSms(args) else listSms(args)
    }

    private fun listSms(args: JsonObject): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "READ_SMS permission not granted. Open Settings → Permissions to allow SMS access."
        }
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
        val formatter = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault())
        val selection = if (query.isNullOrBlank()) null else
            "${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.BODY} LIKE ?"
        val selectionArgs = if (query.isNullOrBlank()) null else arrayOf("%$query%", "%$query%")

        val out = mutableListOf<String>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext() && out.size < 15) {
                val address = cursor.getString(0) ?: "Unknown"
                val body = cursor.getString(1) ?: ""
                val date = formatter.format(Instant.ofEpochMilli(cursor.getLong(2)))
                out.add("• [$date] $address: ${body.take(140)}")
            }
        }
        return if (out.isEmpty()) "No SMS messages found." else out.joinToString("\n")
    }

    private suspend fun sendSms(args: JsonObject): String {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "SEND_SMS permission not granted. Open Settings → Permissions to allow SMS access."
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
            } ?: return "SMS service is unavailable on this device."

            val parts = smsManager.divideMessage(text)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(recipient, null, text, null, null)
            }

            dao.insertAuditLog(
                AuditLogEntity(UUID.randomUUID().toString(), "phone_sms", "send", "To $recipient", true),
            )
            "SMS queued for delivery to $recipient."
        } catch (e: Exception) {
            dao.insertAuditLog(
                AuditLogEntity(
                    UUID.randomUUID().toString(), "phone_sms", "send",
                    "To $recipient: ${e.message}", false,
                ),
            )
            "Failed to send SMS: ${e.message}"
        }
    }
}

class DeviceControlTool(private val context: Context, private val dao: OrinDao) : OrinTool {
    override val name = "device_control"
    override val description = "Launch an installed app ('launch_app', query), list apps ('list_apps'), open settings ('open_settings', target), or set media volume ('set_volume', percent)."
    override val parameters = objSchema(
        "action" to "One of: 'launch_app', 'list_apps', 'open_settings', 'set_volume'.",
        "query" to "App name or package for launch_app / list_apps.",
        "target" to "Settings target: wifi, bluetooth, hotspot, display, sound, notifications.",
        "percent" to "Volume 0..100 for set_volume.",
    )

    override fun approval(args: JsonObject): ToolApprovalRequest? {
        return when (args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list_apps") {
            "launch_app" -> ToolApprovalRequest(
                name, "Launch an app?",
                args["query"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            "open_settings" -> ToolApprovalRequest(
                name, "Open device settings?",
                args["target"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            "set_volume" -> ToolApprovalRequest(
                name, "Change media volume?",
                "${args["percent"]?.jsonPrimitive?.contentOrNull ?: "50"}%",
            )
            else -> null
        }
    }

    override suspend fun execute(args: JsonObject): String {
        return when (args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "list_apps") {
            "launch_app" -> launchApp(args["query"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "open_settings" -> openSettings(args["target"]?.jsonPrimitive?.contentOrNull.orEmpty())
            "set_volume" -> setVolume(args["percent"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 50)
            else -> listApps(args["query"]?.jsonPrimitive?.contentOrNull)
        }
    }

    private fun apps(): List<Pair<String, String>> {
        val packageManager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(intent, 0).mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(packageManager).toString().trim().ifEmpty { packageName }
            label to packageName
        }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
    }

    private fun listApps(query: String?): String {
        val filter = query?.trim()?.lowercase()
        val list = apps().filter {
            filter.isNullOrEmpty() ||
                it.first.lowercase().contains(filter) ||
                it.second.lowercase().contains(filter)
        }
        return if (list.isEmpty()) "No matching apps."
        else list.take(30).joinToString("\n") { "• ${it.first} (${it.second})" }
    }

    private suspend fun launchApp(query: String): String {
        val filter = query.trim().lowercase()
        if (filter.isEmpty()) return "Provide an app name or package."
        val available = apps()
        val match = available.firstOrNull {
            it.first.equals(filter, true) || it.second.equals(filter, true)
        } ?: available.firstOrNull {
            it.first.lowercase().contains(filter) || it.second.lowercase().contains(filter)
        } ?: return "No app matching '$query'."

        val intent = context.packageManager.getLaunchIntentForPackage(match.second)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return "Cannot launch ${match.first}."
        context.startActivity(intent)
        dao.insertAuditLog(
            AuditLogEntity(UUID.randomUUID().toString(), "device_control", "launch_app", match.first, true),
        )
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
        dao.insertAuditLog(
            AuditLogEntity(UUID.randomUUID().toString(), "device_control", "open_settings", target, true),
        )
        return "Opened $target settings."
    }

    private suspend fun setVolume(percent: Int): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return "AudioManager unavailable."
        val volume = percent.coerceIn(0, 100)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (max * (volume / 100.0)).toInt().coerceIn(0, max),
            0,
        )
        dao.insertAuditLog(
            AuditLogEntity(UUID.randomUUID().toString(), "device_control", "set_volume", "$volume%", true),
        )
        return "Set media volume to $volume%."
    }
}
