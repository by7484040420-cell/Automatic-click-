package com.bipin.clicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update + Remote Control + Kill Switch + Broadcast system.
 *
 * Reads BOTH:
 * 1) Server app-control / heartbeat payload (`LicenseManager`)
 * 2) GitHub repo (`by7484040420-cell/bipin-licenses/main/update.txt`)
 *
 * Supported update.txt keys:
 *   #BIPIN-UPDATE
 *   VERSION_CODE=4
 *   VERSION_NAME=1.4
 *   MIN_VERSION=1
 *   URL=https://link-to-apk-or-release
 *   NOTES=Speed boost | Bug fix
 *   KILL_SWITCH=OFF          (ON/TRUE/1 = app band/lock ya crash)
 *   KILL_MODE=lock           (lock = Owner Block Screen, crash = turant band/exit)
 *   KILL_MESSAGE=App temporarily disabled by Owner.
 *   BROADCAST=Optional notice banner for riders
 */
object UpdateManager {

    private const val UPDATE_URL =
        "https://raw.githubusercontent.com/by7484040420-cell/bipin-licenses/main/update.txt"
    private const val PREFS = "update_prefs"
    private const val K_SNOOZE_CODE = "snooze_code"
    private const val K_SNOOZE_UNTIL = "snooze_until"
    private const val SNOOZE_MS = 6L * 60L * 60L * 1000L
    private const val MARKER = "#BIPIN-UPDATE"

    // ZAROORI update (MIN_VERSION) ka saved record
    private const val K_BLOCK_MIN = "block_min"
    private const val K_BLOCK_CODE = "block_code"
    private const val K_BLOCK_NAME = "block_name"
    private const val K_BLOCK_URL = "block_url"
    private const val K_BLOCK_NOTES = "block_notes"

    // Remote Kill Switch & Broadcast record
    private const val K_REMOTE_KILLED = "remote_killed"
    private const val K_KILL_MODE = "kill_mode"
    private const val K_KILL_MSG = "kill_msg"
    private const val K_BROADCAST = "broadcast_msg"

    class Info(
        val code: Int,
        val name: String,
        val minCode: Int,
        val url: String,
        val notes: List<String>,
        val killSwitch: Boolean = false,
        val killMode: String = "lock",
        val killMessage: String = "",
        val broadcastMessage: String = ""
    ) {
        fun isForced(cur: Int) = minCode > cur
        fun hasUpdatePayload() = code > 0 && url.startsWith("https://")
    }

    @Volatile private var checking = false
    @Volatile private var showing = false
    @Volatile private var blockLoaded = false
    @Volatile private var blockMin = 0
    @Volatile private var curCode = -1

    @Volatile private var controlLoaded = false
    @Volatile private var remoteKilledFlag = false
    @Volatile private var killModeVal = "lock"
    @Volatile private var killMsgVal = "App temporarily disabled by Owner."
    @Volatile private var broadcastMsgVal = ""

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

    private fun ensureControl(ctx: Context) {
        if (controlLoaded) return
        synchronized(this) {
            if (controlLoaded) return
            val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            remoteKilledFlag = p.getBoolean(K_REMOTE_KILLED, false)
            killModeVal = p.getString(K_KILL_MODE, "lock") ?: "lock"
            killMsgVal = p.getString(K_KILL_MSG, "App temporarily disabled by Owner.")
                ?: "App temporarily disabled by Owner."
            broadcastMsgVal = p.getString(K_BROADCAST, "") ?: ""
            controlLoaded = true
        }
    }

    /**
     * true = zaroori update aa chuka hai aur ye purana version hai.
     */
    fun isBlocked(ctx: Context): Boolean {
        ensureBlock(ctx)
        return blockMin > curCode
    }

    fun isRemoteKilled(ctx: Context): Boolean {
        ensureControl(ctx)
        return remoteKilledFlag
    }

    fun getKillMode(ctx: Context): String {
        ensureControl(ctx)
        return if (killModeVal.equals("crash", ignoreCase = true)) "crash" else "lock"
    }

    fun getKillMessage(ctx: Context): String {
        ensureControl(ctx)
        return killMsgVal.ifBlank { "App temporarily disabled by Owner." }
    }

    fun getBroadcastMessage(ctx: Context): String {
        ensureControl(ctx)
        return broadcastMsgVal
    }

    private fun saveRemoteControl(
        app: Context,
        killed: Boolean,
        killMode: String,
        killMsg: String,
        broadcast: String
    ) {
        val cleanMode = if (killMode.equals("crash", ignoreCase = true)) "crash" else "lock"
        val cleanMsg = killMsg.trim().ifBlank { "App temporarily disabled by Owner." }
        val cleanBroadcast = broadcast.trim()
        remoteKilledFlag = killed
        killModeVal = cleanMode
        killMsgVal = cleanMsg
        broadcastMsgVal = cleanBroadcast
        controlLoaded = true
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(K_REMOTE_KILLED, killed)
            .putString(K_KILL_MODE, cleanMode)
            .putString(K_KILL_MSG, cleanMsg)
            .putString(K_BROADCAST, cleanBroadcast)
            .apply()
    }

    fun applyServerKillAndBroadcast(
        ctx: Context,
        killed: Boolean,
        killMode: String,
        killMsg: String,
        broadcast: String
    ) {
        saveRemoteControl(ctx.applicationContext, killed, killMode, killMsg, broadcast)
    }

