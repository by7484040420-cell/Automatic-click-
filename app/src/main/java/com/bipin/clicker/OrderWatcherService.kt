package com.bipin.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
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
    }

    private var lastClickTime = 0L
    private var lastClickedSignature: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) return

        try {
            val pickupCities = AreaPrefs.getPickupCities(applicationContext)
            val dropCities = AreaPrefs.getDropCities(applicationContext)
            if (pickupCities.isEmpty() || dropCities.isEmpty()) return

            // Sirf active window nahi, balki HAR khuli window (overlay order-card samet) check karo.
            val roots = mutableListOf<AccessibilityNodeInfo>()
            windows?.forEach { w -> w.root?.let { roots.add(it) } }
            rootInActiveWindow?.let { if (roots.isEmpty()) roots.add(it) }
            if (roots.isEmpty()) return

            for (root in roots) {
                val allTexts = mutableListOf<String>()
                collectText(root, allTexts)
                val screenText = allTexts.joinToString(" | ")

                val pickupMatched = pickupCities.any { screenText.contains(it, ignoreCase = true) }
                val dropMatched = dropCities.any { screenText.contains(it, ignoreCase = true) }

                if (pickupMatched && dropMatched) {
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
     * Agar tap kaam na kare (jaise "slide to accept" button), to left-se-right
     * SWIPE gesture try karta hai.
     */
    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                return swipeAcrossNode(current)
            }
            current = current.parent
            depth++
        }
        // Kabhi-kabhi button khud clickable flag nahi rakhta, tab bhi uske apne
        // bounds par seedha swipe try kar lete hain.
        return swipeAcrossNode(node)
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
