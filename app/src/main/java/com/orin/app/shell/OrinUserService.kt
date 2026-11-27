package com.orin.app.shell

import android.content.Context
import android.os.Process
import androidx.annotation.Keep
import com.orin.app.IOrinShellService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs inside Shizuku's privileged shell process when bound.
 */
@Keep
class OrinUserService() : IOrinShellService.Stub() {

    @Keep
    constructor(context: Context?) : this()

    override fun destroy() {
        System.exit(0)
    }

    override fun exit() {
        destroy()
    }

    override fun getUid(): Int = Process.myUid()

    override fun exec(cmd: Array<String>, dir: String?, maxBytes: Int, timeoutMs: Int): String {
        if (cmd.isEmpty()) return "exit=-1\ntimeout=0\nEmpty command."
        return runCatching {
            val pb = ProcessBuilder(*cmd).redirectErrorStream(true)
            if (!dir.isNullOrBlank()) {
                val f = File(dir)
                if (f.exists() && f.isDirectory) pb.directory(f)
            }
            val process = pb.start()
            val captured = ByteArrayOutputStream()
            val drainer = Thread {
                runCatching {
                    val buf = ByteArray(4096)
                    val cap = maxBytes.coerceIn(1024, 256_000)
                    while (captured.size() < cap) {
                        val n = process.inputStream.read(buf, 0, minOf(buf.size, cap - captured.size()))
                        if (n <= 0) break
                        captured.write(buf, 0, n)
                    }
                }
            }.apply { isDaemon = true; start() }

            val finished = process.waitFor(timeoutMs.toLong().coerceIn(500L, 120_000L), TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                drainer.join(500)
                return "exit=-1\ntimeout=1\n${captured.toString(Charsets.UTF_8.name())}"
            }
            drainer.join(1000)
            "exit=${process.exitValue()}\ntimeout=0\n${captured.toString(Charsets.UTF_8.name())}"
        }.getOrElse { err ->
            "exit=-1\ntimeout=0\n${err.message ?: "exec failed"}"
        }
    }
}
