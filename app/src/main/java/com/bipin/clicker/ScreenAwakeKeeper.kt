// ===== NAYI FILE =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/ScreenAwakeKeeper.kt
package com.bipin.clicker

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager

/**
 * Service chalte waqt phone ki screen apne aap (idle timeout se) lock/band NAHI hone deta.
 *
 * Ye root ya koi khaas permission nahi maangta - Android ka hi normal "keep screen on"
 * window-flag istemal karta hai (jaisa YouTube/Maps video dekhte waqt karte hain), bas
 * ek bilkul CHHUPI hui (invisible, 1x1 pixel, touch na rokne wali) window ke zariye,
 * taaki driver ko kuch dikhe ya feel na ho.
 *
 * DHYAN RAKHO: ye sirf IDLE TIMEOUT se bachata hai. Agar user khud Power button dabakar
 * phone lock kare, to Android use kabhi nahi rokta (suraksha ke liye) - koi bhi normal
 * app aisa nahi kar sakti.
 */
object ScreenAwakeKeeper {
    private var view: View? = null
    private var wm: WindowManager? = null

    fun start(service: AccessibilityService) {
        if (view != null) return
        try {
            val v = View(service)
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )
            val w = service.getSystemService(WindowManager::class.java)
            w.addView(v, lp)
            wm = w
            view = v
        } catch (e: Exception) {
            // Kuch phones ye overlay allow nahi karte; tab app bina is suvidha ke hi chalta rahega
        }
    }

    fun stop() {
        try {
            val v = view
            if (v != null) wm?.removeView(v)
        } catch (e: Exception) {
        }
        view = null
        wm = null
    }
}
