// ===== REPLACE (purani file hatao, ye daalo) =====
// Yaha rakho: app/src/main/java/com/bipin/clicker/AreaPrefs.kt
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
    private const val KEY_PICKUP_KEYWORDS = "pickup_keywords"

    // v13 SPEED: pehle har scan (40-70ms) par SharedPreferences ka JSON 2 baar parse hota tha
    // aur Regex baar-baar ban raha tha. Ab selections memory me cache hain.
    @Volatile private var pickupCache: Map<String, Set<String>>? = null
    @Volatile private var dropCache: Map<String, Set<String>>? = null
    private val WHITESPACE = Regex("\\s+")

    private fun copySelections(src: Map<String, Set<String>>): Map<String, Set<String>> {
        val out = LinkedHashMap<String, Set<String>>()
        src.forEach { (k, v) -> out[k] = v.toSet() }
        return out
    }

    // ---------- Pause / resume (floating bubble) ----------

    fun setPaused(context: Context, paused: Boolean) {
        prefs(context).edit().putBoolean(KEY_PAUSED, paused).apply()
    }

    fun isPaused(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PAUSED, false)

    // ---------- Pickup / Drop selections ----------

    fun getPickupSelections(context: Context): Map<String, Set<String>> =
        pickupCache ?: readSelections(context, KEY_PICKUP_JSON).also { pickupCache = it }

    fun setPickupSelections(context: Context, selections: Map<String, Set<String>>) {
        writeSelections(context, KEY_PICKUP_JSON, selections)
        pickupCache = copySelections(selections)
    }

    fun getDropSelections(context: Context): Map<String, Set<String>> =
        dropCache ?: readSelections(context, KEY_DROP_JSON).also { dropCache = it }

    fun setDropSelections(context: Context, selections: Map<String, Set<String>>) {
        writeSelections(context, KEY_DROP_JSON, selections)
        dropCache = copySelections(selections)
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
    /** Pickup ke "khaas jagah ke naam" (jaise Mote Lala, Gurudwara). Inme se KOI EK bhi mile to chalega. */
    fun getPickupKeywords(context: Context): List<String> =
        (prefs(context).getString(KEY_PICKUP_KEYWORDS, "") ?: "")
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }

    fun setPickupKeywords(context: Context, words: List<String>) {
        prefs(context).edit().putString(KEY_PICKUP_KEYWORDS, words.joinToString("\n")).apply()
    }

    fun matchesScreenText(context: Context, rawScreenText: String): Boolean {
        val dropSelections = getDropSelections(context)
        val pickupSelections = getPickupSelections(context)
        val pickupKeywords = getPickupKeywords(context)

        if (dropSelections.isEmpty()) return false // Drop set kiye bina kabhi match nahi hoga

        // Order card ka text: pickup aur drop alag-alag (alag na ho paye to poora card dono ke liye)
        val (pickupText, dropText) = splitOrder(rawScreenText)

        if (!selectionsMatchText(context, dropSelections, dropText)) return false

        // Pickup: city/locality chuni ho YA khaas naam likhe ho, to inme se KOI EK match hona kaafi hai.
        // Dono khaali ho to koi bhi pickup chalega.
        if (pickupSelections.isEmpty() && pickupKeywords.isEmpty()) return true
        if (pickupSelections.isNotEmpty() && selectionsMatchText(context, pickupSelections, pickupText)) return true
        return pickupKeywords.any { containsWord(pickupText, normalize(it)) }
    }

    // ---------- Order card ko pickup / drop me todna ----------

    private val ACCEPT_IN = Regex("accept\\s*in", RegexOption.IGNORE_CASE)
    private val KM_HEADER = Regex("\\(\\s*\\d+(?:\\.\\d+)?\\s*km\\s*\\)")

    /**
     * "Accept in Ns" ke baad ka text peeche wali screen ka hota hai (galat match karata tha),
     * isliye wahin tak ka text lete hain. Card me do "(x.x km)" wali header lines hoti hain:
     * pehli = Pickup, doosri = Drop. Dono mile to text alag-alag; warna poora card dono ke liye.
     */
    private fun splitOrder(rawText: String): Pair<String, String> {
        var t = rawText
        val acc = ACCEPT_IN.find(t)
        if (acc != null) t = t.substring(0, acc.range.first)

        val lines = t.split(" | ")
        val idx = lines.indices.filter { KM_HEADER.containsMatchIn(lines[it].lowercase()) }
        if (idx.size >= 2) {
            // Agar "(0.7 km)" alag line me aaya to uske upar wali line (sector ka naam) bhi saath lo
            fun startOf(i: Int): Int = if (i > 0 && lines[i].trim().startsWith("(")) i - 1 else i
            val s1 = startOf(idx[0])
            val s2 = startOf(idx[1])
            if (s2 > s1) {
                val pick = lines.subList(s1, s2).joinToString(" | ")
                val drop = lines.subList(s2, lines.size).joinToString(" | ")
                return normalize(pick) to normalize(drop)
            }
        }
        val all = normalize(t)
        return all to all
    }

    // ---------- Poore shabd ka match (Sector 5 ko Sector 50/51/56 se alag rakhta hai) ----------

    private val WORD_CACHE = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    private fun wordRegex(phrase: String): Regex = WORD_CACHE.getOrPut(phrase) {
        val body = phrase.trim().split(Regex("[\\s\\-]+"))
            .filter { it.isNotEmpty() }
            .joinToString("[\\s\\-]*") { Regex.escape(it) }
        Regex("(?<![a-z0-9])" + body + "(?![a-z0-9])")
    }

    private fun containsWord(text: String, phrase: String): Boolean =
        phrase.isNotBlank() && wordRegex(phrase).containsMatchIn(text)

    private val GREATER_NOIDA = Regex("greater[\\s\\-]*noida|gr\\.?\\s*noida")
    private val SECTOR_SPLIT = Regex("^(.+?)\\s+(sector\\s+[a-z0-9]+)$")

    private fun cityMatches(cityNorm: String, text: String): Boolean {
        // "Noida" select ho to "Greater Noida" ke order isse match na ho
        val t = if (cityNorm == "noida") GREATER_NOIDA.replace(text, " ") else text
        return cityAliases(cityNorm).any { containsWord(t, it) }
    }

    private fun localityMatches(text: String, locality: String): Boolean {
        val loc = normalize(locality)
        if (loc.isEmpty()) return false
        if (containsWord(text, loc)) return true
        // "Rohini Sector 5" jaisa naam: address me "Sector 5, Rohini" bhi ho sakta hai
        val m = SECTOR_SPLIT.find(loc)
        if (m != null) {
            return containsWord(text, m.groupValues[1]) && containsWord(text, m.groupValues[2])
        }
        return false
    }

    // ---------- Delhi ke zone: "Delhi - South" jaise naam ----------

    private const val ZONE_PREFIX = "delhi - "
    private val PIN_RE = Regex("(?<![0-9])(110\\d{3})(?![0-9])")
    @Volatile private var zoneNamesCache: Map<String, List<String>>? = null
    @Volatile private var zonePinsCache: Map<String, Set<String>>? = null

    private fun isZone(cityNorm: String): Boolean = cityNorm.startsWith(ZONE_PREFIX)

    private fun zoneNames(context: Context, zoneNorm: String): List<String> {
        var m = zoneNamesCache
        if (m == null) {
            m = LocationRepository.loadVerifiedCities(context.applicationContext)
                .entries.filter { normalize(it.key).startsWith(ZONE_PREFIX) }
                .associate { normalize(it.key) to it.value }
            zoneNamesCache = m
        }
        return m[zoneNorm].orEmpty()
    }

    private fun zonePins(context: Context, zoneNorm: String): Set<String> {
        var m = zonePinsCache
        if (m == null) {
            m = LocationRepository.loadZonePins(context.applicationContext)
                .entries.associate { normalize(it.key) to it.value }
            zonePinsCache = m
        }
        return m[zoneNorm].orEmpty()
    }

    /** "All Delhi - South": us zone ki koi bhi jagah ya pincode text me mile. */
    private fun zoneAnyMatch(context: Context, zoneNorm: String, text: String): Boolean {
        if (zoneNames(context, zoneNorm).any { localityMatches(text, it) }) return true
        val pins = zonePins(context, zoneNorm)
        return pins.isNotEmpty() && PIN_RE.findAll(text).any { it.value in pins }
    }

    private fun selectionsMatchText(
        context: Context,
        selections: Map<String, Set<String>>,
        normalizedText: String
    ): Boolean {
        for ((city, localities) in selections) {
            val cityNorm = normalize(city)
            if (cityNorm.isEmpty()) continue
            val zone = isZone(cityNorm)
            // Zone ho to address me bas "Delhi" milna chahiye (zone ka naam address me nahi hota)
            val cityMatched = cityMatches(if (zone) "delhi" else cityNorm, normalizedText)

            // "All <city>" = sirf city ka naam milna kaafi. "All <zone>" = zone ki koi jagah/pincode bhi mile.
            if (localities.contains(ALL_MARKER)) {
                if (!cityMatched) continue
                if (!zone) return true
                if (zoneAnyMatch(context, cityNorm, normalizedText)) return true
                continue
            }

            // Specific locality: city AUR locality dono usi (pickup ya drop) text me hone chahiye.
            if (!cityMatched) continue
            for (locality in localities) {
                if (localityMatches(normalizedText, locality)) return true
            }
        }
        return false
    }

    // City ke alternate/purane naam jo delivery apps ke order screen par aa sakte hain.
    // Key hamesha normalize() kiya hua (lowercase) hona chahiye.
    private val CITY_ALIASES: Map<String, List<String>> = mapOf(
        "gurugram" to listOf("gurugram", "gurgaon", "ggn"),
        "delhi" to listOf("delhi", "new delhi", "ncr"),
        "greater noida" to listOf("greater noida", "gr. noida", "gr noida", "greaternoida"),
        "noida" to listOf("noida", "new okhla", "noida extension"),
        "ghaziabad" to listOf("ghaziabad", "gzb"),
        "faridabad" to listOf("faridabad", "fbd"),
    )

    private fun cityAliases(normalizedCity: String): List<String> =
        CITY_ALIASES[normalizedCity] ?: listOf(normalizedCity)

    private fun normalize(s: String): String =
        s.trim().lowercase().replace(WHITESPACE, " ")

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
