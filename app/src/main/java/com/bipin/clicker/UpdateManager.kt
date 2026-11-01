package com.bipin.clicker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update notice system (SILENT NAHI).
 *
 * GitHub repo (bipin-licenses) me update.txt rakho. App usse padhta hai aur agar naya version
 * hai to user ko screen par "Update Available" dikhata hai. Download/install user KHUD karta hai.
 *
 * update.txt ka format:
 *   #BIPIN-UPDATE
 *   VERSION_CODE=2
 *   VERSION_NAME=1.1
 *   MIN_VERSION=1
 *   URL=https://link-to-your-apk-or-download-page
 *   NOTES=Naya kya hai | Speed badhi | Bug fix
 *
 * MIN_VERSION: jo user ka versionCode isse chhota ho, use "zaroori update" dikhega (Baad me button nahi)
 * aur purana app KAAM NAHI KAREGA (order bhi nahi uthayega) jab tak wo naya APK install na kare.
 */
object UpdateManager {

    private const val UPDATE_URL =
        "https://raw.githubusercontent.com/by7484040420-cell/bipin-licenses/main/update.txt"
    private const val PREFS = "update_prefs"
    private const val K_SNOOZE_CODE = "snooze_code"
    private const val K_SNOOZE_UNTIL = "snooze_until"
    private const val SNOOZE_MS = 6L * 60L * 60L * 1000L // "Baad me" ke baad 6 ghante tak nahi dikhega
    private const val MARKER = "#BIPIN-UPDATE"

    // ZAROORI update (MIN_VERSION) ka saved record: update hone tak purana app band rahega
    private const val K_BLOCK_MIN = "block_min"
    private const val K_BLOCK_CODE = "block_code"
    private const val K_BLOCK_NAME = "block_name"
    private const val K_BLOCK_URL = "block_url"
    private const val K_BLOCK_NOTES = "block_notes"

    class Info(
        val code: Int,
        val name: String,
        val minCode: Int,
        val url: String,
        val notes: List<String>
    ) {
        fun isForced(cur: Int) = minCode > cur
    }

    @Volatile private var checking = false
    @Volatile private var showing = false
    @Volatile private var blockLoaded = false
    @Volatile private var blockMin = 0
    @Volatile private var curCode = -1

    private fun ensureBlock(ctx: Context) {
        if (blockLoaded) return
        synchronized(this) {
            if (blockLoaded) return
            val app = ctx.applicationContext
            blockMin = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(K_BLOCK_MIN, 0)
            curCode = currentCode(app)
            blockLoaded = true
        }
    }

    /**
     * true = zaroori update aa chuka hai aur ye purana version hai. Tab app/service kaam nahi karte.
     * Naya APK install hote hi (versionCode bada) apne aap false ho jata hai.
     * Sirf memory padhta hai, tez hai.
     */
    fun isBlocked(ctx: Context): Boolean {
        ensureBlock(ctx)
        return blockMin > curCode
    }

    private fun saveBlock(app: Context, i: Info) {
        blockMin = i.minCode
        blockLoaded = true
        if (curCode < 0) curCode = currentCode(app)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(K_BLOCK_MIN, i.minCode)
            .putInt(K_BLOCK_CODE, i.code)
            .putString(K_BLOCK_NAME, i.name)
            .putString(K_BLOCK_URL, i.url)
            .putString(K_BLOCK_NOTES, i.notes.joinToString("|"))
            .apply()
    }

    private fun clearBlock(app: Context) {
        blockMin = 0
        blockLoaded = true
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(K_BLOCK_MIN).apply()
    }

    private fun launch(app: Context, code: Int, name: String, url: String, notes: List<String>, forced: Boolean) {
        if (showing) return
        showing = true
        val it = Intent(app, UpdateActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(UpdateActivity.EXTRA_CODE, code)
            putExtra(UpdateActivity.EXTRA_NAME, name)
            putExtra(UpdateActivity.EXTRA_URL, url)
            putExtra(UpdateActivity.EXTRA_NOTES, ArrayList(notes))
            putExtra(UpdateActivity.EXTRA_FORCED, forced)
        }
        try { app.startActivity(it) } catch (e: Exception) { showing = false }
    }

    /** Internet ke bina bhi: pehle se mila zaroori update ki screen turant dikhao. */
    fun showBlockedNow(ctx: Context) {
        if (!isBlocked(ctx)) return
        val app = ctx.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = p.getString(K_BLOCK_URL, "") ?: ""
        if (url.isBlank()) return
        launch(
            app,
            p.getInt(K_BLOCK_CODE, 0),
            p.getString(K_BLOCK_NAME, "") ?: "",
            url,
            (p.getString(K_BLOCK_NOTES, "") ?: "").split("|").filter { it.isNotBlank() },
            true
        )
    }

    fun currentCode(ctx: Context): Int = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Exception) {
        0
    }

    fun currentName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /** UpdateActivity band hone par flag hatao. */
    fun markClosed() { showing = false }

    /** "Baad me" dabaya: ye version 6 ghante tak na dikhao. */
    fun snooze(ctx: Context, code: Int) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(K_SNOOZE_CODE, code)
            .putLong(K_SNOOZE_UNTIL, System.currentTimeMillis() + SNOOZE_MS)
            .apply()
    }

    private fun isSnoozed(ctx: Context, code: Int): Boolean {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return p.getInt(K_SNOOZE_CODE, -1) == code && System.currentTimeMillis() < p.getLong(K_SNOOZE_UNTIL, 0L)
    }

    /**
     * Background me update.txt dekho. Naya version mila (aur snooze nahi) to UpdateActivity
     * khol deta hai. Internet na ho to chup-chap kuch nahi karta (app normal chalta rahe).
     */
    fun checkAndPrompt(ctx: Context, fromService: Boolean = false) {
        val app = ctx.applicationContext
        if (checking || showing) return
        checking = true
        Thread {
            var info: Info? = null
            try {
                info = fetch()
            } catch (e: Exception) {
                // internet/server dikkat: ignore
            } finally {
                checking = false
            }
            val i = info
            if (i != null) {
                val cur = currentCode(app)
                val newer = i.code > cur && i.url.isNotBlank()
                val forced = newer && i.isForced(cur)
                if (forced) saveBlock(app, i) else if (i.minCode <= cur) clearBlock(app)
                // Service se sirf ZAROORI update dikhao (normal update wali screen driver ko beech me na roke)
                if (newer && (forced || (!fromService && !isSnoozed(app, i.code)))) {
                    Handler(Looper.getMainLooper()).post {
                        launch(app, i.code, i.name, i.url, i.notes, forced)
                    }
                }
            }
        }.start()
    }

    private fun fetch(): Info? {
        val conn = URL("$UPDATE_URL?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.useCaches = false
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.setRequestProperty("User-Agent", "BipinClicker")
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (!body.contains(MARKER)) return null
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String): Info? {
        val m = HashMap<String, String>()
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            m[line.substring(0, idx).trim().uppercase()] = line.substring(idx + 1).trim()
        }
        val code = m["VERSION_CODE"]?.toIntOrNull() ?: return null
        val url = m["URL"] ?: return null
        // Sirf https link kholo (koi gadbad link na chale)
        if (!url.startsWith("https://")) return null
        return Info(
            code = code,
            name = m["VERSION_NAME"] ?: code.toString(),
            minCode = m["MIN_VERSION"]?.toIntOrNull() ?: 0,
            url = url,
            notes = (m["NOTES"] ?: "").split("|").map { it.trim() }.filter { it.isNotEmpty() }
        )
    }
}
