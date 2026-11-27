package com.orin.app.workspace

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.orin.app.data.KeyStoreManager
import com.orin.app.data.OrinDao
import com.orin.app.data.ProjectEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Manages Orin's local & SAF coding workspaces backed by the Room `projects` table.
 */
object WorkspaceManager {
    const val DEFAULT_PROJECT_ID = "default_workspace"
    private const val PREF_ACTIVE_PROJECT = "active_project_id"

    fun defaultRoot(context: Context): File =
        File(context.filesDir, "workspace").apply { mkdirs() }

    fun activeRoot(context: Context, keys: KeyStoreManager): File {
        val activeId = keys.getSecret(PREF_ACTIVE_PROJECT) ?: DEFAULT_PROJECT_ID
        if (activeId == DEFAULT_PROJECT_ID) return defaultRoot(context)
        val customPath = keys.getSecret("project_path_$activeId")
        if (!customPath.isNullOrBlank()) {
            val f = File(customPath)
            if (f.exists() && f.isDirectory) return f
        }
        return File(context.filesDir, "workspaces/$activeId").apply { mkdirs() }
    }

    suspend fun ensureDefaultProject(context: Context, dao: OrinDao): ProjectEntity {
        val existing = dao.getProject(DEFAULT_PROJECT_ID)
        if (existing != null) return existing
        val root = defaultRoot(context)
        val now = System.currentTimeMillis()
        val project = ProjectEntity(
            id = DEFAULT_PROJECT_ID,
            name = "Default Workspace",
            displayPath = root.absolutePath,
            isSaf = false,
            createdAt = now,
            lastOpenedAt = now,
        )
        dao.upsertProject(project)
        return project
    }

    suspend fun createProject(
        context: Context,
        dao: OrinDao,
        keys: KeyStoreManager,
        name: String,
        customDir: File? = null,
    ): ProjectEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString().take(8)
        val dir = (customDir ?: File(context.filesDir, "workspaces/$id")).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val project = ProjectEntity(
            id = id,
            name = name.trim().ifEmpty { "Project $id" },
            displayPath = dir.absolutePath,
            isSaf = false,
            createdAt = now,
            lastOpenedAt = now,
        )
        dao.upsertProject(project)
        keys.putSecret(PREF_ACTIVE_PROJECT, id)
        keys.putSecret("project_path_$id", dir.absolutePath)
        project
    }

    suspend fun importSafDirectory(
        context: Context,
        dao: OrinDao,
        keys: KeyStoreManager,
        treeUri: Uri,
    ): ProjectEntity = withContext(Dispatchers.IO) {
        val doc = DocumentFile.fromTreeUri(context, treeUri)
        val title = doc?.name?.takeIf { it.isNotBlank() } ?: "SAF Folder"
        val id = UUID.randomUUID().toString().take(8)
        val localMirror = File(context.filesDir, "workspaces/$id").apply { mkdirs() }
        // Mirror readable text files (< 2 MB) into the fast local workspace so grep/git/shell work cleanly.
        doc?.listFiles()?.take(60)?.forEach { child ->
            val childName = child.name ?: return@forEach
            if (child.isFile && child.length() in 0..2_000_000) {
                runCatching {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        File(localMirror, childName).outputStream().use { out -> input.copyTo(out) }
                    }
                }
            }
        }
        val now = System.currentTimeMillis()
        val project = ProjectEntity(
            id = id,
            name = title,
            displayPath = localMirror.absolutePath,
            isSaf = true,
            createdAt = now,
            lastOpenedAt = now,
        )
        dao.upsertProject(project)
        keys.putSecret(PREF_ACTIVE_PROJECT, id)
        keys.putSecret("project_path_$id", localMirror.absolutePath)
        project
    }

    suspend fun selectProject(dao: OrinDao, keys: KeyStoreManager, project: ProjectEntity) {
        keys.putSecret(PREF_ACTIVE_PROJECT, project.id)
        keys.putSecret("project_path_${project.id}", project.displayPath)
        dao.upsertProject(project.copy(lastOpenedAt = System.currentTimeMillis()))
    }

    fun activeProjectId(keys: KeyStoreManager): String =
        keys.getSecret(PREF_ACTIVE_PROJECT) ?: DEFAULT_PROJECT_ID

    fun listRelativeFiles(root: File, limit: Int = 200): List<String> {
        if (!root.exists() || !root.isDirectory) return emptyList()
        val rootPath = root.canonicalFile.toPath()
        val ignored = setOf(".git", ".gradle", "build", "node_modules", ".orin-bg")
        val results = mutableListOf<String>()
        fun walk(dir: File) {
            if (results.size >= limit) return
            val children = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })) ?: return
            for (child in children) {
                if (results.size >= limit) return
                if (child.name in ignored) continue
                if (child.isDirectory) {
                    walk(child)
                } else {
                    val rel = rootPath.relativize(child.canonicalFile.toPath()).toString().replace('\\', '/')
                    results.add(rel)
                }
            }
        }
        walk(root)
        return results
    }
}
