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

    @Volatile private var cachedVerified: Map<String, List<String>>? = null
    @Volatile private var cachedPins: Map<String, Set<String>>? = null

    /** Natural numeric comparator: Sector 1, Sector 2, ..., Sector 10, ..., Sector 34. */
    val naturalComparator: Comparator<String> = Comparator { a, b ->
        compareNatural(a, b)
    }

    private fun compareNatural(a: String, b: String): Int {
        var ia = 0
        var ib = 0
        val la = a.length
        val lb = b.length
        while (ia < la && ib < lb) {
            val ca = a[ia]
            val cb = b[ib]
            val da = ca.isDigit()
            val db = cb.isDigit()
            if (da && db) {
                var za = ia
                while (za < la && a[za] == '0') za++
                var zb = ib
                while (zb < lb && b[zb] == '0') zb++

                var ea = za
                while (ea < la && a[ea].isDigit()) ea++
                var eb = zb
                while (eb < lb && b[eb].isDigit()) eb++

                val lenA = ea - za
                val lenB = eb - zb
                if (lenA != lenB) return lenA.compareTo(lenB)

                for (k in 0 until lenA) {
                    val cmp = a[za + k].compareTo(b[zb + k])
                    if (cmp != 0) return cmp
                }

                // Tie-breaker by leading zeros count if numeric values are equal
                val rawLenA = ea - ia
                val rawLenB = eb - ib
                if (rawLenA != rawLenB) return rawLenA.compareTo(rawLenB)

                ia = ea
                ib = eb
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                ia++
                ib++
            }
        }
        return la.compareTo(lb)
    }

    /** Verified cities -> unki localities (locations.json se, natural numeric sorted & cached). */
    fun loadVerifiedCities(context: Context): Map<String, List<String>> {
        cachedVerified?.let { return it }
        val result = LinkedHashMap<String, List<String>>()
        try {
            val json = context.applicationContext.assets.open("locations.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val cities = obj.getJSONObject("cities")
            cities.keys().forEach { city ->
                val arr = cities.getJSONArray(city)
                val list = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) list.add(arr.getString(i))
                list.sortWith(naturalComparator)
                result[city] = list
            }
            if (result.isNotEmpty()) cachedVerified = result
        } catch (e: Exception) {
            // locations.json missing/malformed -> khaali list milegi, app crash nahi hogi
        }
        return result
    }

    /** Delhi ke zone (North/South...) ke pincode: "All Delhi - South" jaisa option inhi se pehchanta hai. */
    fun loadZonePins(context: Context): Map<String, Set<String>> {
        cachedPins?.let { return it }
        val result = LinkedHashMap<String, Set<String>>()
        try {
            val json = context.applicationContext.assets.open("locations.json").bufferedReader().use { it.readText() }
            val pins = JSONObject(json).optJSONObject("zone_pincodes") ?: return result
            pins.keys().forEach { zone ->
                val arr = pins.getJSONArray(zone)
                val set = mutableSetOf<String>()
                for (i in 0 until arr.length()) set.add(arr.getString(i))
                result[zone] = set
            }
            if (result.isNotEmpty()) cachedPins = result
        } catch (e: Exception) {
        }
        return result
    }

    /** Verified + is device par user ke khud add ki hui localities (merged, duplicate-free, natural sorted). */
    fun loadAllCitiesWithCustom(context: Context): Map<String, List<String>> {
        val verified = loadVerifiedCities(context)
        val custom = AreaPrefs.getCustomLocalities(context)
        val merged = LinkedHashMap<String, List<String>>()

        val allCityNames = (verified.keys + custom.keys).distinct()
        for (city in allCityNames) {
            val base = verified[city].orEmpty()
            val extra = custom[city].orEmpty()
            merged[city] = (base + extra)
                .distinctBy { it.trim().lowercase() }
                .sortedWith(naturalComparator)
        }
        return merged
    }

    fun cityNames(context: Context): List<String> = loadAllCitiesWithCustom(context).keys.toList()
}
