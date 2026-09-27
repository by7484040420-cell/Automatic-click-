package com.bipin.clicker

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * Har permission/service ka ASLI status check karta hai - kabhi bhi galat/optimistic
 * "enabled" nahi bolta. Android khud hi restricted permissions (Accessibility, Overlay)
 * ko silently enable nahi hone deta - user ko khud Settings me jaake ON karna padta hai.
 */
object PermissionManager {

    /** Kya humari OrderWatcherService Accessibility Settings me user ne ON ki hai. */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        return enabledServices.any { info ->
            info.resolveInfo.serviceInfo.packageName == context.packageName &&
                info.resolveInfo.serviceInfo.name == OrderWatcherService::class.java.name
        }
    }

    /** "Display over other apps" - floating bubble ke liye zaroori hai. */
    fun isOverlayPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /** Android 13+ par notification dikhane ke liye permission chahiye hoti hai. */
    fun isNotificationPermissionGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Floating Bubble service abhi chal rahi hai ya nahi - service khud is flag ko
     * onCreate/onDestroy me set/clear karti hai.
     */
    fun isFloatingBubbleRunning(context: Context): Boolean {
        return context.getSharedPreferences("clicker_area_prefs", Context.MODE_PRIVATE)
            .getBoolean("bubble_running", false)
    }

    /** Sab kuch (Accessibility + Overlay + Notification) set hai ya nahi - ek nazar me. */
    fun isEverythingReady(context: Context): Boolean =
        isAccessibilityServiceEnabled(context) &&
            isOverlayPermissionGranted(context) &&
            isNotificationPermissionGranted(context)
}
