// ===== NEW ADD (nayi file, pehle se nahi hai) =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/RootShell.kt
package com.bipin.clicker

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * ROOT support (Magisk/KernelSU). Ek hi persistent `su` shell khuli rakhta hai.
 * - swipe(): asli touch events (input touchscreen swipe) - Accessibility gesture se alag raasta.
 * - grantAll(): jo permissions app khud nahi le sakti wo root se de deta hai.
 * Root na ho / deny ho to ready=false rehta hai aur app sirf Accessibility se chalti hai.
 */
object RootShell {
    private val lock = Any()
    private var out: OutputStream? = null
    private var reader: BufferedReader? = null
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile var ready = false
    @Volatile var state = "root try nahi hua"
    @Volatile private var connecting = false

    /** Background thread par su maangta hai. Magisk popup aaye to ALLOW karo. */
    fun connect(onDone: ((Boolean) -> Unit)? = null) {
        if (ready) { onDone?.invoke(true); return }
        if (connecting) return
        connecting = true
        Thread {
            var ok = false
            try {
                val p = Runtime.getRuntime().exec("su")
                val o = p.outputStream
                val r = BufferedReader(InputStreamReader(p.inputStream))
                o.write("id\n".toByteArray()); o.flush()
                val line = r.readLine() ?: ""
                if (line.contains("uid=0")) {
                    synchronized(lock) { out = o; reader = r }
                    state = "ROOT OK"
                    ok = true
                } else {
                    state = "su mila par root nahi mila"
                    try { p.destroy() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                state = "root nahi hai (${e.message})"
            }
            ready = ok
            connecting = false
            onDone?.invoke(ok)
        }.start()
    }

    /** Bina output wali command, background me (shell block nahi hota). */
    private fun fire(cmd: String) {
        worker.execute {
            try {
                synchronized(lock) {
                    out?.write("$cmd >/dev/null 2>&1 &\n".toByteArray())
                    out?.flush()
                }
            } catch (e: Exception) {
                ready = false
                state = "root shell toot gaya"
            }
        }
    }

    fun swipe(x1: Float, y: Float, x2: Float, durMs: Long) {
        fire("input touchscreen swipe ${x1.toInt()} ${y.toInt()} ${x2.toInt()} ${y.toInt()} $durMs")
    }

    fun tap(x: Float, y: Float) {
        fire("input touchscreen tap ${x.toInt()} ${y.toInt()}")
    }

    /** Output ke saath command (BLOCKING - main thread par mat chalao). */
    fun run(cmd: String): String {
        synchronized(lock) {
            val o = out ?: return ""
            val r = reader ?: return ""
            return try {
                o.write("$cmd\necho __END__\n".toByteArray()); o.flush()
                val sb = StringBuilder()
                while (true) {
                    val l = r.readLine() ?: break
                    if (l == "__END__") break
                    sb.append(l).append('\n')
                }
                sb.toString().trim()
            } catch (e: Exception) {
                ready = false
                ""
            }
        }
    }

    /** Saari permissions root se do (BLOCKING - background thread par chalao). */
    fun grantAll(c: Context) {
        if (!ready) return
        val pkg = c.packageName
        val comp = "$pkg/${OrderWatcherService::class.java.name}"
        run("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        run("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        run("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        run("appops set $pkg ACCESS_RESTRICTED_SETTINGS allow")
        run("appops set $pkg PROJECT_MEDIA allow")
        run("appops set $pkg RUN_IN_BACKGROUND allow")
        run("appops set $pkg RUN_ANY_IN_BACKGROUND allow")
        run("dumpsys deviceidle whitelist +$pkg")
        if (!PermissionManager.isAccessibilityServiceEnabled(c)) {
            val cur = run("settings get secure enabled_accessibility_services")
            val v = if (cur.isBlank() || cur == "null") comp else if (cur.contains(comp)) cur else "$cur:$comp"
            run("settings put secure enabled_accessibility_services $v")
            run("settings put secure accessibility_enabled 1")
        }
    }
}
