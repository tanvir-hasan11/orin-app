package com.orin.app.agent

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import com.orin.app.llm.LlmAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads user-picked image/PDF files via ContentResolver within a safe size cap.
 */
object AttachmentCodec {
    private const val MAX_BYTES = 4 * 1024 * 1024 // 4 MB cap per file

    suspend fun loadFromUri(context: Context, uri: Uri): Result<LlmAttachment> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            var name = uri.lastPathSegment ?: "attachment"
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cursor.getString(idx) ?: name
                }
            }
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Cannot open attachment '$name'.")
            require(bytes.size <= MAX_BYTES) {
                "'$name' is ${(bytes.size / (1024 * 1024))} MB (limit 4 MB)."
            }
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            LlmAttachment(name = name, mimeType = mime, base64Data = b64)
        }
    }
}
