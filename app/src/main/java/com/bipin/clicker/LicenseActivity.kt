package com.bipin.clicker

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * WhatsApp Code Approval Screen:
 * - Rider apna Device Code copy karke WhatsApp par (8383872065) bhejta hai
 * - Owner approval dene ke baad "Approval Check Karein" ya har 5 sec me auto-open
 */
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

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(pad, pad, pad, pad * 2)
        }

        root.addView(TextView(this).apply {
            text = "Approval Zaroori Hai"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Neeche diya gaya apna code WhatsApp par bhejein (${LicenseManager.OWNER_WHATSAPP_DISPLAY}). Approval milne par 'Approval Check Karein' dabayein."
            textSize = 13f
            setTextColor(Color.parseColor("#B0BEC5"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 3, 0, pad)
        })

        root.addView(TextView(this).apply {
            text = "AAPKA DEVICE CODE"
            textSize = 11f
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = LicenseManager.deviceId(this@LicenseActivity)
            textSize = 28f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
            setPadding(0, 6, 0, pad)
        })

        root.addView(Button(this).apply {
            text = "Code Copy Karein"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * d).toInt() }
            setOnClickListener {
                LicenseManager.copyDeviceId(this@LicenseActivity)
                Toast.makeText(this@LicenseActivity, "Code copy ho gaya", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(Button(this).apply {
            text = "WhatsApp par Code Bhejein (${LicenseManager.OWNER_WHATSAPP_DISPLAY})"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2E7D32"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * d).toInt() }
            setOnClickListener { LicenseManager.openWhatsAppSupport(this@LicenseActivity, isHelp = false) }
        })

        root.addView(Button(this).apply {
            text = "Help / Support — WhatsApp (${LicenseManager.OWNER_WHATSAPP_DISPLAY})"
            setTextColor(Color.parseColor("#81C784"))
            setBackgroundColor(Color.parseColor("#1D3324"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * d).toInt() }
            setOnClickListener { LicenseManager.openWhatsAppSupport(this@LicenseActivity, isHelp = true) }
        })

        root.addView(Button(this).apply {
            text = "Approval Check Karein"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                statusView.text = "Check ho raha hai..."
                doCheck()
            }
        })

        statusView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#FFB74D"))
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        }
        root.addView(statusView)

        scroll.addView(root)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.showBlockedNow(this)
        UpdateManager.checkAndPrompt(this)
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
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
