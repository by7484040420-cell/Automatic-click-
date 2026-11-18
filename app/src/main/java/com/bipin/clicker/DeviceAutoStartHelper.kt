package com.bipin.clicker

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Kuch phone brands (Xiaomi/MIUI, Vivo, Oppo, OnePlus, etc.) apni khud ki extra
 * "Autostart" / "Background pop-up" / "Battery saver" settings rakhte hain, jinke
 * bina Accessibility Service ON hone ke baad bhi background mein kaam karna band
 * kar deti hai (phone use ki jaanewali RAM/battery bachane ke liye service ko kill
 * kar deta hai).
 *
 * Google ka koi official/common tareeka nahi hai in settings ko seedha kholne ka -
 * har brand ka apna alag (undocumented) settings screen hota hai. Ye helper un
 * known screens ko try karta hai; agar koi match na ho ya screen na khule
 * (naya phone model, MIUI update se path badal gaya, etc.), to normal App Info
 * screen khol deta hai jahan se user khud dhoondh sakta hai.
 *
 * NOTE: Ye sirf USER ko sahi settings screen tak PAHUNCHATA hai - permission
 * khud-ba-khud ON nahi kar sakta, wo user ko khud tap karna padega (Android
 * security rule hai, koi app isse bypass nahi kar sakta).
 */
object DeviceAutoStartHelper {

    private const val TAG = "AutoStartHelper"

    /**
     * Manufacturer-specific "autostart" ya "background permission" screen kholne
     * ki koshish karta hai. Return true agar koi screen safaltapoorvak khuli,
     * false agar kuch bhi nahi khul paya (caller fallback dikha sakta hai).
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()

        val candidateIntents: List<Intent> = when {
            manufacturer.contains("xiaomi") -> listOf(
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                ),
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    setClassName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.permissions.PermissionsEditorActivity"
                    )
                    putExtra("extra_pkgname", context.packageName)
                }
            )

            manufacturer.contains("vivo") -> listOf(
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                ),
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                    )
                )
            )

            manufacturer.contains("oppo") -> listOf(
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                ),
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.startupapp.StartupAppListActivity"
                    )
                )
            )

            manufacturer.contains("oneplus") -> listOf(
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.oneplus.security",
                        "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                    )
                )
            )

            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                )
            )

            manufacturer.contains("samsung") -> listOf(
                // Samsung: usually battery optimisation exemption is enough, no separate autostart screen.
                Intent().setComponent(
                    android.content.ComponentName(
                        "com.samsung.android.lool",
                        "com.samsung.android.sm.ui.battery.BatteryActivity"
                    )
                )
            )

            else -> emptyList()
        }

        for (intent in candidateIntents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "Screen not found, trying next option: ${intent.component}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not open: ${intent.component}", e)
            }
        }

        return false
    }

    /** Battery optimisation se app ko exempt karne ki settings screen kholta hai (sab phones par kaam karta hai). */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            openAppInfoSettings(context)
        }
    }

    /** Har phone par kaam karne waala fallback - normal "App Info" screen kholta hai. */
    fun openAppInfoSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * Ek hi call mein: sabse pehle brand-specific autostart screen try karta hai,
     * agar wo na khule to seedha App Info screen khol deta hai. PermissionSetupActivity
     * ke button se isi function ko call karo.
     */
    fun openBestAvailableAutoStartScreen(context: Context) {
        val opened = openAutoStartSettings(context)
        if (!opened) {
            openAppInfoSettings(context)
        }
    }
}
