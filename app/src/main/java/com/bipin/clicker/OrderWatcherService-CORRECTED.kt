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

/**
 * Reads the live order screen and tries the accept control as soon as it is actionable.
 *
 * v5 FIXES:
 * 1) Runtime check - service sach mein gesture kar sakta hai ya nahi (canPerformGestures)
 * 2) dispatchGesture=false hone par SAFA warning (Magnification gesture conflict)
 * 3) Two-stroke press-hold-drag gesture - Porter jaise apps ACTION_DOWN ko theek se
 *    register karte hain, isliye pehle chhota hold phir drag
 * 4) Swipe ke baad verify + auto-retry loop (event ka intezaar nahi karta)
 */
class OrderWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "OrderWatcherService"
        private const val CHANNEL_ID = "bipin_clicker_status"
        private const val NOTIFICATION_ID = 501

        private const val FAST_RETRY_MS = 100L
        private const val FAST_RETRY_WINDOW_MS = 5200L
        private const val CLICK_COOLDOWN_MS = 1200L

        private const val VERIFY_INTERVAL_MS = 400L
        private const val VERIFY_MAX_ATTEMPTS = 18

        private val ACCEPT_WORDS = listOf(
            "accept", "confirm", "take order", "accept order", "accept now"
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var retryUntil = 0L
    private var retryRunning = false
    private var lastActionAt = 0L
    private var lastSignature: String? = null

    private var verifyAttemptsLeft = 0
    private var verifySwipesDone = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        showOnNotification()
        setStatus("READY")

        // FIX (naya): runtime par check karo ki gesture injection ka permission
        // SAHI SE mila hai ya nahi. Agar false hai to service chal to rahi hai
        // par touch kabhi nahi kar payegi.
        try {
            val canGesture = serviceInfo?.canPerformGestures == true
            val canRead = serviceInfo?.canRetrieveWindowContent == true
            reportDebug("Service connected.\ncanPerformGestures=$canGesture\ncanRetrieveWindowContent=$canRead")
            if (!canGesture) {
                Toast.makeText(
                    this,
                    "GALTI: Gesture permission nahi mila! Accessibility OFF karke dubara ON karein",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "serviceInfo check failed", e)
        }
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

        val roots = currentRoots()
        if (roots.isEmpty()) return

        for (root in roots) {
            try {
                if (root.packageName == packageName) continue

                val texts = mutableListOf<String>()
                collectText(root, texts)
                val screenText = texts.joinToString(" | ")

                if (screenText.isBlank()) continue
                if (!AreaPrefs.matchesScreenText(applicationContext, screenText)) {
                    reportDebug(
"Scan (${root.packageName}):\n\"${screenText.take(150)}\"\n-> NOT match"
                    )
                    continue
                }

                setStatus("ORDER FOUND")

                val signature = "${root.packageName}:${screenText.hashCode()}"
                val now = System.currentTimeMillis()

                if (verifyAttemptsLeft > 0 && signature == lastSignature) {
                    continue
                }

                // Every newly-seen order gets an immediate action attempt.
                // Do not use the screen-text hash as a hard gate: the countdown
                // text changes every second and the Accept control may only become
                // actionable after a short delay.
                if (signature != lastSignature || now - lastActionAt >= 350L) {
                    if (findAndAccept(root)) {
                        lastSignature = signature
                        lastActionAt = now
                        setStatus("ORDER UTHA RAHA")
                        showDebugToast("Bipin Clicker: Order uthane ka action")
                        reportDebug("ORDER UTHA RAHA - live Accept control par gesture bheja")
                        startVerifyRetry()
                    } else {
                        reportDebug("Accept control nahi mila - live screen ko fast retry kar raha hoon")
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

    // ------------------------------------------------------------------
    // Swipe ke baad verify + auto-retry (event par depend nahi karta)
    // ------------------------------------------------------------------
    private val verifyRunnable = object : Runnable {
        override fun run() {
            if (AreaPrefs.isPaused(applicationContext)) {
                verifyAttemptsLeft = 0
                setStatus("OFF")
                return
            }
            if (verifyAttemptsLeft-- <= 0) {
                setStatus("READY")
                reportDebug("Verify khatam - sab tries fail, order accept nahi hua")
                return
            }

            val roots = currentRoots()
            var stillAcceptVisible = false
            var matchRoot: AccessibilityNodeInfo? = null

            for (root in roots) {
                try {
                    if (root.packageName == packageName) continue
                    val texts = mutableListOf<String>()
                    collectText(root, texts)
                    val screenText = texts.joinToString(" | ")
                    if (screenText.isBlank()) continue

                    if (!AreaPrefs.matchesScreenText(applicationContext, screenText)) continue

                    if (screenHasAcceptWord(screenText)) {
                        stillAcceptVisible = true
                        matchRoot = root
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "verify error", e)
                } finally {
                    if (matchRoot !== root) {
                        try { root.recycle() } catch (_: Exception) {}
                    }
                }
            }

            if (!stillAcceptVisible) {
                verifyAttemptsLeft = 0
                setStatus("ACTION RESULT UNKNOWN")
                reportDebug(
                    "Accept control screen se gayab ho gaya. " +
                    "Gesture complete hua, lekin Accessibility se acceptance confirm nahi ki ja sakti."
                )
                matchRoot?.let { try { it.recycle() } catch (_: Exception) {} }
                return
            }

            matchRoot?.let { r ->
                try {
                    verifySwipesDone++
                    val done = if (verifySwipesDone % 4 == 0) {
                        clickNodeOrParent(r) || findAndAccept(r)
                    } else {
                        findAndAccept(r)
                    }
                    lastActionAt = System.currentTimeMillis()
                    reportDebug(
                        "Verify retry #$verifySwipesDone -> action=$done " +
                        "(swipe ${if (verifySwipesDone % 4 == 0) "+click" else "only"})"
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "verify retry error", e)
                } finally {
                    try { r.recycle() } catch (_: Exception) {}
                }
            }

            handler.postDelayed(this, VERIFY_INTERVAL_MS)
        }
    }

    private fun startVerifyRetry() {
        verifyAttemptsLeft = VERIFY_MAX_ATTEMPTS
        verifySwipesDone = 0
        handler.removeCallbacks(verifyRunnable)
        handler.postDelayed(verifyRunnable, VERIFY_INTERVAL_MS)
    }

    private fun screenHasAcceptWord(screenText: String): Boolean {
        val raw = screenText.lowercase()
        // If the target app has already moved to a missed/accepted state,
        // do not treat the old card text as an active Accept control.
        if (raw.contains("missed this order") ||
            raw.contains("order missed") ||
            raw.contains("already accepted") ||
            raw.contains("order accepted")) {
            return false
        }

        val compact = raw.replace(Regex("[^a-z0-9]+"), "")
        return ACCEPT_WORDS.any { word ->
            raw.contains(word) ||
                compact.contains(word.replace(Regex("[^a-z0-9]+"), ""))
        }
    }

    private fun currentRoots(): List<AccessibilityNodeInfo> {
        val roots = LinkedHashSet<AccessibilityNodeInfo>()
        try {
            windows?.forEach { window ->
                window.root?.let { roots.add(it) }
            }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots.add(it) }
        return roots.toList()
    }

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

        val raw = nodeText(node).lowercase().trim()
        val compact = raw.replace(Regex("[^a-z0-9]+"), "")

        // Match the actual Accept control, including the countdown label.
        // "pickup" / "drop" are deliberately NOT accepted as matches.
        val isAcceptNode = ACCEPT_WORDS.any { word ->
            raw.contains(word) ||
                compact.contains(word.replace(Regex("[^a-z0-9]+"), ""))
        }

        if (isAcceptNode && !raw.contains("pickup") && !raw.contains("drop")) {
            // The slider is the primary action. A click is only a fallback.
            val swiped = swipeAcceptControl(node)
            reportDebug("Accept control mila. SWIPE dispatched=$swiped")
            if (swiped) return true

            val clicked = clickNodeOrParent(node)
            reportDebug("SWIPE dispatch nahi hua; CLICK fallback=$clicked")
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

    /**
     * FIX (naya): TWO-STROKE press-hold-drag.
     *
     * Pehle ek chhota stroke: thumb par touch-down + 8px hold (250ms).
     * Phir continueStroke: wahin se poore right tak drag (300ms).
     *
     * Kyun? Porter ka slider ACTION_DOWN ke turant baad hi tez move ko
     * "fling/invalid" maan kar discard kar deta hai. Real ungli pehle dabti
     * hai phir rukti hai phir khisakti hai - wahi pattern copy kiya hai.
     */
    private fun swipeAcceptControl(node: AccessibilityNodeInfo): Boolean {
        val target = findSliderLikeAncestor(node) ?: return false
        val bounds = Rect()

        try {
            target.getBoundsInScreen(bounds)
        } catch (_: Exception) {
            return false
        }

        if (bounds.width() < 200 || bounds.height() < 40) return false

        // The screenshot's control is a wide horizontal slider. Prefer the
        // actual thumb child; otherwise use a point about one thumb-radius
        // inside the left edge. Do NOT start at the text label.
        val thumb = findThumbChild(target, bounds)
        val thumbCenterX = thumb?.centerX()?.toFloat()
            ?: (bounds.left + bounds.height() * 0.82f)
        val thumbCenterY = thumb?.centerY()?.toFloat()
            ?: bounds.centerY().toFloat()

        val startX = thumbCenterX.coerceIn(
            bounds.left + bounds.height() * 0.55f,
            bounds.left + bounds.width() * 0.40f
        )
        val endX = (bounds.right - bounds.height() * 0.30f).toFloat()
        val y = thumbCenterY.coerceIn(
            bounds.top + bounds.height() * 0.25f,
            bounds.bottom - bounds.height() * 0.25f
        )

        if (endX <= startX + 80f) return false

        val path = Path().apply {
            moveTo(startX, y)
            lineTo(startX + 6f, y)
            lineTo(endX, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    420L
                )
            )
            .build()

        val canGesture = try {
            serviceInfo?.canPerformGestures == true
        } catch (_: Exception) {
            false
        }

        if (!canGesture) {
            reportDebug(
                "❌ Gesture permission missing. Accessibility service ko OFF karke ON karein."
            )
            return false
        }

        val dispatched = try {
            dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "Accept swipe completed")
                        reportDebug(
                            "SWIPE DONE\nbounds=$bounds\nstart=${startX.toInt()},${y.toInt()}\nend=${endX.toInt()}"
                        )
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "Accept swipe cancelled")
                        reportDebug(
                            "❌ SWIPE CANCELLED\nbounds=$bounds\nstart=${startX.toInt()},${y.toInt()}\nend=${endX.toInt()}"
                        )
                    }
                },
                null
            )
        } catch (e: Exception) {
            reportDebug("❌ dispatchGesture exception: ${e.message}")
            false
        }

        reportDebug(
            if (dispatched) {
                "SWIPE TRYING\nlive bounds=$bounds\nthumb=${thumb?.let { "${it.centerX()},${it.centerY() }"} ?: "fallback"}"
            } else {
                "❌ SWIPE REJECTED by Android"
            }
        )

        return dispatched
    }

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

    private fun reportDebug(message: String) {
        DebugOverlay.update(applicationContext, message)
        updateStatusNotification(message)
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

    private fun updateStatusNotification(message: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_on)
            .setContentTitle("BIPIN Clicker DEBUG")
            .setContentText(message.take(60))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.take(500)))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        verifyAttemptsLeft = 0
        setStatus("OFF")
        DebugOverlay.hide()
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }
}
