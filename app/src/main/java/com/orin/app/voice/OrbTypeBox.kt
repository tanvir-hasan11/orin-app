package com.orin.app.voice

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout

/**
 * Floating one-line text composer shown over any app when the user double-taps the orb
 * (ported from JARVIS TypeBox). Tapping outside or submitting closes it cleanly.
 */
object OrbTypeBox {
    private var currentView: LinearLayout? = null

    fun show(context: Context, onSubmit: (String) -> Unit) {
        if (currentView != null || !OrbOverlay.canShow(context)) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        val density = context.resources.displayMetrics.density
        val pad = (12 * density).toInt()
        lateinit var input: EditText
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.rgb(26, 25, 24))
                cornerRadius = 24 * density
                setStroke((1.5f * density).toInt(), Color.rgb(224, 162, 78))
            }
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    hide(context)
                }
                false
            }
        }

        val send = {
            val text = input.text?.toString()?.trim().orEmpty()
            hide(context)
            if (text.isNotEmpty()) onSubmit(text)
        }

        input = EditText(context).apply {
            hint = "Ask Orin…"
            setHintTextColor(Color.rgb(160, 155, 148))
            setTextColor(Color.WHITE)
            textSize = 15f
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = null
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    send()
                    true
                } else {
                    false
                }
            }
        }

        val sendButton = Button(context).apply {
            text = "Send"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(217, 119, 87))
                cornerRadius = 18 * density
            }
            setOnClickListener { send() }
        }

        container.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(sendButton)

        runCatching { wm.addView(container, params) }
            .onSuccess {
                currentView = container
                input.requestFocus()
                val imm = context.getSystemService(InputMethodManager::class.java)
                input.post { imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
            }
    }

    fun hide(context: Context) {
        val view = currentView ?: return
        currentView = null
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        runCatching { wm.removeView(view) }
    }
}
