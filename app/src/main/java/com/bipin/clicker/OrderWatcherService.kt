package com.bipin.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import androidx.core.app.NotificationCompat

/**
 * Reads the live order screen and tries the accept control as soon as it is actionable.
 */
class OrderWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "OrderWatcherService"
        private const val CHANNEL_ID = "bipin_clicker_status"
        private const val NOTIFICATION_ID = 501

        @Volatile
        var instance: OrderWatcherService? = null

        private const val FAST_RETRY_MS = 18L
        private const val FAST_RETRY_WINDOW_MS = 900L
        private const val RISE_WATCH_MS = 4L
        private const val RISE_MAX_WAIT_MS = 45L
        private const val CLICK_COOLDOWN_MS = 20L
        private const val OCR_INTERVAL_MS = 280L
        private const val OCR_TURBO_INTERVAL_MS = 35L
        // Ek order (popup) par fast retry limit
        private const val MAX_TRIES_PER_POPUP = 4
        // Swipe duration (ms): 20ms ultra-fast full swipe
        private const val SWIPE_MS = 20L

        private val COUNTDOWN_STRIP_RE = Regex("accept\\s*in\\s*\\d+\\s*s?", RegexOption.IGNORE_CASE)
        private val ACCEPT_TAIL_RE = Regex("\\|\\s*(?:accept|confirm|take\\s+order).*$", RegexOption.IGNORE_CASE)

        private const val VERIFY_INTERVAL_MS = 35L
        private const val VERIFY_MAX_ATTEMPTS = 8

        private val ACCEPT_WORDS = listOf(
            "accept", "confirm", "take order", "accept order", "accept now"
        )
    }

    private data class PreMatchEntry(
        val signature: String,
        val matched: Boolean,
        val firstSeenUptime: Long,
        var lastSeenUptime: Long,
        var lastSliderCenterY: Float,
        var lastSliderWidth: Int,
        var stableFrames: Int
    )

    private val handler = Handler(Looper.getMainLooper())

    // APPROVAL: har 5 minute me server + GitHub se approval/time check (rate-limit safe)
    private val licenseRunnable = object : Runnable {
        override fun run() {
            LicenseManager.refreshAsync(applicationContext)
            UpdateManager.checkAndPrompt(applicationContext, fromService = true)
            handler.postDelayed(this, 300_000L)
        }
    }
    private var retryUntil = 0L
    private var retryRunning = false
    private var riseWatchUntil = 0L
    private var riseWatchRunning = false
    private var lastActionAt = 0L
    private var lastSignature: String? = null
    private var lastOcrSignature: String? = null
    private var ocrPreMatchSig: String? = null
    private var ocrPreMatchFirstAt = 0L
    private var turboOcrUntil = 0L
    private var lastPreMatch: PreMatchEntry? = null

    private var verifyAttemptsLeft = 0

    // FIX: scan throttle + gesture overlap guard + debug spam guard
    private var lastScanAt = 0L
    private val ocrLock = Any()
    @Volatile private var ocrBusy = false
    private var lastOcrHeartbeat = 0L
    private var ocrNoSupportShown = false
    @Volatile private var slideInFlightUntil = 0L
    private var lastReportMsg = ""
    private var lastReportAt = 0L
    private var verifySwipesDone = 0
    private var ocrAttempts = 0
    private var treeDoneAt = 0L
    private var ocrLastSeenAt = 0L
    private var swipeTry = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SwipeZone.service = this
        SwipeZone.ensureAutoCalibrated(applicationContext)
        Thread {
            try {
                AreaPrefs.prewarm(applicationContext)
                ScreenOcrScanner.warmUp()
            } catch (_: Exception) {
            }
        }.start()
        handler.post(licenseRunnable)
        showOnNotification()
        setStatus(if (PermissionManager.isEverythingReady(applicationContext) && !AreaPrefs.isPaused(applicationContext)) "READY" else "OFF")
        // OCR loop: screen ke PIXELS se text padhta hai (photo/screenshot/camera me dikhi screen bhi)
        handler.postDelayed(ocrRunnable, 500L)

        // Sabhi settings ready hon to Floating Bubble khud-ba-khud ON kar do
        try {
            if (PermissionManager.isEverythingReady(applicationContext) &&
                !AreaPrefs.isBubbleManuallyOff(applicationContext) &&
                !PermissionManager.isFloatingBubbleRunning(applicationContext)
            ) {
                androidx.core.content.ContextCompat.startForegroundService(
                    this,
                    Intent(this, FloatingBubbleService::class.java)
                )
            }
        } catch (_: Exception) {
        }

        // FIX (naya): runtime par check karo ki gesture injection ka permission
        // SAHI SE mila hai ya nahi. Agar false hai to service chal to rahi hai
        // par touch kabhi nahi kar payegi.
        try {
            val canGesture = ((serviceInfo?.capabilities ?: 0) and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
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

    private fun isIgnoredPackage(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        if (pkg == packageName) return true
        val lp = pkg.lowercase()
        return lp == "com.android.systemui" ||
            lp == "com.oplus.screenrecorder" ||
            lp.contains("inputmethod") ||
            lp.contains("keyboard") ||
            lp.contains("gboard") ||
            lp.contains("swiftkey") ||
            lp.contains("honeyboard")
    }

    private fun isOwnAppInForeground(): Boolean {
        val activePkg = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        return activePkg == packageName
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (LicenseManager.isOwnUiOpening()) return
        if (!PermissionManager.isEverythingReady(applicationContext)) {
            if (!AreaPrefs.isPaused(applicationContext)) AreaPrefs.setPaused(applicationContext, true)
            return
        }
        if (AreaPrefs.isPaused(applicationContext)) return
        if (!LicenseManager.isActive(applicationContext)) return
        // Apne app ya keyboard typing events par scan mat chalao (lag bachane ke liye)
        val evPkg = event.packageName?.toString()
        if (isIgnoredPackage(evPkg)) return
        if (evPkg == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scanNow(forceImmediate = true)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> scanNow(forceImmediate = false)
            else -> Unit
        }
    }

    private fun computeCardSignature(pkg: String?, screenText: String): String {
        val noCountdown = COUNTDOWN_STRIP_RE.replace(screenText, "accept in")
        val noAcceptTail = ACCEPT_TAIL_RE.replace(noCountdown, "")
        val cardCore = noAcceptTail.replace(Regex("\\|\\s*skip(?:\\s*x)?\\b", RegexOption.IGNORE_CASE), "").trim()
        val keyBase = if (cardCore.length >= 8) cardCore else noCountdown
        return "${pkg.orEmpty()}:${keyBase.hashCode()}"
    }

    private fun looksLikeOrderCardText(screenText: String): Boolean {
        val raw = screenText.lowercase()
        if (screenHasAcceptWord(raw)) return true
        return raw.contains("pickup") ||
            raw.contains("drop") ||
            (raw.contains("km") && (raw.contains("₹") || raw.contains("rs") || raw.contains("india") || raw.contains("sector")))
    }

    private fun scanNow(forceImmediate: Boolean = false) {
        if (LicenseManager.isOwnUiOpening()) return
        if (!LicenseManager.isActive(applicationContext)) return
        if (!PermissionManager.isEverythingReady(applicationContext)) return
        if (AreaPrefs.isPaused(applicationContext)) return

        val tNow = SystemClock.uptimeMillis()
        val hasWarmMatch = lastPreMatch?.let { it.matched && (tNow - it.lastSeenUptime) < 1200L } == true
        val minInterval = if (forceImmediate || hasWarmMatch) 0L else 16L
        if (minInterval > 0L && tNow - lastScanAt < minInterval) return
        lastScanAt = tNow

        val roots = currentRoots()
        if (roots.isEmpty()) return

        for (root in roots) {
            try {
                val pk = root.packageName?.toString()
                if (isIgnoredPackage(pk)) continue

                val texts = ArrayList<String>(24)
                val acceptNodes = ArrayList<AccessibilityNodeInfo>(2)
                collectOrderTree(root, texts, acceptNodes)
                val screenText = texts.joinToString(" | ")

                if (screenText.isBlank()) continue

                // Order accept ho chuka ya trip chal rahi hai (Cash to Collect / End Trip / Drop at):
                // yahan kabhi bhi match ya tap/swipe nahi karna
                if (isTripDetailsScreen(screenText)) {
                    reportDebug("Ongoing trip screen - yaha touch band (order pehle hi uth chuka)")
                    continue
                }

                // Bottom/rising pre-read: agar card ka pickup/drop text dikh gaya ho (Accept slider
                // poora aane se pehle bhi), to niche hi padh kar cache kar lo taaki upar aate hi 0ms swipe ho!
                val hasAccept = acceptNodes.isNotEmpty() || screenHasAcceptWord(screenText)
                if (!hasAccept && !looksLikeOrderCardText(screenText)) continue

                val signature = computeCardSignature(pk, screenText)
                val cached = lastPreMatch
                val matched: Boolean
                val entry: PreMatchEntry
                if (cached != null && cached.signature == signature && (tNow - cached.lastSeenUptime) < 3500L) {
                    matched = cached.matched
                    cached.lastSeenUptime = tNow
                    entry = cached
                } else {
                    matched = AreaPrefs.matchesScreenText(applicationContext, screenText)
                    entry = PreMatchEntry(
                        signature = signature,
                        matched = matched,
                        firstSeenUptime = tNow,
                        lastSeenUptime = tNow,
                        lastSliderCenterY = -1f,
                        lastSliderWidth = 0,
                        stableFrames = 0
                    )
                    lastPreMatch = entry
                    if (matched && !hasAccept) {
                        // Card niche/animation me hi match ho gaya! Turant fast watch chalao
                        setStatus("ORDER PRE-MATCHED")
                        startRiseWatch()
                    }
                }

                if (!matched) {
                    if (hasAccept) {
                        reportDebug(
                            "Scan (${root.packageName}):\n\"${screenText.take(150)}\"\n-> NOT match"
                        )
                    }
                    continue
                }

                if (!hasAccept) continue

                setStatus("ORDER FOUND")

                val now = System.currentTimeMillis()
                if (verifyAttemptsLeft > 0 && signature == lastSignature) {
                    continue
                }

                // Rising/scaling check: agar popup abhi chhota hai ya neeche se tez upar aa raha hai,
                // to bina dobara text padhe 4ms me upar pahunchte hi TURBO swipe maaro!
                if (shouldWaitForRise(acceptNodes, entry, tNow)) {
                    startRiseWatch()
                    continue
                }

                val isNewOrder = signature != lastSignature
                if (isNewOrder) {
                    // Har naye order par pehli koshish hamesha 20ms TURBO style A se ho!
                    swipeTry = 0
                }
                val sameOrderCooldownOk = isNewOrder || (now - treeDoneAt > 200L)
                if (sameOrderCooldownOk && (isNewOrder || now - lastActionAt >= CLICK_COOLDOWN_MS)) {
                    if (acceptFromNodes(acceptNodes) || findAndAccept(root)) {
                        treeDoneAt = now
                        lastSignature = signature
                        lastActionAt = now
                        riseWatchRunning = false
                        turboOcrUntil = 0L
                        setStatus("ORDER UTHA RAHA")
                        reportDebug("ORDER UTHA RAHA - 20ms TURBO swipe bheja")
                        startVerifyRetry()
                    } else {
                        reportDebug("Accept control nahi mila - fast retry chal raha hai")
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

    private fun shouldWaitForRise(
        acceptNodes: List<AccessibilityNodeInfo>,
        entry: PreMatchEntry,
        tNow: Long
    ): Boolean {
        val elapsed = tNow - entry.firstSeenUptime
        if (elapsed >= RISE_MAX_WAIT_MS) return false
        val primary = acceptNodes.firstOrNull() ?: return false
        val sliderContainer = findSliderLikeAncestor(primary)
        val sliderNode = sliderContainer ?: primary
        val r = Rect()
        try {
            sliderNode.getBoundsInScreen(r)
        } catch (_: Exception) {
            return false
        }
        if (r.isEmpty) return false

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels.toFloat()
        val screenH = dm.heightPixels.toFloat()
        val curY = r.exactCenterY()
        val curW = r.width()
        val prevY = entry.lastSliderCenterY
        entry.lastSliderCenterY = curY
        entry.lastSliderWidth = curW

        // Agar slider container nahi mila (sirf chhota text node mila), to width par wait mat karo;
        // warna text node <42% hone ki wajah se 40ms bekaar wait ho jata!
        if (sliderContainer == null) {
            if (curY > screenH * 0.72f) {
                entry.stableFrames = 0
                return true
            }
            entry.stableFrames++
            return false
        }

        // Popup upar/beech me poori width ke saath aa chuka hai -> 0ms wait, turant swipe!
        if (curW >= screenW * 0.48f && curY <= screenH * 0.65f) {
            entry.stableFrames++
            return false
        }

        // Sirf jab popup abhi scale-in ho raha ho (<42% width) ya neeche bottom (>72% height) par ho
        if (curW < screenW * 0.42f || curY > screenH * 0.72f) {
            entry.stableFrames = 0
            return true
        }

        if (prevY > 0f && (prevY - curY) > 18f && curY > screenH * 0.60f) {
            entry.stableFrames = 0
            return true
        }

        entry.stableFrames++
        return false
    }

    private val riseWatchRunnable = object : Runnable {
        override fun run() {
            if (AreaPrefs.isPaused(applicationContext) || !LicenseManager.isActive(applicationContext)) {
                riseWatchRunning = false
                return
            }
            if (SystemClock.uptimeMillis() > riseWatchUntil) {
                riseWatchRunning = false
                return
            }
            scanNow(forceImmediate = true)
            if (riseWatchRunning) {
                handler.postDelayed(this, RISE_WATCH_MS)
            }
        }
    }

    private fun startRiseWatch() {
        riseWatchUntil = maxOf(riseWatchUntil, SystemClock.uptimeMillis() + 300L)
        if (riseWatchRunning) return
        riseWatchRunning = true
        handler.removeCallbacks(riseWatchRunnable)
        handler.postDelayed(riseWatchRunnable, RISE_WATCH_MS)
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
            if (SystemClock.uptimeMillis() < slideInFlightUntil) {
                handler.postDelayed(this, 25L)
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

                    // Trip Details / Ongoing Trip screen = order accept ho gaya, retry nahi
                    if (isTripDetailsScreen(screenText)) continue
                    if (!screenHasAcceptWord(screenText)) continue

                    if (!AreaPrefs.matchesScreenText(applicationContext, screenText)) continue

                    stillAcceptVisible = true
                    matchRoot = root
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
                lastSignature = null
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
                    val done = findAndAccept(r) || (verifySwipesDone % 4 == 0 && clickNodeOrParent(r))
                    lastActionAt = System.currentTimeMillis()
                    reportDebug("Verify retry #$verifySwipesDone -> action=$done")
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

    /**
     * Order ACCEPT HONE KE BAAD wali screens:
     * 1) Trip Details sheet (Cash to Collect, Trip Fare, View Details, Call Customer, Porter support, Flat no.)
     * 2) Active navigation / drop screen (End Trip >>>, Start Trip >>>, Customer's Flat no./Shop name, Drop at)
     * In screens par koi bhi tap/swipe kabhi NAHI chalna chahiye.
     */
    private fun isTripDetailsScreen(screenText: String): Boolean {
        val raw = screenText.lowercase()
        val strongMarkers = listOf(
            "cash to collect",
            "trip fare",
            "porter support",
            "call customer",
            "avoid calling the customer",
            "this fare may change",
            "flat no./floor no./shop no.",
            "flat no./shop name",
            "customer's flat no",
            "end trip",
            "start trip",
            "complete trip",
            "arrived at pickup",
            "arrived at drop",
            "reached pickup",
            "reached drop",
            "collect cash"
        )
        if (strongMarkers.any { raw.contains(it) }) return true
        val softMarkers = listOf("view details", "drop at", "pickup from", "trip started")
        return softMarkers.count { raw.contains(it) } >= 2
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
        // Active / top-most overlay popup windows ko hamesha sabse pehle scan karo
        rootInActiveWindow?.let {
            if (!isIgnoredPackage(it.packageName?.toString())) roots.add(it)
        }
        try {
            windows?.asReversed()?.forEach { window ->
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return@forEach
                window.root?.let { root ->
                    if (!isIgnoredPackage(root.packageName?.toString())) roots.add(root)
                }
            }
        } catch (_: Exception) {
        }
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

    private fun acceptFromNodes(acceptNodes: List<AccessibilityNodeInfo>): Boolean {
        for (node in acceptNodes) {
            if (tryAcceptNode(node)) return true
        }
        return false
    }

    private fun tryAcceptNode(node: AccessibilityNodeInfo): Boolean {
        if (SystemClock.uptimeMillis() < slideInFlightUntil) return false

        val swiped = swipeAcceptControl(node)
        if (swiped) return true

        if (geometricSlideFromNode(node)) return true
        val zone = SwipeZone.get(applicationContext)
        if (zone != null) {
            val nodeRect = Rect()
            try { node.getBoundsInScreen(nodeRect) } catch (_: Exception) {}
            // Dual-Porter safety: Zone fallback sirf tab jab matched Accept node ke paas hi ho
            val sameBand = nodeRect.isEmpty ||
                kotlin.math.abs(zone.y - nodeRect.exactCenterY()) <= resources.displayMetrics.heightPixels * 0.14f
            if (sameBand && slideGesture(0f, 0f, 0f, "ZONE")) return true
        }

        if (SystemClock.uptimeMillis() < slideInFlightUntil) return false
        val clicked = clickNodeOrParent(node, tapFallback = true)
        reportDebug("SWIPE dispatch nahi hua; CLICK fallback=$clicked")
        return clicked
    }

    private fun isAcceptControlNode(node: AccessibilityNodeInfo): Boolean {
        val raw = nodeText(node).lowercase().trim()
        if (raw.isEmpty() || raw.contains("pickup") || raw.contains("drop")) return false
        val compact = raw.replace(Regex("[^a-z0-9]+"), "")
        return ACCEPT_WORDS.any { word ->
            raw.contains(word) ||
                compact.contains(word.replace(Regex("[^a-z0-9]+"), ""))
        }
    }

    private fun findAndAccept(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        if (isAcceptControlNode(node)) {
            if (tryAcceptNode(node)) return true
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

    private fun clickNodeOrParent(node: AccessibilityNodeInfo, tapFallback: Boolean = false): Boolean {
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

        // FIX: ACTION_CLICK kaam nahi karta (React Native / custom view). Node ke beech me
        // real screen tap bhejo.
        return if (tapFallback) tapNodeCenter(node) else false
    }

    // ------------------------------------------------------------------
    // OCR: screenshot -> text -> "Accept" dhoondo -> slide / tap
    // ------------------------------------------------------------------
    private val ocrRunnable = object : Runnable {
        override fun run() {
            runOcrOnce()
        }
    }

    private fun scheduleNextOcr(delayMs: Long = OCR_INTERVAL_MS) {
        handler.removeCallbacks(ocrRunnable)
        handler.postDelayed(ocrRunnable, delayMs)
    }

    private fun nextOcrDelayMs(): Long {
        return if (SystemClock.uptimeMillis() < turboOcrUntil) OCR_TURBO_INTERVAL_MS else OCR_INTERVAL_MS
    }

    private fun runOcrOnce() {
        if (LicenseManager.isOwnUiOpening() || isOwnAppInForeground()) {
            scheduleNextOcr(350L)
            return
        }
        if (!LicenseManager.isActive(applicationContext) ||
            !PermissionManager.isEverythingReady(applicationContext) ||
            AreaPrefs.isPaused(applicationContext)
        ) {
            scheduleNextOcr(500L)
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (!ocrNoSupportShown) {
                ocrNoSupportShown = true
                reportDebug("OCR ke liye Android 11+ chahiye (is phone par band)")
            }
            return
        }
        if (verifyAttemptsLeft > 0 ||
            System.currentTimeMillis() - lastActionAt < 220L ||
            SystemClock.uptimeMillis() < slideInFlightUntil
        ) {
            scheduleNextOcr(80L)
            return
        }
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) {
            scheduleNextOcr(600L)
            return
        }

        synchronized(ocrLock) {
            if (ocrBusy) {
                scheduleNextOcr(80L)
                return
            }
            ocrBusy = true
        }
        ScreenOcrScanner.capture(this) { res, err ->
            handler.post {
                ocrBusy = false
                if (res == null) {
                    val isRateLimited = err?.contains("error 3") == true
                    if (err != null && !isRateLimited) reportDebug("OCR: $err")
                    val turboActive = SystemClock.uptimeMillis() < turboOcrUntil
                    val delay = when {
                        isRateLimited && turboActive -> 25L
                        isRateLimited -> 45L
                        else -> nextOcrDelayMs()
                    }
                    scheduleNextOcr(delay)
                } else {
                    try {
                        handleOcr(res)
                    } catch (e: Exception) {
                        Log.e(TAG, "ocr handle error", e)
                    } finally {
                        try { res.bitmap.recycle() } catch (_: Exception) {}
                        scheduleNextOcr(nextOcrDelayMs())
                    }
                }
            }
        }
    }

    private fun handleOcr(res: ScreenOcrScanner.Result) {
        if (res.lines.isEmpty()) return
        // Apna debug box screenshot me aata hai - uski lines ignore karo
        val ignoreAboveY = DebugOverlay.occupiedBottomPx()
        val lines = res.lines.filter { it.box.top >= ignoreAboveY }
        if (lines.isEmpty()) return

        val hb = SystemClock.uptimeMillis()
        if (hb - lastOcrHeartbeat > 3000L) {
            lastOcrHeartbeat = hb
            reportDebug("OCR chal raha hai: ${lines.size} lines padhi")
        }

        // Dual-Porter / Split-Screen isolation: poora screen ek saath mat jodo!
        // Sirf jis popup par "Accept" slider hai uske upar ka local card alag se padho,
        // taaki neeche wale Porter ki ongoing trip padh kar upar wale dusre order par swipe na ho.
        val sortedLines = lines.sortedBy { it.box.top }
        val acceptCandidates = sortedLines.filter { isOcrAcceptCandidate(it.text) }
        if (acceptCandidates.isEmpty()) {
            // Popup abhi chhota/rising hai aur Accept slider abhi text me nahi dikha ->
            // tabhi OCR se Pickup/Drop padh kar pre-match kar lo!
            val earlyText = sortedLines.joinToString(" | ") { it.text }
            if (earlyText.isNotBlank() &&
                !isTripDetailsScreen(earlyText) &&
                looksLikeOrderCardText(earlyText) &&
                AreaPrefs.matchesScreenText(applicationContext, earlyText)
            ) {
                val earlySig = computeCardSignature("ocr", earlyText)
                lastPreMatch = PreMatchEntry(earlySig, true, hb, hb, -1f, res.bitmap.width, 0)
                turboOcrUntil = hb + 600L
                startRiseWatch()
                setStatus("ORDER PRE-MATCHED")
                reportDebug("OCR: Chhota popup pehle hi match! Bada hote hi 20ms swipe hoga")
            }
            return
        }

        var matchedCard: Triple<ScreenOcrScanner.Line, String, String>? = null
        for (candidate in acceptCandidates) {
            val cardLines = extractPopupCardLines(sortedLines, candidate, res.bitmap.height)
            val cardText = cardLines.joinToString(" | ") { it.text }
            if (cardText.isBlank()) continue
            if (isTripDetailsScreen(cardText)) {
                reportDebug("OCR: Ongoing trip card - yaha touch nahi")
                continue
            }
            val ocrSig = computeCardSignature("ocr", cardText)
            val cached = lastPreMatch
            val isMatch = if (cached != null && cached.signature == ocrSig && (hb - cached.lastSeenUptime) < 3500L) {
                cached.lastSeenUptime = hb
                cached.matched
            } else {
                val m = AreaPrefs.matchesScreenText(applicationContext, cardText)
                lastPreMatch = PreMatchEntry(ocrSig, m, hb, hb, candidate.box.exactCenterY(), res.bitmap.width, 0)
                m
            }
            if (isMatch) {
                matchedCard = Triple(candidate, cardText, ocrSig)
                break
            } else {
                reportDebug("OCR: Accept mila par usi popup ka Pickup/Drop match NAHI\n${cardText.take(140)}")
            }
        }
        val (acceptLine, _, matchedSig) = matchedCard ?: return

        if (verifyAttemptsLeft > 0) return
        val nowT = System.currentTimeMillis()
        val bmpW = res.bitmap.width.toFloat()
        val bmpH = res.bitmap.height.toFloat()
        val slider = ScreenOcrScanner.estimateSlider(res.bitmap, acceptLine.box)

        // Agar popup abhi chhota ya neeche animation me hai -> OCR se pehle hi pre-match kar ke
        // 4ms Accessibility + 35ms Turbo OCR watch chalu karo; bada hote hi 0ms me 20ms swipe!
        if (matchedSig != ocrPreMatchSig || nowT - ocrPreMatchFirstAt > 1800L) {
            ocrPreMatchSig = matchedSig
            ocrPreMatchFirstAt = nowT
        }
        val isStillSmall = slider != null && slider.width() < bmpW * 0.44f
        val isLowBottom = acceptLine.box.exactCenterY() > bmpH * 0.72f
        if ((isStillSmall || isLowBottom) && (nowT - ocrPreMatchFirstAt) < 75L) {
            turboOcrUntil = hb + 600L
            startRiseWatch()
            setStatus("ORDER PRE-MATCHED")
            reportDebug("OCR: Chhota popup padh liya, bada hote hi 20ms swipe")
            return
        }

        if (matchedSig != lastOcrSignature || nowT - ocrLastSeenAt > 1800L) {
            lastOcrSignature = matchedSig
            ocrAttempts = 0 // naya order ya popup dubara aaya -> turant try 0
            swipeTry = 0
        }
        ocrLastSeenAt = nowT
        if (ocrAttempts >= MAX_TRIES_PER_POPUP) {
            return
        }
        if (nowT - lastActionAt < CLICK_COOLDOWN_MS) return
        if (SystemClock.uptimeMillis() < slideInFlightUntil) return

        setStatus("ORDER FOUND")
        ocrAttempts++
        val ok = if (slider != null && slider.width() >= bmpW * 0.44f) {
            val h = slider.height().toFloat()
            val thumbX = ScreenOcrScanner.findThumbCenterX(res.bitmap, slider)?.toFloat()
                ?: (slider.left + maxOf(h * 0.72f, slider.width() * 0.16f))
            slideGesture(
                thumbX,
                slider.exactCenterY(),
                slider.right - h * 0.20f,
                "SLIDER pixel se, try $ocrAttempts"
            )
        } else {
            // Safed (>>) circle screen width ~21% par aur right end ~89% par hota hai
            slideGesture(
                bmpW * 0.21f,
                acceptLine.box.exactCenterY(),
                bmpW * 0.89f,
                "TEXT-y se, try $ocrAttempts"
            )
        }
        lastActionAt = System.currentTimeMillis()
        if (ok) {
            turboOcrUntil = 0L
            setStatus("ORDER UTHA RAHA")
        }
    }

    private fun isOcrAcceptCandidate(rawLine: String): Boolean {
        val t = rawLine.lowercase().trim()
        if (t.isEmpty() || t.length > 32) return false
        if (t.contains("accepted") || t.contains("missed") || t.contains("please") ||
            t.contains("as early") || t.contains("end trip") || t.contains("start trip")
        ) {
            return false
        }
        return t.contains("accept") || t.contains("take order") || t == "confirm"
    }

    /**
     * Ek acceptLine ke theek upar maujood popup card ki lines nikalta hai.
     * Badi khali jagah (split-screen divider / map) ya ongoing trip line aate hi ruk jata hai,
     * isliye neeche ya upar chal rahe doosre Porter app ka address mix nahi hota.
     */
    private fun extractPopupCardLines(
        sortedLines: List<ScreenOcrScanner.Line>,
        acceptLine: ScreenOcrScanner.Line,
        screenH: Int
    ): List<ScreenOcrScanner.Line> {
        val maxLookbackY = (acceptLine.box.top - screenH * 0.62f).toInt()
        val maxBottomY = acceptLine.box.bottom + (screenH * 0.02f).toInt()
        val maxGapPx = (screenH * 0.22f).toInt()

        val idx = sortedLines.indexOf(acceptLine)
        if (idx < 0) return listOf(acceptLine)

        val picked = ArrayList<ScreenOcrScanner.Line>()
        picked.add(acceptLine)

        // Include any line on roughly the same band as acceptLine up to maxBottomY
        for (j in (idx + 1) until sortedLines.size) {
            val l = sortedLines[j]
            if (l.box.top <= maxBottomY) picked.add(l) else break
        }

        // Walk upward from acceptLine within the same card
        var anchorTop = acceptLine.box.top
        for (j in (idx - 1) downTo 0) {
            val l = sortedLines[j]
            if (l.box.top < maxLookbackY) break
            if (anchorTop - l.box.bottom > maxGapPx) break
            if (isTripDetailsScreen(l.text)) break
            val lt = l.text.lowercase().trim()
            // "Skip" aksar card ke top-right par Pickup ke paas hota hai -> sirf Skip hatao, loop mat todo!
            if (lt == "skip" || lt.startsWith("skip ")) continue
            picked.add(0, l)
            if (l.box.top < anchorTop) anchorTop = l.box.top
        }
        return picked
    }

    private fun tapAt(x: Float, y: Float): Boolean {
        if (SystemClock.uptimeMillis() < slideInFlightUntil) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        val ok = try { dispatchGesture(gesture, null, null) } catch (_: Exception) { false }
        reportDebug("OCR TAP at ${x.toInt()},${y.toInt()} dispatched=$ok")
        return ok
    }

    /**
     * Kai apps slider ka container Accessibility tree me expose nahi karte - sirf "Accept in 7s"
     * text node milta hai. Slider text ke theek peeche, screen ke beech me hota hai: isliye text ki
     * height (y) par thumb se right tak slide karo.
     */
    private fun geometricSlideFromNode(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        try { node.getBoundsInScreen(r) } catch (_: Exception) { return false }
        if (r.width() <= 0 || r.height() <= 0 || r.height() > 300) return false
        val w = SwipeZone.getScreenDimensions(applicationContext).first.toFloat()
        return slideGesture(w * 0.21f, r.exactCenterY(), w * 0.89f, "GEOM text=$r")
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        if (SystemClock.uptimeMillis() < slideInFlightUntil) return false
        val r = Rect()
        try { node.getBoundsInScreen(r) } catch (_: Exception) { return false }
        if (r.width() <= 0 || r.height() <= 0) return false
        // Safety: poori screen jaisa bada node ho to beech me tap mat karo
        val dm = resources.displayMetrics
        if (r.height() > dm.heightPixels * 0.5f || r.width() > dm.widthPixels * 0.98f && r.height() > dm.heightPixels * 0.25f) return false

        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        val ok = try { dispatchGesture(gesture, null, null) } catch (_: Exception) { false }
        reportDebug("TAP fallback at ${r.centerX()},${r.centerY()} dispatched=$ok")
        return ok
    }

    private fun swipeAcceptControl(node: AccessibilityNodeInfo): Boolean {
        val target = findSliderLikeAncestor(node) ?: return false
        val bounds = Rect()

        try {
            target.getBoundsInScreen(bounds)
        } catch (_: Exception) {
            return false
        }

        if (bounds.width() < 140 || bounds.height() < 28) return false

        val thumb = findThumbChild(target, bounds)
        val thumbCenterX = thumb?.centerX()?.toFloat()
            ?: (bounds.left + maxOf(bounds.height() * 0.72f, bounds.width() * 0.16f))
        val thumbCenterY = thumb?.centerY()?.toFloat()
            ?: bounds.centerY().toFloat()

        val startX = thumbCenterX.coerceIn(
            bounds.left + bounds.height() * 0.35f,
            bounds.left + bounds.width() * 0.42f
        )
        val endX = (bounds.right - bounds.height() * 0.28f).toFloat()
        val y = thumbCenterY.coerceIn(
            bounds.top + bounds.height() * 0.25f,
            bounds.bottom - bounds.height() * 0.25f
        )

        if (endX <= startX + 80f) return false

        val info = "bounds=$bounds\nstart=${startX.toInt()},${y.toInt()}\nend=${endX.toInt()}\nthumb=${thumb?.let { "${it.centerX()},${it.centerY()}" } ?: "fallback"}"
        return slideGesture(startX, y, endX, info)
    }

    private var swipeStyle = 0
    private var lastSwipeResult = "-"

    /**
     * Fast Press-Latch-Drag swipe:
     * Pehli koshish par 16ms thumb touch-down latch + 68ms full drag taaki Porter slider
     * kabhi fling samajh kar discard na kare aur millisecond me order accept ho.
     */
    private fun slideGesture(startX0: Float, y0: Float, endX0: Float, info0: String): Boolean {
        if (!LicenseManager.isActive(applicationContext)) return false
        if (SwipeZone.isEditing()) {
            SwipeZone.closeEditor()
            handler.postDelayed({ slideGesture(startX0, y0, endX0, info0) }, 60L)
            return true
        }

        val dm = resources.displayMetrics
        val maxX = dm.widthPixels.toFloat() - 1f
        val maxY = dm.heightPixels.toFloat() - 1f

        val auto = y0 > 0f && endX0 > startX0 + 130f
        val startX: Float
        val endX: Float
        val y: Float
        val src: String
        if (auto) {
            startX = startX0.coerceIn(0f, maxX)
            endX = endX0.coerceIn(0f, maxX)
            y = y0.coerceIn(1f, maxY)
            src = "AUTO"
        } else {
            val zone = SwipeZone.get(applicationContext)
            if (zone == null) {
                reportDebug("SWIPE ROKA: slider ki jagah nahi mili aur Swipe Zone bhi set nahi hai")
                return false
            }
            startX = zone.startX.coerceIn(0f, maxX)
            endX = zone.endX.coerceIn(0f, maxX)
            y = zone.y.coerceIn(1f, maxY)
            src = "ZONE"
        }

        if (endX <= startX + 130f) {
            reportDebug("SWIPE ROKA: swipe line bahut chhoti hai")
            return false
        }

        if (SystemClock.uptimeMillis() < slideInFlightUntil) return false

        val canGesture = try {
            ((serviceInfo?.capabilities ?: 0) and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
        } catch (_: Exception) {
            false
        }
        if (!canGesture) {
            reportDebug("Gesture permission missing. Accessibility OFF karke ON karein.")
            return false
        }

        val style = swipeTry++ % 4
        val tag = "$src ${'A' + style}"
        val overshoot = (endX + 12f).coerceAtMost(maxX)
        val info = "$tag start=${startX.toInt()},${y.toInt()} end=${endX.toInt()} | $info0"
        val t0 = SystemClock.uptimeMillis()
        val expectedMs = when (style) {
            0 -> SWIPE_MS
            1 -> 30L
            2 -> 45L
            else -> 55L
        }
        slideInFlightUntil = t0 + expectedMs + 45L

        fun fin(ok: Boolean, what: String) {
            slideInFlightUntil = SystemClock.uptimeMillis() + 5L
            lastSwipeResult = "$tag ${if (ok) "DONE" else what}"
            reportDebug("SWIPE $lastSwipeResult ${SystemClock.uptimeMillis() - t0}ms\n$info")
            if (ok) SoundPlayer.playOrderSound(applicationContext)
        }

        fun single(durationMs: Long, toX: Float): Boolean {
            val endPos = toX.coerceAtMost(maxX)
            val path = Path().apply {
                moveTo(startX, y)
                lineTo(startX + 4f, y)
                lineTo(startX + (endPos - startX) * 0.35f, y)
                lineTo(startX + (endPos - startX) * 0.72f, y)
                lineTo(endPos, y)
            }
            return dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                    .build(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { fin(true, "") }
                    override fun onCancelled(g: GestureDescription?) { fin(false, "CANCELLED") }
                },
                null
            )
        }

        fun chain2(holdMs: Long, dragMs: Long, toX: Float): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return single(holdMs + dragMs, toX)
            val endPos = toX.coerceAtMost(maxX)
            val holdPath = Path().apply { moveTo(startX, y); lineTo(startX + 2f, y) }
            val s1 = GestureDescription.StrokeDescription(holdPath, 0L, holdMs, true)
            return dispatchGesture(
                GestureDescription.Builder().addStroke(s1).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        val dragPath = Path().apply {
                            moveTo(startX + 2f, y)
                            lineTo(startX + (endPos - startX) * 0.40f, y)
                            lineTo(endPos, y)
                        }
                        val s2 = s1.continueStroke(dragPath, 0L, dragMs, false)
                        val ok2 = try {
                            dispatchGesture(
                                GestureDescription.Builder().addStroke(s2).build(),
                                object : GestureResultCallback() {
                                    override fun onCompleted(g2: GestureDescription?) { fin(true, "") }
                                    override fun onCancelled(g2: GestureDescription?) { fin(false, "CANCELLED(drag)") }
                                },
                                null
                            )
                        } catch (e: Exception) { false }
                        if (!ok2) fin(false, "REJECTED(drag)")
                    }
                    override fun onCancelled(g: GestureDescription?) { fin(false, "CANCELLED(hold)") }
                },
                null
            )
        }

        val dispatched = try {
            when (style) {
                0 -> single(SWIPE_MS, overshoot)
                1 -> single(30L, overshoot)
                2 -> chain2(10L, 35L, overshoot)
                else -> single(55L, overshoot)
            }
        } catch (e: Exception) {
            reportDebug("SWIPE exception: ${e.message}")
            false
        }

        if (dispatched) {
            SwipeIndicator.animateSwipe(SWIPE_MS)
        } else {
            slideInFlightUntil = 0L
            lastSwipeResult = "$tag REJECTED"
            reportDebug("SWIPE $tag REJECTED\n$info")
        }
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
        var bestScore = Int.MIN_VALUE
        var depth = 0
        val dm = resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)
        val minPillH = (36f * density).toInt()
        val maxPillH = (115f * density).toInt().coerceAtMost((dm.heightPixels * 0.12f).toInt())
        val minPillW = (dm.widthPixels * 0.45f).toInt().coerceAtLeast(200)
        val idealPillH = (60f * density).toInt()

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

            if (width >= minPillW && height in minPillH..maxPillH && ratio >= 2.6f) {
                val heightPenalty = kotlin.math.abs(height - idealPillH) * 6
                val score = width - heightPenalty + if (current.isClickable) 400 else 0
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

    private fun collectOrderTree(
        node: AccessibilityNodeInfo?,
        textsOut: MutableList<String>,
        acceptNodesOut: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return

        val txt = node.text?.toString()?.takeIf { it.isNotBlank() }
        val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.takeIf { it.isNotBlank() }
        } else null
        val pane = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            node.paneTitle?.toString()?.takeIf { it.isNotBlank() }
        } else null

        if (txt != null) textsOut.add(txt)
        if (desc != null) textsOut.add(desc)
        if (hint != null) textsOut.add(hint)
        if (pane != null) textsOut.add(pane)

        if (txt != null || desc != null || hint != null) {
            val combined = buildString {
                if (txt != null) append(txt).append(' ')
                if (desc != null) append(desc).append(' ')
                if (hint != null) append(hint)
            }.lowercase()
            if (!combined.contains("pickup") && !combined.contains("drop")) {
                val compact = combined.replace(Regex("[^a-z0-9]+"), "")
                val hit = ACCEPT_WORDS.any { word ->
                    combined.contains(word) || compact.contains(word.replace(Regex("[^a-z0-9]+"), ""))
                }
                if (hit) acceptNodesOut.add(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) collectOrderTree(child, textsOut, acceptNodesOut)
        }
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return

        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            node.paneTitle?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) collectText(child, out)
        }
    }

    private fun reportDebug(message: String) {
        // FIX: har 100ms par overlay + notification update karne se system throttle karta tha
        // aur main thread bhari ho jata tha.
        val now = SystemClock.uptimeMillis()
        if (message == lastReportMsg && now - lastReportAt < 1500L) return
        if (message.startsWith("Scan (") && now - lastReportAt < 1000L) return
        lastReportMsg = message
        lastReportAt = now
        val full = if (lastSwipeResult != "-" && !message.startsWith("SWIPE")) "$message\n[last: $lastSwipeResult]" else message
        DebugOverlay.update(this, full)
        if (!message.startsWith("OCR chal raha hai") && !message.startsWith("Scan (")) {
            updateStatusNotification(full)
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
                    "SwiftGate Status",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_on)
            .setContentTitle("SwiftGate: ON")
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
            .setContentTitle("SwiftGate DEBUG")
            .setContentText(message.take(60))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.take(500)))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        verifyAttemptsLeft = 0
        setStatus("OFF")
        DebugOverlay.hide()
        SwipeIndicator.hide()
        SwipeZone.closeEditor()
        SwipeZone.service = null
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }
}
