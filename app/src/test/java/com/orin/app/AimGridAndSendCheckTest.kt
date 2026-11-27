package com.orin.app

import com.orin.app.control.AimGrid
import com.orin.app.control.AimReply
import com.orin.app.control.ScreenBox
import com.orin.app.control.SendCheck
import com.orin.app.control.UiNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AimGridAndSendCheckTest {

    @Test
    fun `aim grid coarse and fine coordinates map inside screen`() {
        val coarse = AimGrid.coarse(1080f, 2400f)
        val cell = coarse.cell("C4")
        assertNotNull(cell)
        val around = coarse.around("A1")!!
        assertEquals(0f, around.left, 0.01f)
        assertEquals(0f, around.top, 0.01f)
        val fine = AimGrid.fine(around)
        assertEquals(25, fine.cells().size)
        val center = fine.cell("c3")!!
        assertTrue(center.centerX in 0f..1080f && center.centerY in 0f..2400f)
    }

    @Test
    fun `aim reply parses fenced json and none`() {
        val parsed = AimReply.parse("""```json
            {"cell": "B2", "sees": "Play button"}
        ```""")
        assertEquals("B2", parsed?.cell)
        assertEquals("Play button", parsed?.sees)

        val none = AimReply.parse("""{"cell": "none", "sees": ""}""")
        assertNull(none?.cell)
    }

    @Test
    fun `send check verifies message moved from input field to timeline`() {
        val msg = "I will reach at 7 PM"
        val draft = listOf(
            UiNode(1, "field", msg, "Message", "EditText", "", clickable = true, editable = true, value = msg),
            UiNode(2, "button", "Send", "Send", "ImageButton", "", clickable = true, editable = false),
        )
        assertFalse(SendCheck.sent(draft, msg))

        val sent = listOf(
            UiNode(1, "field", "", "Message", "EditText", "", clickable = true, editable = true, value = ""),
            UiNode(2, "text", "$msg 19:02", "$msg 19:02", "TextView", "", clickable = false, editable = false),
        )
        assertTrue(SendCheck.sent(sent, msg))
    }

    @Test
    fun `aim reply prompt contains target and grid bounds`() {
        val grid = AimGrid(ScreenBox(0f, 0f, 100f, 200f), 4, 5)
        val prompt = AimReply.prompt("red mic button", grid, fine = false)
        assertTrue(prompt.contains("A-D"))
        assertTrue(prompt.contains("1-5"))
        assertTrue(prompt.contains("red mic button"))
    }
}
