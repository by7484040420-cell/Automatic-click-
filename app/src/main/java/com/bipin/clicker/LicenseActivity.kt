// ===== NAYI FILE =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/LicenseActivity.kt
package com.bipin.clicker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

/**
 * Approval & Instant Business UPI Payment screen:
 * - User apna plan chune -> Business UPI QR Scanner dikhega + PhonePe/GPay/Paytm direct button
 * - Payment aate hi har 1 second poll se turant auto-approval mil jata hai!
 */
class LicenseActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView
    private lateinit var plansContainer: LinearLayout
    private lateinit var qrCard: LinearLayout
    private lateinit var qrTitleView: TextView
    private lateinit var qrImageView: ImageView
    private lateinit var qrStatusView: TextView
    private lateinit var btnOpenUpiApp: Button

    private var activeOrderId: String? = null
    private var activeUpiUri: String = ""

    private val poll = object : Runnable {
        override fun run() {
            val oid = activeOrderId
            if (!oid.isNullOrEmpty()) {
                LicenseManager.pollUpiOrderAsync(this@LicenseActivity, oid) { res ->
                    if (isFinishing || isDestroyed) return@pollUpiOrderAsync
                    if (res.licenseActive || res.status == "PAID" || LicenseManager.isActive(this@LicenseActivity)) {
                        SoundPlayer.playOrderSound(this@LicenseActivity)
                        Toast.makeText(
                            this@LicenseActivity,
                            "Payment Verified! Turant approval mil gaya",
                            Toast.LENGTH_LONG
                        ).show()
                        goMain()
                    } else if (res.status == "EXPIRED") {
                        qrStatusView.text = "QR expire ho gaya — naya QR banayein"
                        activeOrderId = null
                    }
                }
                handler.postDelayed(this, 1000L)
            } else {
                doCheck()
                handler.postDelayed(this, 5000L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Integrity.isTampered(this)) throw IllegalStateException("Corrupted package")
        val d = resources.displayMetrics.density
        val pad = (18 * d).toInt()

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(pad, pad, pad, pad * 2)
        }

        root.addView(TextView(this).apply {
            text = "Instant UPI Approval"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "Plan select karke Business UPI QR scan karein. Payment aate hi 1 second me app khud chalu ho jayega."
            textSize = 13f
            setTextColor(Color.parseColor("#B0BEC5"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 3, 0, pad / 2)
        })

        // Instant UPI Plans Box
        plansContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad / 2, pad / 2, pad / 2, pad / 2)
            setBackgroundColor(Color.parseColor("#1E1E1E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        plansContainer.addView(TextView(this).apply {
            text = "Plans load ho rahe hain..."
            setTextColor(Color.parseColor("#81C784"))
            textSize = 13f
            gravity = Gravity.CENTER
        })
        root.addView(plansContainer)

        // Live QR Scanner Display Box (visible when a plan is tapped)
        qrCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#1B2A1E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 }
        }

        qrTitleView = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        qrCard.addView(qrTitleView)

        val qrSize = (220 * d).toInt()
        qrImageView = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize).apply {
                topMargin = pad / 2
                bottomMargin = pad / 2
            }
        }
        qrCard.addView(qrImageView)

        btnOpenUpiApp = Button(this).apply {
            text = "PhonePe / GPay / Paytm Se Pay Karein"
            setOnClickListener { openUpiIntent() }
        }
        qrCard.addView(btnOpenUpiApp)

        qrStatusView = TextView(this).apply {
            text = "Har 1 second me payment check ho raha hai..."
            textSize = 13f
            setTextColor(Color.parseColor("#81C784"))
            gravity = Gravity.CENTER
            setPadding(0, pad / 2, 0, 0)
        }
        qrCard.addView(qrStatusView)
        root.addView(qrCard)

        // Device ID & Backup Manual/GitHub Buttons
        root.addView(TextView(this).apply {
            text = "DEVICE ID"
            textSize = 11f
            setTextColor(Color.parseColor("#9E9E9E"))
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        })

        root.addView(TextView(this).apply {
            text = LicenseManager.deviceId(this@LicenseActivity)
            textSize = 24f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
            setPadding(0, 4, 0, pad / 2)
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
            setOnClickListener { shareId(false) }
        })

        root.addView(Button(this).apply {
            text = "Help / Payment Dikat? WhatsApp (8383872065)"
            setTextColor(Color.parseColor("#81C784"))
            setBackgroundColor(Color.parseColor("#1D3324"))
            setOnClickListener { shareId(true) }
        })

        root.addView(Button(this).apply {
            text = "Approval Check Karein"
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

        root.addView(Button(this).apply {
            text = "Owner Paytm Auto-Approve Setup (Sirf Merchant Phone)"
            textSize = 11f
            setTextColor(Color.parseColor("#B0BEC5"))
            setBackgroundColor(Color.parseColor("#1B1B1B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad }
            setOnClickListener { showOwnerPaytmSetupDialog() }
        })

        scroll.addView(root)
        setContentView(scroll)

        loadPaymentPlans()
    }

    private fun loadPaymentPlans() {
        LicenseManager.fetchPlansAsync(this) { res ->
            if (isFinishing || isDestroyed) return@fetchPlansAsync
            plansContainer.removeAllViews()
            if (!res.ok || res.plans.isEmpty()) {
                plansContainer.addView(TextView(this).apply {
                    text = "UPI plans abhi load nahi hue (${res.error}). Neeche Approval Check try karein."
                    setTextColor(Color.parseColor("#FFB74D"))
                    textSize = 12f
                    gravity = Gravity.CENTER
                })
                return@fetchPlansAsync
            }

            plansContainer.addView(TextView(this).apply {
                text = "${res.businessName} — Plan Chunein:"
                setTextColor(Color.parseColor("#81C784"))
                setTypeface(typeface, Typeface.BOLD)
                textSize = 14f
                setPadding(0, 0, 0, 12)
            })

            for (plan in res.plans) {
                val badge = if (plan.badge.isNotEmpty()) " (${plan.badge})" else ""
                plansContainer.addView(Button(this).apply {
                    text = "${plan.name} — ₹${plan.priceInr}$badge"
                    setOnClickListener { startUpiPayment(plan) }
                })
            }
        }
    }

    private fun startUpiPayment(plan: LicenseManager.UpiPlan) {
        qrCard.visibility = View.VISIBLE
        qrTitleView.text = "QR ban raha hai (${plan.name} - ₹${plan.priceInr})..."
        qrImageView.setImageBitmap(null)
        qrStatusView.text = "Please wait..."
        btnOpenUpiApp.isEnabled = false

        LicenseManager.createUpiOrderAsync(this, plan.id) { order ->
            if (isFinishing || isDestroyed) return@createUpiOrderAsync
            if (!order.ok) {
                qrTitleView.text = "QR nahi bana"
                qrStatusView.text = order.error
                return@createUpiOrderAsync
            }
            activeOrderId = order.orderId
            activeUpiUri = order.upiUri
            val amtText = formatInr(order.amountInr)
            qrTitleView.text = "Scan Karein: ₹$amtText (${order.planName})\nRef: ${order.orderId}"
            qrStatusView.text = "Har 1 second me payment check ho raha hai — payment hote hi chalu!"
            btnOpenUpiApp.isEnabled = activeUpiUri.startsWith("upi://pay?")

            val imgUrl = order.qrPngUrl.ifBlank { order.qrImageUrl }
            if (imgUrl.isNotBlank()) {
                LicenseManager.loadQrBitmapAsync(imgUrl) { bmp ->
                    if (!isFinishing && !isDestroyed && bmp != null) {
                        qrImageView.setImageBitmap(bmp)
                    }
                }
            }

            handler.removeCallbacks(poll)
            handler.postDelayed(poll, 1000L)
        }
    }

    private fun openUpiIntent() {
        val uriStr = activeUpiUri
        if (!uriStr.startsWith("upi://pay?")) {
            Toast.makeText(this, "UPI app link uplabdh nahi — upar QR scan karein", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)))
        } catch (_: Exception) {
            Toast.makeText(this, "Phone par koi UPI app nahi mila — QR scan karein", Toast.LENGTH_LONG).show()
        }
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

    private fun shareId(isHelp: Boolean = false) {
        val extra = activeOrderId?.let { "Order Ref: $it" }.orEmpty()
        LicenseManager.openWhatsAppSupport(this, isHelp = isHelp, extraInfo = extra)
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

    private fun formatInr(amount: Double): String {
        return if (amount % 1.0 == 0.0) {
            amount.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", amount)
        }
    }

    private fun showOwnerPaytmSetupDialog() {
        OwnerPaytmConfig.showSetupDialog(this)
    }

    private fun goMain() {
        if (isFinishing) return
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

