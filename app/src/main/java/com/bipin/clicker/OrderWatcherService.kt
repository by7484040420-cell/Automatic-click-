package com.bipin.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat

/** Reads the live order screen and tries the accept control as soon as it is actionable. */
class OrderWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "OrderWatcherService"
        private const val CHANNEL_ID = "bipin_clicker_status"
        private const val NOTIFICATION_ID = 501

        private const val FAST_RETRY_MS = 100L
        private const val FAST_RETRY_WINDOW_MS = 5200L
        private const val CLICK_COOLDOWN_MS = 1200L

        private val ACCEPT_WORDS = listOf(
            "accept", "confirm", "take order", "accept order", "accept now"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var retryUntil = 0L
    private var retryRunning = false
    private var lastActionAt = 0L
    private var lastSignature: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        showOnNotification()
        setStatus("READY")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> scanNow()
            else -> Unit
        }
    }

    private fun scanNow() {
        if (AreaPrefs.isPaused(applicationContext)) return

        val roots = LinkedHashSet<AccessibilityNodeInfo>()
        try {
            windows?.forEach { window ->
                window.root?.let { roots.add(it) }
            }
        } catch (_: Exception) {
        }

        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
        if (roots.isEmpty()) return

        for (root in roots) {
            try {
                if (root.packageName == packageName) continue

                val texts = mutableListOf<String>()
                collectText(root, texts)
                val screenText = texts.joinToString(" | ")

                if (screenText.isBlank()) continue
                if (!AreaPrefs.matchesScreenText(applicationContext, screenText)) {
                    DebugOverlay.update(
                        applicationContext,
                        "Scan (${root.packageName}):\n\"${screenText.take(150)}\"\n-> NOT match"
                    )
                    continue
                }

                setStatus("ORDER FOUND")

                val signature = "${root.packageName}:${screenText.hashCode()}"
                val now = System.currentTimeMillis()

                // Do not suppress the very first attempt on a new order.
                if (signature != lastSignature || now - lastActionAt >= CLICK_COOLDOWN_MS) {
                    if (findAndAccept(root)) {
                        lastSignature = signature
                        lastActionAt = now
                        setStatus("ORDER UTHA RAHA")
                        showDebugToast("Bipin Clicker: Order uthane ka action")
                        DebugOverlay.update(applicationContext, "ORDER UTHA RAHA \u2705 (action bheja gaya)")
                    } else {
                        // The order card may appear before the slider becomes
                        // actionable. Poll rapidly for a few seconds.
                        startFastRetry()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "scan error", e)
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Important: "Accept in 5s" is NOT treated as a reason to wait 5 seconds.
     * We try the current control immediately and then retry every 100ms.
     * If the target app keeps the control disabled until its own countdown
     * expires, Android will simply reject the early gesture; the next retry
     * catches it as soon as it becomes actionable.
     */
    private fun startFastRetry() {
        retryUntil = maxOf(
            retryUntil,
            System.currentTimeMillis() + FAST_RETRY_WINDOW_MS
        )

        if (retryRunning) return
        retryRunning = true

        handler.post(object : Runnable {
            override fun run() {
                if (AreaPrefs.isPaused(applicationContext)) {
                    retryRunning = false
                    setStatus("OFF")
                    return
                }

                if (System.currentTimeMillis() > retryUntil) {
                    retryRunning = false
                    setStatus("READY")
                    return
                }

                scanNow()
                handler.postDelayed(this, FAST_RETRY_MS)
            }
        })
    }

    private fun findAndAccept(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val raw = nodeText(node).lowercase()
        val compact = raw.replace(Regex("[^a-z0-9]+"), "")

        val isAcceptNode = ACCEPT_WORDS.any { word ->
            raw.contains(word) ||
                compact.contains(word.replace(Regex("[^a-z0-9]+"), ""))
        }

        // Do NOT wait for "Accept in 5s". Try the live control now.
        // If it is disabled, the action fails and fast retry will try again.
        //
        // IMPORTANT: is button ka design (chhota circle + lamba pill + "Accept in Ns")
        // ek SLIDE/DRAG control hai, simple tap-button nahi. Isliye SWIPE ko PEHLE
        // try karo. Agar sirf click try karte, to Android kabhi-kabhi "click safal (true)"
        // bol deta hai jabki Porter app ke andar asal me kuch hota hi nahi (slider sirf
        // real drag/touch-move events sunta hai, synthetic click event nahi) - isse
        // service ko lagta hai "ho gaya" jabki order abhi bhi waisa hi pada hai.
        if (isAcceptNode) {
            val swiped = swipeAcceptControl(node)
            DebugOverlay.update(
                applicationContext,
                "Accept word mila. Swipe try kiya -> dispatched=$swiped"
            )
            if (swiped) return true

            val clicked = clickNodeOrParent(node)
            DebugOverlay.update(
                applicationContext,
                "Swipe nahi ho paya, CLICK try kiya -> result=$clicked"
            )
            if (clicked) return true
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null && findAndAccept(child)) return true
        }

        return false
    }

    private fun nodeText(node: AccessibilityNodeInfo): String {
        return buildString {
            node.text?.toString()?.let { append(it).append(' ') }
            node.contentDescription?.toString()?.let { append(it).append(' ') }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                node.hintText?.toString()?.let { append(it).append(' ') }
            }
            node.viewIdResourceName?.let { append(it).append(' ') }
        }.trim()
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0

        while (current != null && depth < 8) {
            if (current.isEnabled && current.isClickable) {
                try {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.d(TAG, "ACTION_CLICK succeeded")
                        return true
                    }
                } catch (_: Exception) {
                }
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

        // Agar andar hi koi chhota circle/thumb-jaisa child mile (roughly square,
        // pill ke height ke barabar), to wahin se shuru karo - asli slider isi
        // point par touch-down expect karta hai, sirf pill ke left edge par nahi.
        val thumb = findThumbChild(target, bounds)
        val y = (thumb?.centerY() ?: bounds.centerY()).toFloat()
        val radius = (bounds.height() * 0.43f).coerceAtLeast(22f)
        val startX = (thumb?.centerX()?.toFloat()) ?: (bounds.left + radius)
        // Poore right edge ke bahut kareeb tak jao (chhota sa margin), taaki
        // slider ka "completion threshold" (aksar ~85-95%) paar ho jaaye.
        val endX = bounds.right - (bounds.height() * 0.15f).coerceAtLeast(8f)

        if (endX <= startX + 30f) return false

        // Seedha ek line ki jagah beech mein ek extra point daalo - real
        // human drag jaisa lagta hai, kuch custom sliders sirf single straight
        // jump ko "fling"/invalid samajh kar ignore kar dete hain.
        val midX = startX + (endX - startX) * 0.5f
        val path = Path().apply {
            moveTo(startX, y)
            lineTo(midX, y)
            lineTo(endX, y)
        }

        // Duration thoda badhaya (180ms -> 380ms) - bahut tez synthetic swipe
        // ko kuch sliders "invalid gesture" maan kar reject kar dete hain.
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    380L
                )
            )
            .build()

        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Accept swipe completed")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Accept swipe cancelled")
                }
            },
            null
        )

        Log.d(TAG, "Accept swipe dispatched=$dispatched bounds=$bounds startX=$startX endX=$endX")
        DebugOverlay.update(
            applicationContext,
            "Swipe: bounds=$bounds\nstartX=${startX.toInt()} endX=${endX.toInt()} dispatched=$dispatched"
        )
        return dispatched
    }

    /** Slider track ke andar ek chhota, lagbhag chौkore (square) child dhoondta hai - wahi asal "thumb"/drag handle hota hai. */
    private fun findThumbChild(container: AccessibilityNodeInfo, containerBounds: Rect): Rect? {
        var best: Rect? = null
        var bestDiff = Int.MAX_VALUE

        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 4) return
            val r = Rect()
            try {
                node.getBoundsInScreen(r)
            } catch (_: Exception) {
                return
            }
            if (r.width() in 30..containerBounds.height() * 2 &&
                r.height() in 20..containerBounds.height() &&
                r.left <= containerBounds.left + containerBounds.height()
            ) {
                val diff = kotlin.math.abs(r.width() - r.height())
                if (diff < bestDiff) {
                    bestDiff = diff
                    best = Rect(r)
                }
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                visit(child, depth + 1)
            }
        }

        visit(container, 0)
        return best
    }

    private fun findSliderLikeAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
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

            if (width >= 200 && height >= 40 && ratio >= 2.3f) {
                val score = width + if (current.isClickable) 10000 else 0
                if (score > bestScore) {
                    best = current
                    bestScore = score
                }
            }

            current = try { current.parent } catch (_: Exception) { null }
            depth++
        }

        return best
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

    private fun setStatus(status: String) {
        getSharedPreferences("clicker_live_status", MODE_PRIVATE)
            .edit()
            .putString("status", status)
            .apply()
    }

    private fun showDebugToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showOnNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "BIPIN Clicker Status",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_on)
            .setContentTitle("BIPIN Clicker: ON")
            .setContentText("Live order reading is active")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        setStatus("OFF")
        DebugOverlay.hide()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }
}
