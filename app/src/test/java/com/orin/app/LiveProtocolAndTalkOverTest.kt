package com.orin.app

import com.orin.app.live.LiveEvent
import com.orin.app.live.LiveProtocol
import com.orin.app.llm.LlmToolDeclaration
import com.orin.app.voice.LiveSpeaker
import com.orin.app.voice.TalkOver
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LiveProtocolAndTalkOverTest {
    private val jsonParser = Json { ignoreUnknownKeys = true }

    @Test
    fun `setup message includes model voice tools and vad`() {
        val tool = LlmToolDeclaration(
            name = "meta_ads",
            description = "Get ad insights",
            parameters = buildJsonObject { put("type", "object") },
        )
        val raw = LiveProtocol.setupMessage(
            LiveProtocol.Setup(
                model = "gemini-3.1-flash-live-preview",
                systemInstruction = "Be concise.",
                voice = "Puck",
                languages = listOf("bn-BD", "en-IN"),
                resumeHandle = "handle_1",
                tools = listOf(tool),
            ),
        )
        val setup = jsonParser.parseToJsonElement(raw).jsonObject["setup"]!!.jsonObject
        assertEquals("models/gemini-3.1-flash-live-preview", setup["model"]!!.jsonPrimitive.content)
        assertEquals("handle_1", setup["sessionResumption"]!!.jsonObject["handle"]!!.jsonPrimitive.content)
        val tools = setup["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray
        assertEquals("meta_ads", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parses audio transcription tool calls and control events`() {
        val data = Base64.getEncoder().encodeToString(byteArrayOf(7, 8))
        val msg = """
            {"serverContent": {
              "inputTranscription": {"text": "hello"},
              "modelTurn": {"parts": [{"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": "$data"}}]},
              "outputTranscription": {"text": "Hi!"},
              "turnComplete": true
            }}
        """.trimIndent()
        val events = LiveProtocol.parse(msg)
        assertEquals(LiveEvent.UserText("hello"), events[0])
        val audio = events[1] as LiveEvent.Audio
        assertEquals(24_000, audio.sampleRate)
        assertArrayEquals(byteArrayOf(7, 8), audio.pcm)
        assertEquals(LiveEvent.AssistantText("Hi!"), events[2])
        assertEquals(LiveEvent.TurnComplete, events[3])

        val toolMsg = """{"toolCall": {"functionCalls": [{"id": "c1", "name": "phone_basics", "args": {"action": "date_time"}}]}}"""
        val toolEvents = LiveProtocol.parse(toolMsg)
        val call = (toolEvents[0] as LiveEvent.ToolCall).calls.single()
        assertEquals("c1", call.id)
        assertEquals("phone_basics", call.name)
    }

    @Test
    fun `talk over learns echo attenuation and triggers barge in on speech`() {
        fun chunk(level: Int) = ByteArray(3_200).also { b ->
            for (i in 0 until 1_600) {
                val v = if (i % 2 == 0) level else -level
                b[2 * i] = (v and 0xFF).toByte()
                b[2 * i + 1] = (v shr 8).toByte()
            }
        }

        val t = TalkOver(LiveSpeaker())
        repeat(20) { assertNull(t.duringReply(chunk(5_000), chunk(4_500))) }
        assertFalse(t.ready)

        repeat(8) { assertNull(t.duringReply(chunk(5_000), chunk(150))) }
        assertTrue(t.ready)

        assertNull(t.duringReply(chunk(5_500), chunk(3_000)))
        assertNull(t.duringReply(chunk(5_500), chunk(3_000)))
        val cut = t.duringReply(chunk(5_500), chunk(3_000))
        assertNotNull(cut)
        assertEquals(3, cut!!.size)

        t.reset(newConversation = true)
        assertFalse(t.ready)
    }
}
