package com.bipin.clicker

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import android.widget.ImageView
import com.bipin.clicker.databinding.ActivityAreaPreferenceBinding

private enum class Tab { PICKUP, DROP }

class AreaPreferenceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAreaPreferenceBinding

    private var currentTab = Tab.PICKUP
    private var allCities: Map<String, List<String>> = emptyMap()

    // Working (unsaved) selections - dono tabs ka state ek saath memory me rehta hai
    private val workingPickup = mutableMapOf<String, MutableSet<String>>()
    private val workingDrop = mutableMapOf<String, MutableSet<String>>()
    private var pickupNoRestriction = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAreaPreferenceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        allCities = LocationRepository.loadAllCitiesWithCustom(this)

        loadWorkingStateFrom(AreaPrefs.getPickupSelections(this), workingPickup)
        loadWorkingStateFrom(AreaPrefs.getDropSelections(this), workingDrop)
        pickupNoRestriction = AreaPrefs.getPickupSelections(this).isEmpty() &&
            AreaPrefs.getPickupKeywords(this).isEmpty()
        binding.pickupKeywords.setText(AreaPrefs.getPickupKeywords(this).joinToString(", "))
        // Naam likhna shuru karo to "khaali rakhein" apne aap hat jaye
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
            setCityContainerEnabled(!checked)
        }

        binding.btnSaveAreas.setOnClickListener { saveAndFinish() }

        // Ek button se pura location (pickup, drop, khaas naam) hat jaye
        binding.btnResetAll.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Sab location hatao?")
                .setMessage("Pickup, Drop aur khaas naam sab hat jayenge. Naya Drop set karne tak koi order auto-accept nahi hoga.")
                .setPositiveButton("Haan, sab hatao") { _, _ ->
                    workingPickup.clear()
                    workingDrop.clear()
                    pickupNoRestriction = true
                    binding.pickupKeywords.setText("")
                    binding.cbNoPickupRestriction.isChecked = true
                    AreaPrefs.setPickupSelections(this, emptyMap())
                    AreaPrefs.setDropSelections(this, emptyMap())
                    AreaPrefs.setPickupKeywords(this, emptyList())
                    renderCities()
                    Toast.makeText(this, "Sab location hat gaye", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Nahi", null)
                .show()
        }

        binding.locationSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderCities()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        switchTab(Tab.PICKUP)
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
        binding.tvKeywordHelp.visibility = if (tab == Tab.PICKUP) View.VISIBLE else View.GONE
        binding.pickupKeywords.visibility = if (tab == Tab.PICKUP) View.VISIBLE else View.GONE
        binding.cbNoPickupRestriction.isChecked = pickupNoRestriction
        renderCities()
    }

    private fun activeMap(): MutableMap<String, MutableSet<String>> =
        if (currentTab == Tab.PICKUP) workingPickup else workingDrop

    private fun setCityContainerEnabled(enabled: Boolean) {
        binding.cityContainer.alpha = if (enabled) 1f else 0.4f
        setViewGroupEnabled(binding.cityContainer, enabled)
    }

    private fun setViewGroupEnabled(vg: ViewGroup, enabled: Boolean) {
        for (i in 0 until vg.childCount) {
            val child = vg.getChildAt(i)
            child.isEnabled = enabled
            if (child is ViewGroup) setViewGroupEnabled(child, enabled)
        }
    }

    private fun renderCities() {
        binding.cityContainer.removeAllViews()
        val map = activeMap()
        val query = binding.locationSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val rawQuery = binding.locationSearch.text?.toString()?.trim().orEmpty()
        val verifiedCityNames = LocationRepository.loadVerifiedCities(this).keys

        // Apna naya folder (jaise Gurugram/Noida jaisa) khud bana sakte ho
        binding.cityContainer.addView(Button(this).apply {
            text = "\u2713 Tick kiye hue se Naya Folder Banayein"
            setOnClickListener { showSaveTickedAsFolderDialog() }
        })
        binding.cityContainer.addView(Button(this).apply {
            text = "+ Naya Folder (naam type karke)"
            setOnClickListener { showCreateFolderDialog() }
        })

        // Jo naam list me nahi hai use seedha add karne ki suvidha: naam likho, city ka button dabao
        if (rawQuery.isNotEmpty()) {
            val exact = allCities.values.any { list -> list.any { it.equals(rawQuery, ignoreCase = true) } }
            if (!exact) {
                val niceName = rawQuery.split(" ").filter { it.isNotEmpty() }
                    .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
                binding.cityContainer.addView(TextView(this).apply {
                    text = "\"$niceName\" list me pura nahi mila?\nIse kis city me jodna hai, wo button dabao:"
                    setTextColor(0xFFFFB74D.toInt())
                    textSize = 14f
                    setPadding(0, 12, 0, 8)
                })
                val scroll = android.widget.HorizontalScrollView(this)
                val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                for (cityName in allCities.keys) {
                    btnRow.addView(Button(this).apply {
                        text = "+ $cityName"
                        setOnClickListener {
                            AreaPrefs.addCustomLocality(this@AreaPreferenceActivity, cityName, niceName)
                            allCities = LocationRepository.loadAllCitiesWithCustom(this@AreaPreferenceActivity)
                            activeMap().getOrPut(cityName) { mutableSetOf() }.add(niceName)
                            Toast.makeText(
                                this@AreaPreferenceActivity,
                                "$niceName ($cityName) jud gaya aur select ho gaya. SAVE dabao.",
                                Toast.LENGTH_LONG
                            ).show()
                            binding.locationSearch.setText("")
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
            val selectedSet = map[city] ?: mutableSetOf()
            val isAll = selectedSet.contains(AreaPrefs.ALL_MARKER)

            val section = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 12, 0, 12)
            }

            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val expandArrow = TextView(this).apply {
                text = "\u25B8 $city (${localities.size})"
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(0, 8, 8, 8)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val allCheckBox = CheckBox(this).apply {
                text = "All $city"
                setTextColor(Color.WHITE)
                isChecked = isAll
                background = selectedBackground(isAll)
            }

            headerRow.addView(expandArrow)
            headerRow.addView(allCheckBox)

            // Apna khud banaya folder ho (Gurugram/Noida jaisa built-in nahi) to delete (X) button
            if (city !in verifiedCityNames) {
                headerRow.addView(TextView(this).apply {
                    text = "  \u2716"
                    textSize = 16f
                    setTextColor(0xFFEF5350.toInt())
                    setPadding(16, 8, 4, 8)
                    setOnClickListener {
                        androidx.appcompat.app.AlertDialog.Builder(this@AreaPreferenceActivity)
                            .setTitle("\"$city\" folder hatayein?")
                            .setMessage("Is poore folder ki sab jagah hat jayengi. Agar ye select kiya hua hai, to wo bhi hat jayega.")
                            .setPositiveButton("Haan, hatao") { _, _ ->
                                AreaPrefs.removeCustomCity(this@AreaPreferenceActivity, city)
                                workingPickup.remove(city)
                                workingDrop.remove(city)
                                allCities = LocationRepository.loadAllCitiesWithCustom(this@AreaPreferenceActivity)
                                renderCities()
                                Toast.makeText(this@AreaPreferenceActivity, "\"$city\" hata diya gaya", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Nahi", null)
                            .show()
                    }
                })
            }

            val childContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(24, 0, 0, 0)
            }

            fun rebuildChildCheckboxes() {
                childContainer.removeAllViews()
                val currentSet = map[city] ?: mutableSetOf()
                val currentIsAll = currentSet.contains(AreaPrefs.ALL_MARKER)
                // Naya area likhne wali row: AB SABSE UPAR (list ke neeche nahi)
                val addRow = LinearLayout(this@AreaPreferenceActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 8)
                }
                val input = EditText(this@AreaPreferenceActivity).apply {
                    hint = "Naya area/sector likhein"
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val addBtn = Button(this@AreaPreferenceActivity).apply {
                    text = "+ Add"
                    setOnClickListener {
                        val value = input.text.toString().trim()
                        if (value.isEmpty()) {
                            Toast.makeText(this@AreaPreferenceActivity, "Area ka naam likhein", Toast.LENGTH_SHORT).show()
                        } else {
                            AreaPrefs.addCustomLocality(this@AreaPreferenceActivity, city, value)
                            allCities = LocationRepository.loadAllCitiesWithCustom(this@AreaPreferenceActivity)
                            val set = map.getOrPut(city) { mutableSetOf() }
                            set.add(value)
                            renderCities()
                        }
                    }
                }
                addRow.addView(input)
                addRow.addView(addBtn)
                childContainer.addView(addRow)

                for (locality in localities) {
                    val cb = CheckBox(this@AreaPreferenceActivity).apply {
                        text = locality
                        setTextColor(Color.WHITE)
                        isChecked = currentIsAll || currentSet.contains(locality)
                        isEnabled = !currentIsAll
                        background = selectedBackground(isChecked)
                        setOnCheckedChangeListener { button, checked ->
                            button.background = selectedBackground(checked)
                            val set = map.getOrPut(city) { mutableSetOf() }
                            if (checked) set.add(locality) else set.remove(locality)
                            if (set.isEmpty()) map.remove(city)
                        }
                    }
                    childContainer.addView(cb)
                }

            }
            rebuildChildCheckboxes()

            expandArrow.setOnClickListener {
                val nowVisible = childContainer.visibility != View.VISIBLE
                childContainer.visibility = if (nowVisible) View.VISIBLE else View.GONE
                expandArrow.text = (if (nowVisible) "\u25BE " else "\u25B8 ") + "$city (${localities.size})"
            }

            allCheckBox.setOnCheckedChangeListener { button, checked ->
                button.background = selectedBackground(checked)
                if (checked) {
                    map[city] = mutableSetOf(AreaPrefs.ALL_MARKER)
                } else {
                    map.remove(city)
                }
                rebuildChildCheckboxes()
            }

            section.addView(headerRow)
            section.addView(childContainer)
            binding.cityContainer.addView(section)
        }

        setCityContainerEnabled(!(currentTab == Tab.PICKUP && pickupNoRestriction))
    }

    /**
     * Neeche, har city ke andar jo tick (check) kiya hua hai (is tab - Pickup ya Drop - me),
     * usi ko ek NAYE folder me bundle kar deta hai. "All <city>" tick ho to uski poori list
     * khul kar aa jaati hai. Isse user sirf TICK marking karke apna group bana sakta hai,
     * naam type karne ki zarurat nahi.
     */
    private fun showSaveTickedAsFolderDialog() {
        val map = activeMap()
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
                "Pehle niche kisi city ke andar jagah tick karo, phir ye button dabao",
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
            text = "Naya Folder - Tick ki hui ${flattened.size} jagah"
            textSize = 18f; setPadding(0, 0, 0, pad / 2)
        })
        box.addView(TextView(this).apply {
            text = flattened.joinToString(", ")
            textSize = 13f
            setTextColor(0xFFBDBDBD.toInt())
            setPadding(0, 0, 0, pad / 2)
        })
        val nameInput = EditText(this).apply {
            hint = "Folder ka naam (jaise: Yash Delhi)"
        }
        box.addView(nameInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("Banao") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Folder ka naam likhein", Toast.LENGTH_SHORT).show()
                } else {
                    AreaPrefs.addCustomLocalities(this, name, flattened.toList())
                    allCities = LocationRepository.loadAllCitiesWithCustom(this)
                    renderCities()
                    Toast.makeText(
                        this,
                        "\"$name\" ban gaya (${flattened.size} jagah). Use select karne ke liye niche kholo aur SAVE dabao.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Naam + jagah ki poori list ek saath daal kar naya folder banane wala dialog. */
    private fun showCreateFolderDialog() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val nameInput = EditText(this).apply {
            hint = "Folder ka naam (jaise: Yash Central Delhi)"
        }
        val placesInput = EditText(this).apply {
            hint = "Jagah ke naam, comma ya nayi line se alag karo\n(jaise: Chandni Chowk, Lal Qila, Nehru Place)"
            minLines = 5
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            isSingleLine = false
        }
        box.addView(TextView(this).apply {
            text = "Naya Folder"; textSize = 18f; setPadding(0, 0, 0, pad / 2)
        })
        box.addView(nameInput)
        box.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, pad / 2) })
        box.addView(placesInput)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setView(box)
            .setPositiveButton("Banao") { _, _ ->
                val name = nameInput.text.toString().trim()
                val places = placesInput.text.toString()
                    .split(Regex("[,\n]+")).map { it.trim() }.filter { it.isNotEmpty() }
                if (name.isEmpty()) {
                    Toast.makeText(this, "Folder ka naam likhein", Toast.LENGTH_SHORT).show()
                } else if (places.isEmpty()) {
                    Toast.makeText(this, "Kam se kam ek jagah ka naam likhein", Toast.LENGTH_SHORT).show()
                } else {
                    AreaPrefs.addCustomLocalities(this, name, places)
                    allCities = LocationRepository.loadAllCitiesWithCustom(this)
                    renderCities()
                    Toast.makeText(
                        this,
                        "\"$name\" ban gaya (${places.size} jagah). Niche kholkar select karo aur SAVE dabao.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun selectedBackground(selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = 10f
            setColor(if (selected) Color.rgb(35, 95, 55) else Color.TRANSPARENT)
            setStroke(1, if (selected) Color.rgb(90, 210, 120) else Color.TRANSPARENT)
        }

    private fun saveAndFinish() {
        if (pickupNoRestriction) {
            AreaPrefs.setPickupSelections(this, emptyMap())
            AreaPrefs.setPickupKeywords(this, emptyList())
        } else {
            AreaPrefs.setPickupSelections(this, workingPickup)
            val words = binding.pickupKeywords.text.toString()
                .split(Regex("[,;\\n]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            AreaPrefs.setPickupKeywords(this, words)
        }
        AreaPrefs.setDropSelections(this, workingDrop)

        if (workingDrop.isEmpty()) {
            Toast.makeText(
                this,
                "Dhyan dein: Drop area set kiye bina koi bhi order auto-accept nahi hoga",
                Toast.LENGTH_LONG
            ).show()
        } else {
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
