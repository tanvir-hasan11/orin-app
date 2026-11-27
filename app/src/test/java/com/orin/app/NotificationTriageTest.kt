package com.orin.app

import com.orin.app.notify.NotificationTriage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTriageTest {

    @Test
    fun `one time codes are masked in summaries`() {
        val summary = NotificationTriage.summarise(
            listOf(
                NotificationTriage.Item("Bank", "Your OTP is 483921", 0L, true, false),
            ),
        )
        assertFalse(summary.contains("483921"))
        assertTrue(summary.contains("code hidden"))
    }

    @Test
    fun `links are hidden and counted`() {
        val summary = NotificationTriage.summarise(
            listOf(
                NotificationTriage.Item("Friend", "See https://evil.example/x", 0L, false, true),
            ),
        )
        assertFalse(summary.contains("https://evil.example"))
        assertTrue(summary.contains("link hidden"))
    }

    @Test
    fun `empty inbox is reported`() {
        assertTrue(NotificationTriage.summarise(emptyList()).contains("No recent notifications"))
    }
}
