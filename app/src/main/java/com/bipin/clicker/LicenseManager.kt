// ===== NAYI FILE =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/LicenseManager.kt
package com.bipin.clicker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.OutputStreamWriter
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
 * 1) Instant Business UPI Server check (payment aate hi 1 second me approval milta hai)
 * 2) GitHub licenses.txt fallback (purane/manual approvals ke liye)
 * 3) Order uthane ke waqt koi internet nahi lagta: phone me signed saved time dekha jata hai (0ms).
 */
object LicenseManager {

    private const val DEFAULT_SERVER_URL = "https://ais-pre-6tn427fii6ngl2aha7z3rg-110169053172.asia-east1.run.app"

    // Aapka GitHub username: by7484040420-cell, repo: bipin-licenses, file: licenses.txt
    private const val API_URL =
        "https://api.github.com/repos/by7484040420-cell/bipin-licenses/contents/licenses.txt"
    const val LICENSE_URL =
        "https://raw.githubusercontent.com/by7484040420-cell/bipin-licenses/main/licenses.txt"

    private const val PREFS = "license_prefs"
    private const val K_EXPIRY = "expiry"
    private const val K_OFFSET = "offset"
    private const val K_SIG = "sig"
    private const val K_FALLBACK_ID = "fallback_id"
    private const val K_SERVER_URL = "payment_server_url"
    private const val MARKER = "#BIPIN-LICENSES"
    const val OWNER_WHATSAPP_NUMBER = "918383872065"
    const val OWNER_WHATSAPP_DISPLAY = "8383872065"

    @Volatile private var loaded = false
    @Volatile private var expiryMs = 0L
    @Volatile private var srvTime = 0L
    @Volatile private var srvElapsed = 0L
    @Volatile private var offset = 0L
    @Volatile private var refreshing = false
    @Volatile private var etag: String? = null

    @Volatile
    var lastError: String = ""
        private set

    data class UpiPlan(
        val id: String,
        val name: String,
        val durationHours: Int,
        val priceInr: Int,
        val badge: String
    )

    data class UpiPlansResult(
        val ok: Boolean,
        val businessName: String,
        val businessUpiVpa: String,
        val plans: List<UpiPlan>,
        val error: String = ""
    )

    data class UpiOrderResult(
        val ok: Boolean,
        val orderId: String = "",
        val planName: String = "",
        val amountInr: Double = 0.0,
        val upiUri: String = "",
        val qrImageUrl: String = "",
        val qrPngUrl: String = "",
        val error: String = ""
    )

    data class UpiPollResult(
        val ok: Boolean,
        val status: String = "PENDING",
        val licenseActive: Boolean = false,
        val error: String = ""
    )

    data class OwnerConnectResult(
        val ok: Boolean,
        val webhookSecret: String = "",
        val businessUpiVpa: String = "",
        val error: String = ""
    )

