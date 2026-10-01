package com.bipin.clicker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast

/**
 * AUTO SETUP: app kholte hi ek-ek karke sahi Settings screen khud khol deta hai.
 * User sirf wahan ON / Allow dabata hai, wapas aate hi agli screen khul jati hai.
 *
 * Android khud kisi app ko ye settings chupke se ON karne nahi deta - isliye app
 * sirf SAHI SCREEN tak le jata hai (jo ho chuka hai wo screen dobara nahi kholta).
 */
object SetupWizard {

    private enum class Step { ACCESSIBILITY, OVERLAY, BATTERY, AUTOSTART, NOTIFICATION }

    private val attempted = mutableSetOf<Step>()
    private var active = false

    private fun prefs(c: Context) = c.getSharedPreferences("setup_wizard", Context.MODE_PRIVATE)

    /** forceAll=true: autostart wali screen bhi dobara dikhao (Permission Setup button se). */
    fun start(context: Context, forceAll: Boolean) {
        attempted.clear()
        active = true
        if (forceAll) prefs(context).edit().putBoolean("autostart_opened", false).apply()
    }

    /** Agli adhuri setting ki screen kholta hai. Kuch khula to true. */
    fun runNext(context: Context): Boolean {
        if (!active) return false
        while (true) {
            val step = Step.values().firstOrNull { it !in attempted && !isDone(context, it) }
            if (step == null) {
                active = false
                finish(context)
                return false
            }
            attempted.add(step)
            if (open(context, step)) return true
        }
    }

    private fun isDone(c: Context, step: Step): Boolean = when (step) {
        Step.ACCESSIBILITY -> PermissionManager.isAccessibilityServiceEnabled(c)
        Step.OVERLAY -> PermissionManager.isOverlayPermissionGranted(c)
        Step.BATTERY -> try {
            (c.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(c.packageName)
        } catch (_: Exception) { true }
        Step.AUTOSTART -> prefs(c).getBoolean("autostart_opened", false)
        Step.NOTIFICATION -> PermissionManager.isNotificationPermissionGranted(c)
    }

    private fun toast(c: Context, msg: String) {
        Toast.makeText(c.applicationContext, msg, Toast.LENGTH_LONG).show()
    }

    private fun launch(c: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(intent)
        true
    } catch (_: Exception) { false }

    private fun open(c: Context, step: Step): Boolean = when (step) {
        Step.ACCESSIBILITY -> {
            toast(c, "1) 'BIPIN Clicker' dhoondo aur ON karo.\nAgar grey/band ho: App info -> upar 3 dot -> Allow restricted settings")
            launch(c, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        Step.OVERLAY -> {
            toast(c, "2) 'Display over other apps' ON karo")
            launch(c, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}")))
        }

        Step.BATTERY -> {
            toast(c, "3) Battery: Allow / Unrestricted dabao")
            try {
                DeviceAutoStartHelper.openBatteryOptimizationSettings(c)
                true
            } catch (_: Exception) { false }
        }

        Step.AUTOSTART -> {
            prefs(c).edit().putBoolean("autostart_opened", true).apply()
            val opened = DeviceAutoStartHelper.openAutoStartSettings(c)
            if (opened) toast(c, "4) Autostart / Background ko ON karo")
            opened // stock phone par ye screen hoti hi nahi -> chhod do
        }

        Step.NOTIFICATION -> {
            toast(c, "5) Notifications Allow karo")
            launch(
                c,
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)
            )
        }
    }

    private fun finish(c: Context) {
        toast(c, "Setup poora. Bubble ON hai to screen band nahi hogi.")
        // Floating bubble screen ON rakhta hai (FLAG_KEEP_SCREEN_ON) - isliye use chala do
        try {
            if (PermissionManager.isOverlayPermissionGranted(c) &&
                !PermissionManager.isFloatingBubbleRunning(c)
            ) {
                c.startService(Intent(c, FloatingBubbleService::class.java))
            }
        } catch (_: Exception) {
        }
    }
}
