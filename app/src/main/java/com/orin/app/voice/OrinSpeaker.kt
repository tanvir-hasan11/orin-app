package com.orin.app.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Small wrapper around the platform TextToSpeech engine. */
class OrinSpeaker(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var languageTag = DEFAULT_LANGUAGE
    private var onSpeechDone: (() -> Unit)? = null

    val isReady: Boolean get() = ready

    fun init(onReady: (Boolean) -> Unit = {}) {
        if (tts != null) {
            onReady(ready)
            return
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) applyLanguage()
            onReady(ready)
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                onSpeechDone?.invoke()
            }
            override fun onError(utteranceId: String?) {
                onSpeechDone?.invoke()
            }
        })
    }

    fun setLanguage(tag: String) {
        if (languageTag == tag) return
        languageTag = tag
        if (ready) applyLanguage()
    }

    private fun applyLanguage() {
        val locale = runCatching { Locale.forLanguageTag(languageTag) }.getOrDefault(Locale.ENGLISH)
        when (tts?.setLanguage(locale)) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> {
                tts?.setLanguage(Locale.ENGLISH)
            }
        }
    }

    /**
     * Speaks a chat reply. Markdown decorations are stripped so the assistant
     * does not read asterisks and hash marks aloud.
     */
    fun speak(text: String, onDone: (() -> Unit)? = null) {
        val spoken = text.toSpeakableText()
        if (!ready || spoken.isBlank()) {
            onDone?.invoke()
            return
        }
        onSpeechDone = onDone
        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UTTERANCE_ID)
        }
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
    }

    fun stop() {
        tts?.stop()
        onSpeechDone?.invoke()
        onSpeechDone = null
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun String.toSpeakableText(): String = this
        .replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("`([^`]+)`"), "$1")
        .replace(Regex("[*_#>]+"), "")
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("\\n{2,}"), "\n")
        .trim()
        .take(1_500)

    companion object {
        const val DEFAULT_LANGUAGE = "en-IN"
        private const val UTTERANCE_ID = "orin_voice_reply"
    }
}
