@file:OptIn(ExperimentalMaterial3Api::class)

package com.orin.app.ui.ops

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.TrendingUp
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orin.app.agent.OrinAgentRuntime
import com.orin.app.data.TaskEntity
import com.orin.app.tools.MetaAdsTool
import com.orin.app.tools.PhoneCalendarTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Composable
fun OrinOpsScreen(
    runtime: OrinAgentRuntime,
    onBack: () -> Unit,
    onAskOrin: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = runtime.db.dao()

    val tasks by dao.observeTasks().collectAsState(initial = emptyList())
    val auditLogs by dao.observeAuditLogs().collectAsState(initial = emptyList())

    var calendarText by remember { mutableStateOf("Loading...") }
    var metaText by remember { mutableStateOf("Loading...") }
    var newTaskText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        calendarText = PhoneCalendarTool(context).execute(JsonObject(emptyMap()))
        metaText = MetaAdsTool(runtime.keys).execute(buildJsonObject { put("action", "insights"); put("date_preset", "yesterday") })
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
                        "Brief & Ops",
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.primary)
                            .clickable { onAskOrin("Run my morning brief") }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Morning Brief", style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(scheme.surfaceContainerLow)
                            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(12.dp))
                            .clickable { onAskOrin("Run evening wrap-up") }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Evening Wrap", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
                    }
                }
            }

            item {
                OpsCard("Today's Calendar", Icons.Outlined.CalendarToday) {
                    Text(calendarText, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }

            item {
                OpsCard("Meta Ads Performance", Icons.Outlined.TrendingUp) {
                    Text(metaText, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }

            item {
                OpsCard("Personal Tasks", Icons.Outlined.CheckCircle) {
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
                            if (newTaskText.isEmpty()) {
                                Text("Add a task...", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            BasicTextField(
                                value = newTaskText,
                                onValueChange = { newTaskText = it },
                                textStyle = TextStyle(color = scheme.onSurface, fontSize = 13.sp),
                                cursorBrush = SolidColor(scheme.primary),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = "Add",
                            tint = scheme.primary,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable(enabled = newTaskText.isNotBlank()) {
                                    val title = newTaskText.trim()
                                    newTaskText = ""
                                    scope.launch(Dispatchers.IO) {
                                        dao.upsertTask(TaskEntity(UUID.randomUUID().toString().take(8), title))
                                    }
                                },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    if (tasks.isEmpty()) {
                        Text("No tasks yet.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            tasks.take(10).forEach { task ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch(Dispatchers.IO) {
                                                dao.setTaskCompleted(task.id, !task.completed)
                                            }
                                        },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (task.completed) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (task.completed) scheme.primary else scheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = task.title,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None,
                                        ),
                                        color = if (task.completed) scheme.onSurfaceVariant else scheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                OpsCard("Action Audit Log", Icons.Outlined.Shield) {
                    if (auditLogs.isEmpty()) {
                        Text("No real-world actions recorded yet.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            auditLogs.take(6).forEach { log ->
                                Text(
                                    "• [${if (log.ok) "OK" else "FAIL"}] ${log.tool}.${log.action}: ${log.detail}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpsCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerLow)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}
