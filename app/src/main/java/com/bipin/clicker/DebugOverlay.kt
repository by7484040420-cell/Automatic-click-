package com.bipin.clicker

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * Toast bahut jaldi (2-3 second) gayab ho jaata hai, isliye screenshot lene ka
 * time hi nahi milta. Ye ek CHHOTA, HAMESHA DIKHNE WAALA text-box hai jo screen
 * ke top par baithta hai aur har scan ke baad apna message khud update kar leta
 * hai - jab tak service chal rahi hai wo screen par rehta hai, isliye NORMAL
 * SCREENSHOT se bhi capture ho jaayega, koi jaldi karne ki zaroorat nahi.
 *
 * - Touch nahi karta (FLAG_NOT_TOUCHABLE) - neeche waala "Accept" button dabane
 *   me koi rukawat nahi aayegi.
 * - Bahut chhota hai (sirf 2-3 line), screen ka bada hissa cover nahi karta.
 */
object DebugOverlay {

    private var windowManager: WindowManager? = null
    private var view: TextView? = null

    /** Overlay ka message update karta hai (ya pehli baar ho to overlay banata hai). */
    fun update(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                ensureView(context.applicationContext)
                view?.text = "\uD83D\uDD0D BipinClicker DEBUG:\n$message"
            } catch (_: Exception) {
                // Overlay permission na ho to bhi app crash nahi hogi, bas debug box nahi dikhega
            }
        }
    }

    /** Service band hone par overlay hata do. */
    fun hide() {
        Handler(Looper.getMainLooper()).post {
            view?.let { v -> runCatching { windowManager?.removeView(v) } }
            view = null
        }
    }

    private fun ensureView(context: Context) {
        if (view != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val tv = TextView(context).apply {
            setBackgroundColor(Color.parseColor("#E6000000")) // 90% opaque black
            setTextColor(Color.parseColor("#00FF6A")) // halki green - order screen par saaf dikhega
            textSize = 12f
            setPadding(20, 14, 20, 14)
            maxLines = 4
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 80 // status bar ke thoda neeche
        }

        runCatching { windowManager?.addView(tv, params) }
        view = tv
    }
}
