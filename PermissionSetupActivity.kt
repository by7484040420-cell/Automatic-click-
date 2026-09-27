package com.bipin.clicker

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.bipin.clicker.databinding.ActivityPermissionSetupBinding

class PermissionSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionSetupBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        binding.btnDone.setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatuses()
    }

    private fun refreshStatuses() {
        setRow(
            binding.statusAccessibility,
            PermissionManager.isAccessibilityServiceEnabled(this),
            "Accessibility Service: ON",
            "Accessibility Service: OFF (order auto-accept nahi hoga)"
        )
        setRow(
            binding.statusOverlay,
            PermissionManager.isOverlayPermissionGranted(this),
            "Display over other apps: Allowed",
            "Display over other apps: Not Allowed (bubble nahi dikhega)"
        )
        setRow(
            binding.statusNotification,
            PermissionManager.isNotificationPermissionGranted(this),
            "Notifications: Allowed",
            "Notifications: Not Allowed (status icon nahi dikhega)"
        )
    }

    private fun setRow(view: android.widget.TextView, granted: Boolean, okText: String, badText: String) {
        view.text = if (granted) "\u2705 $okText" else "\u274C $badText"
    }
}
