package com.orin.app

import com.orin.app.control.ControlRules
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRulesTest {

    @Test
    fun `banking and payment apps are blocked`() {
        assertNotNull(ControlRules.rejectionReason("com.phonepe.app", "PhonePe"))
        assertNotNull(ControlRules.rejectionReason("com.android.vending", "Play Store"))
        assertNotNull(ControlRules.rejectionReason("com.android.settings", "Settings"))
        assertNotNull(ControlRules.rejectionReason("com.example.banking.app", "My Bank"))
    }

    @Test
    fun `normal apps are allowed`() {
        assertNull(ControlRules.rejectionReason("com.whatsapp", "WhatsApp"))
        assertNull(ControlRules.rejectionReason("org.mozilla.firefox", "Firefox"))
    }

    @Test
    fun `money actions are refused`() {
        assertTrue(ControlRules.riskOf("Pay ₹500 to the seller") == ControlRules.Risk.MONEY)
        assertTrue(ControlRules.riskOf("भुगतान करो") == ControlRules.Risk.MONEY)
    }

    @Test
    fun `secrets are never typed`() {
        assertFalse(ControlRules.canType("my PIN is 4832"))
        assertFalse(ControlRules.canType("card 4111 1111 1111 1111"))
        assertTrue(ControlRules.canType("hello world"))
    }

    @Test
    fun `outgoing actions need approval`() {
        assertTrue(ControlRules.riskOf("Send the message") == ControlRules.Risk.OUTGOING)
        assertTrue(ControlRules.riskOf("Delete this note") == ControlRules.Risk.DESTRUCTIVE)
    }
}
