package com.orin.app.notify

import android.app.Notification
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import java.util.Locale

/** Keeps a short in-memory view of recent notifications for "what did I miss?" and hands-free replies. */
class OrinNotificationListener : NotificationListenerService() {

    private var speaker: TextToSpeech? = null
    private val lastWatchBySender = mutableMapOf<String, Long>()
    private var lastWatchSpoke = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        speaker = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                speaker?.setLanguage(Locale.forLanguageTag("en-IN"))
            }
        }
        runCatching { activeNotifications?.forEach { record(it, announce = false) } }
    }

    override fun onListenerDisconnected() {
        speaker?.shutdown()
        speaker = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        record(notification, announce = true)
    }

    private fun record(sbn: StatusBarNotification, announce: Boolean) {
        val n = sbn.notification ?: return
        if (sbn.packageName == packageName) return
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
        if (title.isBlank() && text.isBlank()) return

        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName.substringAfterLast('.'))
        val sender = title.ifBlank { appLabel }
        val group = extras.getBoolean(NotificationCompat.EXTRA_IS_GROUP_CONVERSATION) ||
            extras.getCharSequence(NotificationCompat.EXTRA_CONVERSATION_TITLE) != null

        val replyId = if (group) {
            null
        } else {
            NotificationReplies.register(sbn.key, sender, appLabel, sbn.packageName, n, sbn.postTime)
        }

        val item = NotificationTriage.Item(
            sender = sender,
            text = text,
            timestampMs = sbn.postTime,
            isCode = NotificationTriage.codeLike(text),
            hasLink = NotificationTriage.linkLike(text),
            group = group,
            replyId = replyId,
        )
        synchronized(items) {
            items.removeAll { it.timestampMs < System.currentTimeMillis() - KEEP_MS }
            items.add(0, item)
            while (items.size > MAX_ITEMS) items.removeAt(items.lastIndex)
        }

        if (!announce || group) return
        val until = watchPrefs().getLong(KEY_WATCH_UNTIL, 0L)
        val now = System.currentTimeMillis()
        if (until <= now || item.sender.isBlank()) return
        val audio = getSystemService(AudioManager::class.java)
        if (audio?.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val senderKey = "${sbn.packageName}|${item.sender}"
        val lastForSender = lastWatchBySender[senderKey] ?: 0L
        if (now - lastForSender < SAME_PERSON_COOLDOWN_MS || now - lastWatchSpoke < GLOBAL_COOLDOWN_MS) return
        lastWatchBySender[senderKey] = now
        lastWatchSpoke = now
        // Watch mode announces who wrote, never what they wrote.
        val msg = if (item.hasLink) {
            "Careful: a link arrived from ${item.sender} on $appLabel."
        } else {
            "${item.sender} sent a message on $appLabel."
        }
        speaker?.speak(msg, TextToSpeech.QUEUE_ADD, null, "orin_watch")
    }

    private fun watchPrefs(): SharedPreferences =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "orin_notify"
        const val KEY_WATCH_UNTIL = "watch_until"
        private const val MAX_ITEMS = 60
        private const val KEEP_MS = 12 * 60 * 60 * 1000L
        private const val SAME_PERSON_COOLDOWN_MS = 10 * 60_000L
        private const val GLOBAL_COOLDOWN_MS = 45_000L

        private val items = mutableListOf<NotificationTriage.Item>()

        fun snapshot(): List<NotificationTriage.Item> = synchronized(items) {
            val cutoff = System.currentTimeMillis() - KEEP_MS
            items.filter { it.timestampMs >= cutoff }
        }

        fun setWatch(context: Context, minutes: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(KEY_WATCH_UNTIL, System.currentTimeMillis() + minutes.coerceIn(1, 180) * 60_000L)
                .apply()
        }

        fun clearWatch(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putLong(KEY_WATCH_UNTIL, 0L).apply()
        }

        fun isWatching(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_WATCH_UNTIL, 0L) > System.currentTimeMillis()

        fun isEnabled(context: Context): Boolean = try {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            enabled.contains(context.packageName)
        } catch (_: Exception) {
            false
        }
    }
}
