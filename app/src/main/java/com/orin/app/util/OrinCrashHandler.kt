package com.orin.app.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught exceptions to app-private storage so a remote user can share
 * the exact stack trace instead of saying "it crashes".
 */
class OrinCrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val previous = Thread.getDefaultUncaughtExceptionHandler()
    private val directory = File(context.filesDir, "crashes").apply { mkdirs() }

    override fun uncaughtException(thread: Thread, error: Throwable) {
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            File(directory, "crash-$stamp.txt").writeText(buildString {
                appendLine("Thread: ${thread.name} (${thread.id})")
                appendLine("Time: $stamp")
                appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (API ${android.os.Build.VERSION.SDK_INT})")
                appendLine()
                appendStackTrace(error)
            })
        }
        previous?.uncaughtException(thread, error)
    }

    private fun StringBuilder.appendStackTrace(error: Throwable, depth: Int = 0) {
        appendLine("${"  ".repeat(depth)}${error.javaClass.name}: ${error.message}")
        error.stackTrace.take(25).forEach { appendLine("${"  ".repeat(depth)}  at $it") }
        error.cause?.let { cause ->
            if (cause !== error && depth < 3) {
                appendLine("${"  ".repeat(depth)}Caused by:")
                appendStackTrace(cause, depth + 1)
            }
        }
    }

    companion object {
        fun install(context: Context) {
            val current = Thread.getDefaultUncaughtExceptionHandler()
            if (current !is OrinCrashHandler) {
                Thread.setDefaultUncaughtExceptionHandler(OrinCrashHandler(context.applicationContext))
            }
        }

        fun lastCrash(context: Context): Pair<String, String>? {
            val directory = File(context.filesDir, "crashes")
            val file = directory.listFiles()?.filter { it.name.startsWith("crash-") }?.maxByOrNull { it.lastModified() }
                ?: return null
            return file.name to file.readText().take(8_000)
        }

        fun clear(context: Context) {
            File(context.filesDir, "crashes").listFiles()?.forEach { it.delete() }
        }
    }
}
