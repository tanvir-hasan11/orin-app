package com.orin.app.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Bangla-first speech input using the device's own recognition model.
 *
 * On Android 12+ this prefers the on-device recognizer, so audio stays on the
 * phone. On older devices it falls back to the system recognizer with an
 * explicit offline preference. The language pack itself is provided by the
 * device (Google Play Services), not by Orin.
 */
class PlatformSpeechRecognizer(private val context: Context) {

    private val _partial = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _final = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 2)

    val partial: SharedFlow<String> = _partial
    val final: SharedFlow<String> = _final
    val error: SharedFlow<String> = _error

    private var recognizer: SpeechRecognizer? = null
    val isListening: Boolean get() = recognizer != null

    /** True when at least one system recognizer is available. */
    fun isServiceAvailable(): Boolean = try {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        context.packageManager.resolveActivity(intent, 0) != null
    } catch (_: Exception) {
        false
    }

    fun start(languageTag: String) {
        val engine = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = engine
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                recognizer = null
            }
            override fun onError(errorCode: Int) {
                recognizer = null
                _error.tryEmit(errorMessage(errorCode))
            }
            override fun onResults(results: android.os.Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty().trim()
                recognizer = null
                if (text.isNotEmpty()) _final.tryEmit(text)
            }
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty().trim()
                if (text.isNotEmpty()) _partial.tryEmit(text)
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        engine.startListening(intent)
    }

    fun stop() {
        recognizer?.stopListening()
        recognizer = null
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is missing."
        SpeechRecognizer.ERROR_NO_MATCH -> "Couldn't understand that. Try again."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "The system speech service had a network problem."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Bangla speech pack is not installed on this device. Open Settings → Languages & input to download it."
        else -> "Speech recognition failed (code $code)."
    }
}
