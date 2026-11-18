// ===== NAYI FILE =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/Integrity.kt
package com.bipin.clicker

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest

/**
 * Tamper (cracked/badla hua APK) pehchanne ka system.
 *
 * Koi app ko khol kar badal ke dobara banaye (repack) to uski signing key alag ho jati hai.
 * Hum apni asli key ka fingerprint yaha rakhte hain. Alag nikla to:
 *   - approval turant band
 *   - app kholte hi crash
 *   - service kuch second baad crash
 *
 * ZAROORI: ye fingerprint app/debug.keystore ka hai. Wo keystore kabhi mat badalna,
 * warna aapka apna asli app bhi crash hoga.
 */
object Integrity {

    private const val CERT_SHA256 = "1D05025100A3985AC82D16CD782EBF208661A794DA6E3D60DBC77A2FCB89A455"

    @Volatile private var state = 0 // 0 = abhi check nahi hua, 1 = sahi, 2 = tamper
    @Volatile private var punished = false

    /** Tez check (pehli baar hi asli jaanch hoti hai, baad me sirf ek variable padhta hai). */
    fun isTampered(ctx: Context): Boolean {
        if (state == 0) recheck(ctx)
        return state == 2
    }

    /** Dobara asli jaanch. Tamper mila to saza. Sahi hai to true. */
    fun recheck(ctx: Context): Boolean {
        if (state == 2) return false
        val ok = try {
            signatureMatches(ctx.applicationContext)
        } catch (e: Exception) {
            true // jaanch me dikkat aaye to apne asli user ko mat rokna
        }
        state = if (ok) 1 else 2
        if (!ok) punish(ctx)
        return ok
    }

    @Suppress("DEPRECATION")
    private fun signatureMatches(app: Context): Boolean {
        val pm = app.packageManager
        val certs: List<ByteArray> = if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val si = info.signingInfo ?: return true
            val arr = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
            arr.map { it.toByteArray() }
        } else {
            val info = pm.getPackageInfo(app.packageName, PackageManager.GET_SIGNATURES)
            val sigs = info.signatures ?: return true
            sigs.map { it.toByteArray() }
        }
        if (certs.isEmpty()) return true
        val md = MessageDigest.getInstance("SHA-256")
        return certs.all { hex(md.digest(it)) == CERT_SHA256 }
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }

    private fun punish(ctx: Context) {
        if (punished) return
        punished = true
        try { LicenseManager.revoke(ctx) } catch (_: Exception) {}
        // Turant nahi, thodi der baad crash: crack karne wale ko pata na chale kaunsi line se hua
        val delay = 4000L + (Math.random() * 20000L).toLong()
        Handler(Looper.getMainLooper()).postDelayed({
            throw IllegalStateException("Corrupted package")
        }, delay)
    }
}
