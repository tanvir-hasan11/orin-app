package com.orin.app

import com.orin.app.security.MemoryRules
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryRulesTest {

    @Test
    fun `normal notes are allowed`() {
        assertNull(MemoryRules.rejectionReason("parking", "Level 2, row B"))
        assertNull(MemoryRules.rejectionReason("favourite colour", "blue"))
    }

    @Test
    fun `pin and otp shaped values are refused`() {
        assertNotNull(MemoryRules.rejectionReason("wifi", "password: 1234"))
        assertNotNull(MemoryRules.rejectionReason("code", "482913"))
    }

    @Test
    fun `card numbers are refused`() {
        assertNotNull(MemoryRules.rejectionReason("card", "4111 1111 1111 1111"))
    }

    @Test
    fun `api keys are refused`() {
        assertNotNull(MemoryRules.rejectionReason("key", "api_key: sk-abcdefgh12345678"))
    }

    @Test
    fun `oversized notes are refused`() {
        assertNotNull(MemoryRules.rejectionReason("long", "a".repeat(1_001)))
        assertNotNull(MemoryRules.rejectionReason("a".repeat(81), "value"))
    }
}
