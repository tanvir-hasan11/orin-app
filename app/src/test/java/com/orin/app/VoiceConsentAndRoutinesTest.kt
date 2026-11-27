package com.orin.app

import com.orin.app.notify.NotificationReplies
import com.orin.app.notify.VoiceConsent
import com.orin.app.tools.OrinRoutine
import com.orin.app.tools.RoutineRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceConsentAndRoutinesTest {

    @Test
    fun `voice consent accepts clear yes in english hindi bangla`() {
        val readBack = "Send \"On my way\" to Rahul on WhatsApp?"
        assertTrue(VoiceConsent.confirms("yes", "On my way", readBack))
        assertTrue(VoiceConsent.confirms("haan bhej do", "On my way", readBack))
        assertTrue(VoiceConsent.confirms("হ্যাঁ পাঠাও", "On my way", readBack))
    }

    @Test
    fun `voice consent rejects no doubt or tts echo`() {
        val readBack = "Send \"I am reaching in 5\" to Rahul on WhatsApp?"
        assertFalse(VoiceConsent.confirms("no wait", "I am reaching in 5", readBack))
        assertFalse(VoiceConsent.confirms("না থামো", "I am reaching in 5", readBack))
        assertFalse(VoiceConsent.confirms("Send I am reaching in 5", "I am reaching in 5", readBack))
        assertFalse(VoiceConsent.confirms("Rahul WhatsApp", "hello", readBack))
    }

    @Test
    fun `notification replies matches partial contact names`() {
        assertTrue(NotificationReplies.sameName("Rahul Sharma", "rahul"))
        assertTrue(NotificationReplies.sameName("Mom", "mom"))
        assertFalse(NotificationReplies.sameName("Rahul Sharma", "amit"))
    }

    @Test
    fun `routine rules finds exact and fuzzy routine names`() {
        val all = listOf(RoutineRules.GOOD_MORNING, OrinRoutine("Night wrap", listOf("Check tomorrow calendar")))
        assertEquals("Good morning", RoutineRules.find("good morning!", all)?.name)
        assertEquals("Night wrap", RoutineRules.find("night", all)?.name)
    }
}
