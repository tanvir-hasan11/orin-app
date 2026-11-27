package com.orin.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orin.app.ui.components.OrinSunburstMark
import java.time.LocalTime

data class OrinUiMessage(
    val id: String,
    val isUser: Boolean,
    val text: String,
    val toolSummary: String? = null,
)

@Composable
fun OrinChatScreen(
    messages: List<OrinUiMessage>,
    activeModelLabel: String,
    isStreaming: Boolean,
    canRetry: Boolean,
    voiceMode: Boolean,
    isListening: Boolean,
    interimText: String,
    onVoiceToggle: () -> Unit,
    onOpenDrawer: () -> Unit,
    onPickModel: () -> Unit,
    onNewChat: () -> Unit,
    onSendMessage: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LaunchedEffect(messages.lastOrNull()?.text) {
        if (isStreaming && messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = onOpenDrawer) {
                    Icon(Icons.Outlined.Menu, contentDescription = "Menu", tint = scheme.onSurface)
                }

                // Centered Claude-style model selector pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onPickModel)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = activeModelLabel,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                        color = scheme.onSurface,
                    )
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }

                IconButton(onClick = { inputText = ""; onNewChat() }) {
                    Icon(Icons.Outlined.EditNote, contentDescription = "New Chat", tint = scheme.onSurface)
                }
            }
        },
        bottomBar = {
            // Floating Claude-style warm composer
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                if (canRetry) {
                    Text(
                        text = "Last request failed. Tap to retry.",
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onRetry)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(scheme.surfaceContainerHigh)
                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(24.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        if (inputText.isEmpty()) {
                            Text(
                                text = when {
                                    isListening && interimText.isNotBlank() -> interimText
                                    isListening -> "Listening…"
                                    voiceMode -> "Speak, or type here…"
                                    else -> "Reply to Orin..."
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (isListening) scheme.primary else scheme.onSurfaceVariant,
                            )
                        }
                        BasicTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            textStyle = TextStyle(
                                color = scheme.onSurface,
                                fontSize = 16.sp,
                                lineHeight = 22.sp,
                            ),
                            cursorBrush = SolidColor(scheme.primary),
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isListening) scheme.primary
                                    else if (voiceMode) scheme.primaryContainer
                                    else scheme.surfaceContainerHigh,
                                )
                                .clickable(onClick = onVoiceToggle),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.Mic,
                                contentDescription = if (isListening) "Stop listening" else "Speak to Orin",
                                tint = if (isListening) Color.White else scheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }

                        if (isStreaming) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(scheme.primary)
                                    .clickable(onClick = onStop),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Outlined.Stop,
                                    contentDescription = "Stop",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        } else {
                            val canSend = inputText.isNotBlank()
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(if (canSend) scheme.primary else scheme.outlineVariant)
                                    .clickable(enabled = canSend) {
                                        val message = inputText.trim()
                                        inputText = ""
                                        onSendMessage(message)
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.Send,
                                    contentDescription = "Send",
                                    tint = if (canSend) Color.White else scheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (messages.isEmpty()) {
            ClaudeEmptyHome(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onSuggestionTap = onSendMessage,
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(messages, key = { it.id }) { msg ->
                    if (msg.isUser) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 310.dp)
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(scheme.surfaceContainerHigh)
                                    .padding(horizontal = 15.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = msg.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = scheme.onSurface,
                                )
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (msg.toolSummary != null) {
                                Text(
                                    text = msg.toolSummary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(scheme.surfaceContainerLow)
                                        .border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                                color = scheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaudeEmptyHome(
    modifier: Modifier = Modifier,
    onSuggestionTap: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val greeting = remember {
        val hour = LocalTime.now().hour
        when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OrinSunburstMark(size = 46.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Normal,
            ),
            color = scheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))

        // Clean 1-row Claude-style suggestion pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            SuggestionPill(Icons.Outlined.WbSunny, "Morning Brief") {
                onSuggestionTap("Run my morning brief")
            }
            SuggestionPill(Icons.Outlined.TrendingUp, "Meta Ads") {
                onSuggestionTap("Summarize my Meta Ads spend and campaigns")
            }
            SuggestionPill(Icons.Outlined.CalendarToday, "Calendar") {
                onSuggestionTap("What is on my calendar today?")
            }
            SuggestionPill(Icons.Outlined.Code, "Code Bay") {
                onSuggestionTap("Open workspace and check git status")
            }
        }
    }
}

@Composable
private fun SuggestionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(scheme.surfaceContainerLow)
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
    }
}
