import type { VpnPulseConfig, VpnTargetMode } from '../types';

const VPN_PULSE_KEY = 'swiftgate_vpn_pulse_config';

export interface ApkPreset {
  label: string;
  pkg: string;
}

export const COMMON_APK_PRESETS: ApkPreset[] = [
  { label: 'Porter Partner', pkg: 'com.theporter.android.driverapp' },
  { label: 'Porter Customer', pkg: 'com.theporter.android.customerapp' },
  { label: 'Uber Driver', pkg: 'com.ubercab.driver' },
  { label: 'Rapido Captain', pkg: 'com.rapido.rider' },
  { label: 'Ola Driver', pkg: 'com.olacabs.oladriver' },
  { label: 'Zomato Delivery', pkg: 'com.zomato.delivery' },
  { label: 'Swiggy Delivery', pkg: 'in.swiggy.deliveryapp' },
  { label: 'Borzo Courier', pkg: 'com.dostavista.client' },
  { label: 'Shadowfax', pkg: 'in.shadowfax.gandalf' },
];

const DEFAULT_CONFIG: VpnPulseConfig = {
  enabled: true,
  durationMs: 500,
  targetMode: 'ALL_PHONE',
  targetPackages: ['com.theporter.android.driverapp'],
  webhookOffUrl: '',
  webhookOnUrl: '',
};

export function getVpnPulseConfig(): VpnPulseConfig {
  try {
    const raw = localStorage.getItem(VPN_PULSE_KEY);
    if (!raw) return { ...DEFAULT_CONFIG };
    const parsed = JSON.parse(raw) as Partial<VpnPulseConfig>;
    const targetMode: VpnTargetMode =
      parsed.targetMode === 'SELECTED_APPS' ? 'SELECTED_APPS' : 'ALL_PHONE';
    const targetPackages = Array.isArray(parsed.targetPackages)
      ? parsed.targetPackages.filter((p): p is string => typeof p === 'string' && p.trim().length > 0)
      : [...DEFAULT_CONFIG.targetPackages];
    return {
      enabled: parsed.enabled ?? DEFAULT_CONFIG.enabled,
      durationMs:
        typeof parsed.durationMs === 'number' && parsed.durationMs >= 250 && parsed.durationMs <= 5000
          ? parsed.durationMs
          : DEFAULT_CONFIG.durationMs,
      targetMode,
      targetPackages,
      webhookOffUrl: typeof parsed.webhookOffUrl === 'string' ? parsed.webhookOffUrl : '',
      webhookOnUrl: typeof parsed.webhookOnUrl === 'string' ? parsed.webhookOnUrl : '',
    };
  } catch {
    return { ...DEFAULT_CONFIG };
  }
}

export function saveVpnPulseConfig(cfg: VpnPulseConfig): VpnPulseConfig {
  localStorage.setItem(VPN_PULSE_KEY, JSON.stringify(cfg));
  return cfg;
}

export function formatVpnTargetLabel(cfg: VpnPulseConfig): string {
  if (cfg.targetMode === 'ALL_PHONE') return 'Poora Phone';
  if (cfg.targetPackages.length === 0) return 'Koi APK selected nahi';
  if (cfg.targetPackages.length === 1) return cfg.targetPackages[0];
  return `${cfg.targetPackages.length} Selected APKs`;
}

interface NativeAndroidBridge {
  pulseVpn?: (durationMs: number, targetMode?: string, packagesJson?: string) => void;
  setVpnBlocked?: (
    blocked: boolean,
    durationMs: number,
    targetMode?: string,
    packagesJson?: string
  ) => void;
}

function appendWebhookQuery(baseUrl: string, cfg: VpnPulseConfig): string {
  try {
    const u = new URL(baseUrl);
    u.searchParams.set('mode', cfg.targetMode);
    u.searchParams.set('durationMs', String(cfg.durationMs));
    if (cfg.targetMode === 'SELECTED_APPS') {
      u.searchParams.set('apps', cfg.targetPackages.join(','));
    }
    return u.toString();
  } catch {
    return baseUrl;
  }
}

export function fireNativeOrWebhookOff(cfg: VpnPulseConfig): string {
  const pkgJson = JSON.stringify(cfg.targetPackages);
  const bridge = (window as unknown as { SwiftGateBridge?: NativeAndroidBridge }).SwiftGateBridge;
  if (bridge?.pulseVpn) {
    bridge.pulseVpn(cfg.durationMs, cfg.targetMode, pkgJson);
    return `Native Android VPN Bridge invoked (${formatVpnTargetLabel(cfg)})`;
  }
  if (bridge?.setVpnBlocked) {
    bridge.setVpnBlocked(true, cfg.durationMs, cfg.targetMode, pkgJson);
    return `Native VPN block ON (${formatVpnTargetLabel(cfg)})`;
  }
  if (cfg.webhookOffUrl.trim()) {
    const url = appendWebhookQuery(cfg.webhookOffUrl.trim(), cfg);
    fetch(url, { mode: 'no-cors' }).catch(() => {});
    return `VPN OFF webhook sent (${formatVpnTargetLabel(cfg)})`;
  }
  return `In-app VPN Net-Off Pulse active (${formatVpnTargetLabel(cfg)})`;
}

