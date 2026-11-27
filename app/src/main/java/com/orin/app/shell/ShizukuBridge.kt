package com.orin.app.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.orin.app.IOrinShellService
import com.orin.app.data.KeyStoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

enum class AccessMode(val id: String, val label: String) {
    NORMAL("normal", "Normal"),
    SHIZUKU("shizuku", "Shizuku"),
    AUTO("auto", "Auto"),
    ;

    companion object {
        fun from(raw: String?): AccessMode =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) } ?: AUTO
    }
}

object SecretRedactor {
    fun redact(text: String, keys: KeyStoreManager?): String {
        if (keys == null || text.isEmpty()) return text
        var out = text
        val secretKeys = listOf(
            "api_key_gemini",
            "api_key_anthropic",
            "api_key_openai",
            "api_key_deepseek",
            "api_key_custom",
            "meta_ads_token",
            "search_key_brave",
            "search_key_tavily",
            "search_key_serper",
            "search_key_serpapi",
        )
        for (k in secretKeys) {
            val secret = keys.getSecret(k)?.takeIf { it.length >= 8 } ?: continue
            out = out.replace(secret, "[REDACTED]")
        }
        return out
    }
}

data class ShellExecResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    val tier: String = "normal",
)

object ShizukuBridge {
    private const val REQ_CODE = 4201
    private const val SHIZUKU_PKG = "moe.shizuku.privileged.api"

    @Volatile
    private var service: IOrinShellService? = null

    private val _status = MutableStateFlow("Not checked")
    val status: StateFlow<String> = _status.asStateFlow()

    fun accessMode(keys: KeyStoreManager): AccessMode =
        AccessMode.from(keys.getSecret("shell_access_mode"))

    fun setAccessMode(keys: KeyStoreManager, mode: AccessMode) =
        keys.putSecret("shell_access_mode", mode.id)

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationInfo(SHIZUKU_PKG, 0).enabled
    }.getOrDefault(false)

    fun isBinderAlive(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun hasPermission(): Boolean = isBinderAlive() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun refreshStatus(context: Context): String {
        val label = when {
            !isBinderAlive() -> if (isInstalled(context)) "Shizuku installed · service stopped" else "Shizuku not running"
            !hasPermission() -> "Shizuku running · tap to grant permission"
            service != null -> "Shizuku connected (UID ${runCatching { service?.uid }.getOrNull() ?: 2000})"
            else -> "Shizuku granted · ready to bind"
        }
        _status.value = label
        return label
    }

    fun requestPermission(context: Context) {
        if (!isBinderAlive()) {
            refreshStatus(context)
            return
        }
        if (hasPermission()) {
            refreshStatus(context)
            return
        }
        runCatching {
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode == REQ_CODE) {
                        Shizuku.removeRequestPermissionResultListener(this)
                        refreshStatus(context)
                    }
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQ_CODE)
        }
    }

    private suspend fun ensureBound(context: Context): IOrinShellService? {
        service?.let { if (it.asBinder().isBinderAlive) return it else service = null }
        if (!hasPermission()) return null
        return withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine { cont ->
                val args = Shizuku.UserServiceArgs(
                    ComponentName(context.packageName, OrinUserService::class.java.name),
                )
                    .daemon(false)
                    .processNameSuffix("orin_shizuku")
                    .debuggable(false)
                    .version(1)

                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val stub = binder?.let { IOrinShellService.Stub.asInterface(it) }
                        service = stub
                        refreshStatus(context)
                        if (cont.isActive) cont.resume(stub)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        service = null
                        refreshStatus(context)
                    }
                }
                runCatching {
                    Shizuku.bindUserService(args, conn)
                }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    suspend fun exec(
        context: Context,
        command: String,
        cwd: File,
        keys: KeyStoreManager? = null,
        requestedAccess: String? = null,
        timeoutSeconds: Int = 25,
    ): ShellExecResult = withContext(Dispatchers.IO) {
        val mode = when {
            !requestedAccess.isNullOrBlank() -> AccessMode.from(requestedAccess)
            keys != null -> accessMode(keys)
            else -> AccessMode.NORMAL
        }
        val shouldTryShizuku = mode == AccessMode.SHIZUKU || (mode == AccessMode.AUTO && hasPermission())
        if (shouldTryShizuku) {
            val svc = ensureBound(context)
            if (svc != null) {
                val raw = runCatching {
                    svc.exec(arrayOf("sh", "-c", command), cwd.absolutePath, 64_000, timeoutSeconds * 1000)
                }.getOrNull()
                if (raw != null) {
                    val lines = raw.split('\n', limit = 3)
                    val exit = lines.getOrNull(0)?.removePrefix("exit=")?.trim()?.toIntOrNull() ?: -1
                    val timeout = lines.getOrNull(1)?.removePrefix("timeout=")?.trim() == "1"
                    val out = SecretRedactor.redact(lines.getOrNull(2).orEmpty(), keys)
                    return@withContext ShellExecResult(exit, out, timeout, tier = "shizuku")
                }
            }
            if (mode == AccessMode.SHIZUKU) {
                return@withContext ShellExecResult(
                    exitCode = -1,
                    output = "Shizuku mode selected, but Shizuku is not connected/permitted.",
                    tier = "shizuku",
                )
            }
        }
        execLocal(command, cwd, keys, timeoutSeconds)
    }

    private fun execLocal(
        command: String,
        cwd: File,
        keys: KeyStoreManager?,
        timeoutSeconds: Int,
    ): ShellExecResult = runCatching {
        val tempDir = File(cwd, ".tmp").apply { mkdirs() }
        val pb = ProcessBuilder("sh", "-c", command)
            .directory(cwd)
            .redirectErrorStream(true)
        pb.environment()["HOME"] = cwd.absolutePath
        pb.environment()["TMPDIR"] = tempDir.absolutePath
        val process = pb.start()
        val captured = ByteArrayOutputStream()
        val drainer = Thread {
            runCatching {
                val buf = ByteArray(8192)
                while (captured.size() < 16_000) {
                    val n = process.inputStream.read(buf, 0, minOf(buf.size, 16_000 - captured.size()))
                    if (n <= 0) break
                    captured.write(buf, 0, n)
                }
            }
        }.apply { isDaemon = true; start() }

        val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            drainer.join(1_000)
            return@runCatching ShellExecResult(
                exitCode = -1,
                output = SecretRedactor.redact(captured.toString(Charsets.UTF_8.name()), keys),
                timedOut = true,
                tier = "normal",
            )
        }
        drainer.join(2_000)
        ShellExecResult(
            exitCode = process.exitValue(),
            output = SecretRedactor.redact(captured.toString(Charsets.UTF_8.name()), keys),
            timedOut = false,
            tier = "normal",
        )
    }.getOrElse { err ->
        ShellExecResult(-1, "Shell error: ${err.message}", tier = "normal")
    }
}

