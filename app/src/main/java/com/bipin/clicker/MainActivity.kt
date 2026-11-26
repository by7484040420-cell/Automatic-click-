// ===== REPLACE (purani file hatao, ye daalo) =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/MainActivity.kt
package com.bipin.clicker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bipin.clicker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastResumeSyncAt = 0L
    private var lastBubbleStartAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        LicenseManager.markOwnUiOpening(2200L)
        super.onCreate(savedInstanceState)
        // APPROVAL: bina approval ke app ki asli screen nahi khulegi
        if (!LicenseManager.isActive(this)) {
            startActivity(Intent(this, LicenseActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Preload locations.json & regex caches in background taaki Area screen turant khule
        Thread {
            try {
                LocationRepository.loadVerifiedCities(applicationContext)
                LocationRepository.loadZonePins(applicationContext)
                AreaPrefs.prewarm(applicationContext)
            } catch (_: Exception) {
            }
        }.start()

        requestNotificationPermissionIfNeeded()

        binding.btnStartStop.setOnClickListener { toggleStartStop() }

        binding.btnPermissions.setOnClickListener {
            startActivity(Intent(this, PermissionSetupActivity::class.java))
        }

        binding.btnSetArea.setOnClickListener {
            if (!PermissionManager.isEverythingReady(this)) {
                val missing = PermissionManager.missingSettingsList(this).joinToString(", ")
                Toast.makeText(
                    this,
                    "Pehle sabhi settings ON karein ($missing), tabhi Location set hogi",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(this, PermissionSetupActivity::class.java))
                return@setOnClickListener
            }
            startActivity(Intent(this, AreaPreferenceActivity::class.java))
        }

        binding.btnFloatingBubble.setOnClickListener { toggleFloatingBubble() }

        binding.btnSwipeZone.setOnClickListener { autoFixSwipeZone() }
        binding.btnWhatsappHelp.setOnClickListener {
            LicenseManager.openWhatsAppSupport(this, isHelp = true)
        }

        // Har phone ki screen ke hisab se Swipe Zone khud-ba-khud set kar lo
        SwipeZone.ensureAutoCalibrated(this)

        // Purane Android (11 se neeche) par sirf OCR backup band hota hai; asli (tez,
        // screenshot-wala-nahi) tareeka har Android version par chalta hai.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            binding.tvWarn.visibility = View.VISIBLE
            binding.tvWarn.text = "Is phone me Android ${Build.VERSION.RELEASE} hai. " +
                "Asli (tez) order-detect tareeka yahan bhi chalega; sirf OCR wala backup " +
                "(Android 11+ ka) is phone par kaam nahi karega."
        }
    }

    override fun onResume() {
        LicenseManager.markOwnUiOpening(1800L)
        super.onResume()
        if (!::binding.isInitialized) return
        if (!LicenseManager.isActive(this)) {
            startActivity(Intent(this, LicenseActivity::class.java))
            finish()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastResumeSyncAt >= 300_000L) {
            lastResumeSyncAt = now
            LicenseManager.refreshAsync(this)
            UpdateManager.checkAndPrompt(this)
        }
        runCatching { SwipeZone.ensureAutoCalibrated(this) }
        ensureAutoFloatingBubble()
        runCatching { refreshStatus() }
    }

    private fun ensureAutoFloatingBubble() {
        if (!PermissionManager.isEverythingReady(this)) return
        if (AreaPrefs.isBubbleManuallyOff(this)) return
        if (PermissionManager.isFloatingBubbleRunning(this)) return
        val now = System.currentTimeMillis()
        if (now - lastBubbleStartAt < 2500L) return
        lastBubbleStartAt = now
        try {
            ContextCompat.startForegroundService(this, Intent(this, FloatingBubbleService::class.java))
            binding.root.postDelayed({
                if (!isFinishing && !isDestroyed) runCatching { refreshStatus() }
            }, 350L)
        } catch (_: Exception) {
        }
    }

    private fun toggleStartStop() {
        if (!PermissionManager.isEverythingReady(this)) {
            AreaPrefs.setPaused(this, true)
            val missing = PermissionManager.missingSettingsList(this).joinToString(", ")
            Toast.makeText(
                this,
                "Pehle sabhi settings ON karein ($missing), tabhi app ON hoga",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(this, PermissionSetupActivity::class.java))
            refreshStatus()
            return
        }
        val nowPaused = !AreaPrefs.isPaused(this)
        AreaPrefs.setPaused(this, nowPaused)
        if (nowPaused) {
            if (PermissionManager.isFloatingBubbleRunning(this)) {
                stopService(Intent(this, FloatingBubbleService::class.java))
            }
        } else {
            AreaPrefs.setBubbleManuallyOff(this, false)
            ensureAutoFloatingBubble()
        }
        refreshStatus()
        Toast.makeText(
            this,
            if (nowPaused) "Auto-Accept STOP kar diya" else "Auto-Accept START kar diya",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun autoFixSwipeZone() {
        if (!PermissionManager.isEverythingReady(this)) {
            Toast.makeText(
                this,
                "Pehle sabhi settings ON karein",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(this, PermissionSetupActivity::class.java))
            return
        }
        SwipeZone.autoCalibrateAndSave(this)
        val (w, h) = SwipeZone.getScreenDimensions(this)
        try {
            SwipeIndicator.animateSwipe(220L)
        } catch (_: Exception) {
        }
        refreshStatus()
        Toast.makeText(
            this,
            "Swipe Zone is phone (${w}x${h}) par khud fix ho gaya! Order aane par apne aap uthega.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun toggleFloatingBubble() {
        if (!PermissionManager.isEverythingReady(this)) {
            val missing = PermissionManager.missingSettingsList(this).joinToString(", ")
            Toast.makeText(
                this,
                "Pehle sabhi settings ON karein ($missing)",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(this, PermissionSetupActivity::class.java))
            return
        }
        try {
            if (PermissionManager.isFloatingBubbleRunning(this)) {
                AreaPrefs.setBubbleManuallyOff(this, true)
                stopService(Intent(this, FloatingBubbleService::class.java))
            } else {
                AreaPrefs.setBubbleManuallyOff(this, false)
                ContextCompat.startForegroundService(this, Intent(this, FloatingBubbleService::class.java))
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Bubble start nahi ho paya: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        binding.root.postDelayed({ refreshStatus() }, 300)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }
        }
    }

    private fun refreshStatus() {
        val accessibilityOn = PermissionManager.isAccessibilityServiceEnabled(this)
        val overlayOn = PermissionManager.isOverlayPermissionGranted(this)
        val notifOn = PermissionManager.isNotificationPermissionGranted(this)
        val batteryOn = PermissionManager.isBatteryOptimizationIgnored(this)
        val allReady = accessibilityOn && overlayOn && notifOn && batteryOn
        val bubbleOn = PermissionManager.isFloatingBubbleRunning(this)

        // Jab tak sabhi settings ON na hon, app aur bubble OFF hi rahenge
        if (!allReady) {
            if (!AreaPrefs.isPaused(this)) {
                AreaPrefs.setPaused(this, true)
            }
            if (bubbleOn) {
                runCatching { stopService(Intent(this, FloatingBubbleService::class.java)) }
            }
        }
        val paused = AreaPrefs.isPaused(this)

        binding.statusAccessibility.text =
            if (accessibilityOn) "\u2705 Accessibility Service: ON" else "\u274C Accessibility Service: OFF"
        binding.statusOverlay.text =
            if (overlayOn) "\u2705 Overlay Permission: ON" else "\u274C Overlay Permission: OFF"

        val extraIssues = mutableListOf<String>()
        if (!notifOn) extraIssues.add("\u274C Notification Permission: OFF")
        if (!batteryOn) extraIssues.add("\u274C Battery Unrestricted: OFF")
        if (extraIssues.isEmpty()) {
            binding.statusExtraPerms.visibility = View.GONE
        } else {
            binding.statusExtraPerms.visibility = View.VISIBLE
            binding.statusExtraPerms.text = extraIssues.joinToString("\n")
        }

        val pickup = AreaPrefs.getPickupSelections(this)
        val pickupKeywords = AreaPrefs.getPickupKeywords(this)
        val maxPickupKm = AreaPrefs.getMaxPickupKm(this)
        val drop = AreaPrefs.getDropSelections(this)
        val kmLabel = if (maxPickupKm > 0f) {
            if (maxPickupKm % 1f == 0f) "${maxPickupKm.toInt()} km" else "$maxPickupKm km"
        } else ""
        binding.statusRules.text = buildString {
            val parts = mutableListOf<String>()
            if (pickup.isNotEmpty()) parts.add("${pickup.size} city set")
            if (pickupKeywords.isNotEmpty()) parts.add("${pickupKeywords.size} khaas naam")
            if (kmLabel.isNotEmpty()) parts.add("<= $kmLabel")
            val bothLocAndKm = (pickup.isNotEmpty() || pickupKeywords.isNotEmpty()) && kmLabel.isNotEmpty()
            append(
                when {
                    parts.isEmpty() -> "Pickup (A): Kahin se bhi (any distance)"
                    bothLocAndKm -> "Pickup (A): ${parts.joinToString(" + ")} (dono match zaroori)"
                    else -> "Pickup (A): ${parts.joinToString(" · ")}"
                }
            )
            append("\n")
            append(if (drop.isEmpty()) "Drop (A): ABHI SET NAHI HAI (kuch bhi accept nahi hoga)" else "Drop (A): ${drop.size} city set")
        }

        val running = allReady && !paused
        binding.btnStartStop.text = when {
            !allReady -> "\u25CB OFF"
            paused -> "\u25CB OFF"
            else -> "\u25CF ON"
        }
        binding.btnStartStop.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (running) "#43A047" else "#C62828"))

        // Location button lock/unlock state based on all settings
        binding.btnSetArea.text = if (allReady) "LOCATION\n(ACCEPT)" else "\uD83D\uDD12 LOCATION\n(ACCEPT)"
        binding.btnSetArea.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (allReady) "#5E35B1" else "#424242"))
        binding.btnSetArea.alpha = if (allReady) 1.0f else 0.75f

        binding.btnSwipeZone.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (allReady) "#5E35B1" else "#424242"))
        binding.btnSwipeZone.alpha = if (allReady) 1.0f else 0.75f

        // Sirf dikkat ho tab hi permission wali lines/button dikhao
        binding.statusAccessibility.visibility = if (accessibilityOn) View.GONE else View.VISIBLE
        binding.statusOverlay.visibility = if (overlayOn) View.GONE else View.VISIBLE
        binding.btnPermissions.visibility = if (allReady) View.GONE else View.VISIBLE

        binding.btnFloatingBubble.text =
            if (bubbleOn) "\u2705 Floating Bubble: ON (Auto)" else "Floating Bubble: OFF (ON Karein)"
        binding.btnFloatingBubble.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (bubbleOn) "#2E7D32" else "#5E35B1"))
        binding.btnSwipeZone.text =
            if (SwipeZone.isSet(this)) "SWIPE ZONE \u2705" else "SWIPE ZONE"
    }
}
