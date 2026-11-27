package com.orin.app.live

import com.orin.app.llm.LlmToolDeclaration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * JSON protocol for the Gemini Live WebSocket (`BidiGenerateContent`), using Orin's
 * native `kotlinx.serialization` and tool declarations.
 */
object LiveProtocol {
    const val INPUT_SAMPLE_RATE = 16_000
    private const val DEFAULT_OUTPUT_RATE = 24_000
    private val RATE = Regex("""rate=(\d+)""")
    private val jsonParser = Json { ignoreUnknownKeys = true }

    data class Setup(
        val model: String,
        val systemInstruction: String,
        val voice: String?,
        val languages: List<String>,
        val resumeHandle: String?,
        val tools: List<LlmToolDeclaration> = emptyList(),
    )

    fun setupMessage(setup: Setup): String {
        val body = buildJsonObject {
            put("model", "models/${setup.model.removePrefix("models/")}")
            putJsonObject("generationConfig") {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                if (!setup.voice.isNullOrBlank()) {
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") {
                                put("voiceName", setup.voice)
                            }
                        }
                    }
                }
            }
            putJsonObject("systemInstruction") {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", setup.systemInstruction) })
                })
            }
            putJsonObject("inputAudioTranscription") {
                if (setup.languages.isNotEmpty()) {
                    put("languageCodes", buildJsonArray {
                        setup.languages.forEach { add(JsonPrimitive(it)) }
                    })
                }
            }
            if (setup.tools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("functionDeclarations", buildJsonArray {
                            for (tool in setup.tools) {
                                add(buildJsonObject {
                                    put("name", tool.name)
                                    put("description", tool.description)
                                    put("parameters", tool.parameters)
                                })
                            }
                        })
                    })
                })
            }
            putJsonObject("outputAudioTranscription") {}
            putJsonObject("sessionResumption") {
                if (!setup.resumeHandle.isNullOrBlank()) {
                    put("handle", setup.resumeHandle)
                }
            }
            putJsonObject("contextWindowCompression") {
                putJsonObject("slidingWindow") {}
            }
            putJsonObject("realtimeInputConfig") {
                putJsonObject("automaticActivityDetection") {
                    put("prefixPaddingMs", 200)
                    put("silenceDurationMs", 700)
                }
            }
        }
        return buildJsonObject { put("setup", body) }.toString()
    }

    fun audioMessage(pcm: ByteArray): String = buildJsonObject {
        putJsonObject("realtimeInput") {
            putJsonObject("audio") {
                put("data", Base64.getEncoder().encodeToString(pcm))
                put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
            }
        }
    }.toString()

    fun userTextMessage(text: String): String = buildJsonObject {
        putJsonObject("clientContent") {
            put("turns", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", text) })
                    })
                })
            })
            put("turnComplete", true)
        }
    }.toString()

    fun toolResponseMessage(results: List<LiveFunctionResult>): String = buildJsonObject {
        putJsonObject("toolResponse") {
            put("functionResponses", buildJsonArray {
                for (r in results) {
                    add(buildJsonObject {
                        put("id", r.id)
                        put("name", r.name)
                        put("response", r.response)
                    })
                }
            })
        }
    }.toString()

    fun parse(text: String): List<LiveEvent> {
        val message = runCatching { jsonParser.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return emptyList()
        val events = mutableListOf<LiveEvent>()
        if (message.containsKey("setupComplete")) events += LiveEvent.SetupComplete
        message["sessionResumptionUpdate"]?.jsonObject?.let { update ->
            val handle = update["newHandle"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val resumable = update["resumable"]?.jsonPrimitive?.booleanOrNull ?: true
            if (resumable && handle.isNotEmpty()) {
                events += LiveEvent.ResumeHandle(handle)
            }
        }
        message["serverContent"]?.jsonObject?.let { events += parseContent(it) }
        message["toolCall"]?.jsonObject?.get("functionCalls")?.jsonArray?.let { calls ->
            val parsed = calls.mapNotNull { elem ->
                val obj = elem.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val args = obj["args"]?.jsonObject ?: JsonObject(emptyMap())
                LiveFunctionCall(id, name, args)
            }
            if (parsed.isNotEmpty()) events += LiveEvent.ToolCall(parsed)
        }
        message["toolCallCancellation"]?.jsonObject?.get("ids")?.jsonArray?.let { ids ->
            events += LiveEvent.ToolCancelled(ids.mapNotNull { it.jsonPrimitive.contentOrNull })
        }
        if (message.containsKey("goAway")) events += LiveEvent.GoAway
        return events
    }

    private fun parseContent(content: JsonObject): List<LiveEvent> {
        val events = mutableListOf<LiveEvent>()
        if (content["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
            events += LiveEvent.Interrupted
        }
        content["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotEmpty() }?.let { events += LiveEvent.UserText(it) }

        val parts = content["modelTurn"]?.jsonObject?.get("parts")?.jsonArray
        if (parts != null) {
            for (elem in parts) {
                val blob = elem.jsonObject["inlineData"]?.jsonObject ?: continue
                val mime = blob["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val data = blob["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (mime.startsWith("audio/") && data.isNotEmpty()) {
                    runCatching { Base64.getDecoder().decode(data) }.getOrNull()?.let { pcm ->
                        events += LiveEvent.Audio(pcm, sampleRate(mime))
                    }
                }
            }
        }

        content["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotEmpty() }?.let { events += LiveEvent.AssistantText(it) }
        if (content["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
            events += LiveEvent.TurnComplete
        }
        return events
    }

    private fun sampleRate(mime: String): Int =
        RATE.find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_OUTPUT_RATE

    fun describeClose(code: Int, reason: String): String {
        val lower = reason.lowercase()
        return when {
            "api key" in lower || "permission" in lower || "unauth" in lower ->
                "Gemini rejected the API key. Check it in Settings."
            "quota" in lower || "exceeded" in lower || "rate" in lower ->
                "Gemini rate limit reached. Try again in a minute."
            "not found" in lower || "model" in lower ->
                "That Gemini Live model wasn't found."
            code == 1000 -> "Gemini ended the Live session."
            else -> "Lost connection to Gemini Live."
        }
    }
}
