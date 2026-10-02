// ===== REPLACE (purani file hatao, ye daalo) =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/FloatingBubbleService.kt
package com.bipin.clicker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/** Floating live status button for the auto-accept service. */
class FloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: TextView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    private val statusUpdater = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 100L)
        }
    }

    private var longPressTriggered = false
    private var lastBubbleKey = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        try {
            startForegroundWithNotification()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Bubble start nahi ho paya: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

        setRunningFlag(true)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val view = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(6, 6, 6, 6)
            text = "AUTO\nON"
        }

        bubbleView = view

        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            170,
            120,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
            // FIX: Android 12+ untrusted-touch rule (alpha <= 0.8)
            alpha = 0.8f
        }

        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var moved = false

        val longPressRunnable = Runnable {
            longPressTriggered = true
            Toast.makeText(
                this,
                "BipinClicker bubble band kiya",
                Toast.LENGTH_SHORT
            ).show()
            stopSelf()
        }

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = params.x
                    downY = params.y
                    moved = false
                    longPressTriggered = false
                    handler.postDelayed(longPressRunnable, 600L)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()

                    if (abs(dx) > 8 || abs(dy) > 8) {
                        moved = true
                        handler.removeCallbacks(longPressRunnable)
                    }

                    params.x = downX + dx
                    params.y = downY + dy
                    runCatching { windowManager?.updateViewLayout(view, params) }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressTriggered) openAreaSettings()
                    true
                }

                else -> false
            }
        }

        runCatching { windowManager?.addView(view, params) }
        handler.post(statusUpdater)
    }

    private fun refreshStatus() {
        val status = getSharedPreferences(
            "clicker_live_status",
            Context.MODE_PRIVATE
        ).getString("status", "READY") ?: "READY"

        // v13: har 100ms par bubble ka text/color dobara set karne se overlay redraw hota tha.
        // Ab sirf status badalne par update.
        val key = status + AreaPrefs.isPaused(this)
        if (key == lastBubbleKey) return
        lastBubbleKey = key

        bubbleView?.apply {
            when (status) {
                "ORDER FOUND" -> {
                    text = "ORDER\nDETECTED"
                    setBackgroundColor(Color.parseColor("#FF9800"))
                }

                "ORDER UTHA RAHA" -> {
                    text = "ORDER\nUTH RAHA"
                    setBackgroundColor(Color.parseColor("#00C853"))
                }

                "OFF" -> {
                    text = "AUTO\nOFF"
                    setBackgroundColor(Color.parseColor("#9E9E9E"))
                }

                else -> {
                    text = if (AreaPrefs.isPaused(this@FloatingBubbleService)) {
                        "AUTO\nOFF"
                    } else {
                        "AUTO\nON"
                    }
                    setBackgroundColor(
                        if (AreaPrefs.isPaused(this@FloatingBubbleService))
                            Color.parseColor("#9E9E9E")
                        else
                            Color.parseColor("#43A047")
                    )
                }
            }
        }
    }

    private fun openAreaSettings() {
        val intent = Intent(this, AreaPreferenceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // FIX: background se direct startActivity kai phones (ColorOS/MIUI) par chup-chap block hota hai.
        try {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            PendingIntent.getActivity(this, 0, intent, flags).send()
        } catch (e: Exception) {
            try {
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "Settings nahi khuli - app ko manually kholein", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startForegroundWithNotification() {
        val channelId = "bipin_clicker_bubble"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "BIPIN Clicker Bubble",
                NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_service_on)
            .setContentTitle("BIPIN Clicker Bubble chal raha hai")
            .setContentText("Live order status bubble active")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        startForeground(777, notification)
    }

    private fun setRunningFlag(running: Boolean) {
        getSharedPreferences(
            "clicker_area_prefs",
            Context.MODE_PRIVATE
        ).edit()
            .putBoolean("bubble_running", running)
            .apply()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        setRunningFlag(false)

        bubbleView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }

        bubbleView = null
        super.onDestroy()
    }
}
