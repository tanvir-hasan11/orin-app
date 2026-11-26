package com.orin.app

import com.orin.app.security.WorkspacePaths
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspacePathsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `nested path stays inside the workspace`() {
        val root = temporaryFolder.newFolder("workspace")
        val resolved = WorkspacePaths.resolve(root, "notes/today.md")
        assertTrue(resolved.canonicalPath.startsWith(root.canonicalPath))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parent traversal is rejected`() {
        val root = temporaryFolder.newFolder("workspace")
        WorkspacePaths.resolve(root, "../workspace-evil/secret.txt")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sibling prefix is not accepted`() {
        val root = temporaryFolder.newFolder("workspace")
        temporaryFolder.newFolder("workspace-evil")
        WorkspacePaths.resolve(root, "../workspace-evil/secret.txt")
    }

    @Test
    fun `absolute-looking path is treated as relative`() {
        val root = temporaryFolder.newFolder("workspace")
        val resolved = WorkspacePaths.resolve(root, "/etc/passwd")
        assertTrue(resolved.canonicalPath.startsWith(root.canonicalPath))
    }
}
