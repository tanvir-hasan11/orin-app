package com.orin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.orin.app.agent.OrinAgentRuntime
import com.orin.app.ui.chat.OrinChatScreen
import com.orin.app.ui.chat.OrinUiMessage
import com.orin.app.ui.code.OrinCodeScreen
import com.orin.app.ui.drawer.DrawerChatSummary
import com.orin.app.ui.drawer.OrinDrawer
import com.orin.app.ui.ops.OrinOpsScreen
import com.orin.app.ui.settings.OrinSettingsScreen
import com.orin.app.ui.theme.OrinTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val runtime = (application as OrinApp).runtime
        setContent {
            OrinTheme {
                OrinRootShell(runtime)
            }
        }
    }
}

@Composable
private fun OrinRootShell(runtime: OrinAgentRuntime) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    var activeChatId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    val dbConversations by runtime.observeConversations().collectAsState(initial = emptyList())
    val dbMessages by runtime.observeMessages(activeChatId).collectAsState(initial = emptyList())
    var streamingReply by remember { mutableStateOf<String?>(null) }

    val drawerChats = remember(dbConversations) {
        dbConversations.map {
            DrawerChatSummary(id = it.id, title = it.title, group = "Today")
        }
    }

    val uiMessages = remember(dbMessages, streamingReply) {
        buildList {
            for (m in dbMessages) {
                add(OrinUiMessage(id = m.id, isUser = m.isUser, text = m.text, toolSummary = m.toolSummary))
            }
            if (streamingReply != null) {
                add(OrinUiMessage(id = "streaming", isUser = false, text = streamingReply!!))
            }
        }
    }

    fun sendPrompt(text: String) {
        val chatId = activeChatId
        scope.launch {
            streamingReply = ""
            val acc = StringBuilder()
            runtime.runTurn(chatId, text) { chunk ->
                acc.append(chunk)
                withContext(Dispatchers.Main) {
                    streamingReply = acc.toString()
                }
            }
            streamingReply = null
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            OrinDrawer(
                chats = drawerChats,
                activeChatId = activeChatId,
                onNewChat = {
                    activeChatId = UUID.randomUUID().toString()
                    streamingReply = null
                    scope.launch { drawerState.close() }
                    navController.navigate("chat") { launchSingleTop = true }
                },
                onSelectChat = { id ->
                    activeChatId = id
                    streamingReply = null
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
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onPickModel = { navController.navigate("settings") },
                    onNewChat = {
                        activeChatId = UUID.randomUUID().toString()
                        streamingReply = null
                    },
                    onSendMessage = { text -> sendPrompt(text) },
                    onVoiceTap = { },
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
                    keys = runtime.keys,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
