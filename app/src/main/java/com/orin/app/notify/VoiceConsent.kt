package com.orin.app.notify

/**
 * Verifies whether the user's own words after a reply read-back are a clear "yes, send it"
 * (ported from JARVIS VoiceConsent, extended with Bangla confirmation/cancellation words).
 * Anything doubtful or resembling TTS echo counts as NO.
 */
object VoiceConsent {
    private val NO = Regex(
        """\b(no|nope|not|don't|dont|stop|cancel|wait|wrong|change|nahi|nahin|na|mat|ruko|ruk|galat|badlo|thamo|bad)\b|नहीं|मत|रुको|रुक|না|থামো|বাদ|ভুল""",
        RegexOption.IGNORE_CASE,
    )
    private val YES = Regex(
        """\b(yes|yeah|yep|yup|ok|okay|sure|send|send it|go ahead|correct|right|haan|han|haa|ha|ji|bhej|bhejo|bhej do|kar do|theek|thik|hae|ha|pathao|pathiye dao|thik ache)\b|हाँ|हां|हा|जी|भेजो|भेज दो|ठीक|হ্যাঁ|হুম|পাঠাও|পাঠিয়ে দাও|ঠিক আছে""",
        RegexOption.IGNORE_CASE,
    )

    fun confirms(heard: String, message: String, readBack: String = ""): Boolean {
        val said = heard.trim()
        if (said.isEmpty() || said.split(Regex("\\s+")).size > MAX_WORDS) return false
        if (NO.containsMatchIn(said)) return false
        val msg = message.lowercase().trim()
        if (msg.length >= 4 && said.lowercase().contains(msg)) return false
        val echoed = words(said).count { it in words(readBack) - setOf("send") }
        if (echoed >= 2) return false
        return YES.containsMatchIn(said)
    }

    private fun words(text: String) =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 }.toSet()

    private const val MAX_WORDS = 8
}
