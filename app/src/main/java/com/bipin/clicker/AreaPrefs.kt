package com.bipin.clicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Saari settings yahan persist hoti hain (SharedPreferences me JSON ke roop me),
 * app restart / phone restart ke baad bhi bani rehti hain.
 *
 * Selection model: Map<cityName, Set<locality>> - agar kisi city ke liye set me
 * sirf ALL_MARKER ho, to us poori city ki har (verified + custom) locality match
 * hoti hai.
 */
object AreaPrefs {

    const val ALL_MARKER = "__ALL__"

    private const val PREFS_NAME = "clicker_area_prefs"
    private const val KEY_PICKUP_JSON = "pickup_selections_json"
    private const val KEY_DROP_JSON = "drop_selections_json"
    private const val KEY_CUSTOM_JSON = "custom_localities_json"
    private const val KEY_PAUSED = "is_paused"

    // ---------- Pause / resume (floating bubble) ----------

    fun setPaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSED, paused).apply()
    }

    fun isPaused(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PAUSED, false)

    // ---------- Pickup / Drop selections ----------

    fun getPickupSelections(context: Context): Map<String, Set<String>> =
        readSelections(context, KEY_PICKUP_JSON)

    fun setPickupSelections(context: Context, selections: Map<String, Set<String>>) {
        writeSelections(context, KEY_PICKUP_JSON, selections)
    }

    fun getDropSelections(context: Context): Map<String, Set<String>> =
        readSelections(context, KEY_DROP_JSON)

    fun setDropSelections(context: Context, selections: Map<String, Set<String>>) {
        writeSelections(context, KEY_DROP_JSON, selections)
    }

    // ---------- User-added custom localities ----------

    fun getCustomLocalities(context: Context): Map<String, List<String>> {
        val raw = prefs(context).getString(KEY_CUSTOM_JSON, null) ?: return emptyMap()
        val result = LinkedHashMap<String, List<String>>()
        try {
            val obj = JSONObject(raw)
            obj.keys().forEach { city ->
                val arr = obj.getJSONArray(city)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                result[city] = list
            }
        } catch (_: Exception) { }
        return result
    }

    fun addCustomLocality(context: Context, city: String, locality: String) {
        val cleanCity = city.trim()
        val cleanLocality = locality.trim()
        if (cleanCity.isEmpty() || cleanLocality.isEmpty()) return

        val current = getCustomLocalities(context).toMutableMap()
        val existing = current[cleanCity].orEmpty().toMutableList()
        val already = existing.any { it.equals(cleanLocality, ignoreCase = true) }
        if (!already) existing.add(cleanLocality)
        current[cleanCity] = existing

        val obj = JSONObject()
        current.forEach { (c, list) -> obj.put(c, JSONArray(list)) }
        prefs(context).edit().putString(KEY_CUSTOM_JSON, obj.toString()).apply()
    }

    // ---------- Matching (real screen text ke against) ----------

    /**
     * Order tabhi match hota hai jab:
     * - Drop selections me se koi bhi city/locality screen ke text me mile (Drop hamesha zaroori hai)
     * - Pickup selections khaali hain (koi restriction nahi) YA Pickup me se koi city/locality bhi mile
     *
     * "All <City>" select karne par sirf city ka naam screen text me dhoondha jaata hai
     * (kisi bhi sector/locality ke saath match ho jaata hai). Ek specific locality select
     * karne par city ka naam AUR us locality ka naam - dono screen text me hone chahiye
     * (isse "Sector 5" jaisा common naam galti se doosri city se match nahi karta).
     */
    fun matchesScreenText(context: Context, rawScreenText: String): Boolean {
        val text = normalize(rawScreenText)
        val dropSelections = getDropSelections(context)
        val pickupSelections = getPickupSelections(context)

        if (dropSelections.isEmpty()) return false // Drop set kiye bina kabhi match nahi hoga

        val dropMatched = selectionsMatchText(dropSelections, text)
        if (!dropMatched) return false

        val pickupMatched = pickupSelections.isEmpty() || selectionsMatchText(pickupSelections, text)
        return pickupMatched
    }

    private fun selectionsMatchText(selections: Map<String, Set<String>>, normalizedText: String): Boolean {
        val compactText = normalizedText.replace(" ", "")

        for ((city, localities) in selections) {
            val cityNorm = normalize(city)
            if (cityNorm.isEmpty()) continue

            val cityVariants = cityAliases(cityNorm)
            val cityMatched = cityVariants.any { variant ->
                normalizedText.contains(variant) || compactText.contains(variant.replace(" ", ""))
            }

            // "All <city>" = city milte hi match. Kuch delivery apps city ko
            // address ki alag line me dikhati hain, isliye compact form bhi check karo.
            if (localities.contains(ALL_MARKER)) {
                if (cityMatched) return true
                continue
            }

            // Specific locality: pehle city + locality dono try karo.
            // Agar delivery app city ko Accessibility text me expose hi nahi karti,
            // to selected locality ko akela bhi accept karo. Isse "Order not match"
            // unnecessarily nahi aayega jab locality clearly screen par hai.
            for (locality in localities) {
                val localityNorm = normalize(locality)
                if (localityNorm.isEmpty()) continue
                val localityCompact = localityNorm.replace(" ", "")
                val localityMatched = normalizedText.contains(localityNorm) ||
                    compactText.contains(localityCompact)

                if (cityMatched && localityMatched) return true
                if (localityMatched) return true
            }
        }
        return false
    }

    // City ke alternate/purane naam jo delivery apps ke order screen par aa sakte hain.
    // Key hamesha normalize() kiya hua (lowercase) hona chahiye.
    private val CITY_ALIASES: Map<String, List<String>> = mapOf(
        "gurugram" to listOf("gurugram", "gurgaon", "ggn"),
        "delhi" to listOf("delhi", "new delhi", "ncr"),
        "greater noida" to listOf("greater noida", "gr. noida", "gr noida"),
    )

    private fun cityAliases(normalizedCity: String): List<String> =
        CITY_ALIASES[normalizedCity] ?: listOf(normalizedCity)

    private fun normalize(s: String): String =
        s.trim().lowercase().replace(Regex("\\s+"), " ")

    // ---------- internal JSON helpers ----------

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readSelections(context: Context, key: String): Map<String, Set<String>> {
        val raw = prefs(context).getString(key, null) ?: return emptyMap()
        val result = LinkedHashMap<String, Set<String>>()
        try {
            val obj = JSONObject(raw)
            obj.keys().forEach { city ->
                val arr = obj.getJSONArray(city)
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) set.add(arr.getString(i))
                result[city] = set
            }
        } catch (_: Exception) { }
        return result
    }

    private fun writeSelections(context: Context, key: String, selections: Map<String, Set<String>>) {
        val obj = JSONObject()
        selections.forEach { (city, set) -> obj.put(city, JSONArray(set.toList())) }
        prefs(context).edit().putString(key, obj.toString()).apply()
    }
}
