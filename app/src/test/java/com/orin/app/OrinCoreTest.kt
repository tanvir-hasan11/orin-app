package com.orin.app

import com.orin.app.data.AuditLogEntity
import com.orin.app.data.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrinCoreTest {

    @Test
    fun `task entity defaults are valid`() {
        val task = TaskEntity(id = "t1", title = "Review Meta Ads")
        assertEquals("normal", task.priority)
        assertFalse(task.completed)
    }

    @Test
    fun `audit log entity captures status cleanly`() {
        val log = AuditLogEntity(id = "a1", tool = "device_control", action = "launch_app", detail = "WhatsApp", ok = true)
        assertTrue(log.ok)
        assertEquals("WhatsApp", log.detail)
    }
}
