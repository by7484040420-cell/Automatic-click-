package com.bipin.clicker

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bipin.clicker.databinding.ActivityAreaPreferenceBinding

private enum class Tab { PICKUP, DROP }

class AreaPreferenceActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAreaPreferenceBinding
    private var currentTab = Tab.PICKUP
    private var allCities: Map<String, List<String>> = emptyMap()
    private val workingPickup = mutableMapOf<String, MutableSet<String>>()
    private val workingDrop = mutableMapOf<String, MutableSet<String>>()
    private var pickupNoRestriction = false
    private var filterText = ""

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
            pickupNoRestriction = checked; setCityContainerEnabled(!checked)
        }
        binding.etLocationSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filterText = s?.toString()?.trim().orEmpty(); renderCities() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        binding.btnSaveAreas.setOnClickListener { saveAndFinish() }
        switchTab(Tab.PICKUP)
    }

    private fun loadWorkingStateFrom(saved: Map<String, Set<String>>, target: MutableMap<String, MutableSet<String>>) {
        target.clear(); saved.forEach { (city, set) -> target[city] = set.toMutableSet() }
    }
    private fun switchTab(tab: Tab) {
        currentTab = tab
        binding.btnTabPickup.alpha = if (tab == Tab.PICKUP) 1f else .5f
        binding.btnTabDrop.alpha = if (tab == Tab.DROP) 1f else .5f
        binding.cbNoPickupRestriction.visibility = if (tab == Tab.PICKUP) View.VISIBLE else View.GONE
        binding.etLocationSearch.setText(if (tab == Tab.PICKUP) AreaPrefs.getManualPickup(this) else AreaPrefs.getManualDrop(this))
        filterText = binding.etLocationSearch.text.toString()
        renderCities()
    }
    private fun activeMap() = if (currentTab == Tab.PICKUP) workingPickup else workingDrop
    private fun setCityContainerEnabled(enabled: Boolean) { binding.cityContainer.alpha = if (enabled) 1f else .4f; setViewGroupEnabled(binding.cityContainer, enabled) }
    private fun setViewGroupEnabled(vg: ViewGroup, enabled: Boolean) { for (i in 0 until vg.childCount) { val c=vg.getChildAt(i); c.isEnabled=enabled; if(c is ViewGroup)setViewGroupEnabled(c,enabled) } }

    private fun renderCities() {
        binding.cityContainer.removeAllViews()
        val map = activeMap(); val q = filterText.lowercase()
        for ((city, localities) in allCities) {
            val filtered = if (q.isBlank()) localities else localities.filter { city.lowercase().contains(q) || it.lowercase().contains(q) }
            if (q.isNotBlank() && !city.lowercase().contains(q) && filtered.isEmpty()) continue
            val selectedSet = map[city] ?: mutableSetOf(); val isAll = selectedSet.contains(AreaPrefs.ALL_MARKER)
            val section = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,10,0,10) }
            val header = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
            val arrow = TextView(this).apply { text="\u25B8 $city (${localities.size})"; textSize=16f; setTextColor(Color.WHITE); setPadding(0,8,8,8); layoutParams=LinearLayout.LayoutParams(0,-2,1f) }
            val all = CheckBox(this).apply { text="All $city"; setTextColor(Color.WHITE); isChecked=isAll; buttonTintList=android.content.res.ColorStateList.valueOf(Color.CYAN) }
            header.addView(arrow); header.addView(all)
            val children=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=if(q.isNotBlank())View.VISIBLE else View.GONE;setPadding(24,0,0,0)}
            fun rebuild(){
                children.removeAllViews(); val set=map[city]?:mutableSetOf(); val allNow=set.contains(AreaPrefs.ALL_MARKER)
                for(loc in filtered){ val cb=CheckBox(this@AreaPreferenceActivity).apply{ text=loc; textSize=15f; setTextColor(Color.WHITE); isChecked=allNow||set.contains(loc); isEnabled=!allNow; buttonTintList=android.content.res.ColorStateList.valueOf(Color.CYAN); setOnCheckedChangeListener{_,checked->val s=map.getOrPut(city){mutableSetOf()};if(checked)s.add(loc)else s.remove(loc);if(s.isEmpty())map.remove(city)} }; children.addView(cb) }
                val add=EditText(this@AreaPreferenceActivity).apply{hint="Is city me naya area/sector";setHintTextColor(0xFFAAAAAA.toInt());setTextColor(Color.WHITE);layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
                val row=LinearLayout(this@AreaPreferenceActivity).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,8,0,8)}
                val btn=Button(this@AreaPreferenceActivity).apply{text="+ Add";setOnClickListener{val v=add.text.toString().trim();if(v.isEmpty())Toast.makeText(this@AreaPreferenceActivity,"Area ka naam likhein",Toast.LENGTH_SHORT).show()else{AreaPrefs.addCustomLocality(this@AreaPreferenceActivity,city,v);allCities=LocationRepository.loadAllCitiesWithCustom(this@AreaPreferenceActivity);map.getOrPut(city){mutableSetOf()}.add(v);renderCities()}}}
                row.addView(add);row.addView(btn);children.addView(row)
            }
            rebuild()
            arrow.setOnClickListener{val show=children.visibility!=View.VISIBLE;children.visibility=if(show)View.VISIBLE else View.GONE;arrow.text=(if(show)"\u25BE " else "\u25B8 ")+"$city (${localities.size})"}
            all.setOnCheckedChangeListener{_,checked->if(checked)map[city]=mutableSetOf(AreaPrefs.ALL_MARKER)else map.remove(city);rebuild();all.setTextColor(Color.WHITE)}
            section.addView(header);section.addView(children);binding.cityContainer.addView(section)
        }
        setCityContainerEnabled(!(currentTab==Tab.PICKUP&&pickupNoRestriction))
    }

    private fun saveAndFinish(){
        val manual=binding.etLocationSearch.text.toString().trim()
        if(currentTab==Tab.PICKUP) AreaPrefs.setManualPickup(this,manual) else AreaPrefs.setManualDrop(this,manual)
        if(pickupNoRestriction) AreaPrefs.setPickupSelections(this,emptyMap()) else AreaPrefs.setPickupSelections(this,workingPickup)
        AreaPrefs.setDropSelections(this,workingDrop)
        Toast.makeText(this,"Saved",Toast.LENGTH_SHORT).show(); finish()
    }
}
