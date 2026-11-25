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
    private const val KEY_BUBBLE_MANUAL_OFF = "bubble_manually_off"

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
        isPausedCache ?: prefs(context).getBoolean(KEY_PAUSED, true).also { isPausedCache = it }

    fun setBubbleManuallyOff(context: Context, manualOff: Boolean) {
        prefs(context).edit().putBoolean(KEY_BUBBLE_MANUAL_OFF, manualOff).apply()
    }

    fun isBubbleManuallyOff(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BUBBLE_MANUAL_OFF, false)

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

    fun getRejectPickupSelections(context: Context): Map<String, Set<String>> = emptyMap()

    fun setRejectPickupSelections(context: Context, selections: Map<String, Set<String>>) {
        rejectPickupCache = emptyMap()
        prefs(context).edit().remove(KEY_REJECT_PICKUP_JSON).apply()
    }

    fun getRejectDropSelections(context: Context): Map<String, Set<String>> = emptyMap()

    fun setRejectDropSelections(context: Context, selections: Map<String, Set<String>>) {
        rejectDropCache = emptyMap()
        prefs(context).edit().remove(KEY_REJECT_DROP_JSON).apply()
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

    /** Popup par Pickup KM na likha ho to bhi Drop match par uthaye (default true). */
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
            if (unit == "m" && valNum < 15f) continue
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

    fun matchesRejectText(context: Context, rawScreenText: String): Boolean = false

    fun matchesScreenText(context: Context, rawScreenText: String): Boolean {
        val dropSelections = getDropSelections(context)
        // Drop (A) set kiye bina kabhi match nahi hoga — instant exit
        if (dropSelections.isEmpty()) return false

        val pickupSelections = getPickupSelections(context)
        val pickupKeywords = getPickupKeywords(context)
        val maxPickupKm = getMaxPickupKm(context)

        val hasPickupLoc = pickupSelections.isNotEmpty() || pickupKeywords.isNotEmpty()
        val hasPickupKm = maxPickupKm > 0f

        val fullNorm = normalize(rawScreenText)
        // splitOrder ek hi baar chalao
        val (pickupText, dropText) = splitOrder(rawScreenText)

        // 1. Accept (A) Drop check
        if (!selectionsMatchText(context, dropSelections, dropText, fullNorm)) return false

        // 3. Accept (A) Pickup check:
        // - Sirf KM set ho -> KM match
        // - Sirf Location/Naam set ho -> Location/Naam match
        // - DONO set hon -> DONO (Location/Naam AUR KM) match hone par hi order uthega
        // - DONO me kuchh bhi set na ho -> Pickup kahin se bhi aur kitni bhi door se uthega
        val locMatched = !hasPickupLoc ||
            (pickupSelections.isNotEmpty() && selectionsMatchText(context, pickupSelections, pickupText, fullNorm)) ||
            keywordMatches(pickupKeywords, pickupText)

        if (!locMatched) return false

        if (hasPickupKm) {
            val pickupDistKm = extractDistanceKm(pickupText)
            val kmMatched = if (pickupDistKm != null) {
                pickupDistKm <= maxPickupKm + 0.0001f
            } else {
                isAllowPickupWhenKmMissing(context)
            }
            if (!kmMatched) return false
        }

        return true
    }

    // ---------- Order card ko pickup / drop me todna ----------

    private val ACCEPT_IN = Regex("accept\\s*in", RegexOption.IGNORE_CASE)
    private val KM_HEADER = Regex("(?:\\(\\s*)?\\d+(?:[.,]\\d+)?\\s*(?:kms?|kilometers?|meters?|mtrs?|m)(?:\\s*away|\\s*from\\s*you)?(?:\\s*\\))?", RegexOption.IGNORE_CASE)
    private val STANDALONE_KM_RE = Regex("^(?:\\(\\s*)?\\d+(?:[.,]\\d+)?\\s*(?:kms?|kilometers?|meters?|mtrs?|m)(?:\\s*away|\\s*from\\s*you)?(?:\\s*\\))?$", RegexOption.IGNORE_CASE)
    private val LINE_SPLIT_RE = Regex("\\s*\\|\\s*|\\n+")
    private val PICKUP_LABEL_RE = Regex("\\bpick[\\s-]*up\\b", RegexOption.IGNORE_CASE)
    private val DROP_LABEL_RE = Regex("\\bdrop(?:[\\s-]*off)?\\b", RegexOption.IGNORE_CASE)
    private val PIN_LINE_RE = Regex("(?<![0-9])\\d{6}(?![0-9])|,\\s*india\\b|\\b(?:gurugram|gurgaon|new delhi|delhi|noida|greater noida|faridabad|ghaziabad|haryana|uttar pradesh)\\b", RegexOption.IGNORE_CASE)
    private val FARE_ONLY_RE = Regex("^(?:\\u20B9|rs\\.?|inr)\\s*\\d+", RegexOption.IGNORE_CASE)

    private fun isNonPlaceHeaderLine(line: String): Boolean {
        val t = line.trim().lowercase()
        if (t.isEmpty()) return true
        if (t == "skip" || t.startsWith("skip ")) return true
        if (FARE_ONLY_RE.containsMatchIn(t)) return true
        if (TOTAL_TRIP_TAG_RE.containsMatchIn(t)) return true
        if (KM_HEADER.containsMatchIn(t)) return true
        return false
    }

    /**
     * "Accept in Ns" ke baad ka text peeche wali screen ka hota hai (galat match karata tha),
     * isliye wahin tak ka text lete hain.
     * 1) Explicit PICKUP / DROP markers (sabse bharosemand jab dono maujood hon)
     * 2) 2 leg-distance headers (Sector 34 (2.9 km) / Sector 52 (6.9 km) ya title + standalone km)
     * 3) Drop label only fallback
     * 4) 2 address blocks (6-digit PIN / India / city) par
     */
    private fun splitOrder(rawText: String): Pair<String, String> {
        var t = rawText
        val acc = ACCEPT_IN.find(t)
        if (acc != null) t = t.substring(0, acc.range.first)

        val lines = t.split(LINE_SPLIT_RE).map { it.trim() }.filter { it.isNotEmpty() }

        // 1. Explicit PICKUP / DROP marker lines first when both exist
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

        // 2. Distance headers (Total/Trip/Fare aur lambi address lines exclude)
        val legDistIdx = lines.indices.filter { idx ->
            val line = lines[idx]
            !TOTAL_TRIP_TAG_RE.containsMatchIn(line) &&
                !NON_PICKUP_DIST_TAG_RE.containsMatchIn(line) &&
                KM_HEADER.containsMatchIn(line) &&
                (line.length <= 45 || line.trim().startsWith("(") || STANDALONE_KM_RE.matches(line.trim()))
        }

        if (legDistIdx.size >= 2) {
            val firstIdx = legDistIdx[0]
            val secondIdx = legDistIdx[1]
            val firstTrimmed = lines[firstIdx].trim()
            val secondTrimmed = lines[secondIdx].trim()

            var s1 = firstIdx
            var pulledFirst = false
            if (firstIdx > 0 && (firstTrimmed.startsWith("(") || STANDALONE_KM_RE.matches(firstTrimmed)) &&
                !isNonPlaceHeaderLine(lines[firstIdx - 1])
            ) {
                s1 = firstIdx - 1
                pulledFirst = true
            }

            var s2 = secondIdx
            if (secondIdx - 1 > firstIdx &&
                (secondTrimmed.startsWith("(") || (pulledFirst && STANDALONE_KM_RE.matches(secondTrimmed))) &&
                !isNonPlaceHeaderLine(lines[secondIdx - 1])
            ) {
                s2 = secondIdx - 1
            }

            if (s2 > s1) {
                val pick = lines.subList(s1, s2).joinToString(" | ")
                val drop = lines.subList(s2, lines.size).joinToString(" | ")
                return normalize(pick) to normalize(drop)
            }
        }

        // 3. Explicit DROP marker alone
        if (pLabelIdx < 0 && dLabelIdx > 0) {
            val startPick = if (FARE_ONLY_RE.containsMatchIn(lines[0]) && dLabelIdx > 1) 1 else 0
            val pick = lines.subList(startPick, dLabelIdx).joinToString(" | ")
            val drop = lines.subList(dLabelIdx, lines.size).joinToString(" | ")
            return normalize(pick) to normalize(drop)
        }

        // 4. Two address blocks fallback
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

    private fun keywordMatches(keywords: List<String>, pickupText: String): Boolean {
        if (keywords.isEmpty()) return false
        val pickupCompact by lazy { pickupText.replace(Regex("[^a-z0-9]+"), "") }
        for (raw in keywords) {
            val kw = normalize(raw)
            if (kw.isEmpty()) continue
            if (containsWord(pickupText, kw)) return true
            val kwCompact = kw.replace(Regex("[^a-z0-9]+"), "")
            if (kwCompact.length >= 5 && kw.contains(' ') && pickupCompact.contains(kwCompact)) {
                return true
            }
        }
        return false
    }

    private val GREATER_NOIDA = Regex("greater[\\s\\-]*noida|gr\\.?\\s*noida")
    private val SECTOR_SPLIT = Regex("^(.+?)\\s+(sector\\s+[a-z0-9]+)$")
    private val SEC_ABBREV = Regex("^sector\\s+([a-z0-9-]+)$")
    private val PHASE_ABBREV = Regex("^(.+?)\\s+phase\\s+([a-z0-9]+)$")
    private val GENERIC_LOCALITY_RE = Regex("^(?:sector|sec|phase|block|pocket|stage|part)\\s*[a-z0-9-]+$")

    private fun cityMatches(cityNorm: String, text: String): Boolean {
        // "Noida" select ho to "Greater Noida" ke order isse match na ho
        val t = if (cityNorm == "noida") GREATER_NOIDA.replace(text, " ") else text
        if (cityAliases(cityNorm).any { containsWord(t, it) }) return true
        return pinMatchesCity(cityNorm, text)
    }

    private val CITY_PIN_PREFIXES: Map<String, Regex> = mapOf(
        "delhi" to Regex("(?<![0-9])110\\d{3}(?![0-9])"),
        "gurugram" to Regex("(?<![0-9])122\\d{3}(?![0-9])"),
        "greater noida" to Regex("(?<![0-9])20131\\d(?![0-9])"),
        "noida" to Regex("(?<![0-9])20130\\d(?![0-9])"),
        "faridabad" to Regex("(?<![0-9])121\\d{3}(?![0-9])"),
        "ghaziabad" to Regex("(?<![0-9])2010\\d{2}(?![0-9])"),
        "sonipat" to Regex("(?<![0-9])131\\d{3}(?![0-9])"),
        "bahadurgarh" to Regex("(?<![0-9])12450\\d(?![0-9])"),
        "meerut" to Regex("(?<![0-9])250\\d{3}(?![0-9])"),
    )

    private fun pinMatchesCity(cityNorm: String, text: String): Boolean {
        val re = CITY_PIN_PREFIXES[cityNorm] ?: return false
        return re.containsMatchIn(text)
    }

    private fun mentionsOtherKnownCity(targetCityNorm: String, text: String): Boolean {
        for (cityKey in CITY_ALIASES.keys) {
            if (cityKey == targetCityNorm) continue
            // Greater Noida already handled inside cityMatches("noida")
            if (targetCityNorm == "greater noida" && cityKey == "noida") continue
            if (cityMatches(cityKey, text)) return true
        }
        return false
    }

    private fun isVerifiedCityKey(context: Context, cityNorm: String): Boolean {
        if (isZone(cityNorm)) return true
        if (CITY_ALIASES.containsKey(cityNorm)) return true
        val verified = LocationRepository.loadVerifiedCities(context.applicationContext)
        return verified.keys.any { normalize(it) == cityNorm }
    }

    private fun localityMatches(text: String, locality: String): Boolean {
        val loc = normalize(locality)
        if (loc.isEmpty()) return false
        if (containsWord(text, loc)) return true
        // "Rohini Sector 5" jaisa naam: address me "Sector 5, Rohini" bhi ho sakta hai
        val m = SECTOR_SPLIT.find(loc)
        if (m != null) {
            val part1 = m.groupValues[1]
            val part2 = m.groupValues[2]
            if (containsWord(text, part1) && containsWord(text, part2)) return true
            val secShort = part2.replaceFirst("sector ", "sec ")
            if (containsWord(text, part1) && containsWord(text, secShort)) return true
        }
        val secM = SEC_ABBREV.find(loc)
        if (secM != null && containsWord(text, "sec ${secM.groupValues[1]}")) return true
        val phM = PHASE_ABBREV.find(loc)
        if (phM != null && containsWord(text, "${phM.groupValues[1]} ph ${phM.groupValues[2]}")) return true
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
        normalizedText: String,
        fullNormalizedText: String = normalizedText
    ): Boolean {
        val customFolders by lazy { getCustomLocalities(context) }

        for ((city, localities) in selections) {
            val cityNorm = normalize(city)
            if (cityNorm.isEmpty()) continue

            // User ka banaya hua Custom Folder: folder ka naam screen par nahi hota,
            // isliye uske andar ki jagahon ke naam se seedha match karo!
            if (!isVerifiedCityKey(context, cityNorm)) {
                val folderLocs = if (localities.contains(ALL_MARKER)) {
                    customFolders[city].orEmpty()
                } else {
                    localities.toList()
                }
                if (folderLocs.any { localityMatches(normalizedText, it) }) return true
                continue
            }

            val zone = isZone(cityNorm)
            val targetCity = if (zone) "delhi" else cityNorm
            val cityMatched = cityMatches(targetCity, normalizedText)

            // "All <city>" = sirf city ka naam milna kaafi. "All <zone>" = zone ki koi jagah/pincode bhi mile.
            if (localities.contains(ALL_MARKER)) {
                if (cityMatched) {
                    if (!zone) return true
                    if (zoneAnyMatch(context, cityNorm, normalizedText)) return true
                    continue
                }
                if (zone && !mentionsOtherKnownCity("delhi", normalizedText) &&
                    zoneAnyMatch(context, cityNorm, normalizedText)
                ) {
                    return true
                }
                continue
            }

            // Agar segment me koi doosri city likhi hai to galti se match mat karo
            val otherCityInSegment = !cityMatched && mentionsOtherKnownCity(targetCity, normalizedText)
            if (otherCityInSegment) continue

            val fullCardCityMatched = cityMatched || cityMatches(targetCity, fullNormalizedText)
            for (locality in localities) {
                val locNorm = normalize(locality)
                val generic = GENERIC_LOCALITY_RE.matches(locNorm)
                if ((cityMatched || !generic || fullCardCityMatched) &&
                    localityMatches(normalizedText, locality)
                ) {
                    return true
                }
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