    /**
     * Called by LicenseManager whenever heartbeat returns full appControl + update payload.
     */
    fun applyServerControl(
        ctx: Context,
        killed: Boolean,
        killMode: String,
        killMsg: String,
        broadcast: String,
        updateCode: Int = 0,
        updateName: String = "",
        minCode: Int = 0,
        url: String = "",
        notes: List<String> = emptyList()
    ) {
        val app = ctx.applicationContext
        saveRemoteControl(app, killed, killMode, killMsg, broadcast)
        val cur = currentCode(app)
        if (updateCode > 0 && url.startsWith("https://")) {
            val i = Info(updateCode, updateName.ifBlank { updateCode.toString() }, minCode, url, notes)
            if (minCode > cur) {
                saveBlock(app, i)
            } else {
                clearBlock(app)
            }
        } else if (minCode <= cur) {
            clearBlock(app)
        }
    }

    /**
     * Stops watcher/bubble immediately when remote kill switch is ON.
     * In 'crash' mode, terminates the process immediately; in 'lock' mode, shows non-dismissible lock screen.
     */
    fun enforceKillSwitchIfNeeded(ctx: Context, activity: Activity? = null): Boolean {
        if (!isRemoteKilled(ctx)) return false
        val app = ctx.applicationContext
        AreaPrefs.setPaused(app, true)
        runCatching { app.stopService(Intent(app, FloatingBubbleService::class.java)) }
        runCatching { DebugOverlay.hide() }
        runCatching { SwipeIndicator.hide() }

        if (getKillMode(app) == "crash") {
            if (activity != null) {
                runCatching { activity.finishAffinity() }
            }
            Process.killProcess(Process.myPid())
            return true
        }

        if (activity !is UpdateActivity) {
            launchKillScreen(app)
            if (activity != null && !activity.isFinishing) {
                runCatching { activity.finish() }
            }
        }
        return true
    }

    fun launchKillScreen(ctx: Context) {
        val app = ctx.applicationContext
        val it = Intent(app, UpdateActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(UpdateActivity.EXTRA_IS_KILL, true)
            putExtra(UpdateActivity.EXTRA_FORCED, true)
            putExtra(UpdateActivity.EXTRA_KILL_MESSAGE, getKillMessage(app))
        }
        try {
            showing = true
            app.startActivity(it)
        } catch (_: Exception) {
            showing = false
        }
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
            putExtra(UpdateActivity.EXTRA_IS_KILL, false)
            putExtra(UpdateActivity.EXTRA_CODE, code)
            putExtra(UpdateActivity.EXTRA_NAME, name)
            putExtra(UpdateActivity.EXTRA_URL, url)
            putExtra(UpdateActivity.EXTRA_NOTES, ArrayList(notes))
            putExtra(UpdateActivity.EXTRA_FORCED, forced)
        }
        try { app.startActivity(it) } catch (e: Exception) { showing = false }
    }

    /** Internet ke bina bhi: pehle se mila zaroori update ya kill screen turant dikhao. */
    fun showBlockedNow(ctx: Context) {
        if (isRemoteKilled(ctx)) {
            enforceKillSwitchIfNeeded(ctx, ctx as? Activity)
            return
        }
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
     * Background me GitHub update.txt dekho (Kill Switch + Broadcast + Update).
     */
    fun checkAndPrompt(ctx: Context, fromService: Boolean = false) {
        val app = ctx.applicationContext
        if (checking) return
        checking = true
        Thread {
            var info: Info? = null
            try {
                info = fetch()
            } catch (_: Exception) {
                // internet/server dikkat: ignore
            } finally {
                checking = false
            }
            val i = info ?: return@Thread

            // Apply GitHub kill switch & broadcast only when GitHub explicitly turns kill ON,
            // or if update.txt carries explicit control keys. Note: if server already killed this
            // individual device, don't clear it just because GitHub global kill is OFF.
            ensureControl(app)
            val effectiveKill = i.killSwitch || (remoteKilledFlag && !i.killSwitch && i.killMessage.isBlank())
            if (i.killSwitch) {
                saveRemoteControl(app, true, i.killMode, i.killMessage, i.broadcastMessage)
                Handler(Looper.getMainLooper()).post {
                    enforceKillSwitchIfNeeded(app, ctx as? Activity)
                }
                return@Thread
            } else if (i.broadcastMessage.isNotBlank()) {
                saveRemoteControl(app, effectiveKill, killModeVal, killMsgVal, i.broadcastMessage)
            }

            if (i.hasUpdatePayload() && !showing) {
                val cur = currentCode(app)
                val newer = i.code > cur
                val forced = newer && i.isForced(cur)
                if (forced) saveBlock(app, i) else if (i.minCode <= cur) clearBlock(app)
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
        val code = m["VERSION_CODE"]?.toIntOrNull() ?: 0
        val rawUrl = m["URL"] ?: ""
        val safeUrl = if (rawUrl.startsWith("https://")) rawUrl else ""
        val killRaw = (m["KILL_SWITCH"] ?: "OFF").trim().uppercase()
        val killOn = killRaw == "ON" || killRaw == "TRUE" || killRaw == "1" || killRaw == "CRASH"
        val killModeRaw = (m["KILL_MODE"] ?: if (killRaw == "CRASH") "crash" else "lock").trim().lowercase()
        val killMode = if (killModeRaw == "crash") "crash" else "lock"
        val killMsg = m["KILL_MESSAGE"] ?: ""
        val broadcast = m["BROADCAST"] ?: ""

        if (code <= 0 && !killOn && broadcast.isBlank()) return null

        return Info(
            code = code,
            name = m["VERSION_NAME"] ?: if (code > 0) code.toString() else "",
            minCode = m["MIN_VERSION"]?.toIntOrNull() ?: 0,
            url = safeUrl,
            notes = (m["NOTES"] ?: "").split("|").map { it.trim() }.filter { it.isNotEmpty() },
            killSwitch = killOn,
            killMode = killMode,
            killMessage = killMsg,
            broadcastMessage = broadcast
        )
    }
}
