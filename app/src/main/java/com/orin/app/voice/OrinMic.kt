package com.orin.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Streams 16 kHz mono PCM from the microphone into [OrinSpeechRecognizer]. */
class OrinMic(private val context: Context) {

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var recorder: AudioRecord? = null
    private var job: Job? = null

    val isRecording: Boolean get() = job?.isActive == true

    suspend fun start(recognizer: OrinSpeechRecognizer): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        if (isRecording) return true

        val bufferSize = maxOf(
            AudioRecord.getMinBufferSize(
                OrinSpeechRecognizer.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ),
            OrinSpeechRecognizer.CHUNK_SIZE * 2,
        )
        if (bufferSize <= 0) return false

        val source = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            OrinSpeechRecognizer.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (source.state != AudioRecord.STATE_INITIALIZED) {
            source.release()
            return false
        }

        try {
            recognizer.prepare()
        } catch (e: Exception) {
            source.release()
            throw e
        }
        recognizer.start()
        recorder = source
        source.startRecording()

        // Launch on our own scope: using withContext here would suspend start()
        // until the utterance ends, so the UI would never learn that listening
        // actually began.
        job = scope.launch {
            val chunk = ShortArray(OrinSpeechRecognizer.CHUNK_SIZE)
            try {
                while (isActive && recorder == source) {
                    val read = source.read(chunk, 0, chunk.size)
                    if (read <= 0) continue
                    var peak = 0
                    for (i in 0 until read) {
                        val magnitude = kotlin.math.abs(chunk[i].toInt())
                        if (magnitude > peak) peak = magnitude
                    }
                    _level.value = (peak / 32_768f).coerceIn(0f, 1f)
                    if (recognizer.accept(chunk, read)) break
                }
            } catch (_: CancellationException) {
                // Normal stop.
            } catch (e: Exception) {
                // Surface model/audio errors instead of silently going dead.
                recognizer.reportError("Voice input stopped: ${e.message ?: "unknown error"}")
            } finally {
                runCatching { source.stop() }
                source.release()
                if (recorder == source) recorder = null
                _level.value = 0f
            }
        }
        return true
    }

    fun stop(recognizer: OrinSpeechRecognizer) {
        job?.cancel()
        job = null
        recognizer.cancel()
    }

    fun release() {
        job?.cancel()
        job = null
        recorder?.let { runCatching { it.stop(); it.release() } }
        recorder = null
        scope.cancel()
        _level.value = 0f
    }
}
