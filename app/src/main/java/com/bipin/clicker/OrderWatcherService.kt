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

/**
 * Ye service HAR app ke upar kaam karti hai (kisi ek app tak limited nahi hai), aur
 * "overlay" order-cards (jo screen ke upar tairte hain) ko bhi padh leti hai.
 *
 * Jab bhi screen par kuch badalta hai (naya order aata hai), ye:
 * 1) Har khuli window ka text padhti hai (normal screen + overlay dono)
 * 2) Check karti hai ki aapki saved PICKUP city aur DROP city dono wahan
 *    dikh rahi hain ya nahi
 * 3) Agar dono match ho jayein, to "Accept / Confirm / Pickup / OK / Yes" jaisa
 *    button dhoondh kar pehle TAP karti hai; agar wo sirf SWIPE se accept hota hai
 *    (jaise "slide to accept" button) to us button par khud left-se-right swipe
 *    (gesture) bhi kar deti hai.
 *
 * Enable karne ke liye: Phone Settings -> Accessibility -> BIPIN Clicker -> ON karein.
 */
class OrderWatcherService : AccessibilityService() {

    companion object {
        private const val TAG = "OrderWatcherService"

        // Buttons ke ye words dhoondhe jaate hain (chhota-bada dono chalega).
        // Apni delivery app me jo bhi button ka naam ho, yahan add kar sakte ho.
        private val ACCEPT_WORDS = listOf(
            "accept", "confirm", "pickup", "pick up", "go", "ok", "yes", "start", "grab"
        )

        // Ek hi screen par baar-baar click na ho isliye chhota sa gap rakha hai.
        private const val CLICK_COOLDOWN_MS = 3000L

        private const val CHANNEL_ID = "bipin_clicker_status"
        private const val NOTIFICATION_ID = 501
    }

    private var lastClickTime = 0L
    private var lastClickedSignature: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        showOnNotification()
    }

    override fun onDestroy() {
        super.onDestroy()
        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(NOTIFICATION_ID)
    }

    /** Status bar me ek chhota persistent icon dikhata hai jab tak service chal rahi hai. */
    private fun showOnNotification() {
        val nm = getSystemService(NotificationManager::class.java) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BIPIN Clicker Status",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_on)
            .setContentTitle("BIPIN Clicker: ON")
            .setContentText("Auto-Accept chal raha hai")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        try {
            if (AreaPrefs.isPaused(applicationContext)) return

            // Sirf active window nahi, balki HAR khuli window (overlay order-card samet) check karo.
            val roots = mutableListOf<AccessibilityNodeInfo>()
            windows?.forEach { w -> w.root?.let { roots.add(it) } }
            rootInActiveWindow?.let { if (roots.isEmpty()) roots.add(it) }
            if (roots.isEmpty()) return

            for (root in roots) {
                // Apni khud ki app ki screen (Settings, Pickup/Drop list) kabhi bhi scan/click
                // nahi karni - warna wahan likhe city naam hi "match" ban jaate hain aur
                // service galat jagah click/swipe kar deti hai.
                if (root.packageName == packageName) continue

                val allTexts = mutableListOf<String>()
                collectText(root, allTexts)
                val screenText = allTexts.joinToString(" | ")

                if (AreaPrefs.matchesScreenText(applicationContext, screenText)) {
                    val signature = "${event.packageName}:${screenText.hashCode()}"
                    val now = System.currentTimeMillis()
                    if (signature == lastClickedSignature && now - lastClickTime < CLICK_COOLDOWN_MS) {
                        continue // isi screen par abhi-abhi click kar chuke hain, dobara mat karo
                    }

                    val clicked = findAndClickAcceptButton(root)
                    if (clicked) {
                        lastClickedSignature = signature
                        lastClickTime = now
                        Log.d(TAG, "Auto-clicked matching order in ${event.packageName}")
                    }
                    showDebugToast(
                        if (clicked) "BipinClicker: Order match mila, accept try kiya"
                        else "BipinClicker: Order match mila, par button nahi mila"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while scanning screen", e)
        }
    }

    /** Screen ke saare text nodes ek list me jama karta hai. */
    private fun showDebugToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()
        if (!text.isNullOrBlank()) out.add(text)
        val desc = node.contentDescription?.toString()
        if (!desc.isNullOrBlank()) out.add(desc)
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out)
        }
    }

    /** ACCEPT_WORDS me se koi bhi text jis node par ho, use dhoondh kar click/swipe karta hai. */
    private fun findAndClickAcceptButton(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        val text = (node.text?.toString() ?: node.contentDescription?.toString())?.lowercase()
        if (text != null && ACCEPT_WORDS.any { text.contains(it) }) {
            if (clickNodeOrParent(node)) return true
        }

        for (i in 0 until node.childCount) {
            if (findAndClickAcceptButton(node.getChild(i))) return true
        }
        return false
    }

    /**
     * Pehle node khud (ya upar wale clickable parent) par TAP try karta hai.
     * Agar tap kaam na kare (jaise "slide to accept" button), to us button ke
     * SABSE CHAUDE parent container (asli slider bar) par left-se-right SWIPE
     * try karta hai - chhote text label ke bounds par swipe karne se slider
     * trigger nahi hota, isliye poore container ka width use karte hain.
     */
    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                break
            }
            current = current.parent
            depth++
        }
        return swipeAcrossNode(widestAncestor(node))
    }

    /** Node se upar tak (max 5 level) jaake sabse zyada chaude bounds wala ancestor dhoondhta hai. */
    private fun widestAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var best = node
        var bestWidth = boundsWidth(node)
        var current: AccessibilityNodeInfo? = node.parent
        var depth = 0
        while (current != null && depth < 5) {
            val w = boundsWidth(current)
            if (w > bestWidth) {
                bestWidth = w
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

    /** Node ke bounds par left-se-right ek swipe/drag gesture karta hai ("slide to accept" ke liye). */
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

    override fun onInterrupt() {
        // Kuch karne ki zaroorat nahi
    }
}
