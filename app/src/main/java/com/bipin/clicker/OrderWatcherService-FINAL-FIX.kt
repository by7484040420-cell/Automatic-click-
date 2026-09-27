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
        // "pickup" is deliberately NOT included: it is an address label.
        private val ACCEPT_WORDS = listOf(
            "accept", "confirm", "take order", "accept order", "accept now"
        )
        private const val CLICK_COOLDOWN_MS = 3000L
        private const val CHANNEL_ID = "bipin_clicker_status"
        private const val NOTIFICATION_ID = 501
    }

    private var lastClickTime = 0L
    private var lastClickedSignature: String? = null
    private val retryHandler = Handler(Looper.getMainLooper())
    private var retryPending = false

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

        try {
            if (AreaPrefs.isPaused(applicationContext)) return

            val roots = LinkedHashSet<AccessibilityNodeInfo>()
            windows?.forEach { window ->
                window.root?.let { roots.add(it) }
            }
            if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
            if (roots.isEmpty()) return

            for (root in roots) {
                try {
                    if (root.packageName == packageName) continue

                    val allTexts = mutableListOf<String>()
                    collectText(root, allTexts)
                    val screenText = allTexts.joinToString(" | ")

                    if (screenText.isBlank()) continue

                    val matched = AreaPrefs.matchesScreenText(applicationContext, screenText)
                    Log.d(TAG, "scan package=${root.packageName} text=${screenText.take(500)} matched=$matched")

                    if (!matched) continue

                    val signature = "${root.packageName}:${screenText.hashCode()}"
                    val now = System.currentTimeMillis()
                    if (signature == lastClickedSignature && now - lastClickTime < CLICK_COOLDOWN_MS) continue

                    val clicked = findAndClickAcceptButton(root)
                    if (clicked) {
                        lastClickedSignature = signature
                        lastClickTime = now
                        Log.d(TAG, "Accepted matching order in ${root.packageName}")
                        showDebugToast("BipinClicker: Order match + accept action")
                    } else {
                        Log.d(TAG, "Matching order found, but no accessible accept button")
                        showDebugToast("BipinClicker: Order match mila, accept button nahi mila")
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

        // The screenshot shows "Accept in 5s". That button is intentionally
        // disabled until the countdown finishes. Do NOT report an accept action
        // at this point; schedule a fresh scan after the countdown.
        val countdown = Regex("""accept\s+in\s+(\d+)\s*s""")
            .find(raw)?.groupValues?.getOrNull(1)?.toLongOrNull()

        if (countdown != null) {
            scheduleAcceptRetry((countdown + 1L).coerceIn(1L, 7L) * 1000L)
        }

        val isAcceptText = ACCEPT_WORDS.any { word ->
            raw.contains(word) || compact.contains(word.replace(" ", ""))
        }

        // If countdown text is still present, never try to click/drag it early.
        if (countdown != null) {
            // Still inspect children because some apps expose the real control
            // separately from the "Accept in Ns" label.
        } else if (isAcceptText) {
            // First try a genuine accessibility click on the actual clickable
            // control. If that does not work, use the slider gesture.
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
        if (retryPending) return
        retryPending = true

        retryHandler.postDelayed({
            retryPending = false
            if (AreaPrefs.isPaused(applicationContext)) return@postDelayed

            try {
                val roots = LinkedHashSet<AccessibilityNodeInfo>()
                windows?.forEach { window ->
                    window.root?.let { roots.add(it) }
                }
                if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }

                for (root in roots) {
                    try {
                        val texts = mutableListOf<String>()
                        collectText(root, texts)
                        val screenText = texts.joinToString(" | ")
                        if (screenText.isNotBlank() &&
                            AreaPrefs.matchesScreenText(applicationContext, screenText)) {
                            findAndClickAcceptButton(root)
                        }
                    } finally {
                        try { root.recycle() } catch (_: Exception) { }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Accept retry scan failed", e)
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
                        Log.d(TAG, "Accept ACTION_CLICK dispatched")
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
        val target = findSliderLikeAncestor(node) ?: return false
        val bounds = Rect()

        try {
            target.getBoundsInScreen(bounds)
        } catch (_: Exception) {
            return false
        }

        if (bounds.width() < 200 || bounds.height() < 40) return false

        // Porter-style Accept control: drag the left circular handle across
        // the horizontal blue bar to the right.
        val y = bounds.centerY().toFloat()
        val handleRadius = bounds.height() * 0.45f
        val startX = bounds.left + handleRadius
        val endX = bounds.right - handleRadius * 0.55f

        if (endX <= startX + 40f) return false

        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    750L
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

    private fun findSliderLikeAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var best: AccessibilityNodeInfo? = null
        var bestWidth = 0
        var depth = 0

        while (current != null && depth < 8) {
            val r = Rect()
            try {
                current.getBoundsInScreen(r)
            } catch (_: Exception) {
                current = try { current.parent } catch (_: Exception) { null }
                depth++
                continue
            }

            val width = r.width()
            val height = r.height()
            val ratio = if (height > 0) width.toFloat() / height else 0f

            // Prefer a wide, short ancestor that looks like the horizontal
            // Accept slider rather than the whole order card/screen.
            if (width >= 200 && height >= 40 && ratio >= 2.3f && width > bestWidth) {
                best = current
                bestWidth = width
            }

            current = try { current.parent } catch (_: Exception) { null }
            depth++
        }

        return best
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        retryHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
