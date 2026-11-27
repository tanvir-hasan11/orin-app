package com.orin.app.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
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
import java.util.UUID

/**
 * Opens the right Android app with everything filled in — but never presses
 * Send or Call. The final tap always belongs to the user.
 */
class PhoneIntentsTool(
    private val context: Context,
    private val dao: OrinDao,
) : OrinTool {
    override val name = "phone_intents"
    override val description =
        "Open a dialer, WhatsApp, SMS, email or Calendar app with details prefilled. The user taps Send/Call/Save."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") {
                put("type", "string")
                put("description", "call | whatsapp | sms | email | calendar_event")
            }
            putJsonObject("recipient") { put("type", "string"); put("description", "Phone number or email address") }
            putJsonObject("text") { put("type", "string"); put("description", "Message body or event title") }
            putJsonObject("start_ms") { put("type", "string"); put("description", "Event start, epoch milliseconds") }
            putJsonObject("end_ms") { put("type", "string"); put("description", "Event end, epoch milliseconds") }
        }
    }

    override fun approval(args: JsonObject): ToolApprovalRequest? = ToolApprovalRequest(
        name,
        "Open ${when (action(args)) { "call" -> "the dialer"; "whatsapp" -> "WhatsApp"; "sms" -> "Messages"; "email" -> "your mail app"; else -> "Calendar" }}?",
        "${recipient(args)}\n\n${text(args).take(400)}",
    )

    override suspend fun execute(args: JsonObject): String {
        val intent = when (action(args)) {
            "call" -> {
                val number = recipient(args)
                if (number.isBlank()) return "Provide a 'recipient' phone number."
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
            }
            "whatsapp" -> {
                val number = recipient(args).filter { it.isDigit() || it == '+' }
                val body = text(args)
                if (number.isBlank()) return "Provide a 'recipient' phone number."
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    `package` = "com.whatsapp"
                    putExtra(Intent.EXTRA_TEXT, body)
                    data = Uri.parse("https://api.whatsapp.com/send?phone=$number")
                }
            }
            "sms" -> {
                val number = recipient(args)
                if (number.isBlank()) return "Provide a 'recipient' phone number."
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).apply {
                    putExtra("sms_body", text(args))
                }
            }
            "email" -> {
                val address = recipient(args)
                val parts = text(args).split("\n", limit = 2)
                Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address")).apply {
                    putExtra(Intent.EXTRA_SUBJECT, parts.firstOrNull().orEmpty())
                    if (parts.size > 1) putExtra(Intent.EXTRA_TEXT, parts[1])
                }
            }
            "calendar_event" -> {
                val start = args["start_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: (System.currentTimeMillis() + 3_600_000L)
                val end = args["end_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: (start + 3_600_000L)
                Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                    putExtra(CalendarContract.Events.TITLE, text(args))
                }
            }
            else -> return "Unknown action. Use call, whatsapp, sms, email or calendar_event."
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            dao.insertAuditLog(
                AuditLogEntity(
                    UUID.randomUUID().toString(),
                    name,
                    action(args),
                    recipient(args).take(120),
                    true,
                ),
            )
            "Opened ${action(args)} with the details filled in. Your tap is needed to finish."
        } catch (e: Exception) {
            "Couldn't open that app: ${e.message ?: "no app available"}"
        }
    }

    private fun action(args: JsonObject): String =
        args["action"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()

    private fun recipient(args: JsonObject): String =
        args["recipient"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    private fun text(args: JsonObject): String =
        args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
}
