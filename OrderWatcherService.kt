package com.bipin.clicker

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Ye service HAR app ke upar kaam karti hai (kisi ek app tak limited nahi hai).
 *
 * Jab bhi screen par kuch badalta hai (naya order aata hai), ye:
 * 1) Poori screen ka text padhti hai
 * 2) Check karti hai ki aapki saved PICKUP city aur DROP city dono screen par
 *    dikh rahi hain ya nahi
 * 3) Agar dono match ho jayein, to screen par "Accept / Confirm / Pickup / OK / Yes"
 *    jaisa koi bhi clickable button dhoondh kar khud tap kar deti hai
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

        val root = rootInActiveWindow ?: return

        try {
            val pickupCities = AreaPrefs.getPickupCities(applicationContext)
            val dropCities = AreaPrefs.getDropCities(applicationContext)
            if (pickupCities.isEmpty() || dropCities.isEmpty()) return

            val allTexts = mutableListOf<String>()
            collectText(root, allTexts)
            val screenText = allTexts.joinToString(" | ")

            val pickupMatched = pickupCities.any { screenText.contains(it, ignoreCase = true) }
            val dropMatched = dropCities.any { screenText.contains(it, ignoreCase = true) }

            if (pickupMatched && dropMatched) {
                val signature = "${event.packageName}:${screenText.hashCode()}"
                val now = System.currentTimeMillis()
                if (signature == lastClickedSignature && now - lastClickTime < CLICK_COOLDOWN_MS) {
                    return // isi screen par abhi-abhi click kar chuke hain, dobara mat karo
                }

                val clicked = findAndClickAcceptButton(root)
                if (clicked) {
                    lastClickedSignature = signature
                    lastClickTime = now
                    Log.d(TAG, "Auto-clicked matching order in ${event.packageName}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error while scanning screen", e)
        } finally {
            root.recycle()
        }
    }

    /** Screen ke saare text nodes ek list me jama karta hai. */
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

    /** ACCEPT_WORDS me se koi bhi text jis node par ho, use dhoondh kar click karta hai. */
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

    /** Node khud clickable ho to usi par click karta hai, warna upar wale clickable parent par. */
    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            depth++
        }
        return false
    }

    override fun onInterrupt() {
        // Kuch karne ki zaroorat nahi
    }
}
