package com.orin.app

import com.orin.app.local.Habits
import com.orin.app.local.LocalCommand
import com.orin.app.local.LocalRouter
import com.orin.app.local.MediaKey
import com.orin.app.local.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class LocalRouterAndHabitsTest {

    @Test
    fun `matches english hinglish and bangla style local commands`() {
        assertEquals(LocalCommand.SetVolume(30), LocalRouter.match("volume 30 percent kar do"))
        assertEquals(LocalCommand.StepVolume(true), LocalRouter.match("volume barao"))
        assertEquals(LocalCommand.Torch(true), LocalRouter.match("flashlight on karo"))
        assertEquals(LocalCommand.Torch(false), LocalRouter.match("torch bondho koro"))
        assertEquals(LocalCommand.Mute(true), LocalRouter.match("mute phone"))
        assertEquals(LocalCommand.Media(MediaKey.Pause), LocalRouter.match("music pause"))
        assertEquals(LocalCommand.Media(MediaKey.Next), LocalRouter.match("agla gaana"))
        assertEquals(LocalCommand.Battery, LocalRouter.match("kitni battery hai"))
        assertEquals(LocalCommand.Time, LocalRouter.match("koyta baje"))
        assertEquals(LocalCommand.Timer(300), LocalRouter.match("set timer 5 minutes"))
        assertEquals(LocalCommand.Alarm(6, 30, Period.Am), LocalRouter.match("subah 6:30 alarm"))
        assertEquals(LocalCommand.OpenApp("youtube"), LocalRouter.match("YouTube kholo"))
        assertEquals(LocalCommand.Stop, LocalRouter.match("thamo"))
    }

    @Test
    fun `multi step or messaging requests fall through to llm`() {
        assertNull(LocalRouter.match("WhatsApp Rahul ko bolo I am late"))
        assertNull(LocalRouter.match("turn on torch and open camera"))
        assertNull(LocalRouter.match("time in London"))
    }

    @Test
    fun `habits learns new phrase and tracks top commands`() {
        val temp = File.createTempFile("orin-habits-test", ".json").apply { deleteOnExit() }
        temp.delete()
        var now = 1_000L
        val habits = Habits(temp) { now }
        habits.expect("light jala de")
        habits.learn(LocalCommand.Torch(true))
        assertEquals(LocalCommand.Torch(true), habits.lookup(listOf("light jala de")))
        repeat(3) { habits.record(LocalCommand.Battery, 9) }
        assertEquals(listOf(LocalCommand.Battery), habits.top(2, min = 3))
    }
}
