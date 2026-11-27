package com.orin.app

import com.orin.app.agent.ContextCompactor
import com.orin.app.data.BackupManager
import com.orin.app.data.OrinBackupPayload
import com.orin.app.llm.LlmTurnMessage
import com.orin.app.mcp.McpJsonRpc
import com.orin.app.tools.SearchParsers
import com.orin.app.ui.chat.MarkdownBlockParser
import com.orin.app.ui.chat.MdBlock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4BAnd5Test {

    @Test
    fun `context compactor keeps recent turns and summarizes older turns when over budget`() {
        val turns = (1..20).map { i ->
            LlmTurnMessage(
                role = if (i % 2 == 1) "user" else "assistant",
                content = "Turn $i content " + "x".repeat(400),
            )
        }
        val compacted = ContextCompactor.compactIfNeeded(turns, tokenBudget = 1_200)
        assertEquals(9, compacted.size) // 1 summary turn + 8 recent turns
        assertTrue(compacted.first().content.contains("Compacted earlier conversation context"))
        assertTrue(compacted.last().content.contains("Turn 20"))
    }

    @Test
    fun `markdown parser parses headings, code fences, tables, and callouts`() {
        val md = """
            # Executive Summary
            > [!IMPORTANT] Spend increased 12%

            | Metric | Value |
            | --- | --- |
            | Spend | $42.50 |

            ```kotlin
            println("hello")
            ```
        """.trimIndent()
        val blocks = MarkdownBlockParser.parse(md)
        assertTrue(blocks.any { it is MdBlock.Heading && it.text == "Executive Summary" })
        assertTrue(blocks.any { it is MdBlock.Callout && it.kind == "IMPORTANT" })
        assertTrue(blocks.any { it is MdBlock.Table && it.rows.first() == listOf("Spend", "$42.50") })
        assertTrue(blocks.any { it is MdBlock.CodeFence && it.lang == "kotlin" })
    }

    @Test
    fun `search parsers decode tavily brave and serper responses`() {
        val tavily = """{"results":[{"title":"Orin","url":"https://example.com","content":"Android AI"}]}"""
        assertEquals("Orin", SearchParsers.parseTavily(tavily, 3).first().title)

        val brave = """{"web":{"results":[{"title":"BraveHit","url":"https://b.com","description":"Fast"}]}}"""
        assertEquals("BraveHit", SearchParsers.parseBrave(brave, 3).first().title)

        val serper = """{"organic":[{"title":"Serp","link":"https://s.com","snippet":"Quick"}]}"""
        assertEquals("Serp", SearchParsers.parseSerper(serper, 3).first().title)
    }

    @Test
    fun `mcp json rpc frames version 2_0 requests accurately`() {
        val req = McpJsonRpc.buildRequest("tools/call", buildJsonObject { put("name", "ping") }, id = 42)
        assertTrue(req.toString().contains("\"jsonrpc\":\"2.0\""))
        assertTrue(req.toString().contains("\"id\":42"))
        assertTrue(req.toString().contains("\"method\":\"tools/call\""))
    }

    @Test
    fun `backup manager encrypts and decrypts payload with passphrase`() {
        val payload = OrinBackupPayload(
            exportedAtMs = 123456L,
            provider = "gemini",
            model = "gemini-2.5-flash",
            themeStyle = "obsidian",
            secrets = mapOf("api_key_gemini" to "secret-123"),
        )
        val encrypted = BackupManager.encryptPayload(payload, "orin-pass-99")
        assertTrue(!encrypted.contains("secret-123"))
        val restored = BackupManager.decryptPayload(encrypted, "orin-pass-99")
        assertEquals("obsidian", restored.themeStyle)
        assertEquals("secret-123", restored.secrets["api_key_gemini"])
    }
}
