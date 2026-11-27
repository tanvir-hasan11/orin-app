package com.orin.app

import android.app.Application
import com.orin.app.agent.OrinAgentRuntime
import com.orin.app.tools.ScreenControlTool
import com.orin.app.tools.DeviceControlTool
import com.orin.app.tools.FileOpsTool
import com.orin.app.tools.MemoryTool
import com.orin.app.tools.MetaAdsTool
import com.orin.app.tools.NotificationTool
import com.orin.app.tools.MorningBriefTool
import com.orin.app.tools.PhoneBasicsTool
import com.orin.app.tools.PhoneIntentsTool
import com.orin.app.tools.PersonalTrackerTool
import com.orin.app.tools.PhoneCalendarTool
import com.orin.app.tools.PhoneContactsTool
import com.orin.app.tools.PhoneSmsTool
import com.orin.app.tools.WebFetchTool
import com.orin.app.tools.WorkspaceShellTool

class OrinApp : Application() {
    val runtime: OrinAgentRuntime by lazy {
        OrinAgentRuntime(this).apply {
            val cal = PhoneCalendarTool(this@OrinApp)
            val contacts = PhoneContactsTool(this@OrinApp)
            val sms = PhoneSmsTool(this@OrinApp, db.dao())
            val device = DeviceControlTool(this@OrinApp, db.dao())
            val meta = MetaAdsTool(keys)
            val tracker = PersonalTrackerTool(db.dao())
            val brief = MorningBriefTool(cal, meta, tracker)

            registerTool(cal)
            registerTool(contacts)
            registerTool(sms)
            registerTool(device)
            registerTool(meta)
            registerTool(tracker)
            registerTool(brief)
            registerTool(FileOpsTool(this@OrinApp))
            registerTool(WorkspaceShellTool(this@OrinApp, db.dao(), keys))
            registerTool(WebFetchTool())
            registerTool(MemoryTool(db.dao()))
            registerTool(ScreenControlTool(this@OrinApp, db.dao()))
            registerTool(NotificationTool(this@OrinApp))
            registerTool(PhoneIntentsTool(this@OrinApp, db.dao()))
            registerTool(PhoneBasicsTool(this@OrinApp, db.dao()))
        }
    }
}
