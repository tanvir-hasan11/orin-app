package com.orin.app

import com.orin.app.security.UrlSafety
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UrlSafetyTest {

    @Test
    fun `https public url passes syntax validation`() {
        val url = UrlSafety.validateSyntax("https://example.com/path?q=1")
        assertEquals("example.com", url.host)
    }

    @Test
    fun `http is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            UrlSafety.validateSyntax("http://example.com")
        }
    }

    @Test
    fun `localhost is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            UrlSafety.validateSyntax("https://localhost/admin")
        }
    }

    @Test
    fun `local hostname suffix is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            UrlSafety.validateSyntax("https://printer.local/status")
        }
    }

    @Test
    fun `missing host is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            UrlSafety.validateSyntax("https:///nohost")
        }
    }
}
