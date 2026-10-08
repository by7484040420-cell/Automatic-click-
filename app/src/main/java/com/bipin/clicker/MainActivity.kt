// ===== REPLACE (purani file hatao, ye daalo) =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/MainActivity.kt
package com.bipin.clicker

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.media.projection.MediaProjectionManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bipin.clicker.databinding.ActivityMainBinding
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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
        if (Integrity.isTampered(this)) throw IllegalStateException("Corrupted package")
        // APPROVAL: bina approval ke app ki asli screen nahi khulegi
        if (!LicenseManager.isActive(this)) {
            startActivity(Intent(this, LicenseActivity::class.java))
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        MobileAds.initialize(this) {}
        binding.adView.loadAd(AdRequest.Builder().build())

        requestNotificationPermissionIfNeeded()

        // AUTO SETUP: app khulte hi adhuri settings ki screen ek-ek karke khulegi
        SetupWizard.start(this, forceAll = false)

        binding.btnStartStop.setOnClickListener { toggleStartStop() }

        binding.btnPermissions.setOnClickListener {
            // Auto Setup dobara chalao (jo adhura hai wo khulega, autostart bhi)
            SetupWizard.start(this, forceAll = true)
            SetupWizard.runNext(this)
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
        // Root ho to app khulte hi saari permissions khud mil jati hain
        runRootSetup(false)
        binding.btnSwipeZone.setOnClickListener { openSwipeZoneEditor() }

        // Advanced cheezein chhupi rahengi, dabane par hi dikhengi
        binding.tvAdvanced.setOnClickListener {
            val show = binding.advancedBox.visibility != View.VISIBLE
            binding.advancedBox.visibility = if (show) View.VISIBLE else View.GONE
            binding.tvAdvanced.text = if (show) "Advanced \u25B4" else "Advanced \u25BE"
        }

        // Purane Android par order padhne ka tareeka nahi chalta, pehle hi bata do
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            binding.tvWarn.visibility = View.VISIBLE
            binding.tvWarn.text = "\u26A0 Is phone me Android ${Build.VERSION.RELEASE} hai. " +
                "Order padhne ke liye Android 11 ya naya chahiye, isliye ye phone par kaam nahi karega."
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        if (!LicenseManager.isActive(this)) {
            startActivity(Intent(this, LicenseActivity::class.java))
            finish()
            return
        }
        LicenseManager.refreshAsync(this)
        refreshStatus()
        // Settings se wapas aate hi agli adhuri setting khol do
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed) SetupWizard.runNext(this)
        }, 1200L)
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
        if (PermissionManager.isFloatingBubbleRunning(this)) {
            stopService(Intent(this, FloatingBubbleService::class.java))
        } else {
            startService(Intent(this, FloatingBubbleService::class.java))
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
        val drop = AreaPrefs.getDropSelections(this)
        binding.statusRules.text = buildString {
            append(if (pickup.isEmpty()) "Pickup: koi restriction nahi\n" else "Pickup: ${pickup.size} city set\n")
            append(if (drop.isEmpty()) "Drop: ABHI SET NAHI HAI (kuch bhi accept nahi hoga)" else "Drop: ${drop.size} city set")
        }

        val running = accessibilityOn && !paused
        binding.btnStartStop.text = when {
            !accessibilityOn -> "START (pehle Permissions set karein)"
            paused -> "\u25CB BAND HAI\nchalu karne ke liye dabao"
            else -> "\u25CF CHALU HAI\nband karne ke liye dabao"
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
            if (SwipeZone.isSet(this)) "Swipe Zone: SET \u2705 (badalne ke liye dabao)" else "Swipe Zone Set Karein"
    }
}
