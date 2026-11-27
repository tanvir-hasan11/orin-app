package com.orin.app.tools

import android.content.Context
import com.orin.app.agent.OrinTool
import com.orin.app.agent.ToolApprovalRequest
import com.orin.app.notify.NotificationTriage
import com.orin.app.notify.OrinNotificationListener
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Reads recent notifications as a privacy-safe summary. */
class NotificationTool(private val context: Context) : OrinTool {
    override val name = "notifications"
    override val description =
        "Summarise missed messages ('check'), announce who messages for a while ('watch', minutes), or stop watching ('stop_watch'). Codes and links are hidden."
    override val parameters = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("action") { put("type", "string"); put("description", "check | watch | stop_watch | status") }
            putJsonObject("minutes") { put("type", "string"); put("description", "How long to watch, 1..180") }
        }
    }

    override fun approval(args: JsonObject): ToolApprovalRequest? = when (action(args)) {
        "watch", "stop_watch" -> ToolApprovalRequest(
            name,
            if (action(args) == "watch") "Watch your messages?" else "Stop watching messages?",
            "Orin will only say who messaged, never what they wrote.",
        )
        else -> null
    }

    override suspend fun execute(args: JsonObject): String {
        if (!OrinNotificationListener.isEnabled(context)) {
            return "Notification access is off. Open Settings → Notifications → Device and app notifications and allow Orin, then ask again."
        }
        return when (action(args)) {
            "watch" -> {
                val minutes = args["minutes"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 15
                OrinNotificationListener.setWatch(context, minutes)
                "Watching for $minutes minute(s). I'll say who messaged, never what they wrote."
            }
            "stop_watch" -> {
                OrinNotificationListener.clearWatch(context)
                "Stopped watching messages."
            }
            "status" -> if (OrinNotificationListener.isWatching(context)) {
                "Message watch is on."
            } else {
                "Message watch is off."
            }
            else -> {
                val items = OrinNotificationListener.snapshot()
                if (items.isEmpty()) {
                    "No recent notifications to summarise."
                } else {
                    NotificationTriage.summarise(items)
                }
            }
        }
    }

    private fun action(args: JsonObject): String =
        args["action"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "check"
}
