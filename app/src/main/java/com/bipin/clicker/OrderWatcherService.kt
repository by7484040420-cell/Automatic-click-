package com.bipin.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.graphics.Bitmap
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

        @Volatile
        var instance: OrderWatcherService? = null

        private const val FAST_RETRY_MS = 35L
        private const val FAST_RETRY_WINDOW_MS = 1600L
        private const val CLICK_COOLDOWN_MS = 70L
        private const val OCR_INTERVAL_MS = 750L
        // Ek order (popup) par kitni baar swipe karna hai (OCR path).
        private const val MAX_TRIES_PER_POPUP = 2
        // Millisecond turbo swipe duration (ms): human (~250-400ms) se ~10x tez, reliable 2-3 move frames
        private const val SWIPE_MS = 28L

        private const val VERIFY_INTERVAL_MS = 55L
        private const val VERIFY_MAX_ATTEMPTS = 10

        private val COUNTDOWN_TAIL_RE = Regex("accept\\s*in\\s*\\d+\\s*s?", RegexOption.IGNORE_CASE)
        private val NON_ALNUM_RE = Regex("[^a-z0-9]+")
        private val ACCEPT_WORDS = listOf(
            "accept", "confirm", "take order", "accept order", "accept now"
        )
        private val ACCEPT_COMPACT = listOf(
            "accept", "confirm", "takeorder", "acceptorder", "acceptnow"
        )
    }

    private val handler = Handler(Looper.getMainLooper())

    // APPROVAL: har 60 second me GitHub se approval/time dobara check
    private val licenseRunnable = object : Runnable {
        override fun run() {
            LicenseManager.refreshAsync(applicationContext)
            UpdateManager.checkAndPrompt(applicationContext, fromService = true)
            handler.postDelayed(this, 60_000L)
        }
    }
    private var retryUntil = 0L
    private var retryRunning = false
    private var lastActionAt = 0L
    private var lastSignature: String? = null

    private var verifyAttemptsLeft = 0

    // FIX: scan throttle + gesture overlap guard + debug spam guard
    private var lastScanAt = 0L
    private var pendingScanScheduled = false
    private val trailingScanRunnable = Runnable {
        pendingScanScheduled = false
        scanNow(forceInstant = true)
    }
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
    private var lastNotifiedStatus = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        SwipeZone.service = this
        // Background thread par saare rules, regexes aur OCR recognizer pehle se warm rakho (0ms cold start)
        Thread {
            try {
                AreaPrefs.prewarm(applicationContext)
                ScreenOcrScanner.warmUp()
            } catch (_: Exception) {
            }
        }.start()
        handler.post(licenseRunnable)
        showOnNotification()
        setStatus("READY")
        // OCR loop: screen ke PIXELS se text padhta hai (photo/screenshot/camera me dikhi screen bhi)
        handler.postDelayed(ocrRunnable, 750L)

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (AreaPrefs.isPaused(applicationContext)) return
        if (!LicenseManager.isActive(applicationContext)) return
        // FIX: apne hi overlay/notification ke events se scan loop na bane
        if (event.packageName?.toString() == packageName) return

        when (event.eventType) {
            // Popup khulte hi 0ms par turant scan (koi throttle nahi)
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> scanNow(forceInstant = true)

            // Text / layout update par 14ms fast scan + trailing guarantee (koi update miss na ho)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> scanNow(forceInstant = false, minIntervalMs = 14L)

            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> scanNow(forceInstant = false, minIntervalMs = 40L)
            else -> Unit
        }
    }

    private fun scheduleTrailingScan(delayMs: Long) {
        if (pendingScanScheduled) return
        pendingScanScheduled = true
        handler.postDelayed(trailingScanRunnable, delayMs)
    }

    private fun fastContainsAccept(text: String): Boolean {
        return text.contains("accept", ignoreCase = true) ||
            text.contains("confirm", ignoreCase = true) ||
            text.contains("take order", ignoreCase = true) ||
            text.contains("takeorder", ignoreCase = true)
    }

    private fun scanNow(forceInstant: Boolean = false, minIntervalMs: Long = 14L) {
        if (AreaPrefs.isPaused(applicationContext)) return
        if (!LicenseManager.isActive(applicationContext)) return

        val tNow = SystemClock.uptimeMillis()
        val elapsed = tNow - lastScanAt
        if (!forceInstant && elapsed < minIntervalMs) {
            scheduleTrailingScan((minIntervalMs - elapsed).coerceAtLeast(4L))
            return
        }
        if (pendingScanScheduled) {
            pendingScanScheduled = false
            handler.removeCallbacks(trailingScanRunnable)
        }
        lastScanAt = tNow

        // Sabse pehle active window check karo (0 extra IPC); yahin 95% order popups aate hain
        var activeWindowId = -1
        val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }
        if (activeRoot != null) {
            activeWindowId = try { activeRoot.windowId } catch (_: Exception) { -1 }
            try {
                if (scanRootNode(activeRoot)) return
            } catch (e: Exception) {
                Log.e(TAG, "active scan error", e)
            } finally {
                try { activeRoot.recycle() } catch (_: Exception) {}
            }
        }

        // Overlay / multi-window popups ke liye baaki windows check karo
        val extraRoots = currentExtraRoots(activeWindowId)
        for (root in extraRoots) {
            try {
                if (scanRootNode(root)) return
            } catch (e: Exception) {
                Log.e(TAG, "extra scan error", e)
            } finally {
                try { root.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun scanRootNode(root: AccessibilityNodeInfo): Boolean {
        val pk = root.packageName?.toString()
        if (pk == packageName || pk == "com.oplus.screenrecorder" || pk == "com.android.systemui") return false

        val sb = StringBuilder(512)
        val acceptHolder = arrayOfNulls<AccessibilityNodeInfo>(1)
        collectFast(root, sb, acceptHolder)
        if (sb.isEmpty()) return false
        val screenText = sb.toString()

        // Fast idle skip: agar screen par accept/confirm button hi nahi hai to regex match mat chalao
        val acceptNode = acceptHolder[0]
        if (acceptNode == null && !fastContainsAccept(screenText)) {
            if (DebugOverlay.ENABLED) {
                reportDebug("Scan ($pk):\n\"${screenText.take(150)}\"\n-> NOT match")
            }
            return false
        }

        // Order accept ho chuka: Trip Details screen par kuch nahi karna (fast check before regex)
        if (isTripDetailsScreen(screenText)) {
            reportDebug("Trip Details screen - yaha tap nahi (order pehle hi uth chuka)")
            return false
        }

        if (!AreaPrefs.matchesScreenText(applicationContext, screenText)) {
            if (DebugOverlay.ENABLED) {
                val isExplicitR = AreaPrefs.matchesRejectText(applicationContext, screenText)
                if (screenHasAcceptWord(screenText)) {
                    reportDebug(
                        if (isExplicitR) "REJECT (R) match -> ignore\n\"${screenText.take(110)}\""
                        else "A rules NOT match -> ignore\n\"${screenText.take(110)}\""
                    )
                } else {
                    reportDebug("Scan ($pk):\n\"${screenText.take(150)}\"\n-> NOT match")
                }
            }
            return false
        }

        val stableText = COUNTDOWN_TAIL_RE.replace(screenText, "")
        val signature = "${pk.orEmpty()}:${stableText.hashCode()}"
        val now = System.currentTimeMillis()
        val isSameOrder = (signature == lastSignature)

        if (verifyAttemptsLeft > 0 && isSameOrder) {
            return false
        }

        // Naya order: 0ms wait! Same order dobara: 180ms baad
        if (!isSameOrder || (now - treeDoneAt > 180L && now - lastActionAt >= CLICK_COOLDOWN_MS)) {
            val dispatched = if (SwipeZone.isSet(applicationContext)) {
                slideGesture(0f, 0f, 0f, "ZONE", durationOverrideMs = SWIPE_MS)
            } else {
                (acceptNode != null && acceptFromNodeFast(acceptNode)) || findAndAccept(root)
            }

            if (dispatched) {
                treeDoneAt = now
                lastSignature = signature
                lastActionAt = now
                // Turant 55ms par verify + auto-retry chalu karo (agar frame-1 miss hua ho to turant recover)
                startVerifyRetry()
                handler.post {
                    setStatus("ORDER UTHA RAHA")
                    reportDebug("ORDER UTHA RAHA - turbo ${SWIPE_MS}ms swipe bheja")
                }
                return true
            } else {
                setStatus("ORDER FOUND")
                reportDebug("Accept control nahi mila - 35ms fast retry")
                startFastRetry()
            }
        }
        return false
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
                reportDebug("Verify khatam - order close/timeout")
                return
            }

            val roots = ArrayList<AccessibilityNodeInfo>(4)
            var activeWindowId = -1
            try {
                rootInActiveWindow?.let { r ->
                    activeWindowId = try { r.windowId } catch (_: Exception) { -1 }
                    roots.add(r)
                }
            } catch (_: Exception) {
            }

            var stillAcceptVisible = false
            var matchRoot: AccessibilityNodeInfo? = null

            fun inspectRoots(list: List<AccessibilityNodeInfo>) {
                for (root in list) {
                    var keep = false
                    try {
                        if (stillAcceptVisible) continue
                        val pk = root.packageName?.toString()
                        if (pk == packageName || pk == "com.android.systemui") continue
                        val sb = StringBuilder(512)
                        collectText(root, sb)
                        if (sb.isEmpty()) continue
                        val screenText = sb.toString()

                        if (isTripDetailsScreen(screenText)) continue
                        if (!fastContainsAccept(screenText)) continue
                        if (!AreaPrefs.matchesScreenText(applicationContext, screenText)) continue

                        if (screenHasAcceptWord(screenText)) {
                            stillAcceptVisible = true
                            matchRoot = root
                            keep = true
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "verify error", e)
                    } finally {
                        if (!keep) {
                            try { root.recycle() } catch (_: Exception) {}
                        }
                    }
                }
            }

            inspectRoots(roots)
            if (!stillAcceptVisible) {
                inspectRoots(currentExtraRoots(activeWindowId))
            }

            if (!stillAcceptVisible) {
                verifyAttemptsLeft = 0
                lastSignature = null
                setStatus("READY")
                reportDebug("Order accept/dismiss ho gaya (verify clear)")
                matchRoot?.let { try { it.recycle() } catch (_: Exception) {} }
                return
            }

            matchRoot?.let { r ->
                try {
                    verifySwipesDone++
                    val done = when (verifySwipesDone) {
                        // Try 1: dynamic node bounds se 42ms swipe (agar SwipeZone shift hua ho)
                        1 -> findAndAccept(r, preferZone = false, durationOverrideMs = 42L)
                        // Try 2: 58ms swipe + click fallback
                        2 -> findAndAccept(r, preferZone = true, durationOverrideMs = 58L) ||
                            clickNodeOrParent(r, tapFallback = true)
                        // Try 3+: alternate click & firmer 75ms swipe
                        else -> if (verifySwipesDone % 2 == 1) {
                            clickNodeOrParent(r, tapFallback = true) ||
                                findAndAccept(r, preferZone = false, durationOverrideMs = 75L)
                        } else {
                            findAndAccept(r, preferZone = false, durationOverrideMs = 75L)
                        }
                    }
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
     * Order ACCEPT HONE KE BAAD wali "Trip Details" screen (Cash to Collect, Trip Fare,
     * Call Customer, Porter support...). Is screen par koi tap/swipe NAHI chalna chahiye.
     * Sirf naya order popup ("Accept in Ns") par hi kaam hona chahiye.
     */
    private fun isTripDetailsScreen(screenText: String): Boolean {
        val raw = screenText.lowercase()
        val markers = listOf(
            "cash to collect", "trip fare", "view details", "call customer", "porter support"
        )
        return markers.count { raw.contains(it) } >= 2
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

    private fun currentExtraRoots(excludeWindowId: Int): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>(4)
        try {
            windows?.forEach { window ->
                val type = window.type
                if ((type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION ||
                        type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) &&
                    window.id != excludeWindowId
                ) {
                    window.root?.let { roots.add(it) }
                }
            }
        } catch (_: Exception) {
        }
        return roots
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

                scanNow(forceInstant = true)
                handler.postDelayed(this, FAST_RETRY_MS)
            }
        })
    }

    private fun acceptFromNodeFast(
        node: AccessibilityNodeInfo,
        preferZone: Boolean = true,
        durationOverrideMs: Long = SWIPE_MS
    ): Boolean {
        if (preferZone && SwipeZone.isSet(applicationContext) &&
            slideGesture(0f, 0f, 0f, "ZONE", durationOverrideMs)
        ) return true
        if (swipeAcceptControl(node, durationOverrideMs)) return true
        if (SwipeZone.isSet(applicationContext) &&
            slideGesture(0f, 0f, 0f, "ZONE", durationOverrideMs)
        ) return true
        if (geometricSlideFromNode(node, durationOverrideMs)) return true
        return clickNodeOrParent(node, tapFallback = true)
    }

    private fun findAndAccept(
        node: AccessibilityNodeInfo?,
        preferZone: Boolean = true,
        durationOverrideMs: Long = SWIPE_MS
    ): Boolean {
        if (node == null) return false

        val raw = nodeText(node).lowercase().trim()
        if (fastContainsAccept(raw) && !raw.contains("pickup") && !raw.contains("drop")) {
            val compact = NON_ALNUM_RE.replace(raw, "")
            val isAcceptNode = ACCEPT_COMPACT.any { compact.contains(it) } ||
                ACCEPT_WORDS.any { raw.contains(it) }
            if (isAcceptNode) {
                if (acceptFromNodeFast(node, preferZone, durationOverrideMs)) return true
            }
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null && findAndAccept(child, preferZone, durationOverrideMs)) return true
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
            handler.postDelayed(this, OCR_INTERVAL_MS)
        }
    }

    private fun runOcrOnce() {
        if (!LicenseManager.isActive(applicationContext)) return
        // Jab 60fps ScreenWatcher chal raha ho to slow takeScreenshot() mat chalao
        if (ScreenWatcherService.running) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (!ocrNoSupportShown) {
                ocrNoSupportShown = true
                reportDebug("OCR ke liye Android 11+ chahiye (is phone par band)")
            }
            return
        }
        if (AreaPrefs.isPaused(applicationContext)) return
        if (System.currentTimeMillis() - lastActionAt < 180L) return
        if (SystemClock.uptimeMillis() < slideInFlightUntil) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return

        synchronized(ocrLock) {
            if (ocrBusy) return
            ocrBusy = true
        }

        ScreenOcrScanner.capture(this) { res, err ->
            ocrBusy = false
            handler.post {
                if (res == null) {
                    if (err != null && !err.contains("error 3")) reportDebug("OCR: $err")
                } else {
                    try {
                        handleOcr(res)
                    } catch (e: Exception) {
                        Log.e(TAG, "ocr handle error", e)
                    } finally {
                        try { res.bitmap.recycle() } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    /** ScreenWatcherService (60fps MediaProjection) se aayi live frame par turant OCR (direct background thread). */
    fun submitWatcherFrame(bmp: Bitmap) {
        if (!LicenseManager.isActive(applicationContext) ||
            AreaPrefs.isPaused(applicationContext) ||
            SystemClock.uptimeMillis() < slideInFlightUntil
        ) {
            try { bmp.recycle() } catch (_: Exception) {}
            return
        }
        synchronized(ocrLock) {
            if (ocrBusy) {
                try { bmp.recycle() } catch (_: Exception) {}
                return
            }
            ocrBusy = true
        }
        ScreenOcrScanner.recognize(bmp) { res, _ ->
            ocrBusy = false
            if (res != null) {
                handler.post {
                    try {
                        handleOcr(res)
                    } catch (e: Exception) {
                        Log.e(TAG, "watcher ocr handle error", e)
                    } finally {
                        try { res.bitmap.recycle() } catch (_: Exception) {}
                    }
                }
            } else {
                try { bmp.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun handleOcr(res: ScreenOcrScanner.Result) {
        if (res.lines.isEmpty()) {
            // Screenshot poora kaala ho to app screenshot block kar rahi hai (FLAG_SECURE)
            val bmp = res.bitmap
            var allBlack = true
            for (i in 1..5) for (j in 1..5) {
                val px = bmp.getPixel(bmp.width * i / 6, bmp.height * j / 6)
                if ((px and 0xFFFFFF) != 0) allBlack = false
            }
            if (allBlack) reportDebug("OCR: screenshot poora KAALA - app screenshot block kar rahi hai")
            return
        }
        // Apna debug box screenshot me aata hai - uski lines ignore karo
        val ignoreAboveY = DebugOverlay.occupiedBottomPx()
        val lines = res.lines.filter { it.box.top >= ignoreAboveY }
        val text = lines.joinToString(" | ") { it.text }
        if (text.isBlank()) return

        val hb = SystemClock.uptimeMillis()
        if (hb - lastOcrHeartbeat > 3000L) {
            lastOcrHeartbeat = hb
            reportDebug("OCR chal raha hai: ${lines.size} lines padhi")
        }

        val acceptLine = lines.firstOrNull {
            val t = it.text.lowercase()
            t.contains("accept") && !t.contains("accepted") && !t.contains("missed") &&
                !t.contains("please") && !t.contains("as early")
        } ?: return

        if (isTripDetailsScreen(text)) {
            reportDebug("OCR: Trip Details screen - yaha tap nahi")
            return
        }
        if (!AreaPrefs.matchesScreenText(applicationContext, text)) {
            val isExplicitR = AreaPrefs.matchesRejectText(applicationContext, text)
            reportDebug("OCR: ${if (isExplicitR) "REJECT (R)" else "A not match"}\n${text.take(120)}")
            return
        }
        val nowT = System.currentTimeMillis()
        if (nowT - ocrLastSeenAt > 450L) ocrAttempts = 0 // popup gayab hua tha -> naya order
        ocrLastSeenAt = nowT
        if (ocrAttempts >= MAX_TRIES_PER_POPUP) {
            return
        }
        if (nowT - lastActionAt < 110L) return
        // Pichla swipe abhi chal raha hai -> naya mat bhejo, aur koshish ginti me mat jodo
        if (SystemClock.uptimeMillis() < slideInFlightUntil) return

        setStatus("ORDER FOUND")
        ocrAttempts++
        val dur = if (ocrAttempts == 1) SWIPE_MS else 48L
        val bmpW = res.bitmap.width.toFloat()
        val slider = if (ocrAttempts == 1 && SwipeZone.isSet(applicationContext)) null
        else ScreenOcrScanner.estimateSlider(res.bitmap, acceptLine.box)

        val ok = if (ocrAttempts == 1 && SwipeZone.isSet(applicationContext)) {
            slideGesture(0f, 0f, 0f, "OCR+ZONE try 1", dur)
        } else if (slider != null) {
            val h = slider.height().toFloat()
            val thumbX = ScreenOcrScanner.findThumbCenterX(res.bitmap, slider)?.toFloat()
                ?: (slider.left + h * 0.95f)
            slideGesture(
                thumbX,
                slider.exactCenterY(),
                slider.right - h * 0.10f,
                "SLIDER pixel se, try $ocrAttempts",
                dur
            )
        } else {
            slideGesture(
                bmpW * 0.23f,
                acceptLine.box.exactCenterY(),
                bmpW * 0.90f,
                "TEXT-y se, try $ocrAttempts",
                dur
            )
        }
        lastActionAt = System.currentTimeMillis()
        if (ok) {
            setStatus("ORDER UTHA RAHA")
            if (DebugOverlay.ENABLED) showDebugToast("SwiftGate: OCR turbo swipe")
        }
    }

    private fun tapAt(x: Float, y: Float): Boolean {
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
    private fun geometricSlideFromNode(
        node: AccessibilityNodeInfo,
        durationOverrideMs: Long = SWIPE_MS
    ): Boolean {
        val r = Rect()
        try { node.getBoundsInScreen(r) } catch (_: Exception) { return false }
        if (r.width() <= 0 || r.height() <= 0 || r.height() > 300) return false
        val w = resources.displayMetrics.widthPixels.toFloat()
        return slideGesture(w * 0.229f, r.exactCenterY(), w * 0.90f, "GEOM text=$r", durationOverrideMs)
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        try { node.getBoundsInScreen(r) } catch (_: Exception) { return false }
        if (r.width() <= 0 || r.height() <= 0) return false
        // Safety: poori screen jaisa bada node ho to beech me tap mat karo
        val dm = resources.displayMetrics
        if (r.height() > dm.heightPixels * 0.5f || r.width() > dm.widthPixels * 0.98f && r.height() > dm.heightPixels * 0.25f) return false

        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 28L))
            .build()
        val ok = try { dispatchGesture(gesture, null, null) } catch (_: Exception) { false }
        reportDebug("TAP fallback at ${r.centerX()},${r.centerY()} dispatched=$ok")
        return ok
    }

    private fun swipeAcceptControl(
        node: AccessibilityNodeInfo,
        durationOverrideMs: Long = SWIPE_MS
    ): Boolean {
        val target = findSliderLikeAncestor(node) ?: return false
        val bounds = Rect()

        try {
            target.getBoundsInScreen(bounds)
        } catch (_: Exception) {
            return false
        }

        if (bounds.width() < 200 || bounds.height() < 40) return false

        val thumb = findThumbChild(target, bounds)
        val thumbCenterX = thumb?.centerX()?.toFloat()
            ?: (bounds.left + bounds.height() * 0.98f)
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

        val info = "bounds=$bounds\nstart=${startX.toInt()},${y.toInt()}\nend=${endX.toInt()}\nthumb=${thumb?.let { "${it.centerX()},${it.centerY()}" } ?: "fallback"}"
        return slideGesture(startX, y, endX, info, durationOverrideMs)
    }

    private var swipeStyle = 0
    private var lastSwipeResult = "-"

    /**
     * Video se dikha: pehle wala "hold + drag" (2 gesture jodkar) me thumb sirf ~40px hilta tha,
     * phir wapas laut jata tha. Is phone/app par shayad wo tareeka chalta nahi. Isliye ab har
     * koshish me alag STYLE aazmata hai (A, B, C, D), jo chal jaye. Debug box me har koshish
     * ka natija "[last: styleX ...]" me dikhta hai.
     */
    private fun slideGesture(
        startX0: Float,
        y0: Float,
        endX0: Float,
        info0: String,
        durationOverrideMs: Long = SWIPE_MS
    ): Boolean {
        if (!LicenseManager.isActive(applicationContext)) return false
        if (SwipeZone.isEditing()) {
            SwipeZone.closeEditor()
            handler.postDelayed({ slideGesture(startX0, y0, endX0, info0, durationOverrideMs) }, 80L)
            return true
        }

        val dm = resources.displayMetrics
        val maxX = dm.widthPixels.toFloat() - 1f
        val maxY = dm.heightPixels.toFloat() - 1f

        val auto = y0 > 0f && endX0 > startX0 + 150f
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

        if (endX <= startX + 150f) {
            reportDebug("SWIPE ROKA: swipe line bahut chhoti hai")
            return false
        }

        val t0 = SystemClock.uptimeMillis()
        if (t0 < slideInFlightUntil) return false

        val canGesture = try {
            ((serviceInfo?.capabilities ?: 0) and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
        } catch (_: Exception) {
            false
        }
        if (!canGesture) {
            reportDebug("Gesture permission missing. Accessibility OFF karke ON karein.")
            return false
        }

        val durMs = durationOverrideMs.coerceIn(22L, 120L)
        val style = swipeTry++ % 4
        val tag = "$src ${'A' + style}"
        val overshoot = (endX + 28f).coerceAtMost(maxX)
        val info = "$tag ${durMs}ms start=${startX.toInt()},${y.toInt()} end=${endX.toInt()} | $info0"
        slideInFlightUntil = t0 + durMs + 40L

        fun fin(ok: Boolean, what: String) {
            slideInFlightUntil = SystemClock.uptimeMillis() + 15L
            lastSwipeResult = "$tag ${if (ok) "DONE" else what}"
            reportDebug("SWIPE $lastSwipeResult ${SystemClock.uptimeMillis() - t0}ms\n$info")
            if (ok) SoundPlayer.playOrderSound(applicationContext)
        }

        val path = Path().apply {
            moveTo(startX, y)
            lineTo(startX + (overshoot - startX) * 0.15f, y)
            lineTo(overshoot, y)
        }

        val dispatched = try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, durMs))
                    .build(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { fin(true, "") }
                    override fun onCancelled(g: GestureDescription?) { fin(false, "CANCELLED") }
                },
                null
            )
        } catch (e: Exception) {
            reportDebug("SWIPE exception: ${e.message}")
            false
        }

        if (dispatched) {
            handler.post {
                reportDebug("SWIPE $tag TRYING\n$info")
                SwipeIndicator.animateSwipe(durMs)
            }
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

    private fun appendNodePart(sb: StringBuilder, text: CharSequence?) {
        if (text == null) return
        val s = text.toString()
        if (s.isBlank()) return
        if (sb.isNotEmpty()) sb.append(" | ")
        sb.append(s)
    }

    private fun collectFast(
        node: AccessibilityNodeInfo?,
        out: StringBuilder,
        acceptOut: Array<AccessibilityNodeInfo?>
    ) {
        if (node == null) return

        val t = node.text?.toString()?.takeIf { it.isNotBlank() }
        val d = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.takeIf { it.isNotBlank() }
        } else null
        val p = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            node.paneTitle?.toString()?.takeIf { it.isNotBlank() }
        } else null

        if (t != null) appendNodePart(out, t)
        if (d != null) appendNodePart(out, d)
        if (h != null) appendNodePart(out, h)
        if (p != null) appendNodePart(out, p)

        if (acceptOut[0] == null && (t != null || d != null || h != null)) {
            val raw = (t ?: d ?: h ?: "").lowercase()
            if (fastContainsAccept(raw) && !raw.contains("pickup") && !raw.contains("drop")) {
                val compact = NON_ALNUM_RE.replace(raw, "")
                if (ACCEPT_COMPACT.any { compact.contains(it) } || ACCEPT_WORDS.any { raw.contains(it) }) {
                    acceptOut[0] = node
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) collectFast(child, out, acceptOut)
        }
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: StringBuilder) {
        if (node == null) return

        appendNodePart(out, node.text)
        appendNodePart(out, node.contentDescription)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appendNodePart(out, node.hintText)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            appendNodePart(out, node.paneTitle)
        }

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) collectText(child, out)
        }
    }

    private fun reportDebug(message: String) {
        if (!DebugOverlay.ENABLED) return
        val now = SystemClock.uptimeMillis()
        if (message == lastReportMsg && now - lastReportAt < 1500L) return
        if (message.startsWith("Scan (") && now - lastReportAt < 1000L) return
        lastReportMsg = message
        lastReportAt = now
        val full = if (lastSwipeResult != "-" && !message.startsWith("SWIPE")) "$message\n[last: $lastSwipeResult]" else message
        DebugOverlay.update(this, full)
        updateStatusNotification(full)
    }

    private fun setStatus(status: String) {
        getSharedPreferences("clicker_live_status", MODE_PRIVATE)
            .edit()
            .putString("status", status)
            .apply()
        if (!DebugOverlay.ENABLED && status != lastNotifiedStatus) {
            lastNotifiedStatus = status
            updateStatusNotification("Status: $status")
        }
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
            .setContentTitle("BIPIN Clicker")
            .setContentText(message.take(60))
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
