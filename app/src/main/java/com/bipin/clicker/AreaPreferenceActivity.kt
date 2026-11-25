package com.bipin.clicker

import android.content.res.ColorStateList
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

    // Working (unsaved) selections: Sirf A (Accept)
    private val workingPickupA = mutableMapOf<String, MutableSet<String>>()
    private val workingDropA = mutableMapOf<String, MutableSet<String>>()

    private val expandedCities = mutableSetOf<String>()
    private val searchHandler = Handler(Looper.getMainLooper())
    private var pendingSearchRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!PermissionManager.isEverythingReady(this)) {
            Toast.makeText(
                this,
                "Pehle sabhi settings ON karein, tabhi Location set hogi",
                Toast.LENGTH_LONG
            ).show()
            startActivity(android.content.Intent(this, PermissionSetupActivity::class.java))
            finish()
            return
        }
        binding = ActivityAreaPreferenceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        verifiedCityNames = LocationRepository.loadVerifiedCities(this).keys
        allCities = LocationRepository.loadAllCitiesWithCustom(this)

        loadWorkingStateFrom(AreaPrefs.getPickupSelections(this), workingPickupA)
        loadWorkingStateFrom(AreaPrefs.getDropSelections(this), workingDropA)

        binding.pickupKeywords.setText(AreaPrefs.getPickupKeywords(this).joinToString(", "))
        val savedMaxKm = AreaPrefs.getMaxPickupKm(this)
        binding.etMaxPickupKm.setText(
            if (savedMaxKm > 0f) {
                if (savedMaxKm % 1f == 0f) savedMaxKm.toInt().toString() else savedMaxKm.toString()
            } else {
                ""
            }
        )

        binding.btnDistOff.setOnClickListener { binding.etMaxPickupKm.setText("") }
        binding.btnDist05.setOnClickListener { binding.etMaxPickupKm.setText("0.5") }
        binding.btnDist06.setOnClickListener { binding.etMaxPickupKm.setText("0.6") }
        binding.btnDist1.setOnClickListener { binding.etMaxPickupKm.setText("1") }
        binding.btnDist2.setOnClickListener { binding.etMaxPickupKm.setText("2") }
        binding.btnDist3.setOnClickListener { binding.etMaxPickupKm.setText("3") }
        binding.btnDist5.setOnClickListener { binding.etMaxPickupKm.setText("5") }
        binding.etMaxPickupKm.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshDistanceButtons()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        refreshDistanceButtons()

        binding.btnTabPickup.setOnClickListener { switchTab(Tab.PICKUP) }
        binding.btnTabDrop.setOnClickListener { switchTab(Tab.DROP) }

        binding.btnSaveAreas.setOnClickListener { saveAndFinish() }

        binding.btnResetAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Sab location hatao?")
                .setMessage("Pickup (A), Drop (A), distance aur khaas naam sab hat jayenge.")
                .setPositiveButton("Haan, sab hatao") { _, _ ->
                    workingPickupA.clear()
                    workingDropA.clear()
                    binding.pickupKeywords.setText("")
                    binding.etMaxPickupKm.setText("")
                    AreaPrefs.setPickupSelections(this, emptyMap())
                    AreaPrefs.setDropSelections(this, emptyMap())
                    AreaPrefs.setRejectPickupSelections(this, emptyMap())
                    AreaPrefs.setRejectDropSelections(this, emptyMap())
                    AreaPrefs.setPickupKeywords(this, emptyList())
                    AreaPrefs.setMaxPickupKm(this, 0f)
                    AreaPrefs.setAllowPickupWhenKmMissing(this, true)
                    renderCities()
                    Toast.makeText(this, "Sab location hat gaye", Toast.LENGTH_SHORT).show()
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
        binding.pickupFilterBox.visibility = if (tab == Tab.PICKUP) View.VISIBLE else View.GONE
        renderCities()
    }

    private fun activeAcceptMap(): MutableMap<String, MutableSet<String>> =
        if (currentTab == Tab.PICKUP) workingPickupA else workingDropA

    private fun refreshDistanceButtons() {
        val currentKm = binding.etMaxPickupKm.text?.toString()?.trim()?.replace(',', '.')?.toFloatOrNull() ?: 0f
        val offColor = ColorStateList.valueOf(Color.parseColor("#2A2A2A"))
        val activeGreen = ColorStateList.valueOf(Color.parseColor("#43A047"))
        val activeRed = ColorStateList.valueOf(Color.parseColor("#C62828"))
        binding.btnDistOff.backgroundTintList = if (currentKm <= 0f) activeRed else offColor
        val presets = listOf(
            binding.btnDist05 to 0.5f,
            binding.btnDist06 to 0.6f,
            binding.btnDist1 to 1f,
            binding.btnDist2 to 2f,
            binding.btnDist3 to 3f,
            binding.btnDist5 to 5f,
        )
        for ((btn, kmVal) in presets) {
            val isMatch = currentKm > 0f && kotlin.math.abs(currentKm - kmVal) < 0.001f
            btn.backgroundTintList = if (isMatch) activeGreen else offColor
        }
    }

    private fun styleAcceptBox(tv: TextView, isActive: Boolean) {
        val dp = resources.displayMetrics.density
        tv.setTextColor(if (isActive) Color.WHITE else Color.parseColor("#81C784"))
        tv.background = GradientDrawable().apply {
            cornerRadius = 8 * dp
            setColor(if (isActive) Color.parseColor("#43A047") else Color.parseColor("#2A2A2A"))
            setStroke(
                (1 * dp).toInt().coerceAtLeast(1),
                if (isActive) Color.parseColor("#81C784") else Color.parseColor("#424242")
            )
        }
    }

    private fun makeAcceptBox(label: String, isActive: Boolean): TextView {
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
            styleAcceptBox(this, isActive)
        }
    }

    private fun renderCities() {
        binding.cityContainer.removeAllViews()
        val acceptMap = activeAcceptMap()
        val query = binding.locationSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val rawQuery = binding.locationSearch.text?.toString()?.trim().orEmpty()

        binding.cityContainer.addView(TextView(this).apply {
            text = "Jis location ka order uthana hai uske aage [A] (Accept) dabayein"
            setTextColor(Color.parseColor("#B0BEC5"))
            textSize = 12f
            setPadding(0, 4, 0, 10)
        })

        if (rawQuery.isNotEmpty()) {
            val exact = allCities.values.any { list -> list.any { it.equals(rawQuery, ignoreCase = true) } }
            if (!exact) {
                val niceName = rawQuery.split(" ").filter { it.isNotEmpty() }
                    .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
                binding.cityContainer.addView(TextView(this).apply {
                    text = "\"$niceName\" list me nahi mila — kis city me Accept (A) jodna hai:"
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
                            AreaPrefs.addCustomLocality(this@AreaPreferenceActivity, cityName, niceName)
                            allCities = LocationRepository.loadAllCitiesWithCustom(this@AreaPreferenceActivity)
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
            val isAllAccept = acceptSet.contains(AreaPrefs.ALL_MARKER)

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

            val btnCityA = makeAcceptBox("A", isActive = isAllAccept)

            headerRow.addView(expandArrow)
            headerRow.addView(btnCityA)

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
                styleAcceptBox(btnCityA, isActive = nowAllA)
            }

            fun buildChildRows() {
                childContainer.removeAllViews()
                childrenBuilt = true
                val curA = acceptMap[city] ?: mutableSetOf()
                val curAllA = curA.contains(AreaPrefs.ALL_MARKER)

                // Inline add new sector with +A only
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
                val addABtn = makeAcceptBox("+A", isActive = true)
                addABtn.setOnClickListener {
                    val value = input.text.toString().trim()
                    if (value.isEmpty()) {
                        Toast.makeText(this@AreaPreferenceActivity, "Area ka naam likhein", Toast.LENGTH_SHORT).show()
                    } else {
                        AreaPrefs.addCustomLocality(this@AreaPreferenceActivity, city, value)
                        allCities = LocationRepository.loadAllCitiesWithCustom(this@AreaPreferenceActivity)
                        acceptMap.getOrPut(city) { mutableSetOf() }.add(value)
                        expandedCities.add(city)
                        renderCities()
                    }
                }
                addRow.addView(input)
                addRow.addView(addABtn)
                childContainer.addView(addRow)

                val slice = if (localities.size > displayLimit) localities.subList(0, displayLimit) else localities
                for (locality in slice) {
                    val locAccept = curAllA || curA.contains(locality)

                    val row = LinearLayout(this@AreaPreferenceActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(8, 6, 4, 6)
                        if (locAccept) {
                            setBackgroundColor(Color.argb(45, 67, 160, 71))
                        }
                    }

                    val tvLoc = TextView(this@AreaPreferenceActivity).apply {
                        text = locality
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }

                    val boxA = makeAcceptBox("A", isActive = locAccept)

                    fun updateRowVisuals(acceptNow: Boolean) {
                        styleAcceptBox(boxA, isActive = acceptNow)
                        row.setBackgroundColor(
                            if (acceptNow) Color.argb(45, 67, 160, 71) else Color.TRANSPARENT
                        )
                    }

                    boxA.setOnClickListener {
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
                        updateRowVisuals(acceptNow = nowA)
                    }

                    row.addView(tvLoc)
                    row.addView(boxA)
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
                val curAllA = acceptMap[city]?.contains(AreaPrefs.ALL_MARKER) == true
                if (curAllA) {
                    acceptMap.remove(city)
                } else {
                    acceptMap[city] = mutableSetOf(AreaPrefs.ALL_MARKER)
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

    private fun saveAndFinish() {
        val words = binding.pickupKeywords.text.toString()
            .split(Regex("[,;\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        AreaPrefs.setPickupSelections(this, workingPickupA)
        AreaPrefs.setPickupKeywords(this, words)
        val maxKm = binding.etMaxPickupKm.text.toString().trim().replace(',', '.').toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
        AreaPrefs.setMaxPickupKm(this, maxKm)
        AreaPrefs.setAllowPickupWhenKmMissing(this, true)
        AreaPrefs.setDropSelections(this, workingDropA)
        AreaPrefs.setRejectPickupSelections(this, emptyMap())
        AreaPrefs.setRejectDropSelections(this, emptyMap())

        val hasPickupLoc = workingPickupA.isNotEmpty() || words.isNotEmpty()
        val hasPickupKm = maxKm > 0f
        val msg = when {
            workingDropA.isEmpty() ->
                "Saved! Dhyan dein: DROP tab me kam se kam 1 area (A) select karein warna order nahi uthega"
            !hasPickupLoc && !hasPickupKm ->
                "Saved! Pickup: Kahin se bhi aur kitni bhi door se uthega"
            else ->
                "Saved (Accept & Distance rules)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
