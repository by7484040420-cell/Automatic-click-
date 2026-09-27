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
        private val ACCEPT_WORDS = listOf(
            "accept", "confirm", "pickup", "pick up", "go", "ok", "yes", "start", "grab",
            "take", "take order", "accept order", "accept now"
        )
        private const val CLICK_COOLDOWN_MS = 3000L
        private const val CHANNEL_ID = "bipin_clicker_status"
        private const val NOTIFICATION_ID = 501
    }

    private var lastClickTime = 0L
    private var lastClickedSignature: String? = null

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
        }.trim().lowercase()

        val compact = raw.replace(Regex("[^a-z0-9]+"), "")
        val isAcceptText = ACCEPT_WORDS.any { word ->
            raw.contains(word) || compact.contains(word.replace(" ", ""))
        }

        if (isAcceptText && clickNodeOrParent(node)) return true

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null && findAndClickAcceptButton(child)) return true
        }
        return false
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 7) {
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = current.parent
            depth++
        }
        return swipeAcrossNode(widestAncestor(node))
    }

    private fun widestAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var best = node
        var bestWidth = boundsWidth(node)
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 6) {
            val width = boundsWidth(current)
            if (width > bestWidth) {
                bestWidth = width
                best = current
            }
            current = current.parent
            depth++
        }
        return best
    }

    private fun boundsWidth(node: AccessibilityNodeInfo): Int {
        val r = Rect()
        node.getBoundsInScreen(r)
        return r.width()
    }

    private fun swipeAcrossNode(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false

        val startX = bounds.left + bounds.height() / 2f
        val endX = (bounds.right - bounds.height() / 4f).coerceAtLeast(startX + 1f)
        val y = bounds.centerY().toFloat()
        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() = Unit
}
