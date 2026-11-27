package com.orin.app.control

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Confirmation & handover cards that only a real finger can tap — Accessibility
 * refuses to touch Orin's own package, so the model can never approve its own action.
 */
class ApprovalCard(private val context: Context) {

    @Volatile
    var confirming: Boolean = false
        private set

    suspend fun confirm(title: String, detail: String, timeoutMs: Long = 30_000L): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) return false
        val decision = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            confirming = true
            show(
                title = title,
                detail = detail,
                positiveLabel = "Allow",
                negativeLabel = "Deny",
                gravity = Gravity.BOTTOM,
                accentColor = Color.rgb(224, 162, 78),
                decision = decision,
            )
        }
        return try {
            withTimeoutOrNull(timeoutMs) { decision.await() } ?: false
        } finally {
            confirming = false
        }
    }

    /** Top-of-screen handover card for banking/OTP/PIN/payment steps. */
    suspend fun handover(step: String, timeoutMs: Long = 150_000L): Boolean {
        val decision = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            show(
                title = "Your turn — Orin is waiting",
                detail = step,
                positiveLabel = "Done",
                negativeLabel = "Stop",
                gravity = Gravity.TOP,
                accentColor = Color.rgb(255, 180, 84),
                decision = decision,
            )
        }
        return withTimeoutOrNull(timeoutMs) { decision.await() } ?: false
    }

    private fun show(
        title: String,
        detail: String,
        positiveLabel: String,
        negativeLabel: String,
        gravity: Int,
        accentColor: Int,
        decision: CompletableDeferred<Boolean>,
    ) {
        val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            y = 48
        }

        val padding = (context.resources.displayMetrics.density * 18).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.rgb(24, 23, 22))
                cornerRadius = 32f
                setStroke(3, accentColor)
            }
            setPadding(padding, padding, padding, padding)
            alpha = 0.98f
        }
        container.addView(
            TextView(context).apply {
                text = title
                textSize = 17f
                setTextColor(Color.rgb(255, 238, 210))
            },
        )
        container.addView(
            TextView(context).apply {
                text = detail
                textSize = 14f
                setTextColor(Color.rgb(220, 214, 205))
                setPadding(0, 10, 0, 16)
                maxLines = 6
            },
        )
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setGravity(Gravity.END)
        }
        val negative = Button(context).apply { text = negativeLabel; setAllCaps(false) }
        val positive = Button(context).apply { text = positiveLabel; setAllCaps(false) }
        buttons.addView(negative)
        buttons.addView(positive)
        container.addView(buttons)

        fun finish(result: Boolean) {
            runCatching { manager.removeView(container) }
            if (decision.isActive) decision.complete(result)
        }
        negative.setOnClickListener { finish(false) }
        positive.setOnClickListener { finish(true) }

        runCatching { manager.addView(container, params) }
            .onFailure { if (decision.isActive) decision.complete(false) }
    }
}
