// ===== NAYI FILE =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/LicenseManager.kt
package com.bipin.clicker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Approval (license) system.
 *
 * - Har phone ki ek Device ID hoti hai.
 * - Aap GitHub ki licenses.txt me us ID ke saamne EXPIRY (khatam hone ka time) likhte ho.
 * - App har 60 second me file check karta hai. ID/expiry mile aur time baaki ho to hi order uthata hai.
 * - Order uthane ke waqt koi internet nahi lagta: sirf phone me saved time dekha jata hai (bahut tez).
 */
object LicenseManager {

    // >>> SIRF YE EK LINE BADALNI HAI: by7484040420-cell ki jagah apna GitHub username likho <<<
    // (repo ka naam bipin-licenses aur file ka naam licenses.txt rakhna)
    const val LICENSE_URL =
        "https://raw.githubusercontent.com/by7484040420-cell/bipin-licenses/main/licenses.txt"

    private const val PREFS = "license_prefs"
    private const val K_EXPIRY = "expiry"
    private const val K_OFFSET = "offset"
    private const val K_SIG = "sig"
    private const val K_FALLBACK_ID = "fallback_id"
    private const val MARKER = "#BIPIN-LICENSES"

    @Volatile private var loaded = false
    @Volatile private var expiryMs = 0L
    @Volatile private var srvTime = 0L
    @Volatile private var srvElapsed = 0L
    @Volatile private var offset = 0L
    @Volatile private var refreshing = false

    @Volatile
    var lastError: String = ""
        private set

    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val e = p.getLong(K_EXPIRY, 0L)
            val o = p.getLong(K_OFFSET, 0L)
            val s = p.getString(K_SIG, "") ?: ""
            // Prefs ko haath se badla gaya ho (signature na mile) to approval maanya nahi
            if (e > 0L && s == sign(e, o, deviceId(ctx))) {
                expiryMs = e
                offset = o
            } else {
                expiryMs = 0L
                offset = 0L
            }
            loaded = true
        }
    }

    /** Prefs ka signature: koi expiry haath se badle to pakda jaye. */
    private fun sign(expiry: Long, off: Long, id: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(("BK#" + id + "#x7q2").toByteArray(), "HmacSHA256"))
        return mac.doFinal("$expiry|$off".toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Approval turant band (tamper pakda gaya ya admin ne hata diya). */
    fun revoke(ctx: Context) {
        expiryMs = 0L
        loaded = true
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(K_EXPIRY).remove(K_SIG).apply()
    }

    /** Server ke hisab se abhi ka time (phone ki ghadi badalne se bachne ke liye). */
    private fun nowMs(): Long {
        val e = SystemClock.elapsedRealtime()
        return if (srvTime > 0L && e >= srvElapsed) srvTime + (e - srvElapsed)
        else System.currentTimeMillis() + offset
    }

    /** Order wale code me yahi check lagta hai. Sirf memory padhta hai, internet nahi. */
    fun isActive(ctx: Context): Boolean {
        if (Integrity.isTampered(ctx)) return false
        ensureLoaded(ctx)
        return expiryMs > 0L && nowMs() < expiryMs
    }

    fun deviceId(ctx: Context): String {
        val app = ctx.applicationContext
        val aid: String? = try {
            Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (e: Exception) {
            null
        }
        val raw: String = if (!aid.isNullOrBlank() && aid != "9774d56d682e549c") {
            aid
        } else {
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            p.getString(K_FALLBACK_ID, null)
                ?: UUID.randomUUID().toString().replace("-", "").also {
                    p.edit().putString(K_FALLBACK_ID, it).apply()
                }
        }
        return raw.uppercase(Locale.ROOT).take(12)
    }

    fun remainingText(ctx: Context): String {
        ensureLoaded(ctx)
        if (expiryMs <= 0L) return "Approval abhi nahi mila"
        if (expiryMs == Long.MAX_VALUE) return "Unlimited"
        val left = expiryMs - nowMs()
        if (left <= 0L) return "Aapka time khatam ho gaya"
        val mins = left / 60000L
        val d = mins / 1440L
        val h = (mins % 1440L) / 60L
        val m = mins % 60L
        return when {
            d > 0L -> "$d din $h ghanta baaki"
            h > 0L -> "$h ghanta $m minute baaki"
            else -> "$m minute baaki"
        }
    }

    /** Background me GitHub file check karta hai. onDone main thread par chalta hai. */
    fun refreshAsync(ctx: Context, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        ensureLoaded(app)
        if (refreshing) return
        refreshing = true
        Thread {
            try {
                Integrity.recheck(app)
                doRefresh(app)
            } catch (e: Exception) {
                lastError = "Internet/server dikkat: ${e.message}"
            } finally {
                refreshing = false
            }
            if (onDone != null) Handler(Looper.getMainLooper()).post { onDone() }
        }.start()
    }

    private fun doRefresh(app: Context) {
        if (LICENSE_URL.contains("by7484040420-cell")) {
            lastError = "LicenseManager.kt me by7484040420-cell ki jagah apna GitHub naam likho"
            return
        }
        val conn = URL("$LICENSE_URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache")
            val code = conn.responseCode
            if (code != 200) {
                lastError = "Server ne code $code diya"
                return
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val srv = conn.date
            // Galat/adhuri file (jaise wifi login page) ho to purana approval waisa hi rehne do
            if (!body.contains(MARKER)) {
                lastError = "licenses.txt galat hai (pehli line #BIPIN-LICENSES honi chahiye)"
                return
            }
            val newExpiry = findExpiry(body, deviceId(app))
            if (srv > 0L) {
                srvTime = srv
                srvElapsed = SystemClock.elapsedRealtime()
                offset = srv - System.currentTimeMillis()
            }
            expiryMs = newExpiry
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(K_EXPIRY, newExpiry)
                .putLong(K_OFFSET, offset)
                .putString(K_SIG, sign(newExpiry, offset, deviceId(app)))
                .apply()
            lastError = ""
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Line ka format:  DEVICE_ID  2026-10-10 18:00  NAAM
     * Time India (IST) ka hai. Time ki jagah NEVER likho to kabhi band nahi hoga.
     * Sirf date likho (2026-10-10) to us din raat 23:59 tak chalega.
     */
    private fun findExpiry(body: String, myId: String): Long {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            if (!parts[0].equals(myId, ignoreCase = true)) continue
            if (parts.size >= 2 &&
                (parts[1].equals("NEVER", ignoreCase = true) || parts[1].equals("LIFETIME", ignoreCase = true))
            ) return Long.MAX_VALUE
            val text = when {
                parts.size >= 3 -> parts[1] + " " + parts[2]
                parts.size == 2 -> parts[1] + " 23:59"
                else -> null
            } ?: continue
            val d = try { fmt.parse(text) } catch (e: Exception) { null }
            if (d != null) return d.time
        }
        return 0L
    }
}
