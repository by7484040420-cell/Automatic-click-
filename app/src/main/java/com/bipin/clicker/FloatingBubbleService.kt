package com.bipin.clicker

import android.app.NotificationChannel
import android.app.NotificationManager
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

/**
 * Ek chhota floating bubble jo screen par (kisi bhi app ke upar) hamesha dikhta rehta hai.
 * - Tap: Pickup/Drop area settings screen khulti hai
 * - Long-press: bubble band ho jata hai (service stop)
 * - Drag: bubble ko idhar-udhar move kar sakte hain
 * - Rang: GREEN = Auto-Accept chalu, GREY = paused (Start/Stop button se control hota hai)
 *
 * Ek waqt me sirf EK hi bubble/window rahe - isliye onCreate me pehle purani (agar
 * kisi wajah se bach gayi ho) hata kar nayi banata hai.
 */
class FloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: TextView? = null
    private lateinit var params: WindowManager.LayoutParams
    private val longPressHandler = Handler(Looper.getMainLooper())
    private var longPressTriggered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithNotification()
        setRunningFlag(true)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Duplicate window na bane isliye purana view (agar bacha ho) pehle hata do.
        bubbleView?.let { old -> runCatching { windowManager?.removeView(old) } }

        val view = TextView(this).apply {
            text = if (AreaPrefs.isPaused(this@FloatingBubbleService)) "OFF" else "ON"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(bubbleColor())
        }
        bubbleView = view

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            140, 140,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 300
        }

        var downRawX = 0f
        var downRawY = 0f
        var downX = 0
        var downY = 0
        var moved = false

        val longPressRunnable = Runnable {
            longPressTriggered = true
            Toast.makeText(this, "BipinClicker bubble band kiya", Toast.LENGTH_SHORT).show()
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
                    longPressHandler.postDelayed(longPressRunnable, 600)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) {
                        moved = true
                        longPressHandler.removeCallbacks(longPressRunnable)
                    }
                    params.x = downX + dx
                    params.y = downY + dy
                    runCatching { windowManager?.updateViewLayout(view, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    if (!moved && !longPressTriggered) openAreaSettings()
                    true
                }
                else -> false
            }
        }

        runCatching { windowManager?.addView(view, params) }
    }

    /** Bubble par simple tap karne par Pickup/Drop area settings screen khulti hai. */
    private fun openAreaSettings() {
        val intent = Intent(this, AreaPreferenceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /** Bahar se (Start/Stop button se) bubble ka color turant refresh karne ke liye. */
    fun refreshColor() {
        bubbleView?.apply {
            text = if (AreaPrefs.isPaused(this@FloatingBubbleService)) "OFF" else "ON"
            setBackgroundColor(bubbleColor())
        }
    }

    private fun bubbleColor(): Int =
        if (AreaPrefs.isPaused(this)) Color.parseColor("#B0BEC5") else Color.parseColor("#43A047")

    private fun startForegroundWithNotification() {
        val channelId = "bipin_clicker_bubble"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "BIPIN Clicker Bubble", NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_service_on)
            .setContentTitle("BIPIN Clicker Bubble chal raha hai")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(777, notification)
    }

    private fun setRunningFlag(running: Boolean) {
        getSharedPreferences("clicker_area_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("bubble_running", running).apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        longPressHandler.removeCallbacksAndMessages(null)
        setRunningFlag(false)
        bubbleView?.let { view -> runCatching { windowManager?.removeView(view) } }
        bubbleView = null
    }
}
