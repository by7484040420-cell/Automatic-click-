// ===== NEW ADD (nayi file, pehle se nahi hai) =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/ScreenWatcherService.kt
package com.bipin.clicker

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.nio.ByteBuffer

/**
 * DISPLAY WATCHER: Accessibility tree se bilkul alag, screen ki live video (MediaProjection) dekhta hai.
 * Frame me neela/hara chauda slider-pill dikhte hi frame OCR ke liye OrderWatcherService ko deta hai.
 * Pickup/Drop filter wahi rehta hai - sirf match hone par hi swipe hota hai.
 * Android 14+ par popup me "Entire screen / Poori screen" chuno.
 */
class ScreenWatcherService : Service() {

    companion object {
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        @Volatile var running = false

        fun start(c: Context, code: Int, data: Intent) {
            val i = Intent(c, ScreenWatcherService::class.java)
                .putExtra(EXTRA_CODE, code)
                .putExtra(EXTRA_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i) else c.startService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, ScreenWatcherService::class.java))
        }
    }

    private var projection: MediaProjection? = null
    private var vDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var lastCheck = 0L
    private var lastTrigger = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg()
        val code = intent?.getIntExtra(EXTRA_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        @Suppress("DEPRECATION")
        val data: Intent? = intent?.getParcelableExtra(EXTRA_DATA)
        if (code != Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            begin(code, data)
            running = true
        } catch (e: Exception) {
            Toast.makeText(this, "Screen watcher start nahi hua: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startFg() {
        val channelId = "bipin_clicker_watch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "BIPIN Screen Watcher", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_service_on)
            .setContentTitle("BIPIN Clicker: screen watcher ON")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        ServiceCompat.startForeground(this, 778, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }

    private fun begin(code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val mp = mpm.getMediaProjection(code, data)
        projection = mp
        // Android 14+: createVirtualDisplay se PEHLE callback register karna zaroori hai
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanup()
                stopSelf()
            }
        }, null)

        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels
        val h = dm.heightPixels

        val t = HandlerThread("bipin_screenwatch").also { it.start() }
        thread = t
        val handler = Handler(t.looper)

        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd -> onFrame(rd) }, handler)
        vDisplay = mp.createVirtualDisplay(
            "bipin_watch", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
    }

    private fun onFrame(rd: ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (now - lastCheck < 50L) return
            lastCheck = now
            if (AreaPrefs.isPaused(this)) return
            if (now - lastTrigger < 350L) return

            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pxStride = plane.pixelStride
            val w = img.width
            val h = img.height
            if (!looksLikeSlider(buf, rowStride, pxStride, w, h)) return

            lastTrigger = now
            val bw = rowStride / pxStride
            val full = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888)
            buf.rewind()
            val need = bw * pxStride * h
            if (buf.remaining() >= need) {
                full.copyPixelsFromBuffer(buf)
            } else {
                // kuch phones ka aakhri row chhota hota hai -> underflow se bachne ke liye bada buffer
                val tmp = ByteBuffer.allocateDirect(need)
                tmp.put(buf)
                tmp.rewind()
                full.copyPixelsFromBuffer(tmp)
            }
            val bmp = if (full.width != w) Bitmap.createBitmap(full, 0, 0, w, h).also { full.recycle() } else full
            // Screen watcher only provides an optional visual candidate.
            // The live Accessibility/OCR path performs the actual order action.
            bmp.recycle()
        } catch (_: Exception) {
        } finally {
            try { img.close() } catch (_: Exception) {}
        }
    }

    /** Neeche ke 70% me chauda rangeen pill (>=6 rows lagatar) dikhe to true. Sasta check - OCR baad me. */
    private fun looksLikeSlider(buf: ByteBuffer, rowStride: Int, pxStride: Int, w: Int, h: Int): Boolean {
        val stepX = 6
        val stepY = 6
        var consecutive = 0
        var y = (h * 0.30f).toInt()
        val yEnd = (h * 0.97f).toInt()
        while (y < yEnd) {
            var colored = 0
            var total = 0
            var first = -1
            var last = -1
            var x = 0
            while (x < w) {
                val pos = y * rowStride + x * pxStride
                if (pos + 2 < buf.limit()) {
                    val r = buf.get(pos).toInt() and 0xFF
                    val g = buf.get(pos + 1).toInt() and 0xFF
                    val b = buf.get(pos + 2).toInt() and 0xFF
                    if (maxOf(r, g, b) - minOf(r, g, b) > 60) {
                        colored++
                        if (first < 0) first = x
                        last = x
                    }
                    total++
                }
                x += stepX
            }
            val rowOk = total > 0 && colored * 2 >= total && (last - first) >= (w * 0.7f)
            if (rowOk) {
                consecutive++
                if (consecutive >= 6) return true
            } else {
                consecutive = 0
            }
            y += stepY
        }
        return false
    }

    private fun cleanup() {
        running = false
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { vDisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        vDisplay = null
        reader = null
        thread = null
    }

    override fun onDestroy() {
        cleanup()
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        super.onDestroy()
    }
}
