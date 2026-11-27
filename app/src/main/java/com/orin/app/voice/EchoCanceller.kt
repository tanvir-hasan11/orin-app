package com.orin.app.voice

/**
 * SpeexDSP echo canceller and residual-echo suppressor (native, src/main/cpp):
 * removes the assistant's loudspeaker output from the mic so the user can talk over replies.
 */
class EchoCanceller private constructor(private var handle: Long) : AutoCloseable {
    val cleaned = ShortArray(CHUNK)
    val suppressed = ShortArray(CHUNK)

    @Synchronized
    fun process(mic: ShortArray, ref: ShortArray) {
        if (handle != 0L) {
            nativeProcess(handle, mic, ref, cleaned, suppressed)
        } else {
            mic.copyInto(cleaned)
            mic.copyInto(suppressed)
        }
    }

    @Synchronized
    fun reset() {
        if (handle != 0L) nativeReset(handle)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0
    }

    companion object {
        const val RATE = 16_000
        const val CHUNK = 1_600 // 100 ms
        private const val FRAME = 160 // 10 ms
        private const val TAIL = 6_400 // 400 ms tail

        private val loaded = runCatching { System.loadLibrary("orin_aec") }.isSuccess

        fun create(): EchoCanceller? =
            if (!loaded) null else nativeCreate(FRAME, TAIL, RATE).takeIf { it != 0L }?.let(::EchoCanceller)

        @JvmStatic private external fun nativeCreate(frame: Int, tail: Int, rate: Int): Long
        @JvmStatic private external fun nativeProcess(handle: Long, mic: ShortArray, ref: ShortArray, out: ShortArray, suppressed: ShortArray)
        @JvmStatic private external fun nativeReset(handle: Long)
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}
