package com.bipin.clicker

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Owner ke Merchant Phone (jisme Paytm for Business chalta hai) ke liye config.
 * Jab tak owner khud Webhook Secret aur toggle ON nahi karte, listener kuch bhi send nahi karta.
 */
object OwnerPaytmConfig {
    private const val PREFS = "owner_paytm_auto_prefs"
    private const val K_SECRET = "webhook_secret"
    private const val K_ENABLED = "forwarder_enabled"
    private const val K_LAST_STATUS = "last_forward_status"

    fun getSecret(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_SECRET, "")?.trim() ?: ""

    fun isEnabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(K_ENABLED, false)

    fun saveConfig(ctx: Context, secret: String, enabled: Boolean) {
        val cleanSecret = secret.trim()
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_SECRET, cleanSecret)
            .putBoolean(K_ENABLED, enabled && cleanSecret.isNotEmpty())
            .apply()
    }

    fun getLastStatus(ctx: Context): String =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(K_LAST_STATUS, "") ?: ""

    fun setLastStatus(ctx: Context, status: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_LAST_STATUS, status.take(120))
            .apply()
    }

    fun isReady(ctx: Context): Boolean = isEnabled(ctx) && getSecret(ctx).isNotEmpty()

    fun isNotificationAccessGranted(ctx: Context): Boolean {
        return try {
            val flat = Settings.Secure.getString(
                ctx.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            flat.contains(ctx.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun showSetupDialog(activity: AppCompatActivity) {
        val d = activity.resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val notifGranted = isNotificationAccessGranted(activity)
        val alreadyReady = isReady(activity)
        val lastStatus = getLastStatus(activity)
        box.addView(TextView(activity).apply {
            text = "Sirf apne Paytm for Business phone par ON karein (Driver phone par nahi).\n" +
                "Auto-Approve Connect: ${if (alreadyReady) "CONNECTED" else "Not Connected"}\n" +
                "Notification Access: ${if (notifGranted) "ON" else "OFF (Neeche button se ON karein)"}" +
                if (lastStatus.isNotEmpty()) "\nLast: $lastStatus" else ""
            textSize = 12f
            setPadding(0, 0, 0, (10 * d).toInt())
        })

        val etPasscode = EditText(activity).apply {
            hint = "Apna Owner Passcode daalein (6+ chars)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(etPasscode)

        val btnAutoConnect = Button(activity).apply {
            text = "1-Tap Auto-Connect Paytm"
        }
        box.addView(btnAutoConnect)

        val cbEnabled = CheckBox(activity).apply {
            text = "Paytm Business Payment Auto-Approve ON rakhein"
            isChecked = isEnabled(activity)
        }
        box.addView(cbEnabled)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Owner Paytm Auto-Approve")
            .setView(box)
            .setNeutralButton("Notification Access") { _, _ ->
                try {
                    activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(activity, "Phone Settings -> Notification Access kholein", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Band Karein") { _, _ ->
                saveConfig(activity, "", false)
                Toast.makeText(activity, "Owner Auto-Approve off kar diya gaya", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Done") { _, _ ->
                val existing = getSecret(activity)
                if (existing.isNotEmpty()) {
                    saveConfig(activity, existing, cbEnabled.isChecked)
                }
            }
            .show()

        btnAutoConnect.setOnClickListener {
            val pass = etPasscode.text.toString().trim()
            if (pass.length < 6) {
                Toast.makeText(activity, "Kam se kam 6 characters ka Owner Passcode daalein", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnAutoConnect.isEnabled = false
            btnAutoConnect.text = "Connecting..."
            LicenseManager.connectOwnerAutoApproveAsync(activity, pass) { res ->
                if (activity.isFinishing || activity.isDestroyed) return@connectOwnerAutoApproveAsync
                btnAutoConnect.isEnabled = true
                btnAutoConnect.text = "1-Tap Auto-Connect Paytm"
                if (res.ok && res.webhookSecret.isNotEmpty()) {
                    saveConfig(activity, res.webhookSecret, true)
                    cbEnabled.isChecked = true
                    Toast.makeText(
                        activity,
                        "Paytm Auto-Approve connect ho gaya! Ab Notification Access ON karein.",
                        Toast.LENGTH_LONG
                    ).show()
                    if (!isNotificationAccessGranted(activity)) {
                        try {
                            activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        } catch (_: Exception) {
                        }
                    }
                    dialog.dismiss()
                } else {
                    Toast.makeText(activity, res.error.ifBlank { "Connect nahi ho paya" }, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/**
 * Sirf Owner ke merchant phone par Paytm for Business / UPI Merchant notifications sunta hai
 * aur turant `/api/payments/merchant-webhook` par bhejta hai taaki 1 second me approval mile.
 */
class PaytmBusinessListenerService : NotificationListenerService() {

    private val allowedMerchantPackages = setOf(
        "com.paytm.business",
        "net.one97.paytm",
        "com.phonepe.app.business",
        "com.bharatpe.merchant",
        "com.google.android.apps.nbu.paisa.merchant"
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val recentTexts = LinkedHashMap<String, Long>(32, 0.75f, true)

    private val debitOnlyRegex = Regex(
        "\\b(?:paid\\s+to|sent\\s+to|debited|failed|declined|requested)\\b",
        RegexOption.IGNORE_CASE
    )
    private val creditHintRegex = Regex(
        "\\b(?:received|credited|paid\\s+you|added\\s+to)\\b",
        RegexOption.IGNORE_CASE
    )
    private val currencyAmountRegex = Regex(
        "(?:rs\\.?|inr|₹)\\s*[0-9,]+(?:\\.[0-9]{1,2})?",
        RegexOption.IGNORE_CASE
    )

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!OwnerPaytmConfig.isReady(this)) return

        val pkg = sbn.packageName?.lowercase(Locale.ROOT) ?: return
        if (pkg !in allowedMerchantPackages) return

        val notif = sbn.notification ?: return
        if ((notif.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()

        val combined = listOf(title, bigText.ifEmpty { text })
            .filter { it.isNotEmpty() }
            .joinToString(" | ")
            .trim()
        if (combined.length < 6) return

        val hasCreditHint = creditHintRegex.containsMatchIn(combined)
        if (debitOnlyRegex.containsMatchIn(combined) && !hasCreditHint) return
        if (!hasCreditHint && !currencyAmountRegex.containsMatchIn(combined)) return

        val now = System.currentTimeMillis()
        synchronized(recentTexts) {
            val prev = recentTexts[combined]
            if (prev != null && now - prev < 15_000L) return
            recentTexts[combined] = now
            if (recentTexts.size > 32) {
                val eldest = recentTexts.entries.firstOrNull()?.key
                if (eldest != null) recentTexts.remove(eldest)
            }
        }

        val secret = OwnerPaytmConfig.getSecret(this)
        if (secret.isEmpty()) return
        val endpoint = "${LicenseManager.getServerUrl(this)}/api/payments/merchant-webhook"

        executor.execute {
            postPaymentNotification(endpoint, secret, combined)
        }
    }

    private fun postPaymentNotification(endpoint: String, secret: String, text: String) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 7000
                readTimeout = 7000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-webhook-secret", secret)
                setRequestProperty("User-Agent", "SwiftGate-OwnerForwarder")
            }
            val payload = JSONObject().put("text", text).toString()
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(payload) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respBody = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val summary = if (code == 200) {
                val obj = try { JSONObject(respBody) } catch (_: Exception) { null }
                val ord = obj?.optString("orderId").orEmpty()
                if (ord.isNotEmpty()) "Auto-Approved $ord" else "Ignored/Checked (200)"
            } else {
                "Server HTTP $code"
            }
            OwnerPaytmConfig.setLastStatus(this, summary)
        } catch (e: Exception) {
            OwnerPaytmConfig.setLastStatus(this, "Error: ${e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
