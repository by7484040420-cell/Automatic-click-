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
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd

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
    @Volatile private var rstGen = 0

    @Volatile private var keepAlive = false

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // IDLE/WARM-UP: service pehle se foreground me ready rakho (VPN abhi ON nahi hota)
        // taaki order aate hi net TURANT band ho (service start ka wait na lage).
        if (intent?.getBooleanExtra(EXTRA_IDLE, false) == true) {
            keepAlive = true
            try { ensureForeground("Net-Off ready") } catch (e: Throwable) {
                lastError = "foreground fail: ${e.javaClass.simpleName}"
            }
            return START_NOT_STICKY
        }

        val durationMs = (intent?.getLongExtra(EXTRA_DURATION_MS, AreaPrefs.getVpnDurationMs(this))
            ?: AreaPrefs.getVpnDurationMs(this)).coerceIn(250L, 5000L)
        val targetMode = intent?.getStringExtra(EXTRA_TARGET_MODE)
            ?: AreaPrefs.getVpnTargetMode(this)
        val targetPackages = intent?.getStringArrayListExtra(EXTRA_PACKAGES)
            ?: ArrayList(AreaPrefs.getVpnTargetPackages(this))

        pulse(durationMs, targetMode, targetPackages)
        return START_NOT_STICKY
    }

    /**
     * Net TURANT band (sabse pehle VPN establish), uske BAAD durationMs ka timer shuru.
     * Foreground notification ka kaam VPN ke baad hota hai taaki net band hone me deri na ho.
     */
    fun pulse(durationMs: Long, targetMode: String, targetPackages: List<String>) {
        try {
            startBlackholeVpn(targetMode, targetPackages)
        } catch (e: Throwable) {
            lastError = "vpn establish fail: ${e.javaClass.simpleName}"
        }

        // Timer ab shuru hota hai (net band hone ke BAAD)
        stopRunnable?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            stopBlackholeVpn()
            if (!keepAlive) {
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
        }
        stopRunnable = r
        mainHandler.postDelayed(r, durationMs)

        // Foreground (agar pehle se nahi hai) VPN ke baad
        if (!keepAlive) {
            try {
                ensureForeground("Net pulse active...")
            } catch (e: Throwable) {
                lastError = "foreground fail: ${e.javaClass.simpleName}"
            }
        }
    }

    private fun ensureForeground(text: String) {
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
                .setContentText(text)
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
                startRstResponder(fd)
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
        rstGen++
        activeSignature = ""
        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null
    }


    // ------------------------------------------------------------------
    // FAST-FAIL: packet chupchaap drop karne ki jagah TCP RST wapas bhejo.
    // Isse Porter app ko turant "net gaya" dikhta hai (airplane mode jaisa),
    // 2s ke andar hi order/connection toot jata hai.
    // Kuch bhi gadbad ho to bhi VPN band hone me koi rukawat nahi (50ms poll).
    // ------------------------------------------------------------------
    private fun startRstResponder(pfd: ParcelFileDescriptor) {
        val myGen = ++rstGen
        val t = Thread {
            try {
                val fd = pfd.fileDescriptor
                val buf = ByteArray(32767)
                val pf = StructPollfd()
                pf.fd = fd
                pf.events = OsConstants.POLLIN.toShort()
                val arr = arrayOf(pf)
                while (rstGen == myGen) {
                    try {
                        pf.revents = 0
                        val r = Os.poll(arr, 50)
                        if (r <= 0) continue
                        val n = Os.read(fd, buf, 0, buf.size)
                        if (n <= 0) continue
                        val rst = buildRst(buf, n) ?: continue
                        Os.write(fd, rst, 0, rst.size)
                    } catch (_: Throwable) {
                        if (rstGen != myGen) break
                        try { Thread.sleep(10) } catch (_: Throwable) {}
                    }
                }
            } catch (_: Throwable) {
            }
        }
        t.isDaemon = true
        t.name = "swiftgate-rst"
        t.start()
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u32(b: ByteArray, i: Int): Long =
        (u8(b, i).toLong() shl 24) or (u8(b, i + 1).toLong() shl 16) or
            (u8(b, i + 2).toLong() shl 8) or u8(b, i + 3).toLong()

    private fun put16(b: ByteArray, i: Int, v: Int) {
        b[i] = ((v shr 8) and 0xFF).toByte()
        b[i + 1] = (v and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, i: Int, v: Long) {
        b[i] = ((v shr 24) and 0xFF).toByte()
        b[i + 1] = ((v shr 16) and 0xFF).toByte()
        b[i + 2] = ((v shr 8) and 0xFF).toByte()
        b[i + 3] = (v and 0xFF).toByte()
    }

    private fun csum(b: ByteArray, off: Int, len: Int, initial: Long): Int {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += u16(b, i).toLong()
            i += 2
        }
        if (i < end) sum += (u8(b, i) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFFL) + (sum shr 16)
        return (sum.inv() and 0xFFFFL).toInt()
    }

    /** Aaye hue TCP packet (IPv4/IPv6) ke jawab me RST packet banata hai. TCP nahi ho to null. */
    private fun buildRst(p: ByteArray, n: Int): ByteArray? {
        if (n < 20) return null
        val ver = u8(p, 0) shr 4
        val tcpOff: Int
        val totalLen: Int
        if (ver == 4) {
            if (u8(p, 9) != 6) return null
            tcpOff = (u8(p, 0) and 0x0F) * 4
            totalLen = minOf(u16(p, 2), n)
        } else if (ver == 6) {
            if (n < 40 || u8(p, 6) != 6) return null
            tcpOff = 40
            totalLen = minOf(40 + u16(p, 4), n)
        } else {
            return null
        }
        if (totalLen < tcpOff + 20) return null

        val flags = u8(p, tcpOff + 13)
        if ((flags and 0x04) != 0) return null // RST ka jawab RST nahi

        val tcpHdrLen = (u8(p, tcpOff + 12) shr 4) * 4
        val payload = totalLen - tcpOff - tcpHdrLen
        if (payload < 0) return null

        val seq = u32(p, tcpOff + 4)
        val ack = u32(p, tcpOff + 8)
        val hasAck = (flags and 0x10) != 0
        val syn = (flags and 0x02) != 0
        val fin = (flags and 0x01) != 0

        val rSeq: Long
        val rAck: Long
        val rFlags: Int
        if (hasAck) {
            rSeq = ack
            rAck = 0L
            rFlags = 0x04
        } else {
            rSeq = 0L
            rAck = (seq + payload + (if (syn) 1 else 0) + (if (fin) 1 else 0)) and 0xFFFFFFFFL
            rFlags = 0x14
        }

        val out: ByteArray
        val tcp: Int
        var init = 0L
        if (ver == 4) {
            out = ByteArray(40)
            out[0] = 0x45
            out[8] = 64
            out[9] = 6
            put16(out, 2, 40)
            put16(out, 6, 0x4000)
            System.arraycopy(p, 16, out, 12, 4) // src = purana dst
            System.arraycopy(p, 12, out, 16, 4) // dst = purana src
            put16(out, 10, csum(out, 0, 20, 0L))
            tcp = 20
            var i = 12
            while (i < 20) { init += u16(out, i).toLong(); i += 2 }
        } else {
            out = ByteArray(60)
            out[0] = 0x60
            put16(out, 4, 20)
            out[6] = 6
            out[7] = 64
            System.arraycopy(p, 24, out, 8, 16)  // src = purana dst
            System.arraycopy(p, 8, out, 24, 16)  // dst = purana src
            tcp = 40
            var i = 8
            while (i < 40) { init += u16(out, i).toLong(); i += 2 }
        }
        init += 6L + 20L // protocol + tcp length (pseudo header)

        put16(out, tcp, u16(p, tcpOff + 2))     // src port = purana dst port
        put16(out, tcp + 2, u16(p, tcpOff))     // dst port = purana src port
        put32(out, tcp + 4, rSeq)
        put32(out, tcp + 8, rAck)
        out[tcp + 12] = 0x50
        out[tcp + 13] = rFlags.toByte()
        put16(out, tcp + 16, csum(out, tcp, 20, init))
        return out
    }

    override fun onDestroy() {
        if (instance === this) instance = null
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
        @Volatile var instance: NetPulseVpnService? = null
        const val EXTRA_IDLE = "idle"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_TARGET_MODE = "target_mode"
        const val EXTRA_PACKAGES = "target_packages"

        /** Service ko pehle se ready rakho (idle). Accessibility connect hote hi bulao. */
        fun warmUp(context: Context) {
            if (instance != null) return
            if (!AreaPrefs.isVpnPulseEnabled(context)) return
            if (VpnService.prepare(context) != null) return
            val i = Intent(context, NetPulseVpnService::class.java).apply {
                putExtra(EXTRA_IDLE, true)
            }
            try {
                context.startService(i)
            } catch (_: Exception) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(i)
                    }
                } catch (_: Exception) {
                }
            }
        }

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

            // FAST PATH: service pehle se ready hai -> seedha VPN ON (koi start-delay nahi)
            val warm = instance
            if (warm != null) {
                try {
                    warm.pulse(durationMs, targetMode, packages)
                    return true
                } catch (e: Exception) {
                    lastError = "warm pulse fail: ${e.javaClass.simpleName}"
                }
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
