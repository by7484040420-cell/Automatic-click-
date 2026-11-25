package com.bipin.clicker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.bipin.clicker.databinding.ActivityPermissionSetupBinding

class PermissionSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionSetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAutoSetup.setOnClickListener {
            SetupWizard.start(this, forceAll = true)
            if (!SetupWizard.runNext(this)) {
                refreshStatuses()
            }
        }

        binding.btnOpenAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnOpenOverlay.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        binding.btnOpenNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100
                )
            } else {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                startActivity(intent)
            }
        }

        binding.btnOpenBattery.setOnClickListener {
            DeviceAutoStartHelper.openBatteryOptimizationSettings(this)
        }

        binding.btnOpenAutostart.setOnClickListener {
            DeviceAutoStartHelper.openBestAvailableAutoStartScreen(this)
        }

        binding.btnDone.setOnClickListener {
            if (!PermissionManager.isEverythingReady(this)) {
                val missing = PermissionManager.missingSettingsList(this).joinToString(", ")
                Toast.makeText(
                    this,
                    "Abhi bhi baaki hai: $missing (jab tak sab ON nahi hoga, Location aur App band rahenge)",
                    Toast.LENGTH_LONG
                ).show()
            }
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        SetupWizard.runNext(this)
        refreshStatuses()
    }

    private fun refreshStatuses() {
        val acc = PermissionManager.isAccessibilityServiceEnabled(this)
        val ovl = PermissionManager.isOverlayPermissionGranted(this)
        val notif = PermissionManager.isNotificationPermissionGranted(this)
        val bat = PermissionManager.isBatteryOptimizationIgnored(this)
        val allReady = acc && ovl && notif && bat

        setRow(
            binding.statusAccessibility,
            acc,
            "Accessibility Service: ON",
            "Accessibility Service: OFF (order auto-accept nahi hoga)"
        )
        setRow(
            binding.statusOverlay,
            ovl,
            "Display over other apps: Allowed",
            "Display over other apps: Not Allowed (bubble nahi dikhega)"
        )
        setRow(
            binding.statusNotification,
            notif,
            "Notifications: Allowed",
            "Notifications: Not Allowed (status icon nahi dikhega)"
        )
        setRow(
            binding.statusBattery,
            bat,
            "Battery Optimization: Unrestricted",
            "Battery Optimization: Restricted (background me app band ho jayega)"
        )

        binding.tvGateNotice.text = if (allReady) {
            "\u2705 Sabhi zaroori settings ON hain! Ab aap Location set kar sakte hain aur App ON kar sakte hain."
        } else {
            "Jab tak upar ki sabhi 4 settings ON nahi hongi, tab tak Location set nahi hogi aur App OFF rahega."
        }
        binding.tvGateNotice.setTextColor(
            android.graphics.Color.parseColor(if (allReady) "#81C784" else "#FFB74D")
        )
    }

    private fun setRow(view: android.widget.TextView, granted: Boolean, okText: String, badText: String) {
        view.text = if (granted) "\u2705 $okText" else "\u274C $badText"
    }
}
