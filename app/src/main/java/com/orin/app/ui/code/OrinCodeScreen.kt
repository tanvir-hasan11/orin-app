@file:OptIn(ExperimentalMaterial3Api::class)

package com.orin.app.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orin.app.agent.OrinAgentRuntime
import com.orin.app.tools.FileOpsTool
import com.orin.app.tools.WorkspaceShellTool
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun OrinCodeScreen(
    runtime: OrinAgentRuntime,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val shellTool = remember(context, runtime.keys) {
        WorkspaceShellTool(context, runtime.db.dao(), runtime.keys)
    }
    val fileTool = remember(context) { FileOpsTool(context) }

    var command by remember { mutableStateOf("") }
    var terminalOut by remember { mutableStateOf("Orin Workspace Shell Ready.") }
    var filesOut by remember { mutableStateOf("Loading workspace...") }

    LaunchedEffect(Unit) {
        filesOut = fileTool.execute(JsonObject(emptyMap()))
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
                    Text(
                        "Code & Terminal",
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Workspace Files Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.surfaceContainerLow)
                    .border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                Text("Workspace Files", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                Spacer(Modifier.height(6.dp))
                Text(
                    filesOut,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = scheme.onSurfaceVariant,
                )
            }

            // Interactive Terminal Runner
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.surfaceContainerLow)
                    .border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                Text("Interactive Shell", style = MaterialTheme.typography.titleSmall, color = scheme.onSurface)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(scheme.surfaceContainerHigh)
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        if (command.isEmpty()) {
                            Text("ls -la / pwd / uname -a...", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
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
                                    filesOut = fileTool.execute(JsonObject(emptyMap()))
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text("Run", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    terminalOut,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = scheme.onSurface,
                )
            }
        }
    }
}
