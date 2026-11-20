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
    private const val KEY_REJECT_PICKUP_JSON = "reject_pickup_selections_json"
    private const val KEY_REJECT_DROP_JSON = "reject_drop_selections_json"
    private const val KEY_CUSTOM_JSON = "custom_localities_json"
    private const val KEY_PAUSED = "is_paused"
    private const val KEY_PICKUP_KEYWORDS = "pickup_keywords"
    private const val KEY_MAX_PICKUP_KM = "max_pickup_km"
    private const val KEY_ALLOW_MISSING_KM = "allow_pickup_when_km_missing"

    // v13 SPEED: selections + pause state memory me cache hain (0ms lookup)
    @Volatile private var isPausedCache: Boolean? = null
    @Volatile private var pickupCache: Map<String, Set<String>>? = null
    @Volatile private var dropCache: Map<String, Set<String>>? = null
    @Volatile private var rejectPickupCache: Map<String, Set<String>>? = null
    @Volatile private var rejectDropCache: Map<String, Set<String>>? = null
    @Volatile private var pickupKeywordsCache: List<String>? = null
    @Volatile private var maxPickupKmCache: Float? = null
    @Volatile private var allowMissingKmCache: Boolean? = null

    private fun copySelections(src: Map<String, Set<String>>): Map<String, Set<String>> {
        val out = LinkedHashMap<String, Set<String>>()
        src.forEach { (k, v) -> out[k] = v.toSet() }
        return out
    }

    // ---------- Pause / resume (floating bubble) ----------

    fun setPaused(context: Context, paused: Boolean) {
        isPausedCache = paused
        prefs(context).edit().putBoolean(KEY_PAUSED, paused).apply()
    }

    fun isPaused(context: Context): Boolean =
        isPausedCache ?: prefs(context).getBoolean(KEY_PAUSED, false).also { isPausedCache = it }

    // ---------- Pickup / Drop selections (A = Accept, R = Reject) ----------

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

    fun getRejectPickupSelections(context: Context): Map<String, Set<String>> =
        rejectPickupCache ?: readSelections(context, KEY_REJECT_PICKUP_JSON).also { rejectPickupCache = it }

    fun setRejectPickupSelections(context: Context, selections: Map<String, Set<String>>) {
        writeSelections(context, KEY_REJECT_PICKUP_JSON, selections)
        rejectPickupCache = copySelections(selections)
    }

    fun getRejectDropSelections(context: Context): Map<String, Set<String>> =
        rejectDropCache ?: readSelections(context, KEY_REJECT_DROP_JSON).also { rejectDropCache = it }

    fun setRejectDropSelections(context: Context, selections: Map<String, Set<String>>) {
        writeSelections(context, KEY_REJECT_DROP_JSON, selections)
        rejectDropCache = copySelections(selections)
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
        addCustomLocalities(context, city, listOf(locality))
    }

    /**
     * User apna "naya folder" (naya city naam) ek saath kai jagah ke naam daalkar bana sakta
     * hai. Agar ye city naam pehle se verified list me nahi hai, to ye bilkul naya folder ban
     * jata hai, Gurugram/Noida jaisa.
     */
    fun addCustomLocalities(context: Context, city: String, localities: List<String>) {
        val cleanCity = city.trim()
        if (cleanCity.isEmpty()) return
        val clean = localities.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return

        val current = getCustomLocalities(context).toMutableMap()
        val existing = current[cleanCity].orEmpty().toMutableList()
        val seen = existing.map { it.lowercase() }.toMutableSet()
        for (loc in clean) {
            val k = loc.lowercase()
            if (k !in seen) { existing.add(loc); seen.add(k) }
        }
        current[cleanCity] = existing

        val obj = JSONObject()
        current.forEach { (c, list) -> obj.put(c, JSONArray(list)) }
        prefs(context).edit().putString(KEY_CUSTOM_JSON, obj.toString()).apply()
    }

    /**
     * User ka khud bananaya poora folder hata do - Pickup/Drop me agar wo select bhi tha,
     * wo bhi hat jata hai (purani, ab-na-maujood city select rahna hi nahi chahiye).
     */
    fun removeCustomCity(context: Context, city: String) {
        val cleanCity = city.trim()
        val current = getCustomLocalities(context).toMutableMap()
        if (current.remove(cleanCity) == null) return

        val obj = JSONObject()
        current.forEach { (c, list) -> obj.put(c, JSONArray(list)) }
        prefs(context).edit().putString(KEY_CUSTOM_JSON, obj.toString()).apply()

        val pickup = getPickupSelections(context).toMutableMap()
        if (pickup.remove(cleanCity) != null) setPickupSelections(context, pickup)
        val drop = getDropSelections(context).toMutableMap()
        if (drop.remove(cleanCity) != null) setDropSelections(context, drop)
        val rejectPickup = getRejectPickupSelections(context).toMutableMap()
        if (rejectPickup.remove(cleanCity) != null) setRejectPickupSelections(context, rejectPickup)
        val rejectDrop = getRejectDropSelections(context).toMutableMap()
        if (rejectDrop.remove(cleanCity) != null) setRejectDropSelections(context, rejectDrop)
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
        pickupKeywordsCache ?: (prefs(context).getString(KEY_PICKUP_KEYWORDS, "") ?: "")
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            .also { pickupKeywordsCache = it }

    fun setPickupKeywords(context: Context, words: List<String>) {
        val clean = words.map { it.trim() }.filter { it.isNotEmpty() }
        pickupKeywordsCache = clean
        prefs(context).edit().putString(KEY_PICKUP_KEYWORDS, clean.joinToString("\n")).apply()
    }

    /** Pickup max distance in km (0f = off / koi distance limit nahi). */
    fun getMaxPickupKm(context: Context): Float =
        maxPickupKmCache ?: prefs(context).getFloat(KEY_MAX_PICKUP_KM, 0f).coerceAtLeast(0f)
            .also { maxPickupKmCache = it }

    fun setMaxPickupKm(context: Context, km: Float) {
        val clean = km.coerceAtLeast(0f)
        maxPickupKmCache = clean
        prefs(context).edit().putFloat(KEY_MAX_PICKUP_KM, clean).apply()
    }

    /** Popup par Pickup KM na likha ho (jaise ₹41 wala card) to bhi Drop match par uthaye (default true). */
    fun isAllowPickupWhenKmMissing(context: Context): Boolean =
        allowMissingKmCache ?: prefs(context).getBoolean(KEY_ALLOW_MISSING_KM, true).also {
            allowMissingKmCache = it
        }

    fun setAllowPickupWhenKmMissing(context: Context, allow: Boolean) {
        allowMissingKmCache = allow
        prefs(context).edit().putBoolean(KEY_ALLOW_MISSING_KM, allow).apply()
    }

    private val DIST_TOKEN_RE = Regex("(\\d+(?:[.,]\\d+)?)\\s*(kms?|kilometers?|meters?|mtrs?|m)\\b", RegexOption.IGNORE_CASE)
    private val TOTAL_TRIP_TAG_RE = Regex("\\b(?:total|trip|fare|est(?:imated)?|overall)\\b", RegexOption.IGNORE_CASE)
    private val NON_PICKUP_DIST_TAG_RE = Regex("\\b(?:drop(?:[\\s-]*off)?|destination|deliver(?:y)?|total|trip|fare|est(?:imated)?|overall)\\b", RegexOption.IGNORE_CASE)

    fun extractDistanceKm(text: String): Float? {
        for (m in DIST_TOKEN_RE.findAll(text)) {
            val rawNum = m.groupValues.getOrNull(1)?.replace(',', '.') ?: continue
            val unit = m.groupValues.getOrNull(2)?.lowercase() ?: "km"
            val valNum = rawNum.toFloatOrNull() ?: continue
            val km = if (unit.startsWith("m")) valNum / 1000f else valNum

            // Kabhi bhi Drop / Total / Trip / Fare ki doori ko Pickup KM mat maano
            val ctxStart = (m.range.first - 24).coerceAtLeast(0)
            val ctxEnd = (m.range.last + 25).coerceAtMost(text.length)
            val around = text.substring(ctxStart, ctxEnd)
            if (!NON_PICKUP_DIST_TAG_RE.containsMatchIn(around)) {
                return km
            }
        }
        return null
    }

    fun prewarm(context: Context) {
        val app = context.applicationContext
        isPaused(app)
        val p = getPickupSelections(app)
        val d = getDropSelections(app)
        val rp = getRejectPickupSelections(app)
        val rd = getRejectDropSelections(app)
        val kw = getPickupKeywords(app)
        getMaxPickupKm(app)
        isAllowPickupWhenKmMissing(app)

        fun warmMap(m: Map<String, Set<String>>) {
            for ((city, locs) in m) {
                val cityNorm = normalize(city)
                if (cityNorm.isEmpty()) continue
                val zone = isZone(cityNorm)
                cityAliases(if (zone) "delhi" else cityNorm).forEach { wordRegex(it) }
                if (zone && locs.contains(ALL_MARKER)) {
                    zoneNames(app, cityNorm).forEach { wordRegex(normalize(it)) }
                    zonePins(app, cityNorm)
                }
                for (loc in locs) {
                    if (loc != ALL_MARKER) {
                        val ln = normalize(loc)
                        if (ln.isNotEmpty()) wordRegex(ln)
                    }
                }
            }
        }
        warmMap(p)
        warmMap(d)
        warmMap(rp)
        warmMap(rd)
        kw.forEach { wordRegex(normalize(it)) }
    }

    fun matchesRejectText(context: Context, rawScreenText: String): Boolean {
        val rejectPickup = getRejectPickupSelections(context)
        val rejectDrop = getRejectDropSelections(context)
        if (rejectPickup.isEmpty() && rejectDrop.isEmpty()) return false
        val (pickupText, dropText) = splitOrder(rawScreenText)
        return matchesRejectSplit(context, rejectPickup, rejectDrop, pickupText, dropText)
    }

    private fun matchesRejectSplit(
        context: Context,
        rejectPickup: Map<String, Set<String>>,
        rejectDrop: Map<String, Set<String>>,
        pickupText: String,
        dropText: String
    ): Boolean {
        if (rejectPickup.isNotEmpty() && selectionsMatchText(context, rejectPickup, pickupText)) return true
        if (rejectDrop.isNotEmpty() && selectionsMatchText(context, rejectDrop, dropText)) return true
        return false
    }

    fun matchesScreenText(context: Context, rawScreenText: String): Boolean {
        val dropSelections = getDropSelections(context)
        // Drop (A) set kiye bina kabhi match nahi hoga — instant exit
        if (dropSelections.isEmpty()) return false

        val rejectPickup = getRejectPickupSelections(context)
        val rejectDrop = getRejectDropSelections(context)
        val pickupSelections = getPickupSelections(context)
        val pickupKeywords = getPickupKeywords(context)
        val maxPickupKm = getMaxPickupKm(context)

        // splitOrder ek hi baar chalao (pehle 2 baar chalta tha)
        val (pickupText, dropText) = splitOrder(rawScreenText)

        // 1. Pehle Reject (R) check: agar Pickup ya Drop par R match ho to order accept NAHI hoga
        if ((rejectPickup.isNotEmpty() || rejectDrop.isNotEmpty()) &&
            matchesRejectSplit(context, rejectPickup, rejectDrop, pickupText, dropText)
        ) {
            return false
        }

        // 2. Accept (A) Drop check
        if (!selectionsMatchText(context, dropSelections, dropText)) return false

        // 3. Accept (A) Pickup check: Location (A) YA Khas Naam YA Max Pickup KM (jo bhi set ho)
        if (pickupSelections.isEmpty() && pickupKeywords.isEmpty() && maxPickupKm <= 0f) return true
        if (pickupSelections.isNotEmpty() && selectionsMatchText(context, pickupSelections, pickupText)) return true
        if (pickupKeywords.any { containsWord(pickupText, normalize(it)) }) return true
        if (maxPickupKm > 0f) {
            val pickupDistKm = extractDistanceKm(pickupText)
            if (pickupDistKm != null && pickupDistKm <= maxPickupKm + 0.0001f) return true
            if (pickupDistKm == null &&
                isAllowPickupWhenKmMissing(context) &&
                pickupSelections.isEmpty() &&
                pickupKeywords.isEmpty()
            ) {
                return true
            }
        }
        return false
    }

    // ---------- Order card ko pickup / drop me todna ----------

    private val ACCEPT_IN = Regex("accept\\s*in", RegexOption.IGNORE_CASE)
    private val KM_HEADER = Regex("(?:\\(\\s*)?\\d+(?:[.,]\\d+)?\\s*(?:kms?|kilometers?|meters?|mtrs?|m)(?:\\s*away|\\s*from\\s*you)?(?:\\s*\\))?", RegexOption.IGNORE_CASE)
    private val LINE_SPLIT_RE = Regex("\\s*\\|\\s*|\\n+")
    private val PICKUP_LABEL_RE = Regex("\\bpick[\\s-]*up\\b", RegexOption.IGNORE_CASE)
    private val DROP_LABEL_RE = Regex("\\bdrop(?:[\\s-]*off)?\\b", RegexOption.IGNORE_CASE)
    private val PIN_LINE_RE = Regex("(?<![0-9])\\d{6}(?![0-9])|,\\s*india\\b|\\b(?:gurugram|gurgaon|new delhi|delhi|noida|greater noida|faridabad|ghaziabad|haryana|uttar pradesh)\\b", RegexOption.IGNORE_CASE)
    private val FARE_ONLY_RE = Regex("^(?:\\u20B9|rs\\.?|inr)\\s*\\d+", RegexOption.IGNORE_CASE)

    /**
     * "Accept in Ns" ke baad ka text peeche wali screen ka hota hai (galat match karata tha),
     * isliye wahin tak ka text lete hain.
     * 1) Pehle explicit PICKUP / DROP markers par todte hain
     * 2) Phir 2 leg-distance headers par (Total/Trip/Fare hamesha hata kar)
     * 3) Phir 2 address blocks (6-digit PIN / India / city) par — jaise bina KM wala ₹41 popup
     */
    private fun splitOrder(rawText: String): Pair<String, String> {
        var t = rawText
        val acc = ACCEPT_IN.find(t)
        if (acc != null) t = t.substring(0, acc.range.first)

        val lines = t.split(LINE_SPLIT_RE).map { it.trim() }.filter { it.isNotEmpty() }

        // 1. Explicit PICKUP / DROP marker lines
        val pLabelIdx = lines.indexOfFirst { PICKUP_LABEL_RE.containsMatchIn(it) && !DROP_LABEL_RE.containsMatchIn(it) }
        val dSearchStart = if (pLabelIdx >= 0) pLabelIdx + 1 else 1
        val dLabelIdx = if (dSearchStart < lines.size) {
            (dSearchStart until lines.size).firstOrNull {
                DROP_LABEL_RE.containsMatchIn(lines[it]) && !PICKUP_LABEL_RE.containsMatchIn(lines[it])
            } ?: -1
        } else -1

        if (pLabelIdx >= 0 && dLabelIdx > pLabelIdx) {
            val pick = lines.subList(pLabelIdx, dLabelIdx).joinToString(" | ")
            val drop = lines.subList(dLabelIdx, lines.size).joinToString(" | ")
            return normalize(pick) to normalize(drop)
        }
        if (pLabelIdx < 0 && dLabelIdx > 0) {
            val startPick = if (FARE_ONLY_RE.containsMatchIn(lines[0]) && dLabelIdx > 1) 1 else 0
            val pick = lines.subList(startPick, dLabelIdx).joinToString(" | ")
            val drop = lines.subList(dLabelIdx, lines.size).joinToString(" | ")
            return normalize(pick) to normalize(drop)
        }

        // 2. Distance headers (Total/Trip/Fare hamesha exclude)
        val legDistIdx = lines.indices.filter {
            KM_HEADER.containsMatchIn(lines[it]) && !TOTAL_TRIP_TAG_RE.containsMatchIn(lines[it])
        }

        if (legDistIdx.size >= 2) {
            fun startOf(i: Int, prevIdx: Int): Int {
                if (i <= 0) return i
                val trimmed = lines[i].trim()
                return if (trimmed.startsWith("(") && i - 1 > prevIdx) i - 1 else i
            }
            val s1 = startOf(legDistIdx[0], -1)
            val s2 = startOf(legDistIdx[1], legDistIdx[0])
            if (s2 > s1) {
                val pick = lines.subList(s1, s2).joinToString(" | ")
                val drop = lines.subList(s2, lines.size).joinToString(" | ")
                return normalize(pick) to normalize(drop)
            }
        }

        // 3. Two address blocks fallback (bina KM wale cards jaise ₹41 popup)
        val addrEndIdx = lines.indices.filter {
            lines[it].length >= 16 && PIN_LINE_RE.containsMatchIn(lines[it])
        }
        if (addrEndIdx.size >= 2) {
            val splitAfterFirstAddr = addrEndIdx[0] + 1
            if (splitAfterFirstAddr < lines.size) {
                val startPick = if (FARE_ONLY_RE.containsMatchIn(lines[0]) && splitAfterFirstAddr > 1) 1 else 0
                val pick = lines.subList(startPick, splitAfterFirstAddr).joinToString(" | ")
                val drop = lines.subList(splitAfterFirstAddr, lines.size).joinToString(" | ")
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
        "sonipat" to listOf("sonipat", "sonepat"),
        "bahadurgarh" to listOf("bahadurgarh", "bahadurgadh"),
        "meerut" to listOf("meerut", "merath"),
    )

    private fun cityAliases(normalizedCity: String): List<String> =
        CITY_ALIASES[normalizedCity] ?: listOf(normalizedCity)

    private fun normalize(s: String): String {
        val len = s.length
        if (len == 0) return ""
        val sb = StringBuilder(len)
        var inSpace = true
        for (i in 0 until len) {
            val ch = s[i]
            if (ch.isWhitespace()) {
                if (!inSpace) {
                    sb.append(' ')
                    inSpace = true
                }
            } else {
                sb.append(ch.lowercaseChar())
                inSpace = false
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
            sb.setLength(sb.length - 1)
        }
        return sb.toString()
    }

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
