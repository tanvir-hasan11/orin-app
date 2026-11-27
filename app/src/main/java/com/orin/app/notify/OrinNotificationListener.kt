package com.orin.app.notify

import android.app.Notification
import android.content.Context
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import java.util.Locale

/** Keeps a short in-memory view of recent notifications for "what did I miss?". */
class OrinNotificationListener : NotificationListenerService() {

    private var speaker: TextToSpeech? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        speaker = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                speaker?.setLanguage(Locale.forLanguageTag("en-IN"))
            }
        }
    }

    override fun onListenerDisconnected() {
        speaker?.shutdown()
        speaker = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        val extras = notification.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        if (title.isBlank() && text.isBlank()) return
        if (notification.isOngoing) return

        val item = NotificationTriage.Item(
            sender = title.ifBlank { notification.packageName.substringAfterLast('.') },
            text = text,
            timestampMs = notification.postTime,
            isCode = NotificationTriage.codeLike(text),
            hasLink = NotificationTriage.linkLike(text),
        )
        synchronized(items) {
            items.add(0, item)
            while (items.size > MAX_ITEMS) items.removeAt(items.lastIndex)
        }

        val prefs = watchPrefs()
        val until = prefs.getLong(KEY_WATCH_UNTIL, 0L)
        if (until > System.currentTimeMillis() && item.sender.isNotBlank()) {
            // Watch mode announces who wrote, never what they wrote.
            speaker?.speak("${item.sender} sent a message", TextToSpeech.QUEUE_FLUSH, null, "orin_watch")
        }
    }

    private fun watchPrefs(): SharedPreferences =
        getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS = "orin_notify"
        const val KEY_WATCH_UNTIL = "watch_until"
        private const val MAX_ITEMS = 60

        private val items = mutableListOf<NotificationTriage.Item>()

        fun snapshot(): List<NotificationTriage.Item> = synchronized(items) { items.toList() }

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
