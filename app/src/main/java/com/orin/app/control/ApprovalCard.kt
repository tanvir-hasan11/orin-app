package com.orin.app.control

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A confirmation card that only a finger can resolve — the accessibility
 * service is forbidden from tapping Orin's own overlay, so the model can never
 * approve its own risky action.
 */
class ApprovalCard(private val context: Context) {

    suspend fun confirm(title: String, detail: String): Boolean {
        val decision = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main) {
            show(title, detail, decision)
        }
        return decision.await()
    }

    private fun show(title: String, detail: String, decision: CompletableDeferred<Boolean>) {
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
            gravity = Gravity.BOTTOM
            y = 48
        }

        val padding = (context.resources.displayMetrics.density * 20).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(28, 26, 24))
            setPadding(padding, padding, padding, padding)
            alpha = 0.98f
        }
        container.addView(
            TextView(context).apply {
                text = title
                textSize = 18f
                setTextColor(Color.rgb(255, 238, 210))
            },
        )
        container.addView(
            TextView(context).apply {
                text = detail
                textSize = 14f
                setTextColor(Color.rgb(220, 214, 205))
                setPadding(0, 12, 0, 20)
                maxLines = 6
            },
        )
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val deny = Button(context).apply { text = "Deny" }
        val allow = Button(context).apply { text = "Allow" }
        buttons.addView(deny)
        buttons.addView(allow)
        container.addView(buttons)

        fun finish(result: Boolean) {
            runCatching { manager.removeView(container) }
            if (decision.isActive) decision.complete(result)
        }
        deny.setOnClickListener { finish(false) }
        allow.setOnClickListener { finish(true) }

        runCatching { manager.addView(container, params) }
            .onFailure { if (decision.isActive) decision.complete(false) }
    }
}
