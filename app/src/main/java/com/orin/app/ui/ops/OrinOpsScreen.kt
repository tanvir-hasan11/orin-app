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
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orin.app.agent.OrinAgentRuntime
import com.orin.app.data.TaskEntity
import com.orin.app.tools.MetaAdMetrics
import com.orin.app.tools.MetaAdsTool
import com.orin.app.tools.PhoneCalendarTool
import com.orin.app.work.AutomationManager
import com.orin.app.work.ScheduleMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
    val automations by dao.observeAutomations().collectAsState(initial = emptyList())

    var calendarText by remember { mutableStateOf("Loading...") }
    var selectedPreset by remember { mutableStateOf("yesterday") }
    var metaMetrics by remember { mutableStateOf<MetaAdMetrics?>(null) }
    var metaNote by remember { mutableStateOf("Loading...") }
    var campaignsText by remember { mutableStateOf("") }
    var metaRefreshTick by remember { mutableIntStateOf(0) }
    var newTaskText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        calendarText = PhoneCalendarTool(context).execute(JsonObject(emptyMap()))
        AutomationManager.ensureDefaultMorningBrief(dao)
    }

    LaunchedEffect(selectedPreset, metaRefreshTick) {
        metaNote = "Fetching $selectedPreset..."
        val tool = MetaAdsTool(runtime.keys, dao = dao)
        val result = tool.fetchInsights(selectedPreset)
        result.fold(
            onSuccess = { m ->
                metaMetrics = m
                metaNote = "Live · ${formatTime(m.fetchedAtMs)}"
            },
            onFailure = { err ->
                val cached = dao.latestMetaAdSnapshot(selectedPreset)?.let { MetaAdMetrics.fromSnapshot(it) }
                metaMetrics = cached
                metaNote = if (cached != null) {
                    "Cached (${formatTime(cached.fetchedAtMs)}) · ${err.message}"
                } else {
                    err.message ?: "Meta Ads not connected"
                }
            },
        )
        val campaigns = tool.execute(buildJsonObject { put("action", "campaigns") })
        campaignsText = if (campaigns.startsWith("•")) campaigns else ""
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
                OpsCard(
                    title = "Meta Ads Performance",
                    icon = Icons.AutoMirrored.Outlined.TrendingUp,
                    trailing = {
                        Icon(
                            Icons.Outlined.Refresh,
                            contentDescription = "Refresh Meta Ads",
                            tint = scheme.primary,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { metaRefreshTick++ },
                        )
                    },
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            "today" to "Today",
                            "yesterday" to "Yesterday",
                            "last_7d" to "7d",
                            "this_month" to "Month",
                        ).forEach { (preset, label) ->
                            val selected = selectedPreset == preset
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) scheme.primary else scheme.surfaceContainerHigh)
                                    .border(1.dp, if (selected) scheme.primary else scheme.outlineVariant, RoundedCornerShape(8.dp))
                                    .clickable { selectedPreset = preset }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            ) {
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) Color.White else scheme.onSurface,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val metrics = metaMetrics
                    if (metrics != null) {
                        val intFmt = NumberFormat.getIntegerInstance(Locale.US)
                        val kpis = listOf(
                            "Spend" to "${metrics.currency} ${metrics.spend}",
                            "Reach" to intFmt.format(metrics.reach),
                            "Impressions" to intFmt.format(metrics.impressions),
                            "Clicks" to intFmt.format(metrics.clicks),
                            "CTR" to "${"%.2f".format(metrics.ctr)}%",
                            "CPC" to "${metrics.currency} ${"%.2f".format(metrics.cpc)}",
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (rowIndex in 0 until 2) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    for (colIndex in 0 until 3) {
                                        val (label, value) = kpis[rowIndex * 3 + colIndex]
                                        KpiTile(
                                            label = label,
                                            value = value,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (metaNote.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(metaNote, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                    if (campaignsText.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            campaignsText,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            item {
                OpsCard("Scheduled Automations", Icons.Outlined.Schedule) {
                    if (automations.isEmpty()) {
                        Text("Loading automations...", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            automations.forEach { job ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            job.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                            color = scheme.onSurface,
                                        )
                                        Text(
                                            "${job.scheduleType}:${job.scheduleParam} · next ${formatTime(job.nextRunAt)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = scheme.onSurfaceVariant,
                                        )
                                    }
                                    if (job.id != AutomationManager.DEFAULT_MORNING_BRIEF_ID) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "Delete",
                                            tint = scheme.onSurfaceVariant,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clickable {
                                                    scope.launch(Dispatchers.IO) {
                                                        dao.deleteAutomation(job.id)
                                                        AutomationManager.syncWork(context)
                                                    }
                                                },
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Switch(
                                        checked = job.enabled,
                                        onCheckedChange = { enable ->
                                            scope.launch(Dispatchers.IO) {
                                                val next = if (enable) {
                                                    ScheduleMath.nextRunAt(job.scheduleType, job.scheduleParam)
                                                } else {
                                                    job.nextRunAt
                                                }
                                                dao.upsertAutomation(job.copy(enabled = enable, nextRunAt = next))
                                                AutomationManager.syncWork(context)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                OpsCard("Today's Calendar", Icons.Outlined.CalendarToday) {
                    Text(calendarText, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
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
private fun KpiTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.surfaceContainerHigh)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OpsCard(
    title: String,
    icon: ImageVector,
    trailing: (@Composable () -> Unit)? = null,
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            trailing?.invoke()
        }
        Spacer(Modifier.height(10.dp))
        content()
    }
}

private val opsTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")

private fun formatTime(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(opsTimeFormatter)
