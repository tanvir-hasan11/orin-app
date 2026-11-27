package com.orin.app

import android.app.Application
import com.orin.app.agent.OrinAgentRuntime
import com.orin.app.data.FileHistoryManager
import com.orin.app.mcp.McpClientManager
import com.orin.app.mcp.McpTool
import com.orin.app.tools.ApplyPatchTool
import com.orin.app.tools.AutomationTool
import com.orin.app.tools.BgKillTool
import com.orin.app.tools.BgListTool
import com.orin.app.tools.DeviceControlTool
import com.orin.app.tools.EditFileTool
import com.orin.app.tools.FileHistoryTool
import com.orin.app.tools.FileOpsTool
import com.orin.app.tools.GitTool
import com.orin.app.tools.MemoryTool
import com.orin.app.tools.MetaAdsTool
import com.orin.app.tools.MorningBriefTool
import com.orin.app.tools.NotificationReplyTool
import com.orin.app.tools.NotificationTool
import com.orin.app.tools.PersonalTrackerTool
import com.orin.app.tools.PhoneBasicsTool
import com.orin.app.tools.PhoneCalendarTool
import com.orin.app.tools.PhoneContactsTool
import com.orin.app.tools.PhoneIntentsTool
import com.orin.app.tools.PhoneSmsTool
import com.orin.app.tools.RoutineTool
import com.orin.app.tools.ScreenControlTool
import com.orin.app.tools.SearchFilesTool
import com.orin.app.tools.ShellBackgroundTool
import com.orin.app.tools.SubagentTool
import com.orin.app.tools.WebFetchTool
import com.orin.app.tools.WebSearchTool
import com.orin.app.tools.WorkspaceShellTool
import com.orin.app.util.OrinCrashHandler
import com.orin.app.work.AutomationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class OrinApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        OrinCrashHandler.install(this)
        appScope.launch {
            runCatching { AutomationManager.syncWork(this@OrinApp) }
        }
    }

    val runtime: OrinAgentRuntime by lazy {
        OrinAgentRuntime(this).apply {
            val cal = PhoneCalendarTool(this@OrinApp)
            val contacts = PhoneContactsTool(this@OrinApp)
            val sms = PhoneSmsTool(this@OrinApp, db.dao())
            val device = DeviceControlTool(this@OrinApp, db.dao())
            val meta = MetaAdsTool(keys, dao = db.dao())
            val tracker = PersonalTrackerTool(db.dao())
            val brief = MorningBriefTool(cal, meta, tracker)
            val history = FileHistoryManager(File(filesDir, "file_history"))

            registerTool(cal)
            registerTool(contacts)
            registerTool(sms)
            registerTool(device)
            registerTool(meta)
            registerTool(tracker)
            registerTool(brief)
            registerTool(AutomationTool(this@OrinApp, db.dao()))
            registerTool(FileOpsTool(this@OrinApp, keys, history))
            registerTool(EditFileTool(this@OrinApp, keys, history))
            registerTool(ApplyPatchTool(this@OrinApp, keys, history))
            registerTool(SearchFilesTool(this@OrinApp, keys))
            registerTool(FileHistoryTool(this@OrinApp, keys, history))
            registerTool(GitTool(this@OrinApp, db.dao(), keys))
            registerTool(WorkspaceShellTool(this@OrinApp, db.dao(), keys))
            registerTool(ShellBackgroundTool(this@OrinApp, db.dao(), keys))
            registerTool(BgListTool(keys))
            registerTool(BgKillTool())
            registerTool(WebFetchTool())
            registerTool(WebSearchTool(keys))
            registerTool(McpTool(McpClientManager(this@OrinApp, keys)))
            registerTool(SubagentTool { this@apply })
            registerTool(MemoryTool(db.dao()))
            registerTool(ScreenControlTool(this@OrinApp, db.dao(), keys))
            registerTool(NotificationTool(this@OrinApp))
            registerTool(NotificationReplyTool(this@OrinApp))
            registerTool(RoutineTool(this@OrinApp))
            registerTool(PhoneIntentsTool(this@OrinApp, db.dao()))
            registerTool(PhoneBasicsTool(this@OrinApp, db.dao()))
        }
    }
}
