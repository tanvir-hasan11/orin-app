package com.orin.app

import com.orin.app.security.BiometricGate
import com.orin.app.tools.MetaAdMetrics
import com.orin.app.work.ScheduleMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class Phase4AScheduleAndBiometricTest {

    @Test
    fun `daily time chooses today if upcoming otherwise tomorrow`() {
        val zone = ZoneId.of("UTC")
        val morning = ZonedDateTime.of(2026, 11, 27, 7, 30, 0, 0, zone).toInstant().toEpochMilli()
        val expectedSameDay = ZonedDateTime.of(2026, 11, 27, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expectedSameDay, ScheduleMath.nextDailyTime("08:00", morning, zone))

        val later = ZonedDateTime.of(2026, 11, 27, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val expectedNextDay = ZonedDateTime.of(2026, 11, 28, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expectedNextDay, ScheduleMath.nextDailyTime("08:00", later, zone))
    }

    @Test
    fun `cron expression matches next interval accurately`() {
        val zone = ZoneId.of("UTC")
        val base = ZonedDateTime.of(2026, 11, 27, 10, 12, 0, 0, zone).toInstant().toEpochMilli()
        val next = ScheduleMath.nextCron("*/15 * * * *", base, zone)
        val expected = ZonedDateTime.of(2026, 11, 27, 10, 15, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, next)
    }

    @Test
    fun `interval math clamps sub-15m intervals to WorkManager minimum`() {
        val base = 1_000_000L
        assertEquals(base + 15 * 60_000L, ScheduleMath.nextInterval("5m", base))
        assertEquals(base + 2 * 60 * 60_000L, ScheduleMath.nextInterval("2h", base))
    }

    @Test
    fun `biometric gate flags destructive and comms actions only`() {
        assertTrue(BiometricGate.requiresHardwareAuth("Send SMS", "To +88017..."))
        assertTrue(BiometricGate.requiresHardwareAuth("Run shell command", "rm -rf build"))
        assertTrue(BiometricGate.requiresHardwareAuth("Screen control", "Tap Pay now"))
        assertFalse(BiometricGate.requiresHardwareAuth("Read calendar", "List today's events"))
    }

    @Test
    fun `meta ads insights json parses all 6 KPIs`() {
        val sample = """
            {
              "data": [
                {
                  "spend": "42.50",
                  "reach": "12400",
                  "impressions": "18900",
                  "clicks": "512",
                  "ctr": "2.71",
                  "cpc": "0.083",
                  "account_currency": "USD"
                }
              ]
            }
        """.trimIndent()
        val parsed = MetaAdMetrics.parseInsightsJson(sample, "yesterday")
        assertNotNull(parsed)
        assertEquals("42.50", parsed!!.spend)
        assertEquals(4250L, parsed.spendCents)
        assertEquals(12400L, parsed.reach)
        assertEquals(18900L, parsed.impressions)
        assertEquals(512L, parsed.clicks)
        assertEquals(2.71, parsed.ctr, 0.001)
        assertEquals(0.083, parsed.cpc, 0.001)
    }
}
