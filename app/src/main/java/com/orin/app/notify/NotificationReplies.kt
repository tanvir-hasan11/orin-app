package com.orin.app.notify

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle

/**
 * Hands-free replies through a notification's own Reply RemoteInput (WhatsApp, Messages, etc.)
 * without opening the app (ported from JARVIS Replies). Kept in memory only.
 * Sending requires two steps: [prepare] (read back to user) and [send] (verified against [VoiceConsent]).
 */
object NotificationReplies {
    private class Target(
        val key: String,
        val who: String,
        val app: String,
        val pkg: String,
        val action: Notification.Action,
        val at: Long,
    )

    private class Pending(val id: Int, val text: String, val at: Long, val readBack: String)

    private val targets = linkedMapOf<Int, Target>()
    private val ids = mutableMapOf<String, Int>()
    private var next = 1
    private var pending: Pending? = null
    private val heard = StringBuilder()

    @Synchronized
    fun register(key: String, who: String, app: String, pkg: String, n: Notification, at: Long): Int? {
        val action = n.actions?.firstOrNull { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true
        } ?: return null
        val id = ids.getOrPut(key) { next++ }
        targets[id] = Target(key, who, app, pkg, action, at)
        while (targets.size > MAX_TARGETS) {
            targets.remove(targets.keys.first())?.let { ids.remove(it.key) }
        }
        return id
    }

    @Synchronized
    fun idFor(name: String, packages: Set<String>, now: Long): Int? {
        val wanted = name.trim().lowercase().takeIf { it.length >= 2 } ?: return null
        return targets.entries.lastOrNull { (_, t) ->
            t.pkg in packages && now - t.at < FRESH_MS && sameName(t.who.lowercase(), wanted)
        }?.key
    }

    fun sameName(who: String, wanted: String): Boolean {
        val w = who.trim().lowercase()
        val q = wanted.trim().lowercase()
        return w == q || w.split(Regex("\\s+")).containsAll(q.split(Regex("\\s+")))
    }

    @Synchronized
    fun prepare(id: Int, text: String, now: Long): String? {
        val target = targets[id] ?: return null
        val readBack = "Send \"$text\" to ${target.who} on ${target.app}?"
        pending = Pending(id, text, now, readBack)
        heard.clear()
        return readBack
    }

    @Synchronized
    fun heard(text: String) {
        if (pending != null && text.isNotBlank()) {
            heard.append(' ').append(text)
        }
    }

    sealed interface Outcome {
        data class Sent(val who: String, val app: String) : Outcome
        data class Refused(val why: String) : Outcome
    }

    @Synchronized
    fun send(context: Context, now: Long): Outcome {
        val p = pending ?: return Outcome.Refused("Nothing is waiting to be sent. Prepare the reply first.")
        val target = targets[p.id]
        pending = null
        if (target == null) return Outcome.Refused("That notification is gone, so it can't be answered from here.")
        if (now - p.at > CONSENT_MS) return Outcome.Refused("The confirmation timed out. Ask again if the user still wants it.")
        if (!VoiceConsent.confirms(heard.toString(), p.text, p.readBack)) {
            return Outcome.Refused("The user didn't clearly say yes, so nothing was sent.")
        }
        val inputs = target.action.remoteInputs
            ?: return Outcome.Refused("This chat can't be answered from the notification.")
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, p.text) } }
        val fill = Intent().also { RemoteInput.addResultsToIntent(inputs, it, results) }
        return runCatching { target.action.actionIntent.send(context, 0, fill) }.fold(
            onSuccess = { Outcome.Sent(target.who, target.app) },
            onFailure = { Outcome.Refused("The chat app didn't accept the reply. Offer to open the chat instead.") },
        )
    }

    @Synchronized
    fun clear() {
        targets.clear()
        ids.clear()
        pending = null
        heard.clear()
        next = 1
    }

    private const val MAX_TARGETS = 30
    private const val CONSENT_MS = 2 * 60_000L
    private const val FRESH_MS = 2 * 60 * 60_000L
}