data class BgJobInfo(
    val id: String,
    val command: String,
    val cwd: String,
    val logFile: File,
    val startedAtMs: Long,
    val alive: Boolean,
)

/**
 * Tracks long-running workspace background shell processes (dev servers, watchers, builds).
 */
object BgProcessManager {
    private data class Entry(
        val id: String,
        val command: String,
        val cwd: String,
        val logFile: File,
        val startedAtMs: Long,
        val process: Process,
    )

    private val jobs = ConcurrentHashMap<String, Entry>()

    fun start(command: String, cwd: File): BgJobInfo {
        val id = UUID.randomUUID().toString().take(8)
        val logDir = File(cwd, ".orin-bg").apply { mkdirs() }
        val logFile = File(logDir, "$id.log")
        val pb = ProcessBuilder("sh", "-c", command)
            .directory(cwd)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        pb.environment()["HOME"] = cwd.absolutePath
        val proc = pb.start()
        val entry = Entry(id, command, cwd.absolutePath, logFile, System.currentTimeMillis(), proc)
        jobs[id] = entry
        return BgJobInfo(id, command, entry.cwd, logFile, entry.startedAtMs, proc.isAlive)
    }

    fun list(): List<BgJobInfo> = jobs.values.map {
        BgJobInfo(it.id, it.command, it.cwd, it.logFile, it.startedAtMs, it.process.isAlive)
    }.sortedByDescending { it.startedAtMs }

    fun kill(id: String): Boolean {
        val entry = jobs[id] ?: return false
        if (entry.process.isAlive) {
            entry.process.destroy()
            if (!entry.process.waitFor(500, TimeUnit.MILLISECONDS)) {
                entry.process.destroyForcibly()
            }
        }
        return true
    }

    fun tailLog(id: String, maxChars: Int = 2000): String {
        val entry = jobs[id] ?: return "No background job with id '$id'."
        if (!entry.logFile.exists()) return "(no output yet)"
        val text = runCatching { entry.logFile.readText() }.getOrDefault("")
        return if (text.length > maxChars) "...\n" + text.takeLast(maxChars) else text.ifEmpty { "(no output yet)" }
    }
}
