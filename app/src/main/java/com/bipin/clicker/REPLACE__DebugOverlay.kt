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
    private var lastText: String? = null

    /** Overlay ka message update karta hai (ya pehli baar ho to overlay banata hai). */
    fun update(context: Context, message: String) {
        Handler(Looper.getMainLooper()).post {
            try {
                ensureView(context.applicationContext)
                val text = "\uD83D\uDD0D BipinClicker DEBUG:\n$message"
                // FIX: same text dubara set karne se faltu accessibility events bante the
                if (text != lastText) {
                    lastText = text
                    view?.text = text
                }
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
            lastText = null
        }
    }

    /** Debug box screen ke top se kitne pixel neeche tak hai (OCR isse upar ki lines ignore karta hai). */
    fun occupiedBottomPx(): Int = view?.let { 80 + it.height + 20 } ?: 0

    private fun ensureView(context: Context) {
        if (view != null) return

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val tv = TextView(context).apply {
            setBackgroundColor(Color.parseColor("#E6000000")) // 90% opaque black
            setTextColor(Color.parseColor("#00FF6A")) // halki green - order screen par saaf dikhega
            textSize = 11f
            setPadding(20, 14, 20, 14)
            maxLines = 3
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
            // FIX: Android 12+ "untrusted touch" rule - jo overlay 0.8 se zyada opaque ho,
            // uske neeche ke touches (gesture bhi) block ho jate hain. Isliye alpha 0.8.
            alpha = 0.8f
        }

        runCatching { windowManager?.addView(tv, params) }
        view = tv
    }
}
