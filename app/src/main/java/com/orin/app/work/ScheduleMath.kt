package com.orin.app.work

import java.time.Instant
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Pure schedule calculator supporting daily HH:mm, intervals (15m/2h/1d), and 5-field cron.
 */
object ScheduleMath {

    private val intervalRegex = Regex("""^(\d+)\s*([mhd])$""", RegexOption.IGNORE_CASE)

    fun nextRunAt(
        scheduleType: String,
        scheduleParam: String,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val param = scheduleParam.trim()
        return when (scheduleType.trim().lowercase()) {
            "daily_time" -> nextDailyTime(param, nowMs, zone)
            "interval" -> nextInterval(param, nowMs)
            "cron" -> nextCron(param, nowMs, zone)
            else -> nowMs + 24 * 60 * 60_000L
        }
    }

    fun nextDailyTime(hhMm: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val parts = hhMm.split(":")
        val hour = parts.getOrNull(0)?.trim()?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        val minute = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        var candidate = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        if (!candidate.toInstant().toEpochMilli().let { it > nowMs }) {
            candidate = candidate.plusDays(1)
        }
        return candidate.toInstant().toEpochMilli()
    }

    fun nextInterval(param: String, nowMs: Long): Long {
        val match = intervalRegex.matchEntire(param.trim())
        val amount = match?.groupValues?.get(1)?.toLongOrNull()
            ?: param.trim().toLongOrNull()?.coerceAtLeast(15L)
            ?: 60L
        val unit = match?.groupValues?.get(2)?.lowercase() ?: "m"
        val ms = when (unit) {
            "d" -> amount.coerceAtLeast(1L) * 24 * 60 * 60_000L
            "h" -> amount.coerceAtLeast(1L) * 60 * 60_000L
            else -> amount.coerceAtLeast(15L) * 60_000L
        }
        return nowMs + ms
    }

    /**
     * Evaluates a standard 5-field cron expression ("min hour dom mon dow") for the next
     * matching timestamp strictly after [nowMs] within the next 366 days.
     */
    fun nextCron(expr: String, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val fields = expr.trim().split(Regex("""\s+"""))
        if (fields.size != 5) return nowMs + 60 * 60_000L
        val minutes = parseField(fields[0], 0, 59)
        val hours = parseField(fields[1], 0, 23)
        val daysOfMonth = parseField(fields[2], 1, 31)
        val months = parseField(fields[3], 1, 12)
        val daysOfWeek = parseField(fields[4], 0, 7).map { if (it == 7) 0 else it }.toSet()

        var cursor = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
            .truncatedTo(ChronoUnit.MINUTES)
            .plusMinutes(1)

        val limit = 366 * 24 * 60
        for (i in 0 until limit) {
            val cronDow = cursor.dayOfWeek.value % 7 // Sunday = 0, Monday = 1 ... Saturday = 6
            if (cursor.minute in minutes &&
                cursor.hour in hours &&
                cursor.dayOfMonth in daysOfMonth &&
                cursor.monthValue in months &&
                cronDow in daysOfWeek
            ) {
                return cursor.toInstant().toEpochMilli()
            }
            cursor = cursor.plusMinutes(1)
        }
        return nowMs + 24 * 60 * 60_000L
    }

    private fun parseField(spec: String, min: Int, max: Int): Set<Int> {
        val out = LinkedHashSet<Int>()
        for (part in spec.split(",")) {
            val token = part.trim()
            if (token.isEmpty()) continue
            val stepSplit = token.split("/")
            val rangePart = stepSplit[0]
            val step = stepSplit.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val (start, end) = when {
                rangePart == "*" -> min to max
                "-" in rangePart -> {
                    val bounds = rangePart.split("-")
                    val a = bounds.getOrNull(0)?.toIntOrNull()?.coerceIn(min, max) ?: min
                    val b = bounds.getOrNull(1)?.toIntOrNull()?.coerceIn(min, max) ?: max
                    minOf(a, b) to maxOf(a, b)
                }
                else -> {
                    val single = rangePart.toIntOrNull()?.coerceIn(min, max) ?: continue
                    if (stepSplit.size > 1) single to max else single to single
                }
            }
            var v = start
            while (v <= end) {
                out.add(v)
                v += step
            }
        }
        return if (out.isEmpty()) (min..max).toSet() else out
    }
}
