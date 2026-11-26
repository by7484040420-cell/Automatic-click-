package com.bipin.clicker

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * SWIPE ZONE (Zero-Drag Auto-Fix):
 * Har phone ki screen width, height, DPI aur aspect ratio (chhota, bada, patla ya mota)
 * ke hisab se swipe line apne aap exact slider jagah par fix ho jati hai.
 * User ko haath se line upar-neeche set karne ki zaroorat nahi padti.
 */
object SwipeZone {
    class Zone(val startX: Float, val endX: Float, val y: Float)

    @Volatile
    var service: AccessibilityService? = null

    private const val PREFS = "swipe_zone"
    private const val AUTO_ZONE_VERSION = 3

    fun getScreenDimensions(ctx: Context): Pair<Int, Int> {
        try {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val b = wm.maximumWindowMetrics.bounds
                    if (b.width() > 0 && b.height() > 0) {
                        return Pair(b.width(), b.height())
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val dm = DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay?.getRealMetrics(dm)
                    if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                        return Pair(dm.widthPixels, dm.heightPixels)
                    }
                }
            }
        } catch (_: Exception) {
        }
        val fallback = ctx.resources.displayMetrics
        return Pair(fallback.widthPixels.coerceAtLeast(1), fallback.heightPixels.coerceAtLeast(1))
    }

    fun computeAutoZone(ctx: Context): Zone {
        val (realW, realH) = getScreenDimensions(ctx)
        val w = realW.toFloat()
        val h = realH.toFloat()
        val density = ctx.resources.displayMetrics.density.coerceAtLeast(1f)

        val isWideTablet = w > 600f * density
        val sx: Float
        val ex: Float
        if (isWideTablet) {
            val cardW = minOf(w * 0.92f, 440f * density)
            val cardLeft = (w - cardW) / 2f
            sx = cardLeft + cardW * 0.21f
            ex = cardLeft + cardW * 0.91f
        } else {
            // Porter slider blue pill ke andar safed (>>) thumb ~21% width par aur right end ~89% par hota hai
            sx = (74f * density).coerceIn(w * 0.18f, w * 0.23f)
            ex = (w - 36f * density).coerceIn(w * 0.87f, w * 0.91f)
        }

        val minSide = minOf(w, h)
        val maxSide = maxOf(w, h)
        val aspect = maxSide / minSide.coerceAtLeast(1f)
        val dpBasedY = 346f * density
        val ratioBasedY = when {
            aspect >= 2.0f -> h * 0.43f
            aspect >= 1.85f -> h * 0.445f
            else -> h * 0.48f
        }
        val zy = ((dpBasedY + ratioBasedY) * 0.5f).coerceIn(h * 0.40f, h * 0.55f)

        return Zone(sx, ex, zy)
    }

    fun autoCalibrateAndSave(ctx: Context): Zone {
        val (w, h) = getScreenDimensions(ctx)
        val zone = computeAutoZone(ctx)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("set", true)
            .putInt("ver", AUTO_ZONE_VERSION)
            .putInt("w", w)
            .putInt("h", h)
            .putFloat("sx", zone.startX)
            .putFloat("ex", zone.endX)
            .putFloat("y", zone.y)
            .apply()
        return zone
    }

    fun ensureAutoCalibrated(ctx: Context): Zone {
        val (w, h) = getScreenDimensions(ctx)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val isReady = p.getBoolean("set", false) &&
            p.getInt("ver", 0) == AUTO_ZONE_VERSION &&
            p.getInt("w", 0) == w &&
            p.getInt("h", 0) == h
        if (isReady) {
            val sx = p.getFloat("sx", 0f)
            val ex = p.getFloat("ex", 0f)
            val y = p.getFloat("y", 0f)
            if (sx > 0f && ex > sx + 80f && y > 0f) {
                return Zone(sx, ex, y)
            }
        }
        return autoCalibrateAndSave(ctx)
    }

    fun get(ctx: Context): Zone? = ensureAutoCalibrated(ctx)

    fun isSet(ctx: Context): Boolean {
        val (w, h) = getScreenDimensions(ctx)
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getBoolean("set", false) &&
            p.getInt("ver", 0) == AUTO_ZONE_VERSION &&
            p.getInt("w", 0) == w &&
            p.getInt("h", 0) == h
    }

    fun isEditing(): Boolean = false

    fun closeEditor() {
        // Zero-drag mode: koi manual overlay editor nahi khulta
    }
}
