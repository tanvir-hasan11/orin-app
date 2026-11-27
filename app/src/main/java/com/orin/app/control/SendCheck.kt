package com.orin.app.control

import kotlinx.coroutines.delay

/**
 * Verifies whether a WhatsApp message was actually sent by inspecting the visible
 * screen nodes: the text must appear in the chat timeline and no longer sit in the
 * input field (ported from JARVIS SendCheck).
 */
object SendCheck {
    fun sent(nodes: List<UiNode>, message: String): Boolean {
        val head = key(message)
        if (head.isEmpty()) return false
        val stillTyped = nodes.any { it.role == "field" && head in key(it.value.ifEmpty { it.text }) }
        val inChat = nodes.any { it.role != "field" && head in key(it.display) }
        return inChat && !stillTyped
    }

    private fun key(text: String) = text.lowercase().filter { it.isLetterOrDigit() }.take(40)

    suspend fun watchWhatsApp(message: String, timeoutMs: Long = 30_000L): Boolean? {
        val service = OrinControlService.instance ?: return null
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            delay(1_500)
            val pkg = service.currentPackage().orEmpty()
            if (pkg !in WHATSAPP) continue
            val nodes = service.snapshot()
            if (sent(nodes, message)) return true
        }
        return false
    }

    private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
}
