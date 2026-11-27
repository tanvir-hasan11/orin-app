package com.orin.app.control

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Click-by-sight for icon-only buttons, canvases, and unlabelled controls (ported from JARVIS).
 * Sends a transient gridded screenshot to Gemini Flash using the user's Gemini API key;
 * never logs or saves the screenshot.
 */
class SightAim(
    private val apiKey: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    sealed interface Outcome {
        data class Found(val x: Float, val y: Float, val sees: String) : Outcome
        data object NotFound : Outcome
        data class Failed(val message: String) : Outcome
    }

    private val jsonParser = Json { ignoreUnknownKeys = true }

    suspend fun locate(shot: Bitmap, target: String): Outcome = withContext(Dispatchers.IO) {
        val coarse = AimGrid.coarse(shot.width.toFloat(), shot.height.toFloat())
        val first = ask(shot, coarse, target, fine = false) ?: return@withContext Outcome.Failed(FAILED)
        val cell = first.cell?.let(coarse::cell) ?: return@withContext Outcome.NotFound
        val around = coarse.around(first.cell) ?: return@withContext Outcome.Found(cell.centerX, cell.centerY, first.sees)
        val fine = AimGrid.fine(around)
        val second = ask(shot, fine, target, fine = true)
        val exact = second?.cell?.let(fine::cell)
        val finalBox = exact ?: cell
        val sees = (if (exact != null) second.sees else "").ifEmpty { first.sees }
        Outcome.Found(finalBox.centerX, finalBox.centerY, sees)
    }

    private fun ask(shot: Bitmap, grid: AimGrid, target: String, fine: Boolean): AimAnswer? {
        val image = Base64.encodeToString(jpeg(draw(shot, grid)), Base64.NO_WRAP)
        val body = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", AimReply.prompt(target, grid, fine)) })
                        add(buildJsonObject {
                            putJsonObject("inline_data") {
                                put("mime_type", "image/jpeg")
                                put("data", image)
                            }
                        })
                    })
                })
            })
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("temperature", 0)
            }
        }
        val request = Request.Builder()
            .url("$ENDPOINT/$MODEL:generateContent")
            .header("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val raw = response.body?.string().orEmpty()
                val text = jsonParser.parseToJsonElement(raw).jsonObject["candidates"]
                    ?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("content")?.jsonObject
                    ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
                    ?.get("text")?.jsonPrimitive?.contentOrNull
                    ?: return null
                AimReply.parse(text)
            }
        }.getOrNull()
    }

    private fun draw(shot: Bitmap, grid: AimGrid): Bitmap {
        val a = grid.area
        val left = a.left.toInt().coerceIn(0, shot.width - 1)
        val top = a.top.toInt().coerceIn(0, shot.height - 1)
        val width = a.width.toInt().coerceIn(1, shot.width - left)
        val height = a.height.toInt().coerceIn(1, shot.height - top)
        val crop = Bitmap.createBitmap(shot, left, top, width, height)
        val scale = minOf(1f, MAX_SIDE / maxOf(width.toFloat(), height.toFloat()))
        val out = Bitmap.createScaledBitmap(
            crop,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        ).copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val line = Paint().apply {
            color = Color.MAGENTA
            strokeWidth = 2f
            alpha = 200
            style = Paint.Style.STROKE
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = LABEL_PX
            isFakeBoldText = true
        }
        val tag = Paint().apply {
            color = Color.MAGENTA
            alpha = 210
        }
        for ((label, box) in grid.cells()) {
            val x = (box.left - a.left) * scale
            val y = (box.top - a.top) * scale
            canvas.drawRect(x, y, x + box.width * scale, y + box.height * scale, line)
            canvas.drawRect(x, y, x + text.measureText(label) + 6f, y + LABEL_PX + 4f, tag)
            canvas.drawText(label, x + 3f, y + LABEL_PX, text)
        }
        return out
    }

    private fun jpeg(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()

    private companion object {
        const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
        const val MODEL = "gemini-2.5-flash"
        const val MAX_SIDE = 1024f
        const val LABEL_PX = 22f
        const val FAILED = "Gemini couldn't inspect the screen right now."
    }
}
