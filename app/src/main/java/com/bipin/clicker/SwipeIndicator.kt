package com.bipin.clicker

import android.accessibilityservice.AccessibilityService
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
import android.widget.TextView

/**
 * Persistent swipe-zone display.
 *
 * Screen par service ON rehne tak sirf:
 *  - green swipe line
 *  - green circular >> marker
 *  - mismatch hone par neeche "ORDER NOT MATCH"
 *
 * Ye overlay NOT_TOUCHABLE hai, isliye order app ka touch block nahi karta.
 * Zone ko manually upar/niche/left/right set karne ke liye existing
 * "SWIPE ZONE SET KAREIN" editor use hota hai; SAVE ke baad yahi line
 * nayi position par hamesha dikhegi.
 */
object SwipeIndicator {
    private val handler = Handler(Looper.getMainLooper())
    private var lineView: IndicatorView? = null
    private var statusView: TextView? = null
    private var windowManager: WindowManager? = null
    private var shownService: AccessibilityService? = null
    private val syncRunnable = object : Runnable {
        override fun run() {
            val svc = shownService
            val v = lineView
            if (svc != null && v != null) {
                val saved = SwipeZone.get(svc)
                if (saved != null) {
                    v.sx = saved.startX
                    v.ex = saved.endX
                    v.cy = saved.y
                    v.invalidate()
                }
                handler.postDelayed(this, 250L)
            }
        }
    }

    private class IndicatorView(context: Context) : View(context) {
        var sx = 0f
        var ex = 0f
        var cy = 0f

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(225, 0, 220, 90)
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
        }
        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 0, 195, 75)
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
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())

            canvas.drawLine(sx, cy, ex, cy, linePaint)
            canvas.drawCircle(sx, cy, 11f, circlePaint)
            canvas.drawCircle(ex, cy, 11f, circlePaint)

            // Persistent handle: ye sirf visual marker hai; touch nahi leta.
            canvas.drawCircle(sx, cy, 36f, circlePaint)
            canvas.drawCircle(sx, cy, 36f, ringPaint)
            canvas.drawText("»", sx, cy + 14f, textPaint)

            canvas.restore()
        }
    }

    fun showPersistent(service: AccessibilityService) {
        handler.post {
            try {
                shownService = service
                val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                windowManager = wm

                val dm = service.resources.displayMetrics
                val saved = SwipeZone.get(service)
                val sx = saved?.startX ?: dm.widthPixels * 0.23f
                val ex = saved?.endX ?: dm.widthPixels * 0.89f
                val y = saved?.y ?: dm.heightPixels * 0.72f

                if (lineView == null) {
                    val v = IndicatorView(service).apply {
                        this.sx = sx
                        this.ex = ex
                        this.cy = y
                    }
                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        dm.heightPixels,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        x = 0
                        this.y = 0
                    }
                    wm.addView(v, lp)
                    lineView = v
                } else {
                    lineView?.sx = sx
                    lineView?.ex = ex
                    lineView?.cy = y
                    lineView?.invalidate()
                }

                ensureStatusView(service)
                setOrderMatch(true)
                handler.removeCallbacks(syncRunnable)
                handler.post(syncRunnable)
            } catch (_: Exception) {
            }
        }
    }

    private fun ensureStatusView(context: Context) {
        if (statusView != null) return
        try {
            val wm = windowManager ?: return
            val tv = TextView(context).apply {
                text = ""
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(22, 10, 22, 10)
                setBackgroundColor(Color.argb(220, 190, 0, 0))
                gravity = Gravity.CENTER
            }
            val dm = context.resources.displayMetrics
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = (dm.heightPixels * 0.06f).toInt()
            }
            wm.addView(tv, lp)
            statusView = tv
        } catch (_: Exception) {
        }
    }

    /** true = matched/normal, false = current order location did not match. */
    fun setOrderMatch(match: Boolean) {
        handler.post {
            val tv = statusView ?: return@post
            if (match) {
                tv.text = ""
                tv.visibility = View.GONE
            } else {
                tv.text = "ORDER NOT MATCH"
                tv.visibility = View.VISIBLE
            }
        }
    }

    /** Old one-shot animation API is kept for existing calls. */
    fun show(service: AccessibilityService, startX: Float, y: Float, endX: Float, durationMs: Long) {
        // Swipe ke waqt bhi persistent line ko current swipe coordinates par refresh karo.
        handler.post {
            if (lineView == null) showPersistent(service)
            lineView?.sx = startX
            lineView?.ex = endX
            lineView?.cy = y
            lineView?.invalidate()
        }
    }

    fun hide() {
        handler.removeCallbacks(syncRunnable)
        handler.removeCallbacksAndMessages(null)
        try { lineView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        try { statusView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        lineView = null
        statusView = null
        shownService = null
    }
}
