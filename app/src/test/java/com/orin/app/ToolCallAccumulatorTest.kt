package com.orin.app

import com.orin.app.llm.ToolCallAccumulator
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallAccumulatorTest {

    @Test
    fun `fragmented openai arguments are joined`() {
        val accumulator = ToolCallAccumulator()
        accumulator.accept(
            index = 0,
            id = "call_1",
            name = "phone_sms",
            argumentsDelta = """{"action":"se""",
        )
        accumulator.accept(
            index = 0,
            argumentsDelta = """nd","recipient":"+880","text":"hi"}""",
        )

        val calls = accumulator.build()
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("phone_sms", calls[0].name)
        assertEquals("send", calls[0].arguments["action"]?.jsonPrimitive?.contentOrNull)
        assertEquals("+880", calls[0].arguments["recipient"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `multiple tool calls keep their indexes`() {
        val accumulator = ToolCallAccumulator()
        accumulator.accept(0, id = "a", name = "phone_calendar")
        accumulator.accept(1, id = "b", name = "meta_ads")

        val calls = accumulator.build()
        assertEquals(listOf("a", "b"), calls.map { it.id })
        assertEquals(listOf("phone_calendar", "meta_ads"), calls.map { it.name })
    }

    @Test
    fun `invalid json becomes an empty object`() {
        val accumulator = ToolCallAccumulator()
        accumulator.accept(0, id = "a", name = "shell", argumentsDelta = "{not-json")
        val call = accumulator.build().single()
        assertTrue(call.arguments.isEmpty())
    }
}