    fun getServerUrl(ctx: Context): String {
        val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_SERVER_URL, null)?.trim()?.trimEnd('/')
        return if (!raw.isNullOrEmpty() && (raw.startsWith("https://") || raw.startsWith("http://"))) {
            raw
        } else {
            DEFAULT_SERVER_URL
        }
    }

    fun setServerUrl(ctx: Context, url: String) {
        val clean = url.trim().trimEnd('/')
        if (clean.startsWith("https://") || clean.startsWith("http://")) {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(K_SERVER_URL, clean).apply()
        }
    }

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
        // Zaroori update aaya hai aur ye purana version hai -> band
        if (UpdateManager.isBlocked(ctx)) return false
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

    fun copyDeviceId(ctx: Context): String {
        val id = deviceId(ctx)
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("SwiftGate Device ID", id))
        } catch (_: Exception) {
        }
        return id
    }

    fun openWhatsAppSupport(ctx: Context, isHelp: Boolean = false, extraInfo: String = "") {
        val id = copyDeviceId(ctx)
        Toast.makeText(
            ctx,
            "Device ID ($id) copy ho gayi — WhatsApp khul raha hai",
            Toast.LENGTH_SHORT
        ).show()

        val msg = buildString {
            append(
                if (isHelp) "Namaste Sir, mujhe SwiftGate payment/app help chahiye."
                else "Namaste Sir, SwiftGate approval chahiye."
            )
            append("\nDevice ID: ").append(id)
            if (extraInfo.isNotBlank()) {
                append("\n").append(extraInfo)
            }
        }
        val waUrl = "https://wa.me/$OWNER_WHATSAPP_NUMBER?text=${Uri.encode(msg)}"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(waUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(
                ctx,
                "WhatsApp par $OWNER_WHATSAPP_DISPLAY ko message karein (ID copy ho gayi hai)",
                Toast.LENGTH_LONG
            ).show()
        }
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

    /** Background me UPI server + GitHub file check karta hai. onDone main thread par chalta hai. */
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

    // ---------- Instant Business UPI Payment APIs ----------

    fun fetchPlansAsync(ctx: Context, onDone: (UpiPlansResult) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val res = httpGet("${getServerUrl(app)}/api/public/plans?t=${System.currentTimeMillis()}", false, null)
            val result = if (res.code == 200 && !res.body.isNullOrBlank()) {
                try {
                    val obj = JSONObject(res.body)
                    val arr = obj.optJSONArray("plans")
                    val list = mutableListOf<UpiPlan>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val p = arr.getJSONObject(i)
                            list.add(
                                UpiPlan(
                                    id = p.getString("id"),
                                    name = p.getString("name"),
                                    durationHours = p.optInt("durationHours", 24),
                                    priceInr = p.optInt("priceInr", 0),
                                    badge = p.optString("badge", "")
                                )
                            )
                        }
                    }
                    UpiPlansResult(
                        ok = true,
                        businessName = obj.optString("businessName", "SwiftGate"),
                        businessUpiVpa = obj.optString("businessUpiVpa", ""),
                        plans = list
                    )
                } catch (e: Exception) {
                    UpiPlansResult(false, "", "", emptyList(), "Plans parse error: ${e.message}")
                }
            } else {
                UpiPlansResult(false, "", "", emptyList(), res.err ?: "Server status ${res.code}")
            }
            Handler(Looper.getMainLooper()).post { onDone(result) }
        }.start()
    }

    fun createUpiOrderAsync(ctx: Context, planId: String, onDone: (UpiOrderResult) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val url = "${getServerUrl(app)}/api/payments/orders"
            val payload = JSONObject().apply {
                put("deviceId", deviceId(app))
                put("planId", planId)
            }
            val res = httpPostJson(url, payload.toString())
            val out = if (res.code == 200 && !res.body.isNullOrBlank()) {
                try {
                    val obj = JSONObject(res.body)
                    val pObj = obj.optJSONObject("plan")
                    val relPng = obj.optString("qrPngUrl", "")
                    UpiOrderResult(
                        ok = true,
                        orderId = obj.getString("orderId"),
                        planName = pObj?.optString("name") ?: "",
                        amountInr = obj.optDouble("amountInr", 0.0),
                        upiUri = obj.optString("upiUri", ""),
                        qrImageUrl = obj.optString("razorpayQrImageUrl", ""),
                        qrPngUrl = if (relPng.isNotBlank()) getServerUrl(app) + relPng else ""
                    )
                } catch (e: Exception) {
                    UpiOrderResult(false, error = "Order response error: ${e.message}")
                }
            } else {
                val msg = try {
                    JSONObject(res.body ?: "{}").optString("error")
                } catch (_: Exception) { "" }
                UpiOrderResult(false, error = msg.ifBlank { res.err ?: "QR nahi bana (${res.code})" })
            }
            Handler(Looper.getMainLooper()).post { onDone(out) }
        }.start()
    }

    fun pollUpiOrderAsync(ctx: Context, orderId: String, onDone: (UpiPollResult) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val url = "${getServerUrl(app)}/api/payments/orders/$orderId?t=${System.currentTimeMillis()}"
            val res = httpGet(url, false, null)
            val out = if (res.code == 200 && !res.body.isNullOrBlank()) {
                try {
                    val obj = JSONObject(res.body)
                    val status = obj.optString("status", "PENDING")
                    val active = obj.optBoolean("licenseActive", false)
                    val isUnlim = obj.optBoolean("isUnlimited", false)
                    val exp = if (isUnlim) Long.MAX_VALUE else obj.optLong("licenseExpiryMs", 0L)
                    val srvT = obj.optLong("serverTimeMs", System.currentTimeMillis())
                    if ((status == "PAID" || active) && exp > 0L) {
                        applyServerLicense(app, exp, srvT)
                    }
                    UpiPollResult(ok = true, status = status, licenseActive = active || status == "PAID")
                } catch (e: Exception) {
                    UpiPollResult(false, error = e.message ?: "Parse error")
                }
            } else {
                UpiPollResult(false, error = res.err ?: "HTTP ${res.code}")
            }
            Handler(Looper.getMainLooper()).post { onDone(out) }
        }.start()
    }

    fun loadQrBitmapAsync(url: String, onDone: (Bitmap?) -> Unit) {
        Thread {
            var bmp: Bitmap? = null
            if (url.startsWith("https://") || url.startsWith("http://")) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 7000
                    conn.readTimeout = 7000
                    if (conn.responseCode == 200) {
                        conn.inputStream.use { bmp = BitmapFactory.decodeStream(it) }
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                }
            }
            Handler(Looper.getMainLooper()).post { onDone(bmp) }
        }.start()
    }

    fun connectOwnerAutoApproveAsync(ctx: Context, passcode: String, onDone: (OwnerConnectResult) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val url = "${getServerUrl(app)}/api/admin/owner-device/connect"
            val payload = JSONObject().put("passcode", passcode.trim())
            val res = httpPostJson(url, payload.toString())
            val out = if (res.code == 200 && !res.body.isNullOrBlank()) {
                try {
                    val obj = JSONObject(res.body)
                    OwnerConnectResult(
                        ok = obj.optBoolean("ok", false),
                        webhookSecret = obj.optString("webhookSecret", ""),
                        businessUpiVpa = obj.optString("businessUpiVpa", "")
                    )
                } catch (e: Exception) {
                    OwnerConnectResult(false, error = "Parse error: ${e.message}")
                }
            } else {
                val msg = try {
                    JSONObject(res.body ?: "{}").optString("error")
                } catch (_: Exception) { "" }
                OwnerConnectResult(false, error = msg.ifBlank { res.err ?: "Connect fail (${res.code})" })
            }
            Handler(Looper.getMainLooper()).post { onDone(out) }
        }.start()
    }

    private fun applyServerLicense(app: Context, newExpiry: Long, srvMs: Long) {
        val id = deviceId(app)
        syncTime(srvMs)
        expiryMs = newExpiry
        loaded = true
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(K_EXPIRY, newExpiry)
            .putLong(K_OFFSET, offset)
            .putString(K_SIG, sign(newExpiry, offset, id))
            .apply()
    }

    /** Approval screen par dikhne wali jaankari (kya check hua, kya mila). */
    @Volatile
    var debugInfo: String = ""
        private set

    private class Res(val code: Int, val body: String?, val date: Long, val etag: String?, val err: String?)

    private fun httpGet(url: String, api: Boolean, tag: String?): Res {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.setRequestProperty("User-Agent", "BipinClicker")
            if (api) {
                conn.setRequestProperty("Accept", "application/vnd.github.raw")
                if (tag != null) conn.setRequestProperty("If-None-Match", tag)
            }
            val code = conn.responseCode
            if (code == 304) return Res(304, null, conn.date, null, null)
            if (code != 200) return Res(code, null, conn.date, null, null)
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return Res(200, body, conn.date, conn.getHeaderField("ETag"), null)
        } catch (e: Exception) {
            return Res(-1, null, 0L, null, e.javaClass.simpleName + ": " + (e.message ?: ""))
        } finally {
            conn.disconnect()
        }
    }

    private fun httpPostJson(url: String, jsonBody: String): Res {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "BipinClicker")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(jsonBody) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            return Res(code, body, conn.date, null, null)
        } catch (e: Exception) {
            return Res(-1, null, 0L, null, e.javaClass.simpleName + ": " + (e.message ?: ""))
        } finally {
            conn.disconnect()
        }
    }

    private fun fmtIst(ms: Long): String {
        if (ms <= 0L) return "-"
        val f = SimpleDateFormat("dd MMM HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getTimeZone("Asia/Kolkata")
        return f.format(java.util.Date(ms))
    }

    private fun countEntries(body: String): Int =
        body.lines().count { val t = it.trim(); t.isNotEmpty() && !t.startsWith("#") }

    private fun timeInfo(): String =
        " | server " + fmtIst(srvTime) + " | phone " + fmtIst(System.currentTimeMillis())

    private fun syncTime(srv: Long) {
        if (srv > 0L) {
            srvTime = srv
            srvElapsed = SystemClock.elapsedRealtime()
            offset = srv - System.currentTimeMillis()
        }
    }

    private fun applyBody(app: Context, body: String, srv: Long) {
        val id = deviceId(app)
        val newExpiry = findExpiry(body, id)
        syncTime(srv)
        expiryMs = newExpiry
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(K_EXPIRY, newExpiry)
            .putLong(K_OFFSET, offset)
            .putString(K_SIG, sign(newExpiry, offset, id))
            .apply()
    }

    private fun doRefresh(app: Context) {
        val id = deviceId(app)
        val info = StringBuilder()

        // 0) Instant UPI payment server check (1-second approval after payment)
        val upiSrv = httpGet("${getServerUrl(app)}/api/license/status?deviceId=$id&t=${System.currentTimeMillis()}", false, null)
        if (upiSrv.code == 200 && !upiSrv.body.isNullOrBlank()) {
            try {
                val obj = JSONObject(upiSrv.body)
                val active = obj.optBoolean("active", false)
                val isUnlim = obj.optBoolean("isUnlimited", false)
                val exp = if (isUnlim) Long.MAX_VALUE else obj.optLong("expiryMs", 0L)
                val srvMs = obj.optLong("serverTimeMs", upiSrv.date)
                if (active && exp > 0L) {
                    applyServerLicense(app, exp, srvMs)
                    lastError = ""
                    debugInfo = "UPI Server OK | ID $id active (${fmtIst(exp)})"
                    return
                }
                info.append("UPI OK(0) | ")
            } catch (_: Exception) {
            }
        }

        // 1) GitHub API (turant fresh). ETag se "kuch nahi badla" (304) free hota hai.
        val api = httpGet(API_URL, true, etag)
        info.append("API ").append(if (api.code == -1) "err" else api.code.toString())
        if (api.code == 304) {
            syncTime(api.date)
            lastError = ""
            debugInfo = info.append(" (same)").append(timeInfo()).toString()
            return
        }

        var body: String? = null
        var date = api.date
        var fromApi = false
        val apiBody = api.body
        if (api.code == 200 && apiBody != null && apiBody.contains(MARKER)) {
            body = apiBody
            fromApi = true
            info.append(" entries=").append(countEntries(apiBody))
        }
        var expiry = if (body != null) findExpiry(body, id) else 0L

        // 2) API ne approval nahi diya (ya chali hi nahi) -> raw link bhi dekho
        if (expiry <= 0L) {
            val raw = httpGet("$LICENSE_URL?t=${System.currentTimeMillis()}", false, null)
            info.append(" | RAW ").append(if (raw.code == -1) "err" else raw.code.toString())
            val rawBody = raw.body
            if (raw.code == 200 && rawBody != null && rawBody.contains(MARKER)) {
                info.append(" entries=").append(countEntries(rawBody))
                val e2 = findExpiry(rawBody, id)
                if (e2 > 0L) {
                    body = rawBody
                    expiry = e2
                    if (raw.date > 0L) date = raw.date
                    fromApi = false
                } else if (body == null) {
                    debugInfo = info.append(" | ID nahi mili (API chali nahi, purana approval waisa hi)").toString()
                    return
                }
            } else if (body == null) {
                lastError = "Internet/server dikkat (API ${api.code}, RAW ${raw.code})"
                if (api.err != null) lastError += " " + api.err
                if (raw.err != null) lastError += " " + raw.err
                debugInfo = info.toString()
                return
            }
        }

        applyBody(app, body ?: return, date)
        etag = if (fromApi) api.etag else null
        lastError = ""
        info.append(" | ID ").append(id).append(if (expiry > 0L) " mili, expiry " + fmtIst(expiry) else " NAHI mili")
        debugInfo = info.append(timeInfo()).toString()
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

