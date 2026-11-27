package com.orin.app.tools

import android.content.Context
import com.orin.app.agent.OrinTool
import com.orin.app.agent.ToolApprovalRequest
import com.orin.app.notify.NotificationReplies
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Hands-free notification reply tool (ported from JARVIS ReplyPrepare + ReplySend):
 * step 1 (`prepare`) stages the reply and returns the exact read-back question;
 * step 2 (`send`) verifies explicit user confirmation via [VoiceConsent] before firing RemoteInput.
 */
class NotificationReplyTool(private val context: Context) : OrinTool {
    override val name = "notification_reply"
    override val description =
        "Hands-free reply to a [reply N] notification without opening the app. " +
            "First call action='prepare' with reply_id and text, read the returned line to the user and wait; " +
            "only after they confirm yes, call action='send'."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string"); put("description", "'prepare' or 'send'") }
            putJsonObject("reply_id") { put("type", "string"); put("description", "Numeric N from [reply N] in notifications check") }
            putJsonObject("text") { put("type", "string"); put("description", "Exact reply text for 'prepare'") }
        }
    }

    override fun approval(args: JsonObject): ToolApprovalRequest? =
        if (args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() == "send") {
            ToolApprovalRequest(name, "Send notification reply?", "Confirm sending your prepared reply.")
        } else {
            null
        }

    override suspend fun execute(args: JsonObject): String {
        val act = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "prepare"
        return when (act) {
            "prepare" -> {
                val id = args["reply_id"]?.jsonPrimitive?.intOrNull
                    ?: args["reply_id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: return "Provide a numeric 'reply_id'."
                val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(500)
                if (text.isEmpty()) return "Provide 'text' for the reply."
                val line = NotificationReplies.prepare(id, text, System.currentTimeMillis())
                    ?: return "That notification can no longer be answered from here. Offer to open the chat app instead."
                "Prepared. Ask the user word-for-word: \"$line\" Wait for their yes before calling action='send'."
            }
            "send" -> when (val outcome = NotificationReplies.send(context, System.currentTimeMillis())) {
                is NotificationReplies.Outcome.Sent -> "Sent to ${outcome.who} on ${outcome.app}."
                is NotificationReplies.Outcome.Refused -> "Not sent: ${outcome.why}"
            }
            else -> "Unknown action '$act'. Use 'prepare' or 'send'."
        }
    }
}
