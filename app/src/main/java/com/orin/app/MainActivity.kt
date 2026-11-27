package com.orin.app

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.orin.app.agent.OrinAgentRuntime
import com.orin.app.agent.ToolApprovalRequest
import com.orin.app.live.LivePhase
import com.orin.app.live.OrinLiveSession
import com.orin.app.llm.LlmAttachment
import com.orin.app.local.Habits
import com.orin.app.local.LocalActions
import com.orin.app.local.LocalCommand
import com.orin.app.local.LocalRouter
import com.orin.app.notify.NotificationReplies
import com.orin.app.security.BiometricGate
import com.orin.app.ui.chat.OrinChatScreen
import com.orin.app.ui.chat.OrinUiMessage
import com.orin.app.ui.code.OrinCodeScreen
import com.orin.app.ui.drawer.DrawerChatSummary
import com.orin.app.ui.drawer.OrinDrawer
import com.orin.app.ui.onboarding.OrinOnboardingScreen
import com.orin.app.ui.ops.OrinOpsScreen
import com.orin.app.ui.preview.OrinWebPreviewScreen
import com.orin.app.ui.preview.PreviewTarget
import com.orin.app.ui.settings.OrinSettingsScreen
import com.orin.app.ui.theme.OrinTheme
import kotlinx.serialization.json.Json
import com.orin.app.voice.OrinVoice
import com.orin.app.voice.OrinVoiceController
import com.orin.app.work.BriefNotificationHelper
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
import java.util.Calendar
import java.util.UUID

class MainActivity : FragmentActivity() {

    private var pendingVoiceAction by mutableStateOf<String?>(null)
    private var pendingPrompt by mutableStateOf<String?>(null)
    private var pendingOpenOps by mutableStateOf(false)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingIntent(intent)
        val runtime = (application as OrinApp).runtime
        setContent {
            var themeStyle by remember { mutableStateOf(runtime.keys.themeStyle()) }
            OrinTheme(themeStyle = themeStyle) {
                var configured by remember { mutableStateOf(runtime.keys.hasApiKey()) }
                if (configured) {
                    OrinRootShell(
                        activity = this@MainActivity,
                        runtime = runtime,
                        initialVoiceAction = pendingVoiceAction,
                        initialPrompt = pendingPrompt,
                        initialOpenOps = pendingOpenOps,
                        onThemeChanged = { themeStyle = it },
                        onVoiceActionConsumed = {
                            pendingVoiceAction = null
                            pendingPrompt = null
                            pendingOpenOps = false
                        },
                    )
                } else {
                    OrinOnboardingScreen(
                        keys = runtime.keys,
                        onDone = { configured = true },
                    )
                }
            }
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(BriefNotificationHelper.EXTRA_NAVIGATE_OPS, false)) {
            pendingOpenOps = true
        }
        if (intent.action == Intent.ACTION_ASSIST) {
            val micAllowed = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (!micAllowed) return
            val keyguard = getSystemService(KeyguardManager::class.java)
            if (keyguard?.isKeyguardLocked == true) {
                keyguard.requestDismissKeyguard(
                    this,
                    object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() {
                            pendingVoiceAction = OrinVoice.ACTION_VOICE
                        }
                    },
                )
            } else {
                pendingVoiceAction = OrinVoice.ACTION_VOICE
            }
            return
        }
        pendingVoiceAction = intent.action
        pendingPrompt = intent.getStringExtra(OrinVoice.EXTRA_PROMPT)
    }
}

