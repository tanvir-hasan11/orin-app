package com.orin.app.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orin.app.ui.components.OrinSunburstMark

data class DrawerChatSummary(
    val id: String,
    val title: String,
    val group: String, // "Today", "Yesterday", "Previous 7 Days"
)

@Composable
fun OrinDrawer(
    chats: List<DrawerChatSummary>,
    activeChatId: String?,
    onNewChat: () -> Unit,
    onSelectChat: (String) -> Unit,
    onOpenOps: () -> Unit,
    onOpenCode: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val grouped = chats.groupBy { it.group }

    ModalDrawerSheet(
        drawerContainerColor = scheme.surfaceContainerLow,
        drawerContentColor = scheme.onSurface,
        modifier = Modifier.width(300.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            // Brand Header + New Chat
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OrinSunburstMark(size = 28.dp)
                    Text(
                        text = "Orin",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Medium,
                        ),
                    )
                }
                IconButton(onClick = onNewChat) {
                    Icon(Icons.Outlined.Add, contentDescription = "New Chat", tint = scheme.onSurface)
                }
            }

            Spacer(Modifier.height(10.dp))

            // Primary Navigation Rows
            DrawerNavRow(Icons.Outlined.AutoAwesome, "New chat", onClick = onNewChat)
            DrawerNavRow(Icons.Outlined.Dashboard, "Brief & Ops", onClick = onOpenOps)
            DrawerNavRow(Icons.Outlined.Code, "Code & Terminal", onClick = onOpenCode)
            DrawerNavRow(Icons.Outlined.Schedule, "Automations", onClick = onOpenAutomations)

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
            Spacer(Modifier.height(8.dp))

            // Recent Conversations grouped cleanly
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (chats.isEmpty()) {
                    item {
                        Text(
                            text = "No conversations yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    listOf("Today", "Yesterday", "Previous 7 Days").forEach { groupName ->
                        val itemsInGroup = grouped[groupName].orEmpty()
                        if (itemsInGroup.isNotEmpty()) {
                            item {
                                Text(
                                    text = groupName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                            items(itemsInGroup, key = { it.id }) { chat ->
                                val selected = chat.id == activeChatId
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (selected) scheme.surfaceContainerHigh else scheme.surfaceContainerLow
                                        )
                                        .clickable { onSelectChat(chat.id) }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                ) {
                                    Text(
                                        text = chat.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = scheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.6f))
            Spacer(Modifier.height(6.dp))

            // Bottom Profile + Settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenSettings)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(scheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "T",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                            color = scheme.onPrimaryContainer,
                        )
                    }
                    Column {
                        Text("Tanvir Hasan", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                        Text("Personal J.A.R.V.I.S.", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun DrawerNavRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(19.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
    }
}
