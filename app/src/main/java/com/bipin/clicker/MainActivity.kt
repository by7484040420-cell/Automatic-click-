// ===== REPLACE (purani file hatao, ye daalo) =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/MainActivity.kt
package com.bipin.clicker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bipin.clicker.databinding.ActivityMainBinding
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.MobileAds

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var adBanner: AdView? = null
    private var adsInitialized = false
    private var lastResumeSyncAt = 0L

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            ScreenWatcherService.start(this, r.resultCode, data)
            Toast.makeText(this, "Screen Watcher ON", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Screen capture permission nahi mili", Toast.LENGTH_LONG).show()
        }
        binding.root.postDelayed({ refreshStatus() }, 500)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
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
            startActivity(Intent(this, AreaPreferenceActivity::class.java))
        }

        binding.btnFloatingBubble.setOnClickListener { toggleFloatingBubble() }

        binding.btnRootSetup.setOnClickListener { runRootSetup(true) }
        binding.btnScreenWatch.setOnClickListener {
            if (ScreenWatcherService.running) {
                ScreenWatcherService.stop(this)
                binding.root.postDelayed({ refreshStatus() }, 300)
            } else {
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projectionLauncher.launch(mpm.createScreenCaptureIntent())
            }
        }
        binding.btnSwipeZone.setOnClickListener { openSwipeZoneEditor() }
        binding.btnWhatsappHelp.setOnClickListener {
            LicenseManager.openWhatsAppSupport(this, isHelp = true)
        }
        binding.btnOwnerAutoApprove.setOnClickListener { OwnerPaytmConfig.showSetupDialog(this) }

        // First frame turant render hone ke baad Ads load karo (app open lag na ho)
        binding.root.postDelayed({ initAdsIfNeeded() }, 600L)

        // Advanced cheezein chhupi rahengi, dabane par hi dikhengi
        binding.tvAdvanced.setOnClickListener {
            val show = binding.advancedBox.visibility != View.VISIBLE
            binding.advancedBox.visibility = if (show) View.VISIBLE else View.GONE
            binding.tvAdvanced.text = if (show) "Advanced \u25B4" else "Advanced \u25BE"
        }

        // Purane Android (11 se neeche) par sirf OCR backup band hota hai; asli (tez,
        // screenshot-wala-nahi) tareeka har Android version par chalta hai. Isliye ye ab
        // sirf jaankari hai, "kaam nahi karega" nahi.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            binding.tvWarn.visibility = View.VISIBLE
            binding.tvWarn.text = "Is phone me Android ${Build.VERSION.RELEASE} hai. " +
                "Asli (tez) order-detect tareeka yahan bhi chalega; sirf OCR wala backup " +
                "(Android 11+ ka) is phone par kaam nahi karega."
        }
    }

    private fun initAdsIfNeeded() {
        if (adsInitialized || isFinishing || isDestroyed) return
        adsInitialized = true
        try {
            MobileAds.initialize(applicationContext) {
                if (isFinishing || isDestroyed) return@initialize
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    val banner = AdView(this)
                    banner.setAdSize(AdSize.BANNER)
                    banner.adUnitId = AdIds.BANNER
                    binding.adBannerBox.addView(
                        banner,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.CENTER_HORIZONTAL
                        )
                    )
                    banner.loadAd(AdRequest.Builder().build())
                    adBanner = banner
                    NativeVideoAd.load(this, binding.adVideoBox)
                }
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        NativeVideoAd.destroy()
        adBanner?.destroy()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        if (!LicenseManager.isActive(this)) {
            startActivity(Intent(this, LicenseActivity::class.java))
            finish()
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastResumeSyncAt >= 60_000L) {
            lastResumeSyncAt = now
            LicenseManager.refreshAsync(this)
            UpdateManager.checkAndPrompt(this)
        }
        refreshStatus()
    }

    private fun runRootSetup(manual: Boolean) {
        RootShell.connect { ok ->
            if (ok) {
                Thread {
                    RootShell.grantAll(this)
                    runOnUiThread {
                        refreshStatus()
                        if (manual) Toast.makeText(this, "Root se permissions de di gayi", Toast.LENGTH_LONG).show()
                    }
                }.start()
            } else {
                runOnUiThread {
                    refreshStatus()
                    if (manual) Toast.makeText(this, "Root nahi mila: ${RootShell.state}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun toggleStartStop() {
        if (!PermissionManager.isAccessibilityServiceEnabled(this)) {
            Toast.makeText(this, "Pehle Accessibility Service ON karein (Permissions button)", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, PermissionSetupActivity::class.java))
            return
        }
        val nowPaused = !AreaPrefs.isPaused(this)
        AreaPrefs.setPaused(this, nowPaused)
        if (nowPaused) {
            // BAND karte hi baaki sab bhi band: screen watcher aur bubble
            if (ScreenWatcherService.running) ScreenWatcherService.stop(this)
            if (PermissionManager.isFloatingBubbleRunning(this)) {
                stopService(Intent(this, FloatingBubbleService::class.java))
            }
        }
        refreshStatus()
        Toast.makeText(
            this,
            if (nowPaused) "Auto-Accept STOP kar diya" else "Auto-Accept START kar diya",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun openSwipeZoneEditor() {
        if (!SwipeZone.openEditor(this)) {
            Toast.makeText(this, "Pehle Accessibility Service ON karein", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Hari line ko slider par rakho, phir SAVE dabao", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
    }

    private fun toggleFloatingBubble() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Pehle 'Display over other apps' permission allow karein", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        try {
            if (PermissionManager.isFloatingBubbleRunning(this)) {
                stopService(Intent(this, FloatingBubbleService::class.java))
            } else {
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
        val bubbleOn = PermissionManager.isFloatingBubbleRunning(this)
        val paused = AreaPrefs.isPaused(this)

        binding.statusAccessibility.text =
            if (accessibilityOn) "\u2705 Accessibility Service: ON" else "\u274C Accessibility Service: OFF"
        binding.statusOverlay.text =
            if (overlayOn) "\u2705 Overlay Permission: ON" else "\u274C Overlay Permission: OFF"

        val pickup = AreaPrefs.getPickupSelections(this)
        val pickupKeywords = AreaPrefs.getPickupKeywords(this)
        val maxPickupKm = AreaPrefs.getMaxPickupKm(this)
        val drop = AreaPrefs.getDropSelections(this)
        val rejectPickup = AreaPrefs.getRejectPickupSelections(this)
        val rejectDrop = AreaPrefs.getRejectDropSelections(this)
        val kmLabel = if (maxPickupKm > 0f) {
            if (maxPickupKm % 1f == 0f) "${maxPickupKm.toInt()} km" else "$maxPickupKm km"
        } else ""
        binding.statusRules.text = buildString {
            val parts = mutableListOf<String>()
            if (pickup.isNotEmpty()) parts.add("${pickup.size} city set")
            if (pickupKeywords.isNotEmpty()) parts.add("${pickupKeywords.size} khaas naam")
            if (kmLabel.isNotEmpty()) parts.add("<= $kmLabel")
            append(
                if (parts.isEmpty()) "Pickup (A): koi restriction nahi"
                else "Pickup (A): ${parts.joinToString(" · ")}"
            )
            if (rejectPickup.isNotEmpty()) append(" · Reject (R): ${rejectPickup.size}")
            append("\n")
            append(if (drop.isEmpty()) "Drop (A): ABHI SET NAHI HAI (kuch bhi accept nahi hoga)" else "Drop (A): ${drop.size} city set")
            if (rejectDrop.isNotEmpty()) append(" · Reject (R): ${rejectDrop.size}")
        }

        val running = accessibilityOn && !paused
        binding.btnStartStop.text = when {
            !accessibilityOn -> "START"
            paused -> "\u25CB OFF"
            else -> "\u25CF ON"
        }
        binding.btnStartStop.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (running) "#43A047" else "#C62828"))

        // Sirf dikkat ho tab hi permission wali lines/button dikhao
        binding.statusAccessibility.visibility = if (accessibilityOn) View.GONE else View.VISIBLE
        binding.statusOverlay.visibility = if (overlayOn) View.GONE else View.VISIBLE
        binding.btnPermissions.visibility = if (accessibilityOn && overlayOn) View.GONE else View.VISIBLE

        binding.statusRoot.text = (if (RootShell.ready) "\u2705 " else "\u274C ") + "Root: ${RootShell.state}\n" +
            (if (ScreenWatcherService.running) "\u2705 Screen Watcher: ON" else "\u274C Screen Watcher: OFF")
        binding.btnScreenWatch.text =
            if (ScreenWatcherService.running) "Screen Watcher BAND Karein" else "Screen Watcher ON Karein"

        binding.btnFloatingBubble.text =
            if (bubbleOn) "Floating Bubble Band Karein" else "Floating Bubble ON Karein"
        binding.btnSwipeZone.text =
            if (SwipeZone.isSet(this)) "SWIPE ZONE \u2705" else "SWIPE ZONE"
    }
}
