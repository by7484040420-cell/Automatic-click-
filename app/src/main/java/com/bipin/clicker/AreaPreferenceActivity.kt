package com.bipin.clicker

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bipin.clicker.databinding.ActivityAreaPreferenceBinding

class AreaPreferenceActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAreaPreferenceBinding
    private lateinit var allCities: List<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAreaPreferenceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ab poori city/zone select hoti hai (jaise "Gurugram"), sector-by-sector nahi -
        // is city ke andar ka koi bhi sector select karte hi match ho jayega.
        allCities = LocationRepository.loadCityNames(this)

        val pickupAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, allCities)
        binding.listPickup.adapter = pickupAdapter
        binding.listPickup.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        restoreSelection(binding.listPickup, AreaPrefs.getPickupCities(this))

        val dropAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, allCities)
        binding.listDrop.adapter = dropAdapter
        binding.listDrop.choiceMode = ListView.CHOICE_MODE_MULTIPLE
        restoreSelection(binding.listDrop, AreaPrefs.getDropCities(this))

        binding.btnSaveAreas.setOnClickListener {
            val chosenPickup = getCheckedItems(binding.listPickup)
            val chosenDrop = getCheckedItems(binding.listDrop)

            if (chosenPickup.isEmpty() || chosenDrop.isEmpty()) {
                Toast.makeText(this, "Kam se kam ek Pickup aur ek Drop city chunein", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            AreaPrefs.savePickupCities(this, chosenPickup)
            AreaPrefs.saveDropCities(this, chosenDrop)
            Toast.makeText(this, "Service area save ho gaya", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun restoreSelection(listView: ListView, saved: Set<String>) {
        for (i in allCities.indices) {
            if (allCities[i] in saved) {
                listView.setItemChecked(i, true)
            }
        }
    }

    private fun getCheckedItems(listView: ListView): Set<String> {
        val checked = mutableSetOf<String>()
        val checkedPositions = listView.checkedItemPositions
        for (i in 0 until checkedPositions.size()) {
            val position = checkedPositions.keyAt(i)
            if (checkedPositions.valueAt(i)) {
                checked.add(allCities[position])
            }
        }
        return checked
    }
}
