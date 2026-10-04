package com.bipin.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
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

        private const val FAST_RETRY_MS = 60L
        private const val FAST_RETRY_WINDOW_MS = 5200L
        private const val CLICK_COOLDOWN_MS = 450L
        private const val OCR_INTERVAL_MS = 250L
        // Ek order (popup) par kitni baar swipe karna hai. Abhi: SIRF EK BAAR.
        private const val MAX_TRIES_PER_POPUP = 1
        // Swipe kitni der ka ho (ms). Ek hi seedha, dheere-dheere kheencha hua swipe.
        private const val SWIPE_MS = 600L

        private const val VERIFY_INTERVAL_MS = 150L
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

    // FIX: scan throttle + gesture overlap guard + debug spam guard
    private var lastScanAt = 0L
    private var ocrBusy = false
    private var lastOcrHeartbeat = 0L
    private var ocrNoSupportShown = false
    private var slideInFlightUntil = 0L
    private var lastReportMsg = ""
    private var lastReportAt = 0L
    private var verifySwipesDone = 0
    private var ocrAttempts = 0
    private var treeDoneAt = 0L
    private var ocrLastSeenAt = 0L
    private var swipeTry = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        SwipeZone.service = this
        showOnNotification()
        setStatus("READY")
        // OCR loop: screen ke PIXELS se text padhta hai (photo/screenshot/camera me dikhi screen bhi)
        handler.postDelayed(ocrRunnable, 1500L)

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
        // FIX: apne hi overlay/notification ke events se scan loop na bane
        if (event.packageName?.toString() == packageName) return

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

        // FIX: itne events aate hain ki main thread jam ho jata tha -> gesture late/cancel
        val tNow = SystemClock.uptimeMillis()
        if (tNow - lastScanAt < 50L) return
        lastScanAt = tNow

        val roots = currentRoots()
        if (roots.isEmpty()) return

        for (root in roots) {
            try {
                if (root.packageName == packageName) continue
                val pk = root.packageName?.toString()
                if (pk == "com.oplus.screenrecorder" || pk == "com.android.systemui") continue

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
                if (now - treeDoneAt > 9000L && (signature != lastSignature || now - lastActionAt >= 220L)) {
                    if (findAndAccept(root)) {
                        treeDoneAt = now
                        lastSignature = signature
                        lastActionAt = now
                        setStatus("ORDER UTHA RAHA")
                        showDebugToast("Bipin Clicker: Order uthane ka action")
                        reportDebug("ORDER UTHA RAHA - live Accept control par gesture bheja")
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

            if (SwipeZone.isSet(applicationContext) && slideGesture(0f, 0f, 0f, "ZONE")) return true
            // STRICT: slider ka dabba nahi mila to andaze se swipe nahi (OCR wala tareeka pixel se pakadta hai)

            val clicked = clickNodeOrParent(node, tapFallback = true)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (!ocrNoSupportShown) {
                ocrNoSupportShown = true
                reportDebug("OCR ke liye Android 11+ chahiye (is phone par band)")
            }
            return
        }
        if (ocrBusy || AreaPrefs.isPaused(applicationContext)) return
        // Action ke turant baad screenshot mat lo (phone ka screenshot popup slider ke upar aa jata hai)
        if (System.currentTimeMillis() - lastActionAt < 600L) return
        if (SystemClock.uptimeMillis() < slideInFlightUntil) return
        val pm = getSystemService(PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return

        ocrBusy = true
        ScreenOcrScanner.capture(this) { res, err ->
            handler.post {
                ocrBusy = false
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

        if (!AreaPrefs.matchesScreenText(applicationContext, text)) {
            reportDebug("OCR: Accept mila par Pickup/Drop match NAHI\n${text.take(140)}")
            return
        }
        val nowT = System.currentTimeMillis()
        if (nowT - ocrLastSeenAt > 2000L) ocrAttempts = 0 // popup gayab hua tha -> naya order
        ocrLastSeenAt = nowT
        if (ocrAttempts >= MAX_TRIES_PER_POPUP) {
            // is order par ek koshish ho chuki; popup khatam hone tak kuch nahi karna
            return
        }
        if (nowT - lastActionAt < 600L) return
        // Pichla swipe abhi chal raha hai -> naya mat bhejo, aur koshish ginti me mat jodo
        if (SystemClock.uptimeMillis() < slideInFlightUntil) return

        setStatus("ORDER FOUND")
        ocrAttempts++
        val bmpW = res.bitmap.width.toFloat()
        val slider = ScreenOcrScanner.estimateSlider(res.bitmap, acceptLine.box)
        val ok = if (slider != null) {
            val h = slider.height().toFloat()
            // Thumb ke THEEK upar se pakdo (pixel se mila)
            val thumbX = ScreenOcrScanner.findThumbCenterX(res.bitmap, slider)?.toFloat()
                ?: (slider.left + h * 0.95f)
            slideGesture(
                thumbX,
                slider.exactCenterY(),
                slider.right - h * 0.10f,
                "SLIDER pixel se, try $ocrAttempts"
            )
        } else {
            // Slider ka rang nahi pakda: "Accept in Ns" text ki height par hi swipe (wahi pill ka beech hai)
            slideGesture(
                bmpW * 0.23f,
                acceptLine.box.exactCenterY(),
                bmpW * 0.90f,
                "TEXT-y se, try $ocrAttempts"
            )
        }
        lastActionAt = System.currentTimeMillis()
        if (ok) {
            setStatus("ORDER UTHA RAHA")
            showDebugToast("Bipin Clicker: OCR se order uthane ka action")
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
    private fun geometricSlideFromNode(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        try { node.getBoundsInScreen(r) } catch (_: Exception) { return false }
        if (r.width() <= 0 || r.height() <= 0 || r.height() > 300) return false
        val w = resources.displayMetrics.widthPixels.toFloat()
        return slideGesture(w * 0.229f, r.exactCenterY(), w * 0.90f, "GEOM text=$r")
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
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        val ok = try { dispatchGesture(gesture, null, null) } catch (_: Exception) { false }
        reportDebug("TAP fallback at ${r.centerX()},${r.centerY()} dispatched=$ok")
        return ok
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
        return slideGesture(startX, y, endX, info)
    }

    private var swipeStyle = 0
    private var lastSwipeResult = "-"

    /**
     * Video se dikha: pehle wala "hold + drag" (2 gesture jodkar) me thumb sirf ~40px hilta tha,
     * phir wapas laut jata tha. Is phone/app par shayad wo tareeka chalta nahi. Isliye ab har
     * koshish me alag STYLE aazmata hai (A, B, C, D), jo chal jaye. Debug box me har koshish
     * ka natija "[last: styleX ...]" me dikhta hai.
     */
    private fun slideGesture(startX0: Float, y0: Float, endX0: Float, info0: String): Boolean {
        if (SwipeZone.isEditing()) {
            SwipeZone.closeEditor()
            handler.postDelayed({ slideGesture(startX0, y0, endX0, info0) }, 120L)
            return true
        }

        val dm = resources.displayMetrics
        val maxX = dm.widthPixels.toFloat() - 1f
        val maxY = dm.heightPixels.toFloat() - 1f

        // FIX: pehle yahan sahi jagah (OCR/pixel se mili) pheink kar hamesha purani manual green
        // line use hoti thi - wo slider se door thi, isliye swipe slider par girta hi nahi tha.
        // Ab pehle live mili jagah use hoti hai; green zone sirf backup hai.
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

        // FIX (video se): thumb finger ke saath thoda chalta tha par finger turant chhoot jata tha,
        // isliye slider wapas laut jata tha. Ab insaan jaisa swipe: pehle thumb PAKDO (ruko),
        // phir KHEECHO, phir END par thoda RUKO, tab chhodo. Har koshish me alag style.
        val style = swipeTry++ % 4
        val tag = "$src ${'A' + style}"
        val overshoot = (endX + 26f).coerceAtMost(maxX)
        val info = "$tag start=${startX.toInt()},${y.toInt()} end=${endX.toInt()} | $info0"
        val t0 = SystemClock.uptimeMillis()
        slideInFlightUntil = t0 + 4000L

        fun fin(ok: Boolean, what: String) {
            // thumb ko wapas laut'ne ka time do, phir agli koshish
            slideInFlightUntil = SystemClock.uptimeMillis() + 450L
            lastSwipeResult = "$tag ${if (ok) "DONE" else what}"
            reportDebug("SWIPE $lastSwipeResult ${SystemClock.uptimeMillis() - t0}ms\n$info")
        }

        fun single(durationMs: Long, toX: Float): Boolean {
            val path = Path().apply {
                moveTo(startX, y)
                lineTo(startX + 8f, y)
                lineTo(toX, y)
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

        fun chain(holdMs: Long, dragMs: Long, endHoldMs: Long, toX: Float): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return single(holdMs + dragMs + endHoldMs, toX)
            val holdPath = Path().apply { moveTo(startX, y); lineTo(startX + 1f, y) }
            val s1 = GestureDescription.StrokeDescription(holdPath, 0L, holdMs, true)
            return dispatchGesture(
                GestureDescription.Builder().addStroke(s1).build(),
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        val dragPath = Path().apply { moveTo(startX + 1f, y); lineTo(toX, y) }
                        val s2 = s1.continueStroke(dragPath, 0L, dragMs, true)
                        val ok2 = try {
                            dispatchGesture(
                                GestureDescription.Builder().addStroke(s2).build(),
                                object : GestureResultCallback() {
                                    override fun onCompleted(g2: GestureDescription?) {
                                        val endPath = Path().apply { moveTo(toX, y); lineTo(toX - 1f, y) }
                                        val s3 = s2.continueStroke(endPath, 0L, endHoldMs, false)
                                        val ok3 = try {
                                            dispatchGesture(
                                                GestureDescription.Builder().addStroke(s3).build(),
                                                object : GestureResultCallback() {
                                                    override fun onCompleted(g3: GestureDescription?) { fin(true, "") }
                                                    override fun onCancelled(g3: GestureDescription?) { fin(false, "CANCELLED(end)") }
                                                },
                                                null
                                            )
                                        } catch (e: Exception) { false }
                                        if (!ok3) fin(false, "REJECTED(end)")
                                    }
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
            single(SWIPE_MS, endX)
        } catch (e: Exception) {
            reportDebug("SWIPE exception: ${e.message}")
            false
        }

        if (dispatched) {
            reportDebug("SWIPE $tag TRYING\n$info")
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
        // FIX: har 100ms par overlay + notification update karne se system throttle karta tha
        // aur main thread bhari ho jata tha.
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
        SwipeIndicator.hide()
        SwipeZone.closeEditor()
        SwipeZone.service = null
        getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        super.onDestroy()
    }
}
