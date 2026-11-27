package com.orin.app.live

import android.content.Context
import android.os.SystemClock
import com.orin.app.agent.OrinAgentRuntime
import com.orin.app.agent.ToolApprovalRequest
import com.orin.app.control.ApprovalCard
import com.orin.app.llm.LlmToolCall
import com.orin.app.notify.NotificationReplies
import com.orin.app.security.TurnTrust
import com.orin.app.voice.LiveMicRecorder
import com.orin.app.voice.LiveSpeaker
import com.orin.app.voice.MusicPause
import com.orin.app.voice.TalkOver
import com.orin.app.voice.VOICE_LEVEL
import com.orin.app.voice.WaveStop
import com.orin.app.voice.headphonesConnected
import com.orin.app.voice.pcmLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.util.Collections
import java.util.concurrent.TimeUnit

enum class LivePhase { Off, Connecting, Listening, Speaking }

data class LiveState(
    val phase: LivePhase = LivePhase.Off,
    val userCaption: String = "",
    val assistantCaption: String = "",
    val problem: String? = null,
)

/**
 * Full-duplex Gemini Live session with SpeexDSP echo cancellation ([TalkOver]),
 * proximity [WaveStop], [TurnTrust] tool gating, and automatic Room transcript persistence.
 */
class OrinLiveSession(
    private val context: Context,
    private val runtime: OrinAgentRuntime,
    private val onApproval: suspend (ToolApprovalRequest) -> Boolean,
) : LiveClient.Listener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val client = LiveClient(http)
    private val speaker = LiveSpeaker()
    private val mic = LiveMicRecorder(::onMicChunk)
    private val talkOver = TalkOver(speaker)
    private val wave = WaveStop(context) { interrupt() }
    private val musicPause = MusicPause(context)
    private val overlayCard = ApprovalCard(context)
    private val trust = TurnTrust()
    private val cancelled = Collections.synchronizedSet(mutableSetOf<String>())

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var wanted = false
    @Volatile private var conversationId: String = ""
    @Volatile private var resumeHandle: String? = null
    @Volatile private var droppingReply = false
    @Volatile private var lastActivity = 0L
    private var retries = 0
    private var watchdog: Job? = null

    private val userTranscript = StringBuilder()
    private val assistantTranscript = StringBuilder()

    val active: Boolean get() = wanted

    fun start(chatId: String) {
        if (wanted) return
        val apiKey = runtime.keys.getSecret("api_key_gemini")
        if (apiKey.isNullOrBlank()) {
            _state.update { it.copy(problem = "Gemini Live requires a Gemini API key in Settings.") }
            return
        }
        conversationId = chatId
        wanted = true
        retries = 0
        resumeHandle = null
        userTranscript.clear()
        assistantTranscript.clear()
        trust.userSpoke()
        musicPause.pause()
        talkOver.reset(newConversation = true)
        lastActivity = SystemClock.elapsedRealtime()
        connect(apiKey)

        watchdog?.cancel()
        watchdog = scope.launch {
            while (isActive) {
                delay(WATCH_MS)
                if (SystemClock.elapsedRealtime() - lastActivity > IDLE_MS) {
                    stop()
                    _state.update { it.copy(problem = "Live session stopped after 3 quiet minutes.") }
                }
            }
        }
    }

    fun stop() {
        wanted = false
        wave.watch(false)
        watchdog?.cancel()
        mic.stop()
        client.close()
        speaker.flush()
        musicPause.resume()
        cancelled.clear()
        trust.userSpoke()
        flushTurnToHistory()
        _state.update { LiveState(phase = LivePhase.Off) }
        _level.value = 0f
    }

    fun interrupt() {
        if (_state.value.phase != LivePhase.Speaking) return
        wave.watch(false)
        droppingReply = true
        speaker.flush()
        _state.update { it.copy(phase = LivePhase.Listening) }
    }

    fun release() {
        stop()
        speaker.release()
        talkOver.close()
    }

    private fun connect(apiKey: String) {
        _state.update { it.copy(phase = LivePhase.Connecting, problem = null) }
        val setup = LiveProtocol.Setup(
            model = runtime.keys.liveModel(),
            systemInstruction = runtime.liveSystemPrompt(),
            voice = "Puck",
            languages = listOf("bn-BD", "hi-IN", "en-IN"),
            resumeHandle = resumeHandle,
            tools = runtime.toolDeclarations(),
        )
        client.connect(apiKey, setup, this)
    }

    override fun onEvent(event: LiveEvent) {
        when (event) {
            LiveEvent.SetupComplete -> {
                retries = 0
                if (!mic.start()) {
                    stop()
                    _state.update { it.copy(problem = "Microphone is busy or permission missing.") }
                    return
                }
                _state.update { it.copy(phase = LivePhase.Listening) }
            }
            is LiveEvent.Audio -> if (!droppingReply) {
                speaker.play(event.pcm, event.sampleRate)
                _level.value = (pcmLevel(event.pcm) / LOUD_LEVEL).coerceIn(0f, 1f)
                _state.update { it.copy(phase = LivePhase.Speaking) }
                wave.watch(true)
            }
            is LiveEvent.UserText -> {
                lastActivity = SystemClock.elapsedRealtime()
                trust.userSpoke()
                NotificationReplies.heard(event.text)
                userTranscript.append(event.text)
                _state.update { it.copy(userCaption = userTranscript.toString().trim()) }
            }
            is LiveEvent.AssistantText -> if (!droppingReply) {
                lastActivity = SystemClock.elapsedRealtime()
                assistantTranscript.append(event.text)
                _state.update { it.copy(assistantCaption = assistantTranscript.toString().trim()) }
            }
            LiveEvent.Interrupted -> {
                wave.watch(false)
                droppingReply = false
                speaker.flush()
                flushTurnToHistory()
            }
            LiveEvent.TurnComplete -> {
                speaker.endReply()
                droppingReply = false
                flushTurnToHistory()
                scope.launch {
                    while (speaker.isAudible(tailMs = 0)) delay(100)
                    wave.watch(false)
                    if (wanted && _state.value.phase == LivePhase.Speaking) {
                        _state.update { it.copy(phase = LivePhase.Listening) }
                    }
                }
            }
            is LiveEvent.ToolCall -> {
                lastActivity = SystemClock.elapsedRealtime()
                handleToolCalls(event.calls)
            }
            is LiveEvent.ToolCancelled -> cancelled += event.ids
            is LiveEvent.ResumeHandle -> resumeHandle = event.handle
            LiveEvent.GoAway -> if (wanted) {
                val key = runtime.keys.getSecret("api_key_gemini") ?: return stop()
                client.close()
                connect(key)
            }
        }
    }

    override fun onClosed(problem: String?) {
        mic.stop()
        if (!wanted) return
        val key = runtime.keys.getSecret("api_key_gemini")
        if (retries < MAX_RETRIES && !key.isNullOrBlank()) {
            retries++
            scope.launch {
                delay(1_000L * retries)
                if (wanted) connect(key)
            }
        } else {
            stop()
            _state.update { it.copy(problem = problem ?: "Lost connection to Gemini Live.") }
        }
    }

    private fun handleToolCalls(calls: List<LiveFunctionCall>) {
        scope.launch(Dispatchers.Default) {
            val responses = calls.map { call ->
                val resultText = runtime.executeManagedTool(
                    call = LlmToolCall(call.id, call.name, call.args),
                    trust = trust,
                    onApproval = { req ->
                        if (com.orin.app.voice.OrbOverlay.canShow(context)) {
                            overlayCard.confirm(req.title, req.detail)
                        } else {
                            onApproval(req)
                        }
                    },
                )
                LiveFunctionResult(
                    id = call.id,
                    name = call.name,
                    response = buildJsonObject { put("result", resultText) },
                )
            }.filterNot { it.id in cancelled }
            if (wanted && responses.isNotEmpty()) {
                client.send(LiveProtocol.toolResponseMessage(responses))
            }
        }
    }

    private fun flushTurnToHistory() {
        val u = userTranscript.toString().trim()
        val a = assistantTranscript.toString().trim()
        userTranscript.clear()
        assistantTranscript.clear()
        if (u.isEmpty() && a.isEmpty()) return
        val chatId = conversationId
        if (chatId.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            runtime.recordLocalExchange(
                conversationId = chatId,
                userText = u.ifEmpty { "(Live voice)" },
                replyText = a.ifEmpty { "(Audio reply)" },
                toolLabel = "gemini-live",
            )
        }
    }

    private fun onMicChunk(raw: ByteArray) {
        val pcm = talkOver.clean(raw)
        val echoRisk = !headphonesConnected(context)
        if (echoRisk && speaker.isAudible()) {
            val voice = if (talkOver.available) talkOver.duringReply(raw, pcm) else null
            if (voice == null) return
            interrupt()
            voice.forEach(client::sendAudio)
            return
        }
        val level = pcmLevel(pcm)
        _level.value = (level / LOUD_LEVEL).coerceIn(0f, 1f)
        if (level >= VOICE_LEVEL) lastActivity = SystemClock.elapsedRealtime()
        client.sendAudio(pcm)
    }

    private companion object {
        const val MAX_RETRIES = 3
        const val IDLE_MS = 3 * 60_000L
        const val WATCH_MS = 3_000L
        const val LOUD_LEVEL = 3_000f
    }
}
