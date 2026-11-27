package com.orin.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.SharedFlow

/** Ties the offline recognizer, microphone and speaker into one voice session. */
class OrinVoiceController(private val context: Context) {

    private val recognizer = OrinSpeechRecognizer(context)
    private val mic = OrinMic(context)
    private val speaker = OrinSpeaker(context)

    val partial: SharedFlow<String> = recognizer.partial
    val final: SharedFlow<String> = recognizer.final
    val level = mic.level

    val isListening: Boolean get() = mic.isRecording
    val hasRecordPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun initSpeaker(onReady: (Boolean) -> Unit = {}) = speaker.init(onReady)

    suspend fun startListening(): Boolean {
        if (!hasRecordPermission) return false
        // Half duplex: never listen while the assistant is speaking.
        speaker.stop()
        return mic.start(recognizer)
    }

    fun stopListening() = mic.stop(recognizer)

    fun speakReply(text: String, onDone: (() -> Unit)? = null) {
        stopListening()
        speaker.speak(text, onDone)
    }

    fun stopSpeaking() = speaker.stop()

    fun setLanguage(tag: String) = speaker.setLanguage(tag)

    fun release() {
        mic.stop(recognizer)
        recognizer.close()
        speaker.shutdown()
    }
}
