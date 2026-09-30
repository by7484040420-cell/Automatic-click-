package com.bipin.clicker

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
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
        pickupNoRestriction = AreaPrefs.getPickupSelections(this).isEmpty()

        binding.btnTabPickup.setOnClickListener { switchTab(Tab.PICKUP) }
        binding.btnTabDrop.setOnClickListener { switchTab(Tab.DROP) }

        binding.cbNoPickupRestriction.setOnCheckedChangeListener { _, checked ->
            pickupNoRestriction = checked
            setCityContainerEnabled(!checked)
        }

        binding.btnSaveAreas.setOnClickListener { saveAndFinish() }

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

        for ((city, localities) in allCities) {
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
                isChecked = isAll
            }

            headerRow.addView(expandArrow)
            headerRow.addView(allCheckBox)

            val childContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(24, 0, 0, 0)
            }

            fun rebuildChildCheckboxes() {
                childContainer.removeAllViews()
                val currentSet = map[city] ?: mutableSetOf()
                val currentIsAll = currentSet.contains(AreaPrefs.ALL_MARKER)
                for (locality in localities) {
                    val cb = CheckBox(this@AreaPreferenceActivity).apply {
                        text = locality
                        isChecked = currentIsAll || currentSet.contains(locality)
                        isEnabled = !currentIsAll
                        setOnCheckedChangeListener { _, checked ->
                            val set = map.getOrPut(city) { mutableSetOf() }
                            if (checked) set.add(locality) else set.remove(locality)
                            if (set.isEmpty()) map.remove(city)
                        }
                    }
                    childContainer.addView(cb)
                }

                // Custom locality add row
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
            }
            rebuildChildCheckboxes()

            expandArrow.setOnClickListener {
                val nowVisible = childContainer.visibility != View.VISIBLE
                childContainer.visibility = if (nowVisible) View.VISIBLE else View.GONE
                expandArrow.text = (if (nowVisible) "\u25BE " else "\u25B8 ") + "$city (${localities.size})"
            }

            allCheckBox.setOnCheckedChangeListener { _, checked ->
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

    private fun saveAndFinish() {
        if (pickupNoRestriction) {
            AreaPrefs.setPickupSelections(this, emptyMap())
        } else {
            AreaPrefs.setPickupSelections(this, workingPickup)
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
