@file:OptIn(ExperimentalMaterial3Api::class)

package com.orin.app.ui.code

import android.content.Intent
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.orin.app.agent.OrinAgentRuntime
import com.orin.app.data.FileHistoryManager
import com.orin.app.shell.AccessMode
import com.orin.app.shell.BgJobInfo
import com.orin.app.shell.BgProcessManager
import com.orin.app.shell.ShizukuBridge
import com.orin.app.tools.TextDiff
import com.orin.app.tools.WorkspaceShellTool
import com.orin.app.workspace.WorkspaceManager
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

@Composable
fun OrinCodeScreen(
    runtime: OrinAgentRuntime,
    onBack: () -> Unit,
    onOpenPreviewUrl: (String, String) -> Unit = { _, _ -> },
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = runtime.db.dao()
    val history = remember(context) { FileHistoryManager(File(context.filesDir, "file_history")) }
    val shellTool = remember(context, runtime.keys) { WorkspaceShellTool(context, dao, runtime.keys) }

    val projects by dao.observeProjects().collectAsState(initial = emptyList())
    var activeProjectId by remember { mutableStateOf(WorkspaceManager.activeProjectId(runtime.keys)) }
    var workspaceFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedPath by remember { mutableStateOf<String?>(null) }
    var baselineText by remember { mutableStateOf("") }
    var editorText by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var editorRef by remember { mutableStateOf<CodeEditor?>(null) }

    var showFind by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var showDiffDialog by remember { mutableStateOf(false) }
    var showNewFileDialog by remember { mutableStateOf(false) }
    var newFileName by remember { mutableStateOf("") }
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }

    var showTerminal by remember { mutableStateOf(true) }
    var command by remember { mutableStateOf("") }
    var terminalOut by remember { mutableStateOf("Orin Workspace Shell Ready.") }
    var accessMode by remember { mutableStateOf(ShizukuBridge.accessMode(runtime.keys)) }
    val shizukuStatus by ShizukuBridge.status.collectAsState()
    var bgJobs by remember { mutableStateOf<List<BgJobInfo>>(emptyList()) }
    var refreshTick by remember { mutableIntStateOf(0) }

    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            scope.launch {
                val created = WorkspaceManager.importSafDirectory(context, dao, runtime.keys, uri)
                activeProjectId = created.id
                selectedPath = null
                refreshTick++
            }
        }
    }

    LaunchedEffect(activeProjectId, refreshTick) {
        withContext(Dispatchers.IO) {
            WorkspaceManager.ensureDefaultProject(context, dao)
            val root = WorkspaceManager.activeRoot(context, runtime.keys)
            workspaceFiles = WorkspaceManager.listRelativeFiles(root)
            bgJobs = BgProcessManager.list()
        }
        ShizukuBridge.refreshStatus(context)
    }

    fun openFile(relPath: String) {
        scope.launch {
            val root = WorkspaceManager.activeRoot(context, runtime.keys)
            val content = withContext(Dispatchers.IO) {
                val f = File(root, relPath)
                if (f.exists() && f.isFile && f.length() <= 1_000_000) f.readText() else ""
            }
            selectedPath = relPath
            baselineText = content
            editorText = content
            dirty = false
            editorRef?.apply {
                setEditorLanguage(OrinCodeLanguage(relPath))
                setText(content)
            }
        }
    }

    fun saveCurrentFile() {
        val rel = selectedPath ?: return
        val content = editorRef?.text?.toString() ?: editorText
        scope.launch(Dispatchers.IO) {
            val root = WorkspaceManager.activeRoot(context, runtime.keys)
            val f = File(root, rel)
            f.parentFile?.mkdirs()
            history.record(root, rel, "before_editor_save")
            f.writeText(content)
            history.record(root, rel, "after_editor_save")
            withContext(Dispatchers.Main) {
                baselineText = content
                editorText = content
                dirty = false
                refreshTick++
            }
        }
    }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = scheme.background,
                    titleContentColor = scheme.onBackground,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            selectedPath ?: "Code & Terminal",
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (dirty) {
                            Text("Unsaved edits", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                        }
                    }
                },
                actions = {
                    if (selectedPath != null) {
                        IconButton(onClick = { editorRef?.text?.undo() }) {
                            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = "Undo")
                        }
                        IconButton(onClick = { editorRef?.text?.redo() }) {
                            Icon(Icons.AutoMirrored.Outlined.Redo, contentDescription = "Redo")
                        }
                        IconButton(onClick = { showFind = !showFind }) {
                            Icon(Icons.Outlined.Search, contentDescription = "Find")
                        }
                        IconButton(onClick = { showDiffDialog = true }) {
                            Icon(Icons.Outlined.CompareArrows, contentDescription = "Diff")
                        }
                        IconButton(onClick = { saveCurrentFile() }, enabled = dirty) {
                            Icon(
                                Icons.Outlined.Save,
                                contentDescription = "Save",
                                tint = if (dirty) scheme.primary else scheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { showTerminal = !showTerminal }) {
                        Icon(
                            Icons.Outlined.Terminal,
                            contentDescription = "Terminal",
                            tint = if (showTerminal) scheme.primary else scheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Project selector row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                projects.forEach { proj ->
                    val selected = proj.id == activeProjectId
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) scheme.primary else scheme.surfaceContainerLow)
                            .border(1.dp, if (selected) scheme.primary else scheme.outlineVariant, RoundedCornerShape(8.dp))
                            .clickable {
                                scope.launch {
                                    WorkspaceManager.selectProject(dao, runtime.keys, proj)
                                    activeProjectId = proj.id
                                    selectedPath = null
                                    refreshTick++
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            proj.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) Color.White else scheme.onSurface,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.surfaceContainerHigh)
                        .clickable { showNewProjectDialog = true }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Project", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.surfaceContainerHigh)
                        .clickable { safLauncher.launch(null) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Folder", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // File browser strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(scheme.surfaceContainerHigh)
                        .clickable { showNewFileDialog = true }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Text("+ New File", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
                }
                if (workspaceFiles.isEmpty()) {
                    Text("No files yet in workspace", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                } else {
                    workspaceFiles.take(40).forEach { rel ->
                        val active = rel == selectedPath
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) scheme.secondaryContainer else scheme.surfaceContainerLow)
                                .border(1.dp, if (active) scheme.primary else scheme.outlineVariant, RoundedCornerShape(8.dp))
                                .clickable { openFile(rel) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                        ) {
                            Text(
                                rel,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = scheme.onSurface,
                            )
                        }
                    }
                }
            }

            // Find bar inside Sora editor
            if (showFind && selectedPath != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.surfaceContainerLow)
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = findQuery,
                        onValueChange = {
                            findQuery = it
                            if (it.isNotEmpty()) {
                                editorRef?.searcher?.search(it, EditorSearcher.SearchOptions(false, false))
                            } else {
                                editorRef?.searcher?.stopSearch()
                            }
                        },
                        textStyle = TextStyle(color = scheme.onSurface, fontSize = 13.sp),
                        cursorBrush = SolidColor(scheme.primary),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { editorRef?.searcher?.gotoPrevious() }) { Text("Prev") }
                    TextButton(onClick = { editorRef?.searcher?.gotoNext() }) { Text("Next") }
                }
            }

            // Sora Code Editor pane
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(if (showTerminal) 1f else 2f)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, scheme.outlineVariant, RoundedCornerShape(12.dp))
                    .background(scheme.surface),
            ) {
                if (selectedPath == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Tap a file above or create '+ New File' to edit in Sora CodeEditor.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                } else {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            CodeEditor(ctx).apply {
                                typefaceText = Typeface.MONOSPACE
                                typefaceLineNumber = Typeface.MONOSPACE
                                setTextSize(13f)
                                setEditorLanguage(OrinCodeLanguage(selectedPath.orEmpty()))
                                OrinCodeLanguage.applyMaterialTheme(this, scheme)
                                setText(editorText)
                                subscribeEvent(ContentChangeEvent::class.java) { _, _ ->
                                    val latest = text.toString()
                                    editorText = latest
                                    dirty = latest != baselineText
                                }
                                editorRef = this
                            }
                        },
                    )
                }
            }

            // Shell + Shizuku + Background Jobs pane
            if (showTerminal) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.surfaceContainerLow)
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Terminal", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium))
                            Text(
                                shizukuStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.clickable { ShizukuBridge.requestPermission(context) },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            AccessMode.entries.forEach { mode ->
                                val active = accessMode == mode
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (active) scheme.primary else scheme.surfaceContainerHigh)
                                        .clickable {
                                            accessMode = mode
                                            ShizukuBridge.setAccessMode(runtime.keys, mode)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        mode.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (active) Color.White else scheme.onSurface,
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(scheme.surfaceContainerHigh)
                            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            if (command.isEmpty()) {
                                Text("git status / ls -la / pwd...", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            BasicTextField(
                                value = command,
                                onValueChange = { command = it },
                                textStyle = TextStyle(color = scheme.onSurface, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
                                cursorBrush = SolidColor(scheme.primary),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(scheme.primary)
                                .clickable(enabled = command.isNotBlank()) {
                                    val cmd = command.trim()
                                    command = ""
                                    scope.launch {
                                        terminalOut = shellTool.execute(buildJsonObject { put("command", cmd) })
                                        refreshTick++
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text("Run", style = MaterialTheme.typography.labelMedium, color = Color.White)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 110.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            terminalOut,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = scheme.onSurface,
                        )
                    }

                    if (bgJobs.any { it.alive }) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            bgJobs.filter { it.alive }.take(3).forEach { job ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(scheme.errorContainer)
                                        .clickable {
                                            BgProcessManager.kill(job.id)
                                            refreshTick++
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                ) {
                                    Text(
                                        "Kill ${job.id}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDiffDialog && selectedPath != null) {
        val diffText = remember(baselineText, editorText, selectedPath) {
            TextDiff.summarize(selectedPath!!, baselineText, editorText)
        }
        AlertDialog(
            onDismissRequest = { showDiffDialog = false },
            title = { Text("Diff · ${selectedPath}") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(diffText, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            },
            confirmButton = {
                TextButton(onClick = { showDiffDialog = false }) { Text("Close") }
            },
        )
    }

    if (showNewFileDialog) {
        AlertDialog(
            onDismissRequest = { showNewFileDialog = false },
            title = { Text("Create Workspace File") },
            text = {
                BasicTextField(
                    value = newFileName,
                    onValueChange = { newFileName = it },
                    textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.sp),
                    cursorBrush = SolidColor(scheme.primary),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newFileName.isNotBlank(),
                    onClick = {
                        val rel = newFileName.trim().removePrefix("/")
                        newFileName = ""
                        showNewFileDialog = false
                        scope.launch(Dispatchers.IO) {
                            val root = WorkspaceManager.activeRoot(context, runtime.keys)
                            val f = File(root, rel)
                            f.parentFile?.mkdirs()
                            if (!f.exists()) f.writeText("")
                            withContext(Dispatchers.Main) {
                                refreshTick++
                                openFile(rel)
                            }
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewFileDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showNewProjectDialog) {
        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false },
            title = { Text("New Workspace Project") },
            text = {
                BasicTextField(
                    value = newProjectName,
                    onValueChange = { newProjectName = it },
                    textStyle = TextStyle(color = scheme.onSurface, fontSize = 14.sp),
                    cursorBrush = SolidColor(scheme.primary),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newProjectName.isNotBlank(),
                    onClick = {
                        val title = newProjectName.trim()
                        newProjectName = ""
                        showNewProjectDialog = false
                        scope.launch {
                            val created = WorkspaceManager.createProject(context, dao, runtime.keys, title)
                            activeProjectId = created.id
                            selectedPath = null
                            refreshTick++
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showNewProjectDialog = false }) { Text("Cancel") }
            },
        )
    }
}
