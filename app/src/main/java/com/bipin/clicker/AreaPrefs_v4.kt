package com.bipin.clicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object AreaPrefs {
    const val ALL_MARKER = "__ALL__"
    private const val PREFS_NAME = "clicker_area_prefs"
    private const val KEY_PICKUP_JSON = "pickup_selections_json"
    private const val KEY_DROP_JSON = "drop_selections_json"
    private const val KEY_CUSTOM_JSON = "custom_localities_json"
    private const val KEY_PICKUP_MANUAL = "pickup_manual_text"
    private const val KEY_DROP_MANUAL = "drop_manual_text"
    private const val KEY_PAUSED = "is_paused"

    fun setPaused(context: Context, paused: Boolean) { prefs(context).edit().putBoolean(KEY_PAUSED, paused).apply() }
    fun isPaused(context: Context): Boolean = prefs(context).getBoolean(KEY_PAUSED, false)

    fun getPickupSelections(context: Context): Map<String, Set<String>> = readSelections(context, KEY_PICKUP_JSON)
    fun setPickupSelections(context: Context, selections: Map<String, Set<String>>) = writeSelections(context, KEY_PICKUP_JSON, selections)
    fun getDropSelections(context: Context): Map<String, Set<String>> = readSelections(context, KEY_DROP_JSON)
    fun setDropSelections(context: Context, selections: Map<String, Set<String>>) = writeSelections(context, KEY_DROP_JSON, selections)

    fun getManualPickup(context: Context): String = prefs(context).getString(KEY_PICKUP_MANUAL, "") ?: ""
    fun setManualPickup(context: Context, value: String) { prefs(context).edit().putString(KEY_PICKUP_MANUAL, value.trim()).apply() }
    fun getManualDrop(context: Context): String = prefs(context).getString(KEY_DROP_MANUAL, "") ?: ""
    fun setManualDrop(context: Context, value: String) { prefs(context).edit().putString(KEY_DROP_MANUAL, value.trim()).apply() }

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
        val cleanCity = city.trim(); val cleanLocality = locality.trim()
        if (cleanCity.isEmpty() || cleanLocality.isEmpty()) return
        val current = getCustomLocalities(context).toMutableMap()
        val existing = current[cleanCity].orEmpty().toMutableList()
        if (existing.none { it.equals(cleanLocality, true) }) existing.add(cleanLocality)
        current[cleanCity] = existing
        val obj = JSONObject(); current.forEach { (c, list) -> obj.put(c, JSONArray(list)) }
        prefs(context).edit().putString(KEY_CUSTOM_JSON, obj.toString()).apply()
    }

    fun matchesScreenText(context: Context, rawScreenText: String): Boolean {
        val text = normalize(rawScreenText)
        val drop = getDropSelections(context)
        val pickup = getPickupSelections(context)
        val manualDrop = getManualDrop(context)
        val manualPickup = getManualPickup(context)

        val dropOk = if (manualDrop.isNotBlank()) phraseMatches(text, manualDrop) else drop.isNotEmpty() && selectionsMatchText(drop, text)
        if (!dropOk) return false
        val pickupOk = if (manualPickup.isNotBlank()) phraseMatches(text, manualPickup) else pickup.isEmpty() || selectionsMatchText(pickup, text)
        return pickupOk
    }

    private fun phraseMatches(text: String, phrase: String): Boolean {
        val n = normalize(phrase)
        if (n.isEmpty()) return false
        return text.contains(n) || text.replace(" ", "").contains(n.replace(" ", ""))
    }

    private fun selectionsMatchText(selections: Map<String, Set<String>>, text: String): Boolean {
        val compact = text.replace(" ", "")
        for ((city, localities) in selections) {
            val cityMatched = cityAliases(normalize(city)).any { text.contains(it) || compact.contains(it.replace(" ", "")) }
            if (localities.contains(ALL_MARKER)) { if (cityMatched) return true; continue }
            for (locality in localities) {
                val n = normalize(locality)
                val locMatched = text.contains(n) || compact.contains(n.replace(" ", ""))
                // Specific selection is always city + locality. Never locality alone.
                if (cityMatched && locMatched) return true
            }
        }
        return false
    }

    private val CITY_ALIASES = mapOf(
        "gurugram" to listOf("gurugram", "gurgaon", "ggn"),
        "delhi" to listOf("delhi", "new delhi"),
        "noida" to listOf("noida"),
        "ghaziabad" to listOf("ghaziabad"),
        "greater noida" to listOf("greater noida", "gr. noida", "gr noida"),
        "faridabad" to listOf("faridabad", "ballabhgarh")
    )

    private fun cityAliases(city: String): List<String> = CITY_ALIASES[city] ?: listOf(city)
    private fun normalize(s: String): String = s.trim().lowercase().replace(Regex("\\s+"), " ")
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readSelections(context: Context, key: String): Map<String, Set<String>> {
        val raw = prefs(context).getString(key, null) ?: return emptyMap()
        val result = LinkedHashMap<String, Set<String>>()
        try {
            val obj = JSONObject(raw)
            obj.keys().forEach { city ->
                val arr = obj.getJSONArray(city); val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) set.add(arr.getString(i))
                result[city] = set
            }
        } catch (_: Exception) { }
        return result
    }

    private fun writeSelections(context: Context, key: String, selections: Map<String, Set<String>>) {
        val obj = JSONObject(); selections.forEach { (city, set) -> obj.put(city, JSONArray(set.toList())) }
        prefs(context).edit().putString(key, obj.toString()).apply()
    }
}
