package com.bipin.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast

/**
 * SWIPE ZONE: tum khud tay karte ho ki swipe KAHAN hoga.
 *
 *  - Hari line + do handle screen par aate hain.
 *  - Hara gol (») handle: pakad kar upar/neeche/left/right le jao -> poori zone khiskegi.
 *  - Narangi gol handle: sirf line ki lambai (end point) badalta hai.
 *  - SAVE dabao -> ye jagah yaad ho jati hai. Uske baad swipe SIRF isi line par hoga, poori screen par nahi.
 *  - TEST dabao -> ek baar us line par swipe karke dekhta hai.
 *  - RESET -> zone hata do (auto-detect wapas).
 */
object SwipeZone {
    class Zone(val startX: Float, val endX: Float, val y: Float)

    @Volatile
    var service: AccessibilityService? = null

    private const val PREFS = "swipe_zone"
    private val handler = Handler(Looper.getMainLooper())

    fun get(ctx: Context): Zone? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("set", false)) return null
        return Zone(p.getFloat("sx", 0f), p.getFloat("ex", 0f), p.getFloat("y", 0f))
    }

    fun isSet(ctx: Context): Boolean = get(ctx) != null

    private fun save(ctx: Context, sx: Float, ex: Float, y: Float) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("set", true).putFloat("sx", sx).putFloat("ex", ex).putFloat("y", y).apply()
    }

    private fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ------------------------------------------------------------------ editor

    private class LineView(ctx: Context) : View(ctx) {
        var sx = 0f
        var ex = 0f
        var cy = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(225, 0, 220, 90)
            strokeWidth = 14f
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val loc = IntArray(2)
            getLocationOnScreen(loc)
            canvas.save()
            canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
            canvas.drawLine(sx, cy, ex, cy, paint)
            canvas.restore()
        }
    }

    private class HandleView(ctx: Context, val label: String, color: Int, val size: Int) : View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            textSize = size * 0.34f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }

        override fun onDraw(canvas: Canvas) {
            val r = size / 2f
            canvas.drawCircle(r, r, r - 4f, fill)
            canvas.drawCircle(r, r, r - 4f, ring)
            canvas.drawText(label, r, r + text.textSize * 0.35f, text)
        }
    }

    private var wm: WindowManager? = null
    private var lineView: LineView? = null
    private var startView: HandleView? = null
    private var endView: HandleView? = null
    private var panel: View? = null
    private var lineLp: WindowManager.LayoutParams? = null
    private var startLp: WindowManager.LayoutParams? = null
    private var endLp: WindowManager.LayoutParams? = null

    private var sx = 0f
    private var ex = 0f
    private var zy = 0f
    private var offX = 0
    private var offY = 0
    private var screenW = 0
    private var screenH = 0
    private var editing = false

    /** Editor kholta hai. Service ON na ho to false. */
    fun openEditor(ctx: Context): Boolean {
        val svc = service ?: return false
        handler.post { showEditor(svc, true) }
        return true
    }

    fun closeEditor() {
        editing = false
        removeWindows()
    }

    private fun removeWindows() {
        val w = wm
        for (v in listOf<View?>(lineView, startView, endView, panel)) {
            try { if (v != null) w?.removeView(v) } catch (_: Exception) {}
        }
        lineView = null; startView = null; endView = null; panel = null
    }

    private fun showEditor(svc: AccessibilityService, loadSaved: Boolean) {
        removeWindows()
        val dm = svc.resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        if (loadSaved) {
            val saved = get(svc)
            if (saved != null) {
                sx = saved.startX; ex = saved.endX; zy = saved.y
            } else {
                sx = screenW * 0.229f; ex = screenW * 0.90f; zy = screenH * 0.43f
            }
        }
        editing = true

        val w = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm = w
        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val flagsTouch = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        val flagsNoTouch = flagsTouch or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

        // 1) hari line (touch nahi rokti)
        val lv = LineView(svc)
        val llp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, 40, type, flagsNoTouch, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
        lineView = lv; lineLp = llp
        w.addView(lv, llp)

        // 2) start handle (poori zone khiskata hai)
        val sv = HandleView(svc, "\u00BB", Color.argb(240, 0, 180, 70), 120)
        val slp = WindowManager.LayoutParams(120, 120, type, flagsTouch, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START }
        startView = sv; startLp = slp
        attachMoveAll(sv)
        w.addView(sv, slp)

        // 3) end handle (sirf lambai)
        val ev = HandleView(svc, "END", Color.argb(240, 255, 143, 0), 96)
        val elp = WindowManager.LayoutParams(96, 96, type, flagsTouch, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.TOP or Gravity.START }
        endView = ev; endLp = elp
        attachResize(ev)
        w.addView(ev, elp)

        // 4) neeche buttons
        val row = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.argb(230, 25, 25, 25))
            setPadding(8, 8, 8, 8)
        }
        fun btn(label: String, onClick: () -> Unit) = Button(svc).apply {
            text = label
            textSize = 13f
            setOnClickListener { onClick() }
            row.addView(this)
        }
        btn("TEST") { runTest(svc) }
        btn("SAVE") {
            save(svc, sx, ex, zy)
            Toast.makeText(svc, "Swipe zone SAVE ho gaya", Toast.LENGTH_LONG).show()
            closeEditor()
        }
        btn("RESET") {
            clear(svc)
            Toast.makeText(svc, "Zone hata diya (auto-detect wapas)", Toast.LENGTH_LONG).show()
            closeEditor()
        }
        btn("X") { closeEditor() }
        val plp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = 260 }
        panel = row
        w.addView(row, plp)

        updateAll()
        // window ki position aur screen position me farak (status bar wagairah) naap lo
        sv.post {
            val loc = IntArray(2)
            sv.getLocationOnScreen(loc)
            offX = loc[0] - slp.x
            offY = loc[1] - slp.y
            updateAll()
        }
    }

    private fun updateAll() {
        val w = wm ?: return
        try {
            val sv = startView; val slp = startLp
            if (sv != null && slp != null) {
                slp.x = (sx - sv.size / 2f).toInt() - offX
                slp.y = (zy - sv.size / 2f).toInt() - offY
                w.updateViewLayout(sv, slp)
            }
            val ev = endView; val elp = endLp
            if (ev != null && elp != null) {
                elp.x = (ex - ev.size / 2f).toInt() - offX
                elp.y = (zy - ev.size / 2f).toInt() - offY
                w.updateViewLayout(ev, elp)
            }
            val lv = lineView; val llp = lineLp
            if (lv != null && llp != null) {
                lv.sx = sx; lv.ex = ex; lv.cy = zy
                llp.y = (zy - 20f).toInt() - offY
                w.updateViewLayout(lv, llp)
                lv.invalidate()
            }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachMoveAll(v: View) {
        var downX = 0f; var downY = 0f
        var sx0 = 0f; var ex0 = 0f; var y0 = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; sx0 = sx; ex0 = ex; y0 = zy
                }
                MotionEvent.ACTION_MOVE -> {
                    val len = ex0 - sx0
                    sx = (sx0 + (e.rawX - downX)).coerceIn(30f, (screenW - 30f - len).coerceAtLeast(31f))
                    ex = sx + len
                    zy = (y0 + (e.rawY - downY)).coerceIn(60f, screenH - 60f)
                    updateAll()
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachResize(v: View) {
        var downX = 0f
        var ex0 = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; ex0 = ex }
                MotionEvent.ACTION_MOVE -> {
                    ex = (ex0 + (e.rawX - downX)).coerceIn(sx + 150f, screenW - 20f)
                    updateAll()
                }
            }
            true
        }
    }

    /** Editor chhupao (warna handles hi touch le lenge), ek swipe karo, phir editor wapas. */
    private fun runTest(svc: AccessibilityService) {
        val a = sx; val b = ex; val y = zy
        removeWindows()
        handler.postDelayed({
            try {
                val path = Path().apply {
                    moveTo(a, y); lineTo(a + 10f, y); lineTo(b, y)
                }
                svc.dispatchGesture(
                    GestureDescription.Builder()
                        .addStroke(GestureDescription.StrokeDescription(path, 0L, 450L))
                        .build(),
                    null, null
                )
                SwipeIndicator.show(svc, a, y, b, 650L)
            } catch (_: Exception) {
            }
        }, 250L)
        handler.postDelayed({ if (editing) showEditor(svc, false) }, 2200L)
    }
}
