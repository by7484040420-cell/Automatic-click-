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

        binding.btnSetArea.setOnClickListener {
            startActivity(Intent(this, AreaPreferenceActivity::class.java))
        }

        binding.btnEnableService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "List me 'BIPIN Clicker' dhoondh kar ON karein",
                Toast.LENGTH_LONG
            ).show()
        }

        binding.btnFloatingBubble.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(
                    this,
                    "Pehle 'Display over other apps' permission allow karein",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                startService(Intent(this, FloatingBubbleService::class.java))
                Toast.makeText(
                    this,
                    "Floating bubble ON - usse tap karke Auto-Accept Pause/Resume karein",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateAreaSummary()
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

    private fun updateAreaSummary() {
        val pickupCount = AreaPrefs.getPickupCities(this).size
        val dropCount = AreaPrefs.getDropCities(this).size
        binding.areaSummaryText.text = if (pickupCount == 0 || dropCount == 0)
            "Abhi koi city set nahi hai - neeche button se set karein"
        else
            "Aapka area: $pickupCount pickup city, $dropCount drop city\n\n" +
                "Ab 'Auto-Click Service ON Karein' dabakar Accessibility me BIPIN Clicker ON karein. " +
                "Iske baad kisi bhi app (jaise Porter) me jab pickup+drop dono match wala real order " +
                "screen par aayega, wo khud accept ho jayega."
    }
}
