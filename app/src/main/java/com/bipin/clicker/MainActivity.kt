// ===== REPLACE (purani file hatao, ye daalo) =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/MainActivity.kt
package com.bipin.clicker

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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

    private val vpnPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == RESULT_OK) {
            Toast.makeText(this, "VPN Permission Allow ho gayi!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "VPN permission cancel hui", Toast.LENGTH_SHORT).show()
        }
        refreshStatus()
    }

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

        // VPN 1s Net-Off controls
        binding.btnVpnToggle.setOnClickListener {
            val next = !AreaPrefs.isVpnPulseEnabled(this)
            AreaPrefs.setVpnPulseEnabled(this, next)
            refreshStatus()
            if (next && NetPulseVpnService.needsPermission(this)) {
                requestVpnPermission()
            }
        }
        binding.btnVpnAllowPerm.setOnClickListener { requestVpnPermission() }
        binding.btnVpnModeAll.setOnClickListener {
            AreaPrefs.setVpnTargetMode(this, AreaPrefs.VPN_MODE_ALL_PHONE)
            refreshStatus()
        }
        binding.btnVpnModeApps.setOnClickListener {
            AreaPrefs.setVpnTargetMode(this, AreaPrefs.VPN_MODE_SELECTED_APPS)
            refreshStatus()
            if (AreaPrefs.getVpnTargetPackages(this).isEmpty()) {
                openAppPickerDialog()
            }
        }
        binding.btnPickApps.setOnClickListener { openAppPickerDialog() }
        binding.btnVpnDuration.setOnClickListener {
            val steps = listOf(500L, 1000L, 1500L, 2000L)
            val cur = AreaPrefs.getVpnDurationMs(this)
            val idx = steps.indexOfFirst { it == cur }
            val nextMs = steps[(idx + 1) % steps.size]
            AreaPrefs.setVpnDurationMs(this, nextMs)
            refreshStatus()
        }
        binding.btnVpnTest.setOnClickListener {
            if (NetPulseVpnService.needsPermission(this)) {
                requestVpnPermission()
                return@setOnClickListener
            }
            if (AreaPrefs.getVpnTargetMode(this) == AreaPrefs.VPN_MODE_SELECTED_APPS &&
                AreaPrefs.getVpnTargetPackages(this).isEmpty()
            ) {
                Toast.makeText(this, "Pehle kam se kam 1 APK select karein", Toast.LENGTH_SHORT).show()
                openAppPickerDialog()
                return@setOnClickListener
            }
            val ok = NetPulseVpnService.triggerPulse(this)
            val sec = String.format("%.1fs", AreaPrefs.getVpnDurationMs(this) / 1000.0)
            Toast.makeText(
                this,
                if (ok) "1s VPN Net-Off chal gaya ($sec me auto ON hoga)" else "VPN ON nahi ho paya (toggle ON check karein)",
                Toast.LENGTH_SHORT
            ).show()
        }

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

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermLauncher.launch(intent)
        } else {
            Toast.makeText(this, "VPN permission pehle se allow hai", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
    }

    private fun openAppPickerDialog() {
        val pm = packageManager
        val selected = AreaPrefs.getVpnTargetPackages(this).toMutableSet()
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { info ->
                info.packageName != packageName &&
                    ((info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
                        pm.getLaunchIntentForPackage(info.packageName) != null ||
                        info.packageName in selected)
            }
            .map { info ->
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                label to info.packageName
            }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }

        if (apps.isEmpty()) {
            showCustomPackageDialog(selected)
            return
        }

        val labels = apps.map { "${it.first}\n(${it.second})" }.toTypedArray()
        val checked = BooleanArray(apps.size) { i -> apps[i].second in selected }

        AlertDialog.Builder(this)
            .setTitle("Chune Hue APKs (1s Net Off)")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                val pkg = apps[which].second
                if (isChecked) selected.add(pkg) else selected.remove(pkg)
            }
            .setPositiveButton("Save") { _, _ ->
                AreaPrefs.setVpnTargetPackages(this, selected)
                refreshStatus()
            }
            .setNeutralButton("Custom Package") { _, _ ->
                AreaPrefs.setVpnTargetPackages(this, selected)
                showCustomPackageDialog(selected)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCustomPackageDialog(selected: MutableSet<String>) {
        val input = EditText(this).apply {
            hint = "Package naam daalein (e.g. com.theporter.android.driverapp)"
        }
        AlertDialog.Builder(this)
            .setTitle("Custom APK Package Add Karein")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val pkg = input.text.toString().trim()
                if (pkg.isNotEmpty()) {
                    selected.add(pkg)
                    AreaPrefs.setVpnTargetPackages(this, selected)
                    refreshStatus()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
        LicenseManager.refreshAsync(this)
        UpdateManager.checkAndPrompt(this)
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
        val rejectPickup = AreaPrefs.getRejectPickupSelections(this)
        val rejectDrop = AreaPrefs.getRejectDropSelections(this)
        binding.statusRules.text = buildString {
            append(if (pickup.isEmpty()) "Pickup (A): koi restriction nahi" else "Pickup (A): ${pickup.size} city set")
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

        // VPN Panel Status
        val vpnEnabled = AreaPrefs.isVpnPulseEnabled(this)
        val vpnNeedsPerm = NetPulseVpnService.needsPermission(this)
        val vpnMode = AreaPrefs.getVpnTargetMode(this)
        val vpnApps = AreaPrefs.getVpnTargetPackages(this)
        val vpnDurMs = AreaPrefs.getVpnDurationMs(this)

        binding.btnVpnToggle.text = if (vpnEnabled) "VPN: ON" else "VPN: OFF"
        binding.btnVpnToggle.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (vpnEnabled) "#43A047" else "#616161"))
        binding.btnVpnAllowPerm.visibility = if (vpnEnabled && vpnNeedsPerm) View.VISIBLE else View.GONE

        val isAllMode = vpnMode != AreaPrefs.VPN_MODE_SELECTED_APPS
        binding.btnVpnModeAll.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (isAllMode) "#C62828" else "#2A2A2A"))
        binding.btnVpnModeApps.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (!isAllMode) "#43A047" else "#2A2A2A"))
        binding.btnVpnModeApps.text = "Chune Hue APKs (${vpnApps.size})"
        binding.vpnAppsBar.visibility = if (!isAllMode) View.VISIBLE else View.GONE
        binding.tvVpnSelectedApps.text = if (vpnApps.isEmpty()) {
            "Selected APKs: koi nahi (niche button se chunein)"
        } else {
            "Selected APKs: ${vpnApps.joinToString(", ")}"
        }
        binding.btnVpnDuration.text = String.format("Timer: %.1fs", vpnDurMs / 1000.0)

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
