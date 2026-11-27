package com.orin.app.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

/** 16 kHz mono PCM capture in 100 ms chunks for Gemini Live streaming. */
class LiveMicRecorder(private val onChunk: (ByteArray) -> Unit) {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val rate = 16_000
        val chunkBytes = rate / 10 * 2
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, chunkBytes * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        val echo = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId) else null
        val noise = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId) else null
        val effects = listOfNotNull(echo, noise).onEach { it.enabled = true }

        running = true
        record.startRecording()
        thread = Thread({
            val buffer = ByteArray(chunkBytes)
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) onChunk(buffer.copyOf(read))
            }
            runCatching { record.stop() }
            effects.forEach { runCatching { it.release() } }
            record.release()
        }, "orin-live-mic").apply { start() }
        return true
    }

    fun stop() {
        running = false
        thread = null
    }
}
