package com.orin.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Ties speech input, microphone, speaker and the chosen language together.
 *
 * Bangla uses the device's own recognizer (bn-BD), which is the most accurate
 * option available without shipping a large model. Hindi and English use the
 * bundled offline Vosk model. Speech output always uses platform TTS.
 */
class OrinVoiceController(private val context: Context) {

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

    private val vosk = OrinSpeechRecognizer(context)
    private val platform = PlatformSpeechRecognizer(context)
    private val mic = OrinMic(context)
    private val speaker = OrinSpeaker(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var language: String = OrinVoice.DEFAULT_LANGUAGE

    val level = mic.level
    val isListening: Boolean get() = mic.isRecording || platform.isListening
    val hasRecordPermission: Boolean
        get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    init {
        scope.launch { vosk.partial.collect { _partial.emit(it) } }
        scope.launch { vosk.final.collect { _final.emit(it) } }
        scope.launch { vosk.error.collect { _error.emit(it) } }
        scope.launch { platform.partial.collect { _partial.emit(it) } }
        scope.launch { platform.final.collect { _final.emit(it) } }
        scope.launch {
            platform.error.collect { message ->
                _error.emit(message)
                // A missing Bangla language pack must not leave voice mode dead.
                if (language.startsWith("bn")) {
                    language = "hi-IN"
                    _error.emit("Hindi offline মডেলে ফিরে গেছি।")
                    mic.start(vosk)
                }
            }
        }
    }

    fun initSpeaker(onReady: (Boolean) -> Unit = {}) = speaker.init(onReady)

    fun setLanguage(tag: String) {
        language = tag
        speaker.setLanguage(tag)
    }

    suspend fun startListening(): Boolean {
        if (!hasRecordPermission) return false
        // Half duplex: never listen while the assistant is speaking.
        speaker.stop()
        return if (language.startsWith("bn") && platform.isServiceAvailable()) {
            _partial.tryEmit("")
            platform.start("bn-BD")
            true
        } else {
            if (language.startsWith("bn")) {
                _error.tryEmit("বাংলা স্পিচ সার্ভিস পাওয়া যায়নি — Hindi offline মডেল ব্যবহার করছি।")
            }
            mic.start(vosk)
        }
    }

    fun stopListening() {
        mic.stop(vosk)
        platform.stop()
    }

    fun speakReply(text: String, onDone: (() -> Unit)? = null) {
        stopListening()
        speaker.speak(text, onDone)
    }

    fun stopSpeaking() = speaker.stop()

    fun release() {
        stopListening()
        mic.release()
        scope.cancel()
        vosk.close()
        platform.destroy()
        speaker.shutdown()
    }
}
