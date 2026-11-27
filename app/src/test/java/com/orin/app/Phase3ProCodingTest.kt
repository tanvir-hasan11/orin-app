package com.orin.app

import com.orin.app.data.FileHistoryManager
import com.orin.app.tools.ExactOrWhitespaceEdit
import com.orin.app.tools.SearchFilesTool
import com.orin.app.tools.TextDiff
import com.orin.app.tools.UnifiedPatchEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Phase3ProCodingTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `exact and whitespace tolerant edit detects ambiguity`() {
        val src = "fun a() {\n    println(1)  \n}\n"
        val res = ExactOrWhitespaceEdit.apply(src, "fun a() {\n    println(1)\n}", "fun a() {\n    println(2)\n}", false)
        assertTrue(res is ExactOrWhitespaceEdit.Result.Ok)
        assertTrue((res as ExactOrWhitespaceEdit.Result.Ok).newText.contains("println(2)"))

        val dup = "x = 1\ny = 2\nx = 1\n"
        val amb = ExactOrWhitespaceEdit.apply(dup, "x = 1", "x = 9", replaceAll = false)
        assertTrue(amb is ExactOrWhitespaceEdit.Result.Ambiguous)

        val all = ExactOrWhitespaceEdit.apply(dup, "x = 1", "x = 9", replaceAll = true)
        assertTrue(all is ExactOrWhitespaceEdit.Result.Ok)
        assertFalse((all as ExactOrWhitespaceEdit.Result.Ok).newText.contains("x = 1"))
    }

    @Test
    fun `unified patch engine applies valid hunks and fails cleanly on mismatch`() {
        val original = "line 1\nline 2\nline 3\nline 4\n"
        val patch = """
            --- a/sample.txt
            +++ b/sample.txt
            @@ -1,4 +1,4 @@
             line 1
            -line 2
            +line TWO
             line 3
             line 4
        """.trimIndent()
        val parsed = UnifiedPatchEngine.parse(patch)
        assertEquals(1, parsed.size)
        val out = UnifiedPatchEngine.applyToText(original, parsed.first().hunks, "sample.txt")
        assertTrue(out.contains("line TWO"))

        val badPatch = """
            --- a/sample.txt
            +++ b/sample.txt
            @@ -1,2 +1,2 @@
            -does not exist
            +replacement
        """.trimIndent()
        var threw = false
        try {
            UnifiedPatchEngine.applyToText(original, UnifiedPatchEngine.parse(badPatch).first().hunks, "sample.txt")
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun `glob matcher handles double star wildcards`() {
        val regex = SearchFilesTool.globToRegex("**/*.kt")
        assertTrue(regex.matches("app/src/Main.kt"))
        assertTrue(regex.matches("Test.kt"))
        assertFalse(regex.matches("app/src/Main.java"))
    }

    @Test
    fun `file history records compressed snapshot and restores verified content`() = runBlocking {
        val ws = tempFolder.newFolder("ws")
        val histDir = tempFolder.newFolder("hist")
        val target = ws.resolve("notes.txt").apply { writeText("v1 hello") }
        val mgr = FileHistoryManager(histDir)

        val snap = mgr.record(ws, "notes.txt", "before_edit")
        assertNotNull(snap)

        target.writeText("v2 modified")
        val versions = mgr.versions(ws, "notes.txt")
        assertEquals(1, versions.size)

        val restoredMsg = mgr.restore(ws, snap!!.id)
        assertTrue(restoredMsg.contains("SHA-256 verified"))
        assertEquals("v1 hello", target.readText())
    }

    @Test
    fun `text diff summarizes added and removed lines`() {
        val summary = TextDiff.summarize("demo.kt", "a\nb\nc", "a\nB2\nc\nd")
        assertTrue(summary.contains("- b"))
        assertTrue(summary.contains("+ B2"))
        assertTrue(summary.contains("+ d"))
    }
}
