package com.bipin.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ClickerAccessibilityService : AccessibilityService() {

    private var targetWords: Set<String> = emptySet()
    private var lastActionKey = ""
    private var lastActionTime = 0L
    private var lastConfigRefresh = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        loadWords()
    }

    private fun loadWords() {
        val prefs = getSharedPreferences("clicker_prefs", Context.MODE_PRIVATE)
        targetWords = prefs.getStringSet("target_words", emptySet()) ?: emptySet()
        lastConfigRefresh = SystemClock.uptimeMillis()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val now = SystemClock.uptimeMillis()
        if (now - lastConfigRefresh > 500) loadWords()

        val prefs = getSharedPreferences("clicker_prefs", Context.MODE_PRIVATE)
        val pickupDropEnabled = prefs.getBoolean("pickup_drop_enabled", false)
        // Keep the automation armed after the user closes the app.
        // No action is taken unless a complete Pickup + Drop match is visible.
        val running = prefs.getBoolean("automation_running", pickupDropEnabled)

        if (pickupDropEnabled && running) {
            val pickup = prefs.getString("pickup", "")?.trim().orEmpty()
            val drop = prefs.getString("drop", "")?.trim().orEmpty()
            if (pickup.isNotEmpty() && drop.isNotEmpty()) {
                // IMPORTANT: when Pickup/Drop mode is enabled, never run the
                // old generic word-clicker. This prevents unrelated taps while
                // the user is normally using the phone.
                processMatchingRide(pickup, drop)
            }
            return
        }

        if (targetWords.isEmpty()) return
        clickMatchingWords(targetWords)
    }

    /**
     * Finds pickup + drop in the same ride/card and then automatically handles
     * the accept control. It supports:
     *  - normal clickable Accept/Book controls
     *  - slide/swipe controls such as "Accept in 6s"
     *  - city wildcards such as "all delhi", "all noida", "all gurugram"
     */
    private fun processMatchingRide(pickup: String, drop: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val pickupCandidates = ArrayList<AccessibilityNodeInfo>()
        val dropCandidates = ArrayList<AccessibilityNodeInfo>()
        collectMatchingNodes(root, pickup, pickupCandidates)
        collectMatchingNodes(root, drop, dropCandidates)

        if (pickupCandidates.isEmpty() || dropCandidates.isEmpty()) return false

        for (p in pickupCandidates) {
            for (d in dropCandidates) {
                val common = findCommonAncestor(p, d) ?: continue

                // First preference: an Accept/Book/Slide control inside this same ride.
                val actionNode = findActionNode(common)
                if (actionNode != null) {
                    val actionText = nodeLabel(actionNode)
                    val key = "${nodeLabel(p)}|${nodeLabel(d)}|$actionText"
                    if (isCoolingDown(key)) continue

                    // A slider normally exposes text such as "Accept in 6s".
                    // Drag it from the left side of its bounds to the right side.
                    if (looksLikeSlider(actionText)) {
                        if (swipeAction(actionNode)) {
                            markAction(key)
                            return true
                        }
                    }

                    // Normal button/action: use Accessibility click or its parent.
                    if (safeClick(actionNode, key)) return true
                }

                // Fallback: if the ride/card itself is clickable, tap it.
                if (safeClick(common, "${nodeLabel(p)}|${nodeLabel(d)}")) return true
            }
        }
        return false
    }

    private fun findActionNode(container: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(container)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 250) {
            val node = queue.removeFirst()
            val label = nodeLabel(node).lowercase()
            if (label.contains("accept") || label.contains("book") ||
                label.contains("take order") || label.contains("slide")) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun looksLikeSlider(label: String): Boolean {
        val s = label.lowercase()
        return s.contains("accept in") || s.contains("slide") ||
            s.contains("swipe") || s.contains("slide to")
    }

    private fun swipeAction(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() < 80 || bounds.height() < 20) return false

        // For a left-to-right accept slider, start near the thumb/left side
        // and finish near the right side of the same control.
        val startX = bounds.left + (bounds.width() * 0.12f)
        val endX = bounds.right - (bounds.width() * 0.12f)
        val y = bounds.centerY().toFloat()
        if (startX >= endX) return false

        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 550))
            .build()

        return dispatchGesture(gesture, null, null)
    }

    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo,
        target: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        val text = node.text?.toString()?.trim().orEmpty()
        val contentDescription = node.contentDescription?.toString()?.trim().orEmpty()
        if (matchesLocation(text, target) || matchesLocation(contentDescription, target)) out.add(node)

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectMatchingNodes(it, target, out) }
        }
    }

    private fun matchesLocation(actualText: String, requested: String): Boolean {
        val actual = normalize(actualText)
        val wanted = normalize(requested)
        if (actual.isEmpty() || wanted.isEmpty()) return false

        if (wanted.startsWith("all ")) {
            val city = normalizeCity(wanted.removePrefix("all ").trim())
            if (city.isEmpty()) return false
            return cityAliases(city).any { actual.contains(it) }
        }
        return actual == wanted || actual.contains(wanted)
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace("–", "-")
        .replace("—", "-")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun normalizeCity(city: String): String = when (city) {
        "gurgaon", "gurugam", "gurugram" -> "gurugram"
        "new delhi", "delhi" -> "delhi"
        "noida" -> "noida"
        "ghaziabad", "gaziabad" -> "ghaziabad"
        else -> city
    }

    private fun cityAliases(city: String): List<String> = when (normalizeCity(city)) {
        "gurugram" -> listOf("gurugram", "gurgaon", "gurugam")
        "delhi" -> listOf("delhi", "new delhi")
        "noida" -> listOf("noida")
        "ghaziabad" -> listOf("ghaziabad", "gaziabad")
        else -> listOf(city)
    }

    private fun findCommonAncestor(first: AccessibilityNodeInfo, second: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val firstAncestors = ArrayList<AccessibilityNodeInfo>()
        var a: AccessibilityNodeInfo? = first
        var depth = 0
        while (a != null && depth++ < 15) {
            firstAncestors.add(a)
            a = a.parent
        }
        var b: AccessibilityNodeInfo? = second
        depth = 0
        while (b != null && depth++ < 15) {
            if (firstAncestors.any { it == b }) return b
            b = b.parent
        }
        return null
    }

    private fun clickMatchingWords(words: Set<String>) {
        for (word in words) if (clickTarget(word)) return
    }

    private fun clickTarget(target: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val wanted = target.trim()
        if (wanted.isEmpty()) return false
        val nodes = root.findAccessibilityNodeInfosByText(wanted)
        for (node in nodes) {
            val text = nodeLabel(node)
            if (text.equals(wanted, ignoreCase = true) || text.contains(wanted, ignoreCase = true)) {
                if (safeClick(node, "word:$text")) return true
            }
        }
        return false
    }

    private fun safeClick(node: AccessibilityNodeInfo, key: String): Boolean {
        if (isCoolingDown(key)) return false
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth++ < 12) {
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    markAction(key)
                    return true
                }
                return false
            }
            current = current.parent
        }
        return false
    }

    private fun isCoolingDown(key: String): Boolean {
        return key == lastActionKey && SystemClock.uptimeMillis() - lastActionTime < 1800
    }

    private fun markAction(key: String) {
        lastActionKey = key
        lastActionTime = SystemClock.uptimeMillis()
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String {
        return node.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: node.contentDescription?.toString()?.trim().orEmpty()
    }

    override fun onInterrupt() {}
}