export function fireNativeOrWebhookOn(cfg: VpnPulseConfig): string {
  const pkgJson = JSON.stringify(cfg.targetPackages);
  const bridge = (window as unknown as { SwiftGateBridge?: NativeAndroidBridge }).SwiftGateBridge;
  if (bridge?.setVpnBlocked) {
    bridge.setVpnBlocked(false, 0, cfg.targetMode, pkgJson);
    return 'Native VPN block OFF (Net restored)';
  }
  if (cfg.webhookOnUrl.trim()) {
    const url = appendWebhookQuery(cfg.webhookOnUrl.trim(), cfg);
    fetch(url, { mode: 'no-cors' }).catch(() => {});
    return 'VPN ON webhook sent';
  }
  return 'Net auto-restored';
}

export function generateAndroidVpnKotlinCode(cfg: VpnPulseConfig): string {
  const pkgsLiteral =
    cfg.targetPackages.length > 0
      ? cfg.targetPackages.map((p) => `"${p.replace(/"/g, '')}"`).join(', ')
      : '"com.theporter.android.driverapp"';

  return `package com.bipin.clicker

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
 * GitHub Path: app/src/main/java/com/bipin/clicker/NetPulseVpnService.kt
 * Target Mode: ${cfg.targetMode} | Duration: ${cfg.durationMs}ms
 */
class NetPulseVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var stopRunnable: Runnable? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()

        val durationMs = intent?.getLongExtra(EXTRA_DURATION_MS, ${cfg.durationMs}L) ?: ${cfg.durationMs}L
        val targetMode = intent?.getStringExtra(EXTRA_TARGET_MODE) ?: "${cfg.targetMode}"
        val targetPackages = intent?.getStringArrayListExtra(EXTRA_PACKAGES)
            ?: arrayListOf(${pkgsLiteral})

        startBlackholeVpn(targetMode, targetPackages)

        stopRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            stopBlackholeVpn()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
        stopRunnable = r
        mainHandler.postDelayed(r, durationMs)
        return START_NOT_STICKY
    }

    private fun ensureForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "SwiftGate VPN Net Pulse",
                    NotificationManager.IMPORTANCE_LOW
                )
                nm?.createNotificationChannel(ch)
            }
            val n = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("SwiftGate Net-Off Pulse")
                .setContentText("Temporary 1s net block active...")
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        }
    }

    private fun startBlackholeVpn(targetMode: String, targetPackages: List<String>) {
        if (vpnInterface != null) return
        val builder = Builder()
            .setSession("SwiftGate 1s Net-Off")
            .addAddress("10.250.250.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("10.250.250.1")
            .addAddress("fd00:250::2", 128)
            .addRoute("::", 0)

        if (targetMode == "SELECTED_APPS" && targetPackages.isNotEmpty()) {
            // Sirf chune hue APKs ka net block karega (baaki phone chalu rahega)
            for (pkg in targetPackages) {
                try {
                    builder.addAllowedApplication(pkg.trim())
                } catch (_: Exception) {}
            }
        } else {
            // Poora phone ka net block karega (khud is app ko chhodkar)
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {}
        }

        vpnInterface = builder.establish()
    }

    private fun stopBlackholeVpn() {
        try {
            vpnInterface?.close()
        } catch (_: Exception) {}
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
        const val REQ_VPN_PERMISSION = 4098
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_TARGET_MODE = "target_mode"
        const val EXTRA_PACKAGES = "target_packages"

        fun requestPermissionIfNeeded(activity: Activity): Boolean {
            val prep = VpnService.prepare(activity) ?: return true
            activity.startActivityForResult(prep, REQ_VPN_PERMISSION)
            return false
        }

        fun triggerPulse(
            context: Context,
            durationMs: Long = ${cfg.durationMs}L,
            targetMode: String = "${cfg.targetMode}",
            packages: ArrayList<String> = arrayListOf(${pkgsLiteral})
        ) {
            if (VpnService.prepare(context) != null) return
            val i = Intent(context, NetPulseVpnService::class.java).apply {
                putExtra(EXTRA_DURATION_MS, durationMs)
                putExtra(EXTRA_TARGET_MODE, targetMode)
                putStringArrayListExtra(EXTRA_PACKAGES, packages)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}
`;
}

export function generateManifestSnippet(): string {
  return `<!-- GitHub Path: app/src/main/AndroidManifest.xml -->
<!-- <manifest> ke andar ye permissions add karein: -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />

<!-- <application> ke andar ye service add karein: -->
<service
    android:name=".NetPulseVpnService"
    android:permission="android.permission.BIND_VPN_SERVICE"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    <intent-filter>
        <action android:name="android.net.VpnService" />
    </intent-filter>
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="temporary_vpn_pulse" />
</service>`;
}

export function generateWatcherHookSnippet(cfg: VpnPulseConfig): string {
  return `// 1) MainActivity.kt (onCreate ya button click me VPN permission lene ke liye):
NetPulseVpnService.requestPermissionIfNeeded(this)

// 2) OrderWatcherService.kt (jab order REJECT (R) ho ya A se match na kare):
// Jahan "NOT match" / reject handle hota hai wahan ye line call karein:
NetPulseVpnService.triggerPulse(
    context = this,
    durationMs = ${cfg.durationMs}L,
    targetMode = "${cfg.targetMode}"
)`;
}
