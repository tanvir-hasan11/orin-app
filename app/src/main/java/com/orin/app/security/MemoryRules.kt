package com.orin.app.security

/**
 * Refuses to store secrets in Orin's long-term memory.
 *
 * This is a deliberate safety boundary: even if the model is tricked into
 * remembering a credential, the value never reaches persistent storage.
 */
object MemoryRules {
    private val blockedPatterns = listOf(
        Regex("""\b(?:password|passwd|passcode|pin|otp|one[- ]?time|cvv|cvc)\b\s*[:=]?\s*\S+""", RegexOption.IGNORE_CASE),
        Regex("""\b\d{4,6}\b"""), // PIN / OTP shaped numbers
        Regex("""\b(?:\d[ -]?){13,19}\b"""), // card numbers
        Regex("""\b(?:api[_ -]?key|token|secret)\b\s*[:=]?\s*\S+""", RegexOption.IGNORE_CASE),
        Regex("""(?:https?://)?\S*(?:aistudio|openai|anthropic)\S*sk-[A-Za-z0-9_-]{8,}""", RegexOption.IGNORE_CASE),
    )

    fun rejectionReason(key: String, value: String): String? {
        if (key.isBlank()) return "A memory needs a short label."
        if (key.length > 80) return "Memory label is too long (max 80 characters)."
        if (value.length > 1_000) return "Memory is too long (max 1000 characters)."
        if (blockedPatterns.any { it.containsMatchIn("$key $value") }) {
            return "I won't store passwords, PINs, OTPs, card numbers or API keys."
        }
        return null
    }
}
