package com.bipin.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/** Accessibility service used to read matching order screens and press an accept action. */
class OrderWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "OrderWatcherService"
        // Do not include "pickup" here: PICKUP is an address label, not the
        // accept control. Matching it was causing the wrong screen area to be used.
        private val ACCEPT_WORDS = listOf(
            "accept", "confirm", "take order", "accept order", "accept now"
        )
        private const val CLICK_COOLDOWN_MS = 3000L
        private const val CHANNEL_ID = "bipin_clicker_status"
        private const val NOTIFICATION_ID = 501
    }

    private var lastClickTime = 0L
    private var lastClickedSignature: String? = null
    private val scanHandler = Handler(Looper.getMainLooper())
    private var scanGeneration = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        showOnNotification()
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun showOnNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "BIPIN Clicker Status", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_on)
            .setContentTitle("BIPIN Clicker: ON")
            .setContentText("Order screen reading is active")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> Unit
            else -> return
        }

        if (AreaPrefs.isPaused(applicationContext)) return

        // Order cards often appear in stages. The first accessibility event can
        // arrive before the address/button text is attached to the view tree.
        // Scan again shortly afterwards instead of declaring the order unreadable.
        val generation = ++scanGeneration
        scanHandler.post { if (generation == scanGeneration) scanCurrentScreens() }
        scanHandler.postDelayed { if (generation == scanGeneration) scanCurrentScreens() }, 250L
        scanHandler.postDelayed { if (generation == scanGeneration) scanCurrentScreens() }, 700L
    }

    private fun scanCurrentScreens() {
        if (AreaPrefs.isPaused(applicationContext)) return

        try {
            val roots = LinkedHashSet<AccessibilityNodeInfo>()
            rootInActiveWindow?.let { roots.add(it) }

            // Some apps render the order in a secondary accessibility window.
            windows?.forEach { window ->
                try { window.root?.let { roots.add(it) } } catch (_: Exception) { }
            }

            for (root in roots) {
                try {
                    if (root.packageName == packageName) continue

                    val allTexts = mutableListOf<String>()
                    collectText(root, allTexts)
                    val screenText = allTexts.joinToString(" | ")
                    if (screenText.isBlank()) continue

                    val matched = AreaPrefs.matchesScreenText(applicationContext, screenText)
                    Log.d(TAG, "scan package=${root.packageName} text=${screenText.take(800)} matched=$matched")
                    if (!matched) continue

                    val signature = "${root.packageName}:${normaliseForSignature(screenText)}"
                    val now = System.currentTimeMillis()
                    if (signature == lastClickedSignature && now - lastClickTime < CLICK_COOLDOWN_MS) continue

                    if (findAndClickAcceptButton(root)) {
                        lastClickedSignature = signature
                        lastClickTime = now
                        Log.d(TAG, "Accepted matching order in ${root.packageName}")
                        showDebugToast("Bipin Clicker: Order + Accept action")
                    } else {
                        Log.d(TAG, "Matching order found, accept control not clickable yet")
                    }
                } catch (inner: Exception) {
                    Log.e(TAG, "Error scanning accessibility window", inner)
                } finally {
                    try { root.recycle() } catch (_: Exception) { }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while scanning screen", e)
        }
    }

    private fun normaliseForSignature(value: String): String =
        value.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun showDebugToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        }
        node.paneTitle?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) collectText(child, out)
        }
    }

    private fun findAndClickAcceptButton(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val raw = buildString {
            node.text?.toString()?.let { append(it).append(' ') }
            node.contentDescription?.toString()?.let { append(it).append(' ') }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.let { append(it).append(' ') }
            }
            node.viewIdResourceName?.let { append(it).append(' ') }
        }.trim().lowercase()

        val compact = raw.replace(Regex("[^a-z0-9]+"), "")
        val isAcceptText = ACCEPT_WORDS.any { word ->
            raw.contains(word) || compact.contains(word.replace(" ", ""))
        }

        if (isAcceptText) {
            // If the app shows "Accept in 5s", the control is intentionally
            // unavailable for a few seconds. Wait for that countdown and scan
            // again rather than trying to press it too early.
            val countdown = Regex("""accept\s+in\s+(\d+)\s*s""")
                .find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()

            if (countdown != null) {
                val delay = ((countdown + 1L) * 1000L).coerceAtMost(7000L)
                scheduleAcceptRetry(delay)
                return false
            }

            if (clickNodeOrParent(node)) return true
            if (swipeAcceptControl(node)) return true
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null && findAndClickAcceptButton(child)) return true
        }
        return false
    }

    private fun scheduleAcceptRetry(delayMs: Long) {
        scanHandler.postDelayed({
            if (!AreaPrefs.isPaused(applicationContext)) {
                scanCurrentScreens()
            }
        }, delayMs)
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0

        while (current != null && depth < 8) {
            if (current.isEnabled && current.isClickable) {
                try {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.d(TAG, "Accept: ACTION_CLICK succeeded")
                        return true
                    }
                } catch (_: Exception) { }
            }
            current = try { current.parent } catch (_: Exception) { null }
            depth++
        }
        return false
    }

    private fun swipeAcceptControl(node: AccessibilityNodeInfo): Boolean {
        // Prefer the nearest clickable ancestor because the text node itself
        // may cover only the words "Accept in 5s", while the actual control is
        // the large blue slider around it.
        var target: AccessibilityNodeInfo? = node
        var depth = 0
        while (target != null && depth < 8) {
            if (target.isClickable && target.isEnabled) break
            target = try { target.parent } catch (_: Exception) { null }
            depth++
        }

        if (target == null) target = node

        val bounds = Rect()
        try {
            target.getBoundsInScreen(bounds)
        } catch (_: Exception) {
            return false
        }

        if (bounds.width() < 100 || bounds.height() < 40) return false

        // The Porter-style control in the screenshot is a horizontal slider:
        // start near the left handle and drag to the right side of the blue bar.
        val y = bounds.centerY().toFloat()
        val startX = bounds.left + (bounds.height() * 0.50f)
        val endX = bounds.right - (bounds.height() * 0.35f)

        if (endX <= startX + 20f) return false

        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    650L
                )
            )
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Accept slider gesture completed")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Accept slider gesture cancelled")
                }
            },
            null
        )

        Log.d(TAG, "Accept slider gesture dispatched=$dispatched bounds=$bounds")
        return dispatched
    }

    override fun onInterrupt() = Unit
}
