package com.bipin.clicker

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Naya update aane par ya Owner dwara App Kill / Lock kiye jaane par ye screen dikhti hai.
 * Download/install user khud karta hai ("Update Karein" dabake). Kuch bhi chup-chap install nahi hota.
 */
class UpdateActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_NAME = "name"
        const val EXTRA_URL = "url"
        const val EXTRA_NOTES = "notes"
        const val EXTRA_FORCED = "forced"
        const val EXTRA_IS_KILL = "is_kill"
        const val EXTRA_KILL_MESSAGE = "kill_message"
    }

    private var forced = false
    private var isKillScreen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        LicenseManager.markOwnUiOpening(2000L)
        super.onCreate(savedInstanceState)

        isKillScreen = intent.getBooleanExtra(EXTRA_IS_KILL, false) || UpdateManager.isRemoteKilled(this)
        if (isKillScreen) {
            if (UpdateManager.getKillMode(this) == "crash") {
                UpdateManager.enforceKillSwitchIfNeeded(this, this)
                return
            }
            renderKillScreen()
            return
        }

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val name = intent.getStringExtra(EXTRA_NAME) ?: ""
        val url = intent.getStringExtra(EXTRA_URL) ?: ""
        val notes = intent.getStringArrayListExtra(EXTRA_NOTES) ?: arrayListOf()
        forced = intent.getBooleanExtra(EXTRA_FORCED, false)

        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(pad, pad * 2, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "Naya Update Available"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Aapka version: ${UpdateManager.currentName(this@UpdateActivity)}\nNaya version: $name"
            textSize = 15f
            setTextColor(Color.parseColor("#CFD8DC"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, pad / 2)
        })

        if (forced) {
            root.addView(TextView(this).apply {
                text = "Ye update zaroori hai. Update karne ke baad hi app chalega."
                textSize = 14f
                setTextColor(Color.parseColor("#FFB74D"))
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, pad / 2)
            })
        }

        if (notes.isNotEmpty()) {
            val sv = ScrollView(this)
            sv.addView(TextView(this).apply {
                text = notes.joinToString("\n") { "• $it" }
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            })
            root.addView(sv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            root.addView(android.view.View(this), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        root.addView(Button(this).apply {
            text = "Update Karein"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2E7D32"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * d).toInt() }
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(this@UpdateActivity, "Link nahi khul paya", Toast.LENGTH_LONG).show()
                }
            }
        })

        if (!forced) {
            root.addView(Button(this).apply {
                text = "Baad Me"
                setOnClickListener {
                    UpdateManager.snooze(this@UpdateActivity, code)
                    finish()
                }
            })
        }

        setContentView(root)
    }

    private fun renderKillScreen() {
        forced = true
        val d = resources.displayMetrics.density
        val pad = (22 * d).toInt()
        val msg = intent.getStringExtra(EXTRA_KILL_MESSAGE)
            ?.takeIf { it.isNotBlank() }
            ?: UpdateManager.getKillMessage(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#180A0A"))
            setPadding(pad, pad * 2, pad, pad * 2)
        }

        root.addView(TextView(this).apply {
            text = "App Owner Dwara Band Kiya Gaya"
            textSize = 22f
            setTextColor(Color.parseColor("#FF8A80"))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (12 * d).toInt())
        })

        root.addView(TextView(this).apply {
            text = msg
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (18 * d).toInt())
        })

        root.addView(TextView(this).apply {
            text = "Device Code: ${LicenseManager.deviceId(this@UpdateActivity)}"
            textSize = 13f
            setTextColor(Color.parseColor("#B0BEC5"))
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (24 * d).toInt())
        })

        val statusLabel = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(Color.parseColor("#FFB74D"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (14 * d).toInt())
        }
        root.addView(statusLabel)

        root.addView(Button(this).apply {
            text = "Status Dobara Check Karein"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#C62828"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (10 * d).toInt() }
            setOnClickListener {
                statusLabel.text = "Server se status check ho raha hai..."
                val app = applicationContext
                LicenseManager.sendHeartbeatAsync(
                    ctx = app,
                    isRunning = false,
                    accessibilityOn = PermissionManager.isAccessibilityServiceEnabled(app),
                    ordersAccepted = AreaPrefs.getOrdersAcceptedCount(app)
                ) {
                    UpdateManager.checkAndPrompt(app)
                    if (UpdateManager.isRemoteKilled(app)) {
                        if (UpdateManager.getKillMode(app) == "crash") {
                            UpdateManager.enforceKillSwitchIfNeeded(app, this@UpdateActivity)
                        } else {
                            statusLabel.text = UpdateManager.getKillMessage(app)
                        }
                    } else {
                        val nextIntent = Intent(
                            this@UpdateActivity,
                            if (LicenseManager.isActive(app)) MainActivity::class.java else LicenseActivity::class.java
                        ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(nextIntent)
                        finish()
                    }
                }
            }
        })

        root.addView(Button(this).apply {
            text = "WhatsApp Support (${LicenseManager.OWNER_WHATSAPP_DISPLAY})"
            setTextColor(Color.parseColor("#81C784"))
            setBackgroundColor(Color.parseColor("#1D3324"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                LicenseManager.openWhatsAppSupport(this@UpdateActivity, isHelp = true)
            }
        })

        setContentView(root)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!forced && !isKillScreen) {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        UpdateManager.markClosed()
    }
}
