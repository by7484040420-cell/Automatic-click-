package com.bipin.clicker

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator

/**
 * Jab swipe hota hai tab screen par HARI line + hara gol button (») dikhata hai, jo
 * start point se end point tak sweep karta hai. Isse dikhta hai ki swipe kahan ho raha hai.
 *
 * Accessibility overlay hai: touch nahi rokta (NOT_TOUCHABLE) aur order card ke upar bhi dikhta hai.
 */
object SwipeIndicator {
    private val handler = Handler(Looper.getMainLooper())
    private var view: IndicatorView? = null
    private var windowManager: WindowManager? = null
    private var animator: ValueAnimator? = null
    private val removeRunnable = Runnable { hide() }

    private class IndicatorView(context: Context) : View(context) {
        var sx = 0f
        var ex = 0f
        var cy = 0f
        var progress = 0f

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 0, 220, 90)
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
        }
        private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(240, 0, 200, 80)
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 40f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        override fun onDraw(canvas: Canvas) {
            // Window screen ke kisi bhi hisse me ho sakti hai - screen coordinates me badlo
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())

            canvas.drawLine(sx, cy, ex, cy, linePaint)
            canvas.drawCircle(sx, cy, 11f, dotPaint)
            canvas.drawCircle(ex, cy, 11f, dotPaint)

            val x = sx + (ex - sx) * progress
            canvas.drawCircle(x, cy, 36f, dotPaint)
            canvas.drawCircle(x, cy, 36f, ringPaint)
            canvas.drawText("»", x, cy + 14f, textPaint)

            canvas.restore()
        }
    }

    fun show(service: AccessibilityService, startX: Float, y: Float, endX: Float, durationMs: Long) {
        handler.post {
            try {
                hide()
                val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val v = IndicatorView(service).apply {
                    sx = startX
                    ex = endX
                    cy = y
                }
                val h = 240
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    h,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    this.y = (y - h / 2f).toInt().coerceAtLeast(0)
                }
                wm.addView(v, lp)
                windowManager = wm
                view = v

                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = durationMs
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        v.progress = it.animatedValue as Float
                        v.invalidate()
                    }
                    start()
                }
                handler.removeCallbacks(removeRunnable)
                handler.postDelayed(removeRunnable, durationMs + 700L)
            } catch (_: Exception) {
            }
        }
    }

    fun hide() {
        handler.removeCallbacks(removeRunnable)
        try { animator?.cancel() } catch (_: Exception) {}
        animator = null
        try { view?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        view = null
    }
}
