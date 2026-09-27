package com.bipin.clicker

import android.content.Context
import org.json.JSONObject

/**
 * locations.json se verified cities+localities padhta hai. Ye ek STARTER list hai
 * (poori/official database nahi hai) - user apni custom areas AreaPrefs ke through
 * add kar sakta hai, jo verified list ke saath merge ho jaati hain (LOCALITY select
 * karte waqt).
 */
object LocationRepository {

    /** Verified cities -> unki localities (locations.json se, sorted). */
    fun loadVerifiedCities(context: Context): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        try {
            val json = context.assets.open("locations.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val cities = obj.getJSONObject("cities")
            cities.keys().forEach { city ->
                val arr = cities.getJSONArray(city)
                val list = mutableListOf<String>()
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                result[city] = list.sorted()
            }
        } catch (e: Exception) {
            // locations.json missing/malformed -> khaali list milegi, app crash nahi hogi
        }
        return result
    }

    /** Verified + is device par user ke khud add ki hui localities (merged, duplicate-free). */
    fun loadAllCitiesWithCustom(context: Context): Map<String, List<String>> {
        val verified = loadVerifiedCities(context)
        val custom = AreaPrefs.getCustomLocalities(context)
        val merged = LinkedHashMap<String, List<String>>()

        val allCityNames = (verified.keys + custom.keys).distinct()
        for (city in allCityNames) {
            val base = verified[city].orEmpty()
            val extra = custom[city].orEmpty()
            merged[city] = (base + extra).distinctBy { it.trim().lowercase() }
        }
        return merged
    }

    fun cityNames(context: Context): List<String> = loadAllCitiesWithCustom(context).keys.toList()
}
