package com.orin.app

import com.orin.app.security.DuplicateGuard
import com.orin.app.security.TurnTrust
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTrustTest {

    @Test
    fun `clean turn does not force extra approval`() {
        val trust = TurnTrust()
        trust.userSpoke()
        assertFalse(trust.outsideText)
        assertFalse(trust.requiresApprovalAfterExternalRead("memory", buildJsonObject { put("action", "remember") }))
        assertFalse(trust.requiresApprovalAfterExternalRead("phone_intents", buildJsonObject { put("action", "whatsapp") }))
    }

    @Test
    fun `reading screen or notifications taints the turn`() {
        val trust = TurnTrust()
        trust.after("screen_control", buildJsonObject { put("action", "ui_read") })
        assertTrue(trust.outsideText)
        assertTrue(trust.requiresApprovalAfterExternalRead("memory", buildJsonObject { put("action", "remember") }))
        assertTrue(trust.requiresApprovalAfterExternalRead("phone_intents", buildJsonObject { put("action", "call") }))
        assertTrue(trust.requiresApprovalAfterExternalRead("shell", buildJsonObject { put("command", "ls") }))
        // Read-only lookups remain unblocked.
        assertFalse(trust.requiresApprovalAfterExternalRead("memory", buildJsonObject { put("action", "recall") }))
        assertFalse(trust.requiresApprovalAfterExternalRead("notifications", buildJsonObject { put("action", "check") }))
    }

    @Test
    fun `user speaking clears taint`() {
        val trust = TurnTrust()
        trust.after("web_fetch", buildJsonObject { put("url", "https://example.com") })
        assertTrue(trust.outsideText)
        trust.userSpoke()
        assertFalse(trust.outsideText)
    }

    @Test
    fun `duplicate guard blocks repeated sensitive calls within window`() {
        var now = 1_000L
        val guard = DuplicateGuard(windowMs = 5_000L) { now }
        assertTrue(guard.allow("phone_sms", """{"action":"send","recipient":"123"}"""))
        assertFalse(guard.allow("phone_sms", """{"action":"send","recipient":"123"}"""))
        assertTrue(guard.allow("phone_sms", """{"action":"send","recipient":"456"}"""))
        guard.forget("phone_sms", """{"action":"send","recipient":"123"}""")
        assertTrue(guard.allow("phone_sms", """{"action":"send","recipient":"123"}"""))
        now += 6_000L
        assertTrue(guard.allow("phone_sms", """{"action":"send","recipient":"456"}"""))
    }
}