@Composable
private fun OrinRootShell(
    activity: FragmentActivity,
    runtime: OrinAgentRuntime,
    initialVoiceAction: String?,
    initialPrompt: String?,
    initialOpenOps: Boolean,
    onThemeChanged: (String) -> Unit,
    onVoiceActionConsumed: () -> Unit,
) {
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
    var previewTarget by remember { mutableStateOf<PreviewTarget?>(null) }

    val context = LocalContext.current
    val voice = remember { OrinVoiceController(context) }
    val habits = remember { Habits.get(context) }
    val localActions = remember { LocalActions(context) }
    var voiceMode by remember { mutableStateOf(false) }
    var isListening by remember { mutableStateOf(false) }
    var interimText by remember { mutableStateOf("") }

    suspend fun requestUiApproval(request: ToolApprovalRequest): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            approvalRequest = request
            approvalDeferred = deferred
        }
        val approved = deferred.await()
        if (!approved) return false
        if (runtime.keys.biometricGateEnabled() &&
            BiometricGate.requiresHardwareAuth(request.title, request.detail)
        ) {
            return BiometricGate.authenticate(
                activity = activity,
                title = "Confirm ${request.title}",
                subtitle = request.detail,
            )
        }
        return true
    }

    val liveSession = remember { OrinLiveSession(context, runtime, ::requestUiApproval) }
    val liveState by liveSession.state.collectAsState()

    var onVoicePermissionGranted by remember { mutableStateOf<() -> Unit>({}) }
    val voicePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) onVoicePermissionGranted()
    }

    DisposableEffect(voice, liveSession) {
        onDispose {
            voice.release()
            liveSession.release()
        }
    }

    val drawerChats = remember(dbConversations) {
        dbConversations.map {
            DrawerChatSummary(id = it.id, title = it.title, group = groupFor(it.updatedAtMs))
        }
    }

    val uiMessages = remember(dbMessages, streamingReply) {
        buildList {
            for (message in dbMessages) {
                val atts = message.attachmentsJson?.let { raw ->
                    runCatching { Json.decodeFromString<List<String>>(raw) }.getOrNull()
                }.orEmpty()
                add(
                    OrinUiMessage(
                        id = message.id,
                        isUser = message.isUser,
                        text = message.text,
                        toolSummary = message.toolSummary,
                        attachmentNames = atts,
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
        liveSession.stop()
        activeChatId = UUID.randomUUID().toString()
        streamingReply = null
        isStreaming = false
        lastPrompt = null
    }

    suspend fun startListening() {
        if (!voice.hasRecordPermission) {
            voicePermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS,
                ),
            )
            return
        }
        if (runtime.keys.geminiLiveEnabled()) {
            voiceMode = true
            liveSession.start(activeChatId)
            return
        }
        voice.initSpeaker()
        voice.setLanguage(runtime.keys.voiceLanguage())
        voiceMode = true
        isListening = voice.startListening()
        interimText = ""
    }

    onVoicePermissionGranted = { scope.launch { startListening() } }

    fun toggleVoice() {
        if (runtime.keys.geminiLiveEnabled()) {
            when {
                liveState.phase == LivePhase.Speaking -> liveSession.interrupt()
                liveSession.active -> liveSession.stop()
                else -> scope.launch { startListening() }
            }
            return
        }
        if (isListening) {
            voice.stopListening()
            isListening = false
        } else {
            scope.launch { startListening() }
        }
    }

    fun sendPrompt(text: String, fromVoice: Boolean = false, attachments: List<LlmAttachment> = emptyList()) {
        val prompt = text.trim()
        if ((prompt.isEmpty() && attachments.isEmpty()) || isStreaming) return
        NotificationReplies.heard(prompt)
        val chatId = activeChatId
        if (fromVoice) {
            val localCmd = LocalRouter.match(prompt) ?: habits.lookup(listOf(prompt))
            if (localCmd == LocalCommand.Stop) {
                voice.stopListening()
                voice.stopSpeaking()
                isListening = false
                return
            }
            if (localCmd != null) {
                val res = runCatching { localActions.run(localCmd) }.getOrNull()
                if (res != null && !res.handOff) {
                    habits.record(localCmd, Calendar.getInstance().get(Calendar.HOUR_OF_DAY))
                    scope.launch {
                        runtime.recordLocalExchange(chatId, prompt, res.reply, localCmd.javaClass.simpleName)
                        if (voiceMode && runtime.keys.speakReplies()) {
                            voice.speakReply(res.reply)
                        }
                    }
                    return
                }
            }
            habits.expect(prompt)
        }
        lastPrompt = prompt
        turnJob = scope.launch {
            isStreaming = true
            streamingReply = ""
            try {
                val reply = runtime.runTurn(
                    conversationId = chatId,
                    userText = prompt,
                    attachments = attachments,
                    onUpdate = { full ->
                        streamingReply = full.ifEmpty { null }
                    },
                    onApproval = ::requestUiApproval,
                )
                if (voiceMode && runtime.keys.speakReplies()) {
                    voice.speakReply(reply)
                }
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

    LaunchedEffect(Unit) {
        voice.partial.collect { interimText = it }
    }

    LaunchedEffect(Unit) {
        voice.final.collect { text ->
            isListening = false
            interimText = ""
            if (text.isNotBlank()) sendPrompt(text, fromVoice = true)
        }
    }

    LaunchedEffect(Unit) {
        voice.error.collect { message ->
            isListening = false
            interimText = message
        }
    }

    LaunchedEffect(initialVoiceAction, initialPrompt, initialOpenOps) {
        if (initialOpenOps) {
            onVoiceActionConsumed()
            navController.navigate("ops") { launchSingleTop = true }
            return@LaunchedEffect
        }
        when (initialVoiceAction) {
            OrinVoice.ACTION_VOICE -> {
                onVoiceActionConsumed()
                navController.navigate("chat") { launchSingleTop = true }
                startListening()
            }
            OrinVoice.ACTION_TYPE -> {
                val prompt = initialPrompt
                onVoiceActionConsumed()
                navController.navigate("chat") { launchSingleTop = true }
                if (!prompt.isNullOrBlank()) sendPrompt(prompt, fromVoice = true)
            }
            OrinVoice.ACTION_STOP -> {
                onVoiceActionConsumed()
                liveSession.stop()
                voice.stopListening()
                voice.stopSpeaking()
                isListening = false
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
                val liveStatus = when (liveState.phase) {
                    LivePhase.Connecting -> "Gemini Live · Connecting…"
                    LivePhase.Listening -> listOfNotNull(
                        "Gemini Live · Listening",
                        liveState.userCaption.takeIf { it.isNotBlank() }?.takeLast(60),
                    ).joinToString(" — ")
                    LivePhase.Speaking -> listOfNotNull(
                        "Gemini Live · Speaking (tap to interrupt)",
                        liveState.assistantCaption.takeIf { it.isNotBlank() }?.takeLast(60),
                    ).joinToString(" — ")
                    LivePhase.Off -> liveState.problem
                }
                OrinChatScreen(
                    messages = uiMessages,
                    activeModelLabel = runtime.keys.activeModel(),
                    isStreaming = isStreaming,
                    canRetry = lastPrompt != null && !isStreaming,
                    voiceMode = voiceMode || liveSession.active,
                    isListening = isListening || liveState.phase == LivePhase.Listening || liveState.phase == LivePhase.Connecting,
                    interimText = interimText,
                    liveStatus = liveStatus,
                    onVoiceToggle = { toggleVoice() },
                    onInterruptLive = { liveSession.interrupt() },
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onPickModel = { navController.navigate("settings") },
                    onNewChat = { newChat() },
                    onSendMessage = { text, atts -> sendPrompt(text, attachments = atts) },
                    onPreviewSnippet = { title, content, isMermaid ->
                        val html = if (isMermaid) {
                            """
                            <!DOCTYPE html><html><body style="background:#0f172a;color:#f8fafc;padding:16px;font-family:sans-serif">
                            <pre class="mermaid">$content</pre>
                            <script type="module">
                            import mermaid from 'https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs';
                            mermaid.initialize({ startOnLoad: true, theme: 'dark' });
                            </script></body></html>
                            """.trimIndent()
                        } else {
                            content
                        }
                        previewTarget = PreviewTarget(title = title, html = html)
                        navController.navigate("preview") { launchSingleTop = true }
                    },
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
                OrinCodeScreen(
                    runtime = runtime,
                    onBack = { navController.popBackStack() },
                    onOpenPreviewUrl = { title, url ->
                        previewTarget = PreviewTarget(title = title, url = url)
                        navController.navigate("preview") { launchSingleTop = true }
                    },
                )
            }
            composable("preview") {
                OrinWebPreviewScreen(
                    target = previewTarget ?: PreviewTarget("Preview", html = "<p>No preview loaded.</p>"),
                    onBack = { navController.popBackStack() },
                )
            }
            composable("settings") {
                OrinSettingsScreen(
                    runtime = runtime,
                    onThemeChanged = onThemeChanged,
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
