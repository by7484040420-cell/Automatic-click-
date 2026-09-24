package com.bipin.clicker

import android.content.Context
import org.json.JSONObject

/** One specific sector/area, tagged with which city/zone it belongs to. */
data class LocationEntry(val city: String, val area: String) {
    val label: String get() = "$city - $area"
}

object LocationRepository {

    /** Every sector/area from locations.json, each tagged with its city. */
    fun loadAllEntries(context: Context): List<LocationEntry> {
        val result = mutableListOf<LocationEntry>()
        try {
            val json = context.assets.open("locations.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val cities = obj.getJSONObject("cities")
            cities.keys().forEach { city ->
                val arr = cities.getJSONArray(city)
                for (i in 0 until arr.length()) {
                    result.add(LocationEntry(city, arr.getString(i)))
                }
            }
        } catch (e: Exception) {
            // If the file is missing or malformed, just return whatever we have so far
        }
        return result
    }

    /** Just the city/zone names (e.g. "Gurugram", "Noida", "Delhi - Rohini") - used for selection. */
    fun loadCityNames(context: Context): List<String> {
        return loadAllEntries(context).map { it.city }.distinct().sorted()
    }
}
