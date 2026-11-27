package com.orin.app.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.orin.app.MainActivity
import com.orin.app.R

/** Keeps the floating orb alive while Orin is used over other apps. */
class OrinVoiceService : Service() {

    private var orb: OrbOverlay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        val enabled = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(OrinVoice.PREF_ORB_ENABLED, false)
        if (enabled && OrbOverlay.canShow(this)) {
            orb = OrbOverlay(
                context = this,
                onTap = { launch(OrinVoice.ACTION_VOICE) },
                onDoubleTap = { launch(OrinVoice.ACTION_TYPE) },
            ).also { it.show() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            OrinVoice.ACTION_STOP -> stopSelf()
            ACTION_REFRESH -> {
                val enabled = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getBoolean(OrinVoice.PREF_ORB_ENABLED, false)
                if (enabled && OrbOverlay.canShow(this)) {
                    if (orb == null) {
                        orb = OrbOverlay(
                            context = this,
                            onTap = { launch(OrinVoice.ACTION_VOICE) },
                            onDoubleTap = { launch(OrinVoice.ACTION_TYPE) },
                        )
                    }
                    orb?.show()
                } else {
                    orb?.hide()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        orb?.hide()
        orb = null
        super.onDestroy()
    }

    private fun launch(action: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            this.action = action
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Orin voice",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps the floating voice orb available" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, OrinVoiceService::class.java).setAction(OrinVoice.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Orin is listening")
            .setContentText("Tap to open Orin")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "orin_voice"
        private const val NOTIFICATION_ID = 2027
        private const val PREFS = "orin_voice"
        private const val ACTION_REFRESH = "com.orin.app.VOICE_REFRESH"

        fun start(context: Context) {
            val intent = Intent(context, OrinVoiceService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        fun refresh(context: Context) {
            val intent = Intent(context, OrinVoiceService::class.java).setAction(ACTION_REFRESH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OrinVoiceService::class.java))
        }
    }
}
