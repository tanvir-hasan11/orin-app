package com.orin.app.voice

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Offline speech-to-text backed by Vosk.
 *
 * The model ships as an asset (fetched and hash-verified at build time) and is
 * extracted once into app-private storage. No audio ever leaves the device.
 */
class OrinSpeechRecognizer(private val context: Context) {

    private val _partial = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _final = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Interim transcription, replaced as more audio arrives. */
    val partial: SharedFlow<String> = _partial

    /** A completed utterance; emitted once per recognition pass. */
    val final: SharedFlow<String> = _final

    private var model: Model? = null
    private var recognizer: Recognizer? = null

    val isListening: Boolean get() = recognizer != null

    suspend fun prepare() = withContext(Dispatchers.IO) {
        if (model == null) model = Model(modelPath().absolutePath)
    }

    fun start() {
        val current = model ?: return
        recognizer?.close()
        recognizer = Recognizer(current, SAMPLE_RATE.toFloat())
    }

    /**
     * Feeds 16-bit PCM at 16 kHz. Returns true once a final result was emitted,
     * so callers can end the turn without waiting for a silence timeout.
     */
    fun accept(pcm: ShortArray, length: Int = pcm.size): Boolean {
        val current = recognizer ?: return false
        if (length <= 0) return false
        current.acceptWaveForm(pcm, length)
        val finalJson = current.result
        if (finalJson.isNotBlank()) {
            val text = JSONObject(finalJson).optString("text", "").trim()
            current.close()
            recognizer = null
            if (text.isNotEmpty()) _final.tryEmit(text)
            return text.isNotEmpty()
        }
        val partialJson = current.partialResult
        if (partialJson.isNotBlank()) {
            val text = JSONObject(partialJson).optString("partial", "").trim()
            if (text.isNotEmpty()) _partial.tryEmit(text)
        }
        return false
    }

    fun cancel() {
        recognizer?.close()
        recognizer = null
        _partial.tryEmit("")
    }

    fun close() {
        cancel()
        model?.close()
        model = null
    }

    private fun modelPath(): File {
        val target = File(context.filesDir, "vosk-model")
        val marker = File(target, "am/final.mdl")
        if (!marker.exists()) copyAssets("vosk", target)
        return target
    }

    private fun copyAssets(source: String, target: File) {
        val children = context.assets.list(source).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(source).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (child in children) {
            copyAssets("$source/$child", File(target, child))
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_SIZE = 2_048
    }
}
