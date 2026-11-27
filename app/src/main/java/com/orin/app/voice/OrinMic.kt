package com.orin.app.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Streams 16 kHz mono PCM from the microphone into [OrinSpeechRecognizer]. */
class OrinMic(private val context: Context) {

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

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

        recognizer.prepare()
        recognizer.start()
        recorder = source
        source.startRecording()

        job = withContext(Dispatchers.Default) {
            launch {
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
                        if (recognizer.accept(chunk, read)) {
                            // A final result ends this listening pass.
                            break
                        }
                    }
                } catch (_: CancellationException) {
                    // Normal stop.
                } finally {
                    runCatching { source.stop() }
                    source.release()
                    if (recorder == source) recorder = null
                    _level.value = 0f
                }
            }
        }
        return true
    }

    fun stop(recognizer: OrinSpeechRecognizer) {
        job?.cancel()
        job = null
        recognizer.cancel()
    }
}
