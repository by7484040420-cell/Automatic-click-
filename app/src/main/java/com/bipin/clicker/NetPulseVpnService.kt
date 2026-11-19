package com.bipin.clicker

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor

/**
 * 1-second local blackhole VpnService:
 * - ALL_PHONE: blocks all phone internet except this app for durationMs, then auto-restores.
 * - SELECTED_APPS: blocks ONLY user-selected APK packages for durationMs, then auto-restores.
 */
class NetPulseVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var activeSignature: String = ""
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // FIX: foreground fail ho (Android 12+/ColorOS background restriction) to bhi VPN chalna chahiye
        try {
            ensureForeground()
        } catch (e: Throwable) {
            lastError = "foreground fail: ${e.javaClass.simpleName}"
        }

        val durationMs = (intent?.getLongExtra(EXTRA_DURATION_MS, AreaPrefs.getVpnDurationMs(this))
            ?: AreaPrefs.getVpnDurationMs(this)).coerceIn(250L, 5000L)
        val targetMode = intent?.getStringExtra(EXTRA_TARGET_MODE)
            ?: AreaPrefs.getVpnTargetMode(this)
        val targetPackages = intent?.getStringArrayListExtra(EXTRA_PACKAGES)
            ?: ArrayList(AreaPrefs.getVpnTargetPackages(this))

        try {
            startBlackholeVpn(targetMode, targetPackages)
        } catch (e: Throwable) {
            lastError = "vpn establish fail: ${e.javaClass.simpleName}"
        }

        stopRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            stopBlackholeVpn()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            } catch (_: Throwable) {
            }
            stopSelf()
        }
        stopRunnable = r
        mainHandler.postDelayed(r, durationMs)
        return START_NOT_STICKY
    }

    private fun ensureForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!channelCreated) {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
                    val ch = NotificationChannel(
                        CHANNEL_ID,
                        "SwiftGate VPN Net Pulse",
                        NotificationManager.IMPORTANCE_LOW
                    )
                    nm?.createNotificationChannel(ch)
                }
                channelCreated = true
            }
            val n = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SwiftGate Net-Off Pulse")
                .setContentText("Net pulse active...")
                .setSmallIcon(R.drawable.ic_service_on)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        }
    }

    private fun startBlackholeVpn(targetMode: String, targetPackages: List<String>) {
        val sig = "$targetMode:${targetPackages.joinToString(",")}"
        if (vpnInterface != null && sig == activeSignature) return
        if (vpnInterface != null) stopBlackholeVpn()

        val builder = Builder()
            .setSession("SwiftGate Net-Off")
            .addAddress("10.250.250.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("10.250.250.1")
            .addAddress("fd00:250::2", 128)
            .addRoute("::", 0)

        if (targetMode == AreaPrefs.VPN_MODE_SELECTED_APPS) {
            var addedAny = false
            for (pkg in targetPackages) {
                val clean = pkg.trim()
                if (clean.isNotEmpty() && clean != packageName) {
                    try {
                        builder.addAllowedApplication(clean)
                        addedAny = true
                    } catch (_: Exception) {
                    }
                }
            }
            if (!addedAny) {
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: Exception) {
                }
            }
        } else {
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {
            }
        }

        vpnInterface = try {
            val fd = builder.establish()
            if (fd != null) {
                activeSignature = sig
                lastError = ""
            } else {
                lastError = "establish()=null (VPN permission revoked / dusra VPN ON)"
            }
            fd
        } catch (e: Exception) {
            activeSignature = ""
            lastError = "establish error: ${e.javaClass.simpleName}"
            null
        }
    }

    private fun stopBlackholeVpn() {
        activeSignature = ""
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
    }

    override fun onDestroy() {
        stopRunnable?.let { mainHandler.removeCallbacks(it) }
        stopBlackholeVpn()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "swiftgate_vpn_pulse"
        private const val NOTIF_ID = 4099
        @Volatile private var channelCreated = false
        /** Pichli baar net-off kyun fail hua - debug box me dikhta hai */
        @Volatile var lastError: String = ""
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_TARGET_MODE = "target_mode"
        const val EXTRA_PACKAGES = "target_packages"

        fun needsPermission(context: Context): Boolean =
            VpnService.prepare(context) != null

        fun triggerPulse(context: Context): Boolean {
            if (!AreaPrefs.isVpnPulseEnabled(context)) {
                lastError = "VPN toggle OFF hai"
                return false
            }
            if (VpnService.prepare(context) != null) {
                lastError = "VPN permission nahi mili - app me 'VPN Allow' dabao"
                return false
            }

            val durationMs = AreaPrefs.getVpnDurationMs(context)
            val targetMode = AreaPrefs.getVpnTargetMode(context)
            val packages = ArrayList(AreaPrefs.getVpnTargetPackages(context))
            if (targetMode == AreaPrefs.VPN_MODE_SELECTED_APPS && packages.isEmpty()) {
                lastError = "Selected Apps mode me koi APK select nahi hai"
                return false
            }

            val i = Intent(context, NetPulseVpnService::class.java).apply {
                putExtra(EXTRA_DURATION_MS, durationMs)
                putExtra(EXTRA_TARGET_MODE, targetMode)
                putStringArrayListExtra(EXTRA_PACKAGES, packages)
            }

            // FIX: pehle seedha startService (background se bhi chalta hai kyunki app ka
            // Floating Bubble foreground service pehle se chal raha hota hai).
            // Fail ho to startForegroundService try karo.
            try {
                context.startService(i)
                lastError = ""
                return true
            } catch (e: Exception) {
                lastError = "startService fail: ${e.javaClass.simpleName}"
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
                lastError = ""
                true
            } catch (e: Exception) {
                lastError = "service start fail: ${e.javaClass.simpleName} (Battery: Unrestricted + Autostart ON karo)"
                false
            }
        }
    }
}
