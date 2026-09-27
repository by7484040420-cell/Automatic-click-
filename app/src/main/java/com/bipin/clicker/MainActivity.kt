package com.bipin.clicker

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.bipin.clicker.databinding.ActivityMainBinding
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        MobileAds.initialize(this) {}
        binding.adView.loadAd(AdRequest.Builder().build())

        requestNotificationPermissionIfNeeded()

        binding.btnStartStop.setOnClickListener { toggleStartStop() }

        binding.btnPermissions.setOnClickListener {
            startActivity(Intent(this, PermissionSetupActivity::class.java))
        }

        binding.btnSetArea.setOnClickListener {
            startActivity(Intent(this, AreaPreferenceActivity::class.java))
        }

        binding.btnFloatingBubble.setOnClickListener { toggleFloatingBubble() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun toggleStartStop() {
        if (!PermissionManager.isAccessibilityServiceEnabled(this)) {
            Toast.makeText(this, "Pehle Accessibility Service ON karein (Permissions button)", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, PermissionSetupActivity::class.java))
            return
        }
        val nowPaused = !AreaPrefs.isPaused(this)
        AreaPrefs.setPaused(this, nowPaused)
        refreshStatus()
        Toast.makeText(
            this,
            if (nowPaused) "Auto-Accept STOP kar diya" else "Auto-Accept START kar diya",
            Toast.LENGTH_SHORT
        ).show()
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

        binding.btnStartStop.text = if (!accessibilityOn) {
            "START (pehle Permissions set karein)"
        } else if (paused) {
            "\u25B6 START Auto-Accept"
        } else {
            "\u25A0 STOP Auto-Accept"
        }

        binding.btnFloatingBubble.text =
            if (bubbleOn) "Floating Bubble Band Karein" else "Floating Bubble ON Karein"
    }
}
