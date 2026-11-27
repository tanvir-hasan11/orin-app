package com.orin.app.data

import com.orin.app.security.WorkspacePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

@Serializable
data class FileVersionMeta(
    val id: String,
    val workspace: String,
    val path: String,
    val sha256: String,
    val bytes: Int,
    val createdAt: Long,
    val reason: String,
    val deleted: Boolean = false,
)

/**
 * Durable gzipped snapshot history for workspace files with SHA-256 integrity checks.
 */
class FileHistoryManager(private val historyDir: File) {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    private fun readMetaList(): List<FileVersionMeta> = historyDir.listFiles().orEmpty()
        .filter { it.extension == "json" }
        .mapNotNull { runCatching { json.decodeFromString<FileVersionMeta>(it.readText()) }.getOrNull() }

    suspend fun versions(workspaceRoot: File, relPath: String): List<FileVersionMeta> = withContext(Dispatchers.IO) {
        lock.withLock {
            val wsKey = workspaceRoot.canonicalPath
            val norm = relPath.trim().replace('\\', '/').removePrefix("/")
            readMetaList()
                .filter { it.workspace == wsKey && it.path == norm }
                .sortedByDescending { it.createdAt }
        }
    }

    suspend fun record(workspaceRoot: File, relPath: String, reason: String): FileVersionMeta? =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val wsKey = workspaceRoot.canonicalPath
                val norm = relPath.trim().replace('\\', '/').removePrefix("/")
                val target = WorkspacePaths.resolve(workspaceRoot, norm)
                val previous = readMetaList()
                    .filter { it.workspace == wsKey && it.path == norm }
                    .maxByOrNull { it.createdAt }
                val deleted = !target.exists()
                if (!deleted && !target.isFile) return@withLock null
                if (!deleted && target.length() > MAX_SNAPSHOT_BYTES) return@withLock null

                val raw = if (deleted) ByteArray(0) else target.readBytes()
                val digest = if (deleted) "missing" else sha256Hex(raw)
                if (previous?.sha256 == digest) return@withLock previous

                historyDir.mkdirs()
                val meta = FileVersionMeta(
                    id = UUID.randomUUID().toString().take(12),
                    workspace = wsKey,
                    path = norm,
                    sha256 = digest,
                    bytes = raw.size,
                    createdAt = maxOf(System.currentTimeMillis(), (previous?.createdAt ?: 0L) + 1L),
                    reason = reason,
                    deleted = deleted,
                )
                val gzFile = File(historyDir, "${meta.id}.gz")
                FileOutputStream(gzFile).use { fos ->
                    GZIPOutputStream(fos).use { gz -> gz.write(raw) }
                }
                File(historyDir, "${meta.id}.json").writeText(json.encodeToString(meta))
                meta
            }
        }

    suspend fun readSnapshotBytes(versionId: String): Pair<FileVersionMeta, ByteArray> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val metaFile = File(historyDir, "$versionId.json")
                check(metaFile.exists()) { "Snapshot '$versionId' not found." }
                val meta = json.decodeFromString<FileVersionMeta>(metaFile.readText())
                if (meta.deleted) return@withLock meta to ByteArray(0)
                val gzFile = File(historyDir, "${meta.id}.gz")
                check(gzFile.exists()) { "Snapshot payload missing for '$versionId'." }
                val data = GZIPInputStream(FileInputStream(gzFile)).use { it.readBytes() }
                val actualHash = sha256Hex(data)
                check(actualHash == meta.sha256) {
                    "Snapshot SHA-256 verification failed ($actualHash != ${meta.sha256})."
                }
                meta to data
            }
        }

    suspend fun restore(workspaceRoot: File, versionId: String): String = withContext(Dispatchers.IO) {
        val (meta, bytes) = readSnapshotBytes(versionId)
        val target = WorkspacePaths.resolve(workspaceRoot, meta.path)
        record(workspaceRoot, meta.path, "before_restore:$versionId")
        if (meta.deleted) {
            if (target.exists()) target.delete()
            return@withContext "Restored deletion of ${meta.path}."
        }
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        "Restored ${meta.path} to snapshot $versionId (${bytes.size} bytes, SHA-256 verified)."
    }

    companion object {
        private const val MAX_SNAPSHOT_BYTES = 2 * 1024 * 1024L

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        fun gzipText(text: String): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            return out.toByteArray()
        }
    }
}
