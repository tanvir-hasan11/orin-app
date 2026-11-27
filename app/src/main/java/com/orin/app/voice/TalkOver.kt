package com.orin.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loudspeaker barge-in detector using [EchoCanceller] + [LiveSpeaker] reference audio.
 */
class TalkOver(
    private val speaker: LiveSpeaker,
    private val level: Int = VOICE,
    private val chunks: Int = CHUNKS,
) {
    private val aec = EchoCanceller.create()
    private val mic = ShortArray(EchoCanceller.CHUNK)
    private val ref = ShortArray(EchoCanceller.CHUNK)
    private val held = ArrayDeque<ByteArray>()
    private var attenuation = 1.0
    private var echoChunks = 0
    private var chunk = 0
    private var readyAt = -1

    val ready: Boolean get() = echoChunks >= LEARN_CHUNKS && attenuation < GOOD_ATTENUATION
    val available: Boolean get() = aec != null

    fun clean(pcm: ByteArray): ByteArray {
        val canceller = aec ?: return pcm
        if (pcm.size != EchoCanceller.CHUNK * 2) return pcm
        ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(mic)
        speaker.reference(ref, REF_BACK)
        canceller.process(mic, ref)
        return ByteArray(pcm.size).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(canceller.cleaned)
        }
    }

    fun duringReply(raw: ByteArray, cleaned: ByteArray): List<ByteArray>? {
        val before = pcmLevel(raw)
        val after = pcmLevel(cleaned)
        val voice = after >= level && after >= before * maxOf(VOICE_KEPT, 2 * attenuation)
        if (ready && readyAt < 0) readyAt = chunk
        chunk++
        if (!voice || !ready) {
            if (before > ECHO_LEVEL) {
                attenuation = 0.7 * attenuation + 0.3 * (after.toDouble() / before)
                echoChunks++
            }
            held.clear()
            return null
        }
        held.addLast(cleaned)
        if (held.size < chunks) return null
        return held.toList().also { held.clear() }
    }

    fun reset(newConversation: Boolean = false) {
        held.clear()
        if (newConversation) attenuation = 1.0
        echoChunks = if (newConversation) 0 else LEARN_CHUNKS - REPLY_CHUNKS
        chunk = 0
        readyAt = -1
    }

    fun close() {
        aec?.close()
    }

    companion object {
        const val VOICE = 1_000
        const val CHUNKS = 3
        private const val VOICE_KEPT = 0.35
        private const val ECHO_LEVEL = 800
        private const val LEARN_CHUNKS = 5
        private const val REPLY_CHUNKS = 3
        private const val GOOD_ATTENUATION = 0.25
        private const val REF_BACK = EchoCanceller.RATE * 60 / 1000
    }
}
