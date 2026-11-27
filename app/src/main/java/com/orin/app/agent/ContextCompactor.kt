package com.orin.app.agent

import com.orin.app.llm.LlmTurnMessage

/**
 * Estimates prompt tokens and compacts older history when approaching model context limits.
 */
object ContextCompactor {
    private const val DEFAULT_TOKEN_BUDGET = 24_000
    private const val KEEP_RECENT_TURNS = 8

    fun estimateTokens(text: String): Int = (text.length + 3) / 4

    fun estimateTranscriptTokens(messages: List<LlmTurnMessage>): Int =
        messages.sumOf { msg ->
            estimateTokens(msg.content) + msg.toolCalls.sumOf { estimateTokens(it.arguments.toString()) } + 8
        }

    fun compactIfNeeded(
        messages: List<LlmTurnMessage>,
        tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
    ): List<LlmTurnMessage> {
        if (messages.size <= KEEP_RECENT_TURNS || estimateTranscriptTokens(messages) <= tokenBudget) {
            return messages
        }
        val splitIndex = (messages.size - KEEP_RECENT_TURNS).coerceAtLeast(1)
        val older = messages.subList(0, splitIndex)
        val recent = messages.subList(splitIndex, messages.size)

        val digestLines = mutableListOf<String>()
        var charBudget = 3_600
        for (msg in older) {
            val line = "[${msg.role}] ${msg.content.replace('\n', ' ').take(180)}"
            if (charBudget - line.length < 0) break
            digestLines.add(line)
            charBudget -= line.length
        }

        val summaryTurn = LlmTurnMessage(
            role = "user",
            content = "[Compacted earlier conversation context (${older.size} turns)]:\n" +
                digestLines.joinToString("\n"),
        )
        return listOf(summaryTurn) + recent
    }
}
