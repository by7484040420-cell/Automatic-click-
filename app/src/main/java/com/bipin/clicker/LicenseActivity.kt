// ===== NAYI FILE =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/LicenseActivity.kt
package com.bipin.clicker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Approval se pehle ye screen dikhti hai: Device ID + bhejne/check karne ke button. */
class LicenseActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView

    private val poll = object : Runnable {
        override fun run() {
            doCheck()
            handler.postDelayed(this, 5000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Integrity.isTampered(this)) throw IllegalStateException("Corrupted package")
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Approval Zaroori Hai"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Neeche wali Device ID admin ko bhejo. Approve hote hi app khud chalu ho jayega."
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad)
        })

        root.addView(TextView(this).apply {
            text = LicenseManager.deviceId(this@LicenseActivity)
            textSize = 28f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
            setPadding(0, pad / 2, 0, pad)
        })

        root.addView(Button(this).apply {
            text = "ID Copy Karein"
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("device id", LicenseManager.deviceId(this@LicenseActivity)))
                Toast.makeText(this@LicenseActivity, "ID copy ho gayi", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(Button(this).apply {
            text = "ID WhatsApp par Bhejein"
            setOnClickListener { shareId() }
        })

        root.addView(Button(this).apply {
            text = "Approval Check Karein"
            setOnClickListener {
                statusView.text = "Check ho raha hai..."
                doCheck()
            }
        })

        statusView = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        }
        root.addView(statusView)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun shareId() {
        val text = "SwiftGate Device ID: ${LicenseManager.deviceId(this)}"
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(i, "ID bhejein"))
        } catch (e: Exception) {
            Toast.makeText(this, "Share nahi ho paya, ID copy karke bhejein", Toast.LENGTH_LONG).show()
        }
    }

    private fun doCheck() {
        if (LicenseManager.isActive(this)) {
            goMain()
            return
        }
        LicenseManager.refreshAsync(this) { updateStatus() }
        updateStatus()
    }

    private fun updateStatus() {
        if (isFinishing || isDestroyed) return
        if (LicenseManager.isActive(this)) {
            goMain()
            return
        }
        val err = LicenseManager.lastError
        val info = LicenseManager.debugInfo
        statusView.text = LicenseManager.remainingText(this) + "\nApprove hone ka intezaar..." +
            (if (err.isNotEmpty()) "\n($err)" else "") +
            (if (info.isNotEmpty()) "\n\n[$info]" else "")
    }

    private fun goMain() {
        if (isFinishing) return
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
