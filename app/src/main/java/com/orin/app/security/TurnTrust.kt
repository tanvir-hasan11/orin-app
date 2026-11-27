package com.orin.app.security

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Code-level prompt-injection & replay guard ported from JARVIS's TurnTrust + DuplicateGuard.
 *
 * Once a turn reads untrusted external text (another app's screen, notifications, SMS,
 * calendar events, or web pages), any subsequent side-effecting tool (sending, calling,
 * writing memory/routines, modifying files, running shell commands) MUST obtain explicit
 * user confirmation — even if the tool wouldn't normally prompt on a clean user turn.
 */
class TurnTrust {
    @Volatile
    var outsideText: Boolean = false
        private set

    fun userSpoke() {
        outsideText = false
    }

    fun after(toolName: String, args: JsonObject) {
        if (isExternalRead(toolName, args)) {
            outsideText = true
        }
    }

    fun requiresApprovalAfterExternalRead(toolName: String, args: JsonObject): Boolean {
        if (!outsideText) return false
        if (isReadOnly(toolName, args)) return false
        return toolName in TAINT_GATED_TOOLS
    }

    companion object {
        private val TAINT_GATED_TOOLS = setOf(
            "phone_sms",
            "phone_intents",
            "phone_calendar",
            "phone_basics",
            "device_control",
            "memory",
            "routine",
            "notification_reply",
            "screen_control",
            "file_ops",
            "shell",
            "git",
            "edit_file",
        )

        fun isExternalRead(toolName: String, args: JsonObject): Boolean {
            val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            return when (toolName) {
                "screen_control" -> action != "hand_to_user"
                "notifications" -> action.isEmpty() || action == "check"
                "web_fetch", "web_search" -> true
                "phone_sms" -> action.isEmpty() || action == "list"
                "phone_calendar" -> action.isEmpty() || action == "list"
                else -> false
            }
        }

        fun isReadOnly(toolName: String, args: JsonObject): Boolean {
            val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            return when (toolName) {
                "memory" -> action == "recall" || action == "list" || action.isEmpty()
                "routine" -> action == "list" || action == "run" || action.isEmpty()
                "phone_sms" -> action == "list" || action.isEmpty()
                "phone_calendar" -> action == "list" || action.isEmpty()
                "phone_contacts", "notifications", "web_fetch", "web_search", "meta_ads", "morning_brief" -> true
                "device_control" -> action == "list_apps" || action.isEmpty()
                "phone_basics" -> action == "date_time"
                "file_ops" -> action == "list" || action == "read" || action.isEmpty()
                "screen_control" -> action == "ui_read" || action == "ui_scroll" || action == "hand_to_user"
                "notification_reply" -> action == "prepare"
                else -> false
            }
        }

        fun isSensitive(toolName: String, args: JsonObject): Boolean {
            val action = args["action"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
            return when (toolName) {
                "phone_sms" -> action == "send"
                "phone_intents" -> action in setOf("call", "whatsapp", "sms", "email", "calendar_event")
                "notification_reply" -> action == "send"
                "phone_calendar" -> action == "create"
                "shell" -> true
                "screen_control" -> action in setOf("ui_click", "ui_tap_seen", "ui_type")
                else -> false
            }
        }
    }
}

/**
 * Stops the same sensitive action from firing twice within [windowMs] (e.g. from an LLM
 * retry or duplicate tool batch).
 */
class DuplicateGuard(
    private val windowMs: Long = 90_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val recent = mutableMapOf<String, Long>()

    @Synchronized
    fun allow(toolName: String, argsKey: String): Boolean {
        val now = clock()
        recent.entries.removeAll { now - it.value > windowMs }
        val key = "$toolName|$argsKey"
        if (key in recent) return false
        recent[key] = now
        return true
    }

    @Synchronized
    fun forget(toolName: String, argsKey: String) {
        recent.remove("$toolName|$argsKey")
    }
}
