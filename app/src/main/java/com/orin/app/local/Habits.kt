package com.orin.app.local

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * On-device habit tracker (ported from JARVIS Habits): remembers command frequencies,
 * hour-of-day usage, and custom phrases the user taught through normal conversation.
 */
class Habits(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val counts = mutableMapOf<String, Int>()
    private val hours = mutableMapOf<String, IntArray>()
    private val phrases = mutableMapOf<String, String>()
    private var pending: Pair<String, Long>? = null
    private val jsonParser = Json { ignoreUnknownKeys = true }

    init {
        load()
    }

    @Synchronized
    fun record(command: LocalCommand, hour: Int) {
        val key = HabitKeys.key(command) ?: return
        counts[key] = (counts[key] ?: 0) + 1
        hours.getOrPut(key) { IntArray(24) }[hour.coerceIn(0, 23)]++
        save()
    }

    @Synchronized
    fun expect(text: String) {
        pending = LocalRouter.normalize(text)
            .takeIf { it.isNotEmpty() && it.split(" ").size <= MAX_WORDS }
            ?.let { it to clock() }
    }

    @Synchronized
    fun learn(command: LocalCommand) {
        val (phrase, at) = pending ?: return
        pending = null
        val key = HabitKeys.key(command) ?: return
        if (clock() - at > LEARN_WINDOW_MS) return
        phrases[phrase] = key
        if (phrases.size > MAX_PHRASES) phrases.remove(phrases.keys.first())
        save()
    }

    @Synchronized
    fun lookup(alternatives: List<String>): LocalCommand? =
        alternatives.firstNotNullOfOrNull { phrases[LocalRouter.normalize(it)]?.let(HabitKeys::command) }

    @Synchronized
    fun top(n: Int, min: Int = 3): List<LocalCommand> =
        counts.filter { it.value >= min }.entries
            .sortedByDescending { it.value }
            .mapNotNull { HabitKeys.command(it.key) }
            .take(n)

    @Synchronized
    fun summary(hour: Int): String {
        if (counts.isEmpty()) return ""
        val often = counts.entries.sortedByDescending { it.value }.take(5)
            .joinToString(", ") { "${HabitKeys.describe(it.key)} (${it.value}×)" }
        val now = hours.entries.filter { it.value[hour.coerceIn(0, 23)] >= 2 }
            .joinToString(", ") { HabitKeys.describe(it.key) }
        return "Often asks for: $often." + if (now.isNotEmpty()) " Around this hour usually: $now." else ""
    }

    @Synchronized
    fun learnedPhrases(): Int = phrases.size

    @Synchronized
    fun forget() {
        counts.clear()
        hours.clear()
        phrases.clear()
        pending = null
        file.delete()
    }

    private fun load() {
        if (!file.exists()) return
        runCatching {
            val root = jsonParser.parseToJsonElement(file.readText()).jsonObject
            root["counts"]?.jsonObject?.forEach { (k, v) ->
                v.jsonPrimitive.intOrNull?.let { counts[k] = it }
            }
            root["hours"]?.jsonObject?.forEach { (k, v) ->
                val arr = v.jsonArray
                hours[k] = IntArray(24) { idx -> arr.getOrNull(idx)?.jsonPrimitive?.intOrNull ?: 0 }
            }
            root["phrases"]?.jsonObject?.forEach { (k, v) ->
                v.jsonPrimitive.contentOrNull?.let { phrases[k] = it }
            }
        }
    }

    private fun save() {
        val root = buildJsonObject {
            put("counts", buildJsonObject { counts.forEach { (k, v) -> put(k, v) } })
            put("hours", buildJsonObject {
                hours.forEach { (k, arr) ->
                    put(k, buildJsonArray { arr.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                }
            })
            put("phrases", buildJsonObject { phrases.forEach { (k, v) -> put(k, v) } })
        }
        runCatching { file.writeText(root.toString()) }
    }

    companion object {
        private const val LEARN_WINDOW_MS = 60_000L
        private const val MAX_PHRASES = 200
        private const val MAX_WORDS = 8

        @Volatile
        private var instance: Habits? = null

        fun get(context: Context): Habits = instance ?: synchronized(this) {
            instance ?: Habits(File(context.filesDir, "orin_habits.json")).also { instance = it }
        }
    }
}

object HabitKeys {
    fun key(command: LocalCommand): String? = when (command) {
        is LocalCommand.SetVolume -> "volume:${command.percent}"
        is LocalCommand.StepVolume -> if (command.up) "volume:up" else "volume:down"
        is LocalCommand.Mute -> if (command.on) "mute:on" else "mute:off"
        is LocalCommand.Media -> "media:${command.key.name.lowercase()}"
        is LocalCommand.Torch -> if (command.on) "torch:on" else "torch:off"
        LocalCommand.Battery -> "battery"
        LocalCommand.Time -> "time"
        LocalCommand.Date -> "date"
        is LocalCommand.Timer -> "timer:${command.seconds}"
        is LocalCommand.OpenApp -> "app:${command.name.lowercase()}"
        is LocalCommand.Alarm, LocalCommand.Stop -> null
    }

    fun command(key: String): LocalCommand? {
        val (kind, value) = key.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (kind) {
            "volume" -> when (value) {
                "up" -> LocalCommand.StepVolume(true)
                "down" -> LocalCommand.StepVolume(false)
                else -> value.toIntOrNull()?.takeIf { it in 0..100 }?.let { LocalCommand.SetVolume(it) }
            }
            "mute" -> LocalCommand.Mute(value == "on")
            "media" -> MediaKey.entries.firstOrNull { it.name.lowercase() == value }?.let { LocalCommand.Media(it) }
            "torch" -> LocalCommand.Torch(value == "on")
            "battery" -> LocalCommand.Battery
            "time" -> LocalCommand.Time
            "date" -> LocalCommand.Date
            "timer" -> value.toIntOrNull()?.takeIf { it in 1..86_400 }?.let { LocalCommand.Timer(it) }
            "app" -> value.takeIf { it.isNotEmpty() }?.let { LocalCommand.OpenApp(it) }
            else -> null
        }
    }

    fun describe(key: String): String {
        val (kind, value) = key.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when (kind) {
            "volume" -> if (value.toIntOrNull() != null) "volume $value%" else "volume $value"
            "mute" -> if (value == "on") "mute" else "unmute"
            "media" -> "music $value"
            "torch" -> "torch $value"
            "timer" -> "${(value.toIntOrNull() ?: 0) / 60} min timer"
            "app" -> "open ${value.replaceFirstChar { it.uppercase() }}"
            else -> kind
        }
    }
}
