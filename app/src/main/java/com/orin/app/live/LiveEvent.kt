package com.orin.app.live

import kotlinx.serialization.json.JsonObject

sealed interface LiveEvent {
    data object SetupComplete : LiveEvent
    class Audio(val pcm: ByteArray, val sampleRate: Int) : LiveEvent
    data class UserText(val text: String) : LiveEvent
    data class AssistantText(val text: String) : LiveEvent
    data object Interrupted : LiveEvent
    data object TurnComplete : LiveEvent
    data class ResumeHandle(val handle: String) : LiveEvent
    data object GoAway : LiveEvent
    data class ToolCall(val calls: List<LiveFunctionCall>) : LiveEvent
    data class ToolCancelled(val ids: List<String>) : LiveEvent
}

data class LiveFunctionCall(val id: String, val name: String, val args: JsonObject)

data class LiveFunctionResult(val id: String, val name: String, val response: JsonObject)
