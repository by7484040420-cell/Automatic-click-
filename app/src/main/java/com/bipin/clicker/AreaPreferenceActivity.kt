package com.bipin.clicker

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bipin.clicker.databinding.ActivityAreaPreferenceBinding

private enum class Tab { PICKUP, DROP }

class AreaPreferenceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAreaPreferenceBinding

    private var currentTab = Tab.PICKUP
    private var allCities: Map<String, List<String>> = emptyMap()
    private var verifiedCityNames: Set<String> = emptySet()

    // Working (unsaved) selections: A = Accept, R = Reject
    private val workingPickupA = mutableMapOf<String, MutableSet<String>>()
    private val workingDropA = mutableMapOf<String, MutableSet<String>>()
    private val workingPickupR = mutableMapOf<String, MutableSet<String>>()
    private val workingDropR = mutableMapOf<String, MutableSet<String>>()
    private var pickupNoRestriction = false

    private val expandedCities = mutableSetOf<String>()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAreaPreferenceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        verifiedCityNames = LocationRepository.loadVerifiedCities(this).keys
        allCities = LocationRepository.loadAllCitiesWithCustom(this)

        loadWorkingStateFrom(AreaPrefs.getPickupSelections(this), workingPickupA)
        loadWorkingStateFrom(AreaPrefs.getDropSelections(this), workingDropA)
        loadWorkingStateFrom(AreaPrefs.getRejectPickupSelections(this), workingPickupR)
        loadWorkingStateFrom(AreaPrefs.getRejectDropSelections(this), workingDropR)

        pickupNoRestriction = AreaPrefs.getPickupSelections(this).isEmpty() &&
            AreaPrefs.getPickupKeywords(this).isEmpty()
        binding.pickupKeywords.setText(AreaPrefs.getPickupKeywords(this).joinToString(", "))
        val savedMaxKm = AreaPrefs.getMaxPickupKm(this)
        binding.etMaxPickupKm.setText(
            if (savedMaxKm > 0f) {
                if (savedMaxKm % 1f == 0f) savedMaxKm.toInt().toString() else savedMaxKm.toString()
            } else {
                ""
            }
        )
        binding.cbAllowMissingKm.isChecked = AreaPrefs.isAllowPickupWhenKmMissing(this)

        binding.btnDistOff.setOnClickListener { binding.etMaxPickupKm.setText("") }
        binding.btnDist05.setOnClickListener { binding.etMaxPickupKm.setText("0.5") }
        binding.btnDist06.setOnClickListener { binding.etMaxPickupKm.setText("0.6") }
        binding.btnDist1.setOnClickListener { binding.etMaxPickupKm.setText("1") }
        binding.btnDist2.setOnClickListener { binding.etMaxPickupKm.setText("2") }
        binding.btnDist3.setOnClickListener { binding.etMaxPickupKm.setText("3") }
        binding.btnDist5.setOnClickListener { binding.etMaxPickupKm.setText("5") }

        binding.pickupKeywords.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!s.isNullOrBlank() && binding.cbNoPickupRestriction.isChecked) {
                    binding.cbNoPickupRestriction.isChecked = false
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.btnTabPickup.setOnClickListener { switchTab(Tab.PICKUP) }
        binding.btnTabDrop.setOnClickListener { switchTab(Tab.DROP) }

        binding.cbNoPickupRestriction.setOnCheckedChangeListener { _, checked ->
            pickupNoRestriction = checked
        }

        binding.btnSaveAreas.setOnClickListener { saveAndFinish() }

        binding.btnResetAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Sab location hatao?")
                .setMessage("Pickup (A/R), Drop (A/R), distance aur khaas naam sab hat jayenge.")
                .setPositiveButton("Haan, sab hatao") { _, _ ->
                    workingPickupA.clear()
                    workingDropA.clear()
                    workingPickupR.clear()
                    workingDropR.clear()
                    pickupNoRestriction = true
                    binding.pickupKeywords.setText("")
                    binding.etMaxPickupKm.setText("")
                    binding.cbNoPickupRestriction.isChecked = true
                    binding.cbAllowMissingKm.isChecked = true
                    AreaPrefs.setPickupSelections(this, emptyMap())
                    AreaPrefs.setDropSelections(this, emptyMap())
                    AreaPrefs.setRejectPickupSelections(this, emptyMap())
                    AreaPrefs.setRejectDropSelections(this, emptyMap())
                    AreaPrefs.setPickupKeywords(this, emptyList())
                    AreaPrefs.setMaxPickupKm(this, 0f)
                    AreaPrefs.setAllowPickupWhenKmMissing(this, true)
                    renderCities()
                    Toast.makeText(this, "Sab location (A aur R) hat gaye", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Nahi", null)
                .show()
        }

        binding.locationSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pendingSearchRunnable?.let { searchHandler.removeCallbacks(it) }
                val r = Runnable {
                    if (!isFinishing && !isDestroyed) renderCities()
                }
                pendingSearchRunnable = r
                searchHandler.postDelayed(r, 40L)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        switchTab(Tab.PICKUP)
    }

    override fun onDestroy() {
        pendingSearchRunnable?.let { searchHandler.removeCallbacks(it) }
        super.onDestroy()
    }

    private fun loadWorkingStateFrom(
        saved: Map<String, Set<String>>,
        target: MutableMap<String, MutableSet<String>>
    ) {
        target.clear()
        saved.forEach { (city, set) -> target[city] = set.toMutableSet() }
    }

    private fun switchTab(tab: Tab) {
        currentTab = tab
        binding.btnTabPickup.alpha = if (tab == Tab.PICKUP) 1f else 0.5f
        binding.btnTabDrop.alpha = if (tab == Tab.DROP) 1f else 0.5f
        binding.cbNoPickupRestriction.visibility = if (tab == Tab.PICKUP) View.VISIBLE else View.GONE
        binding.pickupDistanceBox.visibility = if (tab == Tab.PICKUP) View.VISIBLE else View.GONE
        binding.tvKeywordHelp.visibility = if (tab == Tab.PICKUP) View.VISIBLE else View.GONE
        binding.pickupKeywords.visibility = if (tab == Tab.PICKUP) View.VISIBLE else View.GONE
        binding.cbNoPickupRestriction.isChecked = pickupNoRestriction
        renderCities()
    }

    private fun activeAcceptMap(): MutableMap<String, MutableSet<String>> =
        if (currentTab == Tab.PICKUP) workingPickupA else workingDropA

    private fun activeRejectMap(): MutableMap<String, MutableSet<String>> =
        if (currentTab == Tab.PICKUP) workingPickupR else workingDropR

    private fun ensurePickupAcceptActive() {
        if (currentTab == Tab.PICKUP && pickupNoRestriction) {
            pickupNoRestriction = false
            binding.cbNoPickupRestriction.isChecked = false
        }
    }

    private fun styleModeBox(tv: TextView, isAcceptBox: Boolean, isActive: Boolean) {
        val dp = resources.displayMetrics.density
        tv.setTextColor(
            when {
                isActive -> Color.WHITE
                isAcceptBox -> Color.parseColor("#81C784")
                else -> Color.parseColor("#EF5350")
            }
        )
        tv.background = GradientDrawable().apply {
            cornerRadius = 8 * dp
            setColor(
                when {
                    isActive && isAcceptBox -> Color.parseColor("#43A047")
                    isActive && !isAcceptBox -> Color.parseColor("#C62828")
                    else -> Color.parseColor("#2A2A2A")
                }
            )
            setStroke(
                (1 * dp).toInt().coerceAtLeast(1),
                when {
                    isActive && isAcceptBox -> Color.parseColor("#81C784")
                    isActive && !isAcceptBox -> Color.parseColor("#EF5350")
                    else -> Color.parseColor("#424242")
                }
            )
        }
    }

    private fun makeModeBox(label: String, isAcceptBox: Boolean, isActive: Boolean): TextView {
        val dp = resources.displayMetrics.density
        val sizePx = (42 * dp).toInt()
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                marginStart = (6 * dp).toInt()
            }
            styleModeBox(this, isAcceptBox, isActive)
        }
    }

    private fun renderCities() {
        binding.cityContainer.removeAllViews()
        val acceptMap = activeAcceptMap()
        val rejectMap = activeRejectMap()
        val query = binding.locationSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val rawQuery = binding.locationSearch.text?.toString()?.trim().orEmpty()

        binding.cityContainer.addView(TextView(this).apply {
            text = "Har location ke aage [A] = Accept, [R] = Reject"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
            setPadding(0, 4, 0, 10)
        })

        binding.cityContainer.addView(Button(this).apply {
            text = "\u2713 Tick (A) kiye hue se Naya Folder Banayein"
            setOnClickListener { showSaveTickedAsFolderDialog() }
        })
        binding.cityContainer.addView(Button(this).apply {
            text = "+ Naya Folder (naam type karke)"
            setOnClickListener { showCreateFolderDialog() }
        })

        if (rawQuery.isNotEmpty()) {
            val exact = allCities.values.any { list -> list.any { it.equals(rawQuery, ignoreCase = true) } }
            if (!exact) {
                val niceName = rawQuery.split(" ").filter { it.isNotEmpty() }
                    .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
                binding.cityContainer.addView(TextView(this).apply {
                    text = "\"$niceName\" list me nahi mila — kis city me A ya R jodna hai:"
                    setTextColor(0xFFFFB74D.toInt())
                    textSize = 13f
                    setPadding(0, 12, 0, 8)
                })
                val scroll = android.widget.HorizontalScrollView(this)
                val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (cityName in allCities.keys) {
                    btnRow.addView(Button(this).apply {
                        text = "$cityName +A"
                        setOnClickListener {
                            ensurePickupAcceptActive()
                            AreaPrefs.addCustomLocality(this@AreaPreferenceActivity, cityName, niceName)
                            allCities = LocationRepository.loadAllCitiesWithCustom(this@AreaPreferenceActivity)
                            activeRejectMap()[cityName]?.remove(niceName)
                            activeAcceptMap().getOrPut(cityName) { mutableSetOf() }.add(niceName)
                            expandedCities.add(cityName)
                            binding.locationSearch.setText("")
                            Toast.makeText(
                                this@AreaPreferenceActivity,
                                "$niceName ($cityName) [A] me jud gaya",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    })
                    btnRow.addView(Button(this).apply {
                        text = "$cityName +R"
                        setOnClickListener {
                            AreaPrefs.addCustomLocality(this@AreaPreferenceActivity, cityName, niceName)
                            allCities = LocationRepository.loadAllCitiesWithCustom(this@AreaPreferenceActivity)
                            activeAcceptMap()[cityName]?.remove(niceName)
                            activeRejectMap().getOrPut(cityName) { mutableSetOf() }.add(niceName)
                            expandedCities.add(cityName)
                            binding.locationSearch.setText("")
                            Toast.makeText(
                                this@AreaPreferenceActivity,
                                "$niceName ($cityName) [R] me jud gaya",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    })
                }
                scroll.addView(btnRow)
                binding.cityContainer.addView(scroll)
            }
        }

        for ((city, allLocalities) in allCities) {
            val localities = if (query.isEmpty()) allLocalities else allLocalities.filter {
                city.lowercase().contains(query) || it.lowercase().contains(query)
            }
            if (query.isNotEmpty() && localities.isEmpty() && !city.lowercase().contains(query)) continue

            val isExpanded = if (query.isNotEmpty()) localities.size <= 80 else city in expandedCities
            val acceptSet = acceptMap[city] ?: mutableSetOf()
            val rejectSet = rejectMap[city] ?: mutableSetOf()
            val isAllAccept = acceptSet.contains(AreaPrefs.ALL_MARKER)
            val isAllReject = rejectSet.contains(AreaPrefs.ALL_MARKER)

            val section = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val expandArrow = TextView(this).apply {
                text = (if (isExpanded) "\u25BE " else "\u25B8 ") + "$city (${localities.size})"
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(0, 10, 8, 10)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val btnCityA = makeModeBox("A", isAcceptBox = true, isActive = isAllAccept)
            val btnCityR = makeModeBox("R", isAcceptBox = false, isActive = isAllReject)

            headerRow.addView(expandArrow)
            headerRow.addView(btnCityA)
            headerRow.addView(btnCityR)

            if (city !in verifiedCityNames) {
                headerRow.addView(TextView(this).apply {
                    text = "  \u2716"
                    textSize = 16f
                    setTextColor(0xFFEF5350.toInt())
                    setPadding(12, 8, 4, 8)
                    setOnClickListener {
                        AlertDialog.Builder(this@AreaPreferenceActivity)
                            .setTitle("\"$city\" folder hatayein?")
                            .setMessage("Is poore folder ki sab jagah hat jayengi.")
                            .setPositiveButton("Haan, hatao") { _, _ ->
                                AreaPrefs.removeCustomCity(this@AreaPreferenceActivity, city)
                                workingPickupA.remove(city)
                                workingDropA.remove(city)
                                workingPickupR.remove(city)
                                workingDropR.remove(city)
                                expandedCities.remove(city)
                                allCities = LocationRepository.loadAllCitiesWithCustom(this@AreaPreferenceActivity)
                                renderCities()
                            }
                            .setNegativeButton("Nahi", null)
                            .show()
                    }
                })
            }

            val childContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (isExpanded) View.VISIBLE else View.GONE
                setPadding(16, 4, 0, 4)
            }

            var childrenBuilt = false
            var displayLimit = 350

            fun refreshHeaderBoxes() {
                val nowAllA = acceptMap[city]?.contains(AreaPrefs.ALL_MARKER) == true
                val nowAllR = rejectMap[city]?.contains(AreaPrefs.ALL_MARKER) == true
                styleModeBox(btnCityA, isAcceptBox = true, isActive = nowAllA)
                styleModeBox(btnCityR, isAcceptBox = false, isActive = nowAllR)
            }

            fun buildChildRows() {
                childContainer.removeAllViews()
                childrenBuilt = true
                val curA = acceptMap[city] ?: mutableSetOf()
                val curR = rejectMap[city] ?: mutableSetOf()
                val curAllA = curA.contains(AreaPrefs.ALL_MARKER)
                val curAllR = curR.contains(AreaPrefs.ALL_MARKER)

                // Inline add new sector with +A and +R
                val addRow = LinearLayout(this@AreaPreferenceActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 6, 0, 10)
                }
                val input = EditText(this@AreaPreferenceActivity).apply {
                    hint = "Naya sector/area likhein"
                    setTextColor(Color.WHITE)
                    setHintTextColor(Color.parseColor("#9E9E9E"))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val addABtn = makeModeBox("+A", isAcceptBox = true, isActive = true)
                val addRBtn = makeModeBox("+R", isAcceptBox = false, isActive = true)
                addABtn.setOnClickListener {
                    val value = input.text.toString().trim()
                    if (value.isEmpty()) {
                        Toast.makeText(this@AreaPreferenceActivity, "Area ka naam likhein", Toast.LENGTH_SHORT).show()
                    } else {
                        ensurePickupAcceptActive()
                        AreaPrefs.addCustomLocality(this@AreaPreferenceActivity, city, value)
                        allCities = LocationRepository.loadAllCitiesWithCustom(this@AreaPreferenceActivity)
                        rejectMap[city]?.remove(value)
                        acceptMap.getOrPut(city) { mutableSetOf() }.add(value)
                        expandedCities.add(city)
                        renderCities()
                    }
                }
                addRBtn.setOnClickListener {
                    val value = input.text.toString().trim()
                    if (value.isEmpty()) {
                        Toast.makeText(this@AreaPreferenceActivity, "Area ka naam likhein", Toast.LENGTH_SHORT).show()
                    } else {
                        AreaPrefs.addCustomLocality(this@AreaPreferenceActivity, city, value)
                        allCities = LocationRepository.loadAllCitiesWithCustom(this@AreaPreferenceActivity)
                        acceptMap[city]?.remove(value)
                        rejectMap.getOrPut(city) { mutableSetOf() }.add(value)
                        expandedCities.add(city)
                        renderCities()
                    }
                }
                addRow.addView(input)
                addRow.addView(addABtn)
                addRow.addView(addRBtn)
                childContainer.addView(addRow)

                val slice = if (localities.size > displayLimit) localities.subList(0, displayLimit) else localities
                for (locality in slice) {
                    val locReject = curAllR || curR.contains(locality)
                    val locAccept = !locReject && (curAllA || curA.contains(locality))

                    val row = LinearLayout(this@AreaPreferenceActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(8, 6, 4, 6)
                        if (locReject) {
                            setBackgroundColor(Color.argb(45, 198, 40, 40))
                        } else if (locAccept) {
                            setBackgroundColor(Color.argb(45, 67, 160, 71))
                        }
                    }

                    val tvLoc = TextView(this@AreaPreferenceActivity).apply {
                        text = locality
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val boxA = makeModeBox("A", isAcceptBox = true, isActive = locAccept)
                    val boxR = makeModeBox("R", isAcceptBox = false, isActive = locReject)

                    fun updateRowVisuals(acceptNow: Boolean, rejectNow: Boolean) {
                        styleModeBox(boxA, isAcceptBox = true, isActive = acceptNow)
                        styleModeBox(boxR, isAcceptBox = false, isActive = rejectNow)
                        row.setBackgroundColor(
                            when {
                                rejectNow -> Color.argb(45, 198, 40, 40)
                                acceptNow -> Color.argb(45, 67, 160, 71)
                                else -> Color.TRANSPARENT
                            }
                        )
                    }

                    boxA.setOnClickListener {
                        ensurePickupAcceptActive()
                        rejectMap[city]?.remove(AreaPrefs.ALL_MARKER)
                        rejectMap[city]?.remove(locality)
                        if (rejectMap[city]?.isEmpty() == true) rejectMap.remove(city)

                        val setA = acceptMap.getOrPut(city) { mutableSetOf() }
                        val wasAllA = setA.contains(AreaPrefs.ALL_MARKER)
                        if (setA.contains(locality)) {
                            setA.remove(locality)
                        } else if (!wasAllA) {
                            setA.add(locality)
                        }
                        if (setA.isEmpty()) acceptMap.remove(city)
                        refreshHeaderBoxes()
                        val nowAllA = acceptMap[city]?.contains(AreaPrefs.ALL_MARKER) == true
                        val nowA = nowAllA || acceptMap[city]?.contains(locality) == true
                        updateRowVisuals(acceptNow = nowA, rejectNow = false)
                    }

                    boxR.setOnClickListener {
                        val setR = rejectMap.getOrPut(city) { mutableSetOf() }
                        if (setR.contains(locality)) {
                            setR.remove(locality)
                            if (setR.isEmpty()) rejectMap.remove(city)
                        } else {
                            setR.add(locality)
                            acceptMap[city]?.remove(locality)
                            if (acceptMap[city]?.isEmpty() == true) acceptMap.remove(city)
                        }
                        refreshHeaderBoxes()
                        val nowAllA = acceptMap[city]?.contains(AreaPrefs.ALL_MARKER) == true
                        val nowAllR = rejectMap[city]?.contains(AreaPrefs.ALL_MARKER) == true
                        val nowR = nowAllR || rejectMap[city]?.contains(locality) == true
                        val nowA = !nowR && (nowAllA || acceptMap[city]?.contains(locality) == true)
                        updateRowVisuals(acceptNow = nowA, rejectNow = nowR)
                    }

                    row.addView(tvLoc)
                    row.addView(boxA)
                    row.addView(boxR)
                    childContainer.addView(row)
                }

                if (localities.size > displayLimit) {
                    val remaining = localities.size - displayLimit
                    childContainer.addView(Button(this@AreaPreferenceActivity).apply {
                        text = "Aur $remaining jagah dikhayein (search se jaldi milega)"
                        setOnClickListener {
                            displayLimit += 150
                            buildChildRows()
                        }
                    })
                }
            }

            // ONLY build child rows right now if this city is actually expanded!
            if (isExpanded) {
                buildChildRows()
            }

            expandArrow.setOnClickListener {
                val nowVisible = childContainer.visibility != View.VISIBLE
                if (nowVisible) {
                    expandedCities.add(city)
                    if (!childrenBuilt) buildChildRows()
                    childContainer.visibility = View.VISIBLE
                } else {
                    expandedCities.remove(city)
                    childContainer.visibility = View.GONE
                }
                expandArrow.text = (if (nowVisible) "\u25BE " else "\u25B8 ") + "$city (${localities.size})"
            }

            btnCityA.setOnClickListener {
                ensurePickupAcceptActive()
                val curAllA = acceptMap[city]?.contains(AreaPrefs.ALL_MARKER) == true
                if (curAllA) {
                    acceptMap.remove(city)
                } else {
                    acceptMap[city] = mutableSetOf(AreaPrefs.ALL_MARKER)
                    rejectMap[city]?.remove(AreaPrefs.ALL_MARKER)
                    if (rejectMap[city]?.isEmpty() == true) rejectMap.remove(city)
                }
                refreshHeaderBoxes()
                if (childContainer.visibility == View.VISIBLE) {
                    buildChildRows()
                } else {
                    childrenBuilt = false
                }
            }

            btnCityR.setOnClickListener {
                val curAllR = rejectMap[city]?.contains(AreaPrefs.ALL_MARKER) == true
                if (curAllR) {
                    rejectMap.remove(city)
                } else {
                    rejectMap[city] = mutableSetOf(AreaPrefs.ALL_MARKER)
                    acceptMap.remove(city)
                }
                refreshHeaderBoxes()
                if (childContainer.visibility == View.VISIBLE) {
                    buildChildRows()
                } else {
                    childrenBuilt = false
                }
            }

            section.addView(headerRow)
            section.addView(childContainer)
            binding.cityContainer.addView(section)
        }
    }

    private fun showSaveTickedAsFolderDialog() {
        val map = activeAcceptMap()
        val flattened = LinkedHashSet<String>()
        for ((city, set) in map) {
            if (set.contains(AreaPrefs.ALL_MARKER)) {
                flattened.addAll(allCities[city].orEmpty())
            } else {
                flattened.addAll(set)
            }
        }
        if (flattened.isEmpty()) {
            Toast.makeText(
                this,
                "Pehle kisi location par [A] select karein, phir folder banayein",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        box.addView(TextView(this).apply {
            text = "Naya Folder - ${flattened.size} jagah"
            textSize = 18f
            setPadding(0, 0, 0, pad / 2)
        })
        val nameInput = EditText(this).apply {
            hint = "Folder ka naam (jaise: Yash Delhi)"
        }
        box.addView(nameInput)

        AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("Banao") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isNotEmpty()) {
                    AreaPrefs.addCustomLocalities(this, name, flattened.toList())
                    allCities = LocationRepository.loadAllCitiesWithCustom(this)
                    renderCities()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreateFolderDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val nameInput = EditText(this).apply { hint = "Folder ka naam" }
        val placesInput = EditText(this).apply {
            hint = "Jagah ke naam (comma ya nayi line se alag)"
            minLines = 4
            gravity = Gravity.TOP or Gravity.START
            isSingleLine = false
        }
        box.addView(nameInput)
        box.addView(placesInput)

        AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("Banao") { _, _ ->
                val name = nameInput.text.toString().trim()
                val places = placesInput.text.toString()
                    .split(Regex("[,\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
                if (name.isNotEmpty() && places.isNotEmpty()) {
                    AreaPrefs.addCustomLocalities(this, name, places)
                    allCities = LocationRepository.loadAllCitiesWithCustom(this)
                    renderCities()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveAndFinish() {
        if (pickupNoRestriction) {
            AreaPrefs.setPickupSelections(this, emptyMap())
            AreaPrefs.setPickupKeywords(this, emptyList())
        } else {
            AreaPrefs.setPickupSelections(this, workingPickupA)
            val words = binding.pickupKeywords.text.toString()
                .split(Regex("[,;\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            AreaPrefs.setPickupKeywords(this, words)
        }
        val maxKm = binding.etMaxPickupKm.text.toString().trim().replace(',', '.').toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
        AreaPrefs.setMaxPickupKm(this, maxKm)
        AreaPrefs.setAllowPickupWhenKmMissing(this, binding.cbAllowMissingKm.isChecked)
        AreaPrefs.setDropSelections(this, workingDropA)
        AreaPrefs.setRejectPickupSelections(this, workingPickupR)
        AreaPrefs.setRejectDropSelections(this, workingDropR)

        val msg = if (workingDropA.isEmpty()) {
            "Saved! Dhyan dein: DROP tab me kam se kam 1 area (A) select karein warna order nahi uthega"
        } else {
            "Saved (A, R & Distance rules)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
