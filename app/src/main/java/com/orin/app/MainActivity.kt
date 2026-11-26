package com.orin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.orin.app.agent.OrinAgentRuntime
import com.orin.app.agent.ToolApprovalRequest
import com.orin.app.ui.chat.OrinChatScreen
import com.orin.app.ui.chat.OrinUiMessage
import com.orin.app.ui.code.OrinCodeScreen
import com.orin.app.ui.drawer.DrawerChatSummary
import com.orin.app.ui.drawer.OrinDrawer
import com.orin.app.ui.onboarding.OrinOnboardingScreen
import com.orin.app.ui.ops.OrinOpsScreen
import com.orin.app.ui.settings.OrinSettingsScreen
import com.orin.app.ui.theme.OrinTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = (application as OrinApp).runtime
        setContent {
            OrinTheme {
                var configured by remember { mutableStateOf(runtime.keys.hasApiKey()) }
                if (configured) {
                    OrinRootShell(runtime)
                } else {
                    OrinOnboardingScreen(
                        keys = runtime.keys,
                        onDone = { configured = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun OrinRootShell(runtime: OrinAgentRuntime) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var activeChatId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    val dbConversations by runtime.observeConversations().collectAsState(initial = emptyList())
    val dbMessages by runtime.observeMessages(activeChatId).collectAsState(initial = emptyList())

    var streamingReply by remember { mutableStateOf<String?>(null) }
    var isStreaming by remember { mutableStateOf(false) }
    var turnJob by remember { mutableStateOf<Job?>(null) }
    var lastPrompt by remember { mutableStateOf<String?>(null) }

    var approvalRequest by remember { mutableStateOf<ToolApprovalRequest?>(null) }
    var approvalDeferred by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    val drawerChats = remember(dbConversations) {
        dbConversations.map {
            DrawerChatSummary(id = it.id, title = it.title, group = groupFor(it.updatedAtMs))
        }
    }

    val uiMessages = remember(dbMessages, streamingReply) {
        buildList {
            for (message in dbMessages) {
                add(
                    OrinUiMessage(
                        id = message.id,
                        isUser = message.isUser,
                        text = message.text,
                        toolSummary = message.toolSummary,
                    ),
                )
            }
            streamingReply?.let {
                add(OrinUiMessage(id = "streaming", isUser = false, text = it))
            }
        }
    }

    fun respondToApproval(allow: Boolean) {
        approvalDeferred?.complete(allow)
        approvalDeferred = null
        approvalRequest = null
    }

    fun newChat() {
        turnJob?.cancel()
        activeChatId = UUID.randomUUID().toString()
        streamingReply = null
        isStreaming = false
        lastPrompt = null
    }

    fun sendPrompt(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || isStreaming) return
        val chatId = activeChatId
        lastPrompt = prompt
        turnJob = scope.launch {
            isStreaming = true
            streamingReply = ""
            try {
                runtime.runTurn(
                    conversationId = chatId,
                    userText = prompt,
                    onUpdate = { full ->
                        streamingReply = full.ifEmpty { null }
                    },
                    onApproval = { request ->
                        val deferred = CompletableDeferred<Boolean>()
                        withContext(Dispatchers.Main) {
                            approvalRequest = request
                            approvalDeferred = deferred
                        }
                        deferred.await()
                    },
                )
                lastPrompt = null
            } catch (e: CancellationException) {
                // runTurn persists the partial reply; keep the UI consistent.
                lastPrompt = null
            } catch (e: Exception) {
                // runTurn persists a user-facing error; lastPrompt stays for retry.
            } finally {
                withContext(NonCancellable) {
                    streamingReply = null
                    isStreaming = false
                    approvalRequest = null
                    approvalDeferred = null
                }
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            OrinDrawer(
                chats = drawerChats,
                activeChatId = activeChatId,
                onNewChat = {
                    newChat()
                    scope.launch { drawerState.close() }
                    navController.navigate("chat") { launchSingleTop = true }
                },
                onSelectChat = { id ->
                    turnJob?.cancel()
                    activeChatId = id
                    streamingReply = null
                    isStreaming = false
                    lastPrompt = null
                    scope.launch { drawerState.close() }
                    navController.navigate("chat") { launchSingleTop = true }
                },
                onOpenOps = {
                    scope.launch { drawerState.close() }
                    navController.navigate("ops") { launchSingleTop = true }
                },
                onOpenCode = {
                    scope.launch { drawerState.close() }
                    navController.navigate("code") { launchSingleTop = true }
                },
                onOpenAutomations = {
                    scope.launch { drawerState.close() }
                    navController.navigate("ops") { launchSingleTop = true }
                },
                onOpenSettings = {
                    scope.launch { drawerState.close() }
                    navController.navigate("settings") { launchSingleTop = true }
                },
            )
        },
    ) {
        NavHost(
            navController = navController,
            startDestination = "chat",
        ) {
            composable("chat") {
                OrinChatScreen(
                    messages = uiMessages,
                    activeModelLabel = runtime.keys.activeModel(),
                    isStreaming = isStreaming,
                    canRetry = lastPrompt != null && !isStreaming,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onPickModel = { navController.navigate("settings") },
                    onNewChat = { newChat() },
                    onSendMessage = { text -> sendPrompt(text) },
                    onStop = {
                        turnJob?.cancel()
                        lastPrompt = null
                    },
                    onRetry = { lastPrompt?.let { sendPrompt(it) } },
                )
            }
            composable("ops") {
                OrinOpsScreen(
                    runtime = runtime,
                    onBack = { navController.popBackStack() },
                    onAskOrin = { prompt ->
                        navController.navigate("chat") { launchSingleTop = true }
                        sendPrompt(prompt)
                    },
                )
            }
            composable("code") {
                OrinCodeScreen(runtime = runtime, onBack = { navController.popBackStack() })
            }
            composable("settings") {
                OrinSettingsScreen(
                    runtime = runtime,
                    onBack = { navController.popBackStack() },
                    onDataCleared = {
                        newChat()
                        navController.navigate("chat") { launchSingleTop = true }
                    },
                )
            }
        }
    }

    approvalRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { respondToApproval(false) },
            title = { Text(request.title) },
            text = { Text(request.detail) },
            confirmButton = {
                TextButton(onClick = { respondToApproval(true) }) { Text("Allow once") }
            },
            dismissButton = {
                TextButton(onClick = { respondToApproval(false) }) { Text("Deny") }
            },
        )
    }
}

private fun groupFor(timestampMs: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(timestampMs).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.isAfter(today.minusDays(7)) -> "Previous 7 Days"
        else -> "Older"
    }
}
