package com.orin.app.voice

/** Shared intent actions and preferences for the voice shell. */
object OrinVoice {
    const val ACTION_VOICE = "com.orin.app.VOICE_INPUT"
    const val ACTION_TYPE = "com.orin.app.TYPE_INPUT"
    const val ACTION_STOP = "com.orin.app.VOICE_STOP"

    const val PREF_LANGUAGE = "voice_language"
    const val PREF_SPEAK_REPLIES = "voice_speak_replies"
    const val PREF_ORB_ENABLED = "voice_orb_enabled"

    fun languages(): List<Pair<String, String>> = listOf(
        "en-IN" to "English (India)",
        "hi-IN" to "हिन्दी",
    )
}
