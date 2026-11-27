package com.orin.app.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.hypot

/**
 * A small draggable orb drawn on top of other apps.
 *
 * Tap opens Orin and starts listening; double-tap opens the keyboard entry;
 * dragging parks the orb at the side of the screen.
 */
class OrbOverlay(
    private val context: Context,
    private val onTap: () -> Unit,
    private val onDoubleTap: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences("orin_orb", Context.MODE_PRIVATE)
    private val orbView = OrbView(context)

    private var params: WindowManager.LayoutParams? = null
    private var pulseAnimator: ValueAnimator? = null

    val isShowing: Boolean get() = params != null && orbView.parent != null

    fun show() {
        if (isShowing) return
        val size = context.resources.displayMetrics.density * ORB_SIZE_DP
        val layout = WindowManager.LayoutParams(
            size.toInt(),
            size.toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, -1)
            y = prefs.getInt(KEY_Y, context.resources.displayMetrics.heightPixels / 4)
        }
        params = layout
        runCatching { windowManager.addView(orbView, layout) }

        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener { orbView.setPulse(it.animatedValue as Float) }
            start()
        }
    }

    fun hide() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        params?.let { runCatching { windowManager.removeView(orbView) } }
        params = null
    }

    fun setLevel(level: Float) {
        orbView.setLevel(level)
    }

    private inner class OrbView(context: Context) : View(context) {
        private val gesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDoubleTap()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                val layout = params ?: return false
                layout.x -= distanceX.toInt()
                layout.y -= distanceY.toInt()
                val maxX = context.resources.displayMetrics.widthPixels - width
                layout.x = layout.x.coerceIn(0, maxX.coerceAtLeast(0))
                layout.y = layout.y.coerceIn(0, context.resources.displayMetrics.heightPixels - height)
                runCatching { windowManager.updateViewLayout(this@OrbView, layout) }
                prefs.edit()
                    .putInt(KEY_X, layout.x)
                    .putInt(KEY_Y, layout.y)
                    .apply()
                return true
            }
        })

        private var pulse = 0f
        private var level = 0f
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE0A24E.toInt()
        }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF2C27D.toInt()
        }

        init {
            alpha = 0.92f
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            gesture.onTouchEvent(event)
            return true
        }

        fun setPulse(value: Float) {
            pulse = value
            invalidate()
        }

        fun setLevel(value: Float) {
            level = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val base = minOf(cx, cy)
            val radius = base * (0.62f + pulse * 0.04f + level * 0.22f)

            val haloRadius = radius * 1.7f
            halo.alpha = (42 + pulse * 26).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, haloRadius, halo)

            canvas.drawCircle(cx, cy, radius, fill)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = (context.resources.displayMetrics.density * ORB_SIZE_DP).toInt()
            setMeasuredDimension(
                View.resolveSizeAndState(size, widthMeasureSpec, 0),
                View.resolveSizeAndState(size, heightMeasureSpec, 0),
            )
        }
    }

    companion object {
        private const val ORB_SIZE_DP = 62f
        private const val KEY_X = "x"
        private const val KEY_Y = "y"

        /** True when the user has granted the overlay permission in Settings. */
        fun canShow(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || android.provider.Settings.canDrawOverlays(context)

        fun distance(fromX: Float, fromY: Float, toX: Float, toY: Float): Float =
            hypot(toX - fromX, toY - fromY)
    }
}
