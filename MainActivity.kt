package com.bipin.clicker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bipin.clicker.databinding.ActivityMainBinding
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var adapter: ArrayAdapter<String>
    private val words = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("clicker_prefs", Context.MODE_PRIVATE)
        words.addAll(prefs.getStringSet("target_words", emptySet())!!.toMutableList())
        words.sort()

        binding.editPickup.setText(prefs.getString("pickup", ""))
        binding.editDrop.setText(prefs.getString("drop", ""))
        val savedAutomation = prefs.getBoolean("pickup_drop_enabled", false)
        binding.switchPickupDrop.isChecked = savedAutomation

        binding.switchPickupDrop.setOnCheckedChangeListener { _, checked ->
            prefs.edit()
                .putBoolean("pickup_drop_enabled", checked)
                .putBoolean("automation_running", checked)
                .apply()
            Toast.makeText(
                this,
                if (checked) "Automation ON — ab baar-baar ON nahi karna padega" else "Automation OFF",
                Toast.LENGTH_SHORT
            ).show()
            updateStatus()
        }

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, words)
        binding.listWords.adapter = adapter

        binding.btnSavePickupDrop.setOnClickListener {
            val pickup = binding.editPickup.text.toString().trim()
            val drop = binding.editDrop.text.toString().trim()
            if (pickup.isEmpty() || drop.isEmpty()) {
                Toast.makeText(this, "Pickup aur Drop dono likhein", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit()
                .putString("pickup", pickup)
                .putString("drop", drop)
                .putBoolean("pickup_drop_enabled", true)
                .putBoolean("automation_running", true)
                .apply()
            binding.switchPickupDrop.isChecked = true
            Toast.makeText(this, "Pickup → Drop automation ON", Toast.LENGTH_SHORT).show()
        }

        binding.btnStopPickupDrop.setOnClickListener {
            prefs.edit().putBoolean("automation_running", false).apply()
            binding.switchPickupDrop.isChecked = false
            Toast.makeText(this, "Pickup/Drop automation STOP", Toast.LENGTH_SHORT).show()
        }

        binding.btnAdd.setOnClickListener {
            val text = binding.editWord.text.toString().trim()
            if (text.isNotEmpty() && !words.contains(text)) {
                words.add(text)
                words.sort()
                saveWords()
                adapter.notifyDataSetChanged()
                binding.editWord.setText("")
            }
        }

        binding.listWords.setOnItemClickListener { _, _, position, _ ->
            words.removeAt(position)
            saveWords()
            adapter.notifyDataSetChanged()
        }

        binding.btnEnableService.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnLoadLocations.setOnClickListener {
            loadLocationsFromAssets()
        }

        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val enabled = isAccessibilityServiceEnabled()
        // Once enabled, the setting is stored in SharedPreferences and survives
        // closing/reopening the app. The accessibility service will read it again
        // whenever Android reconnects the service.
        val configured = prefs.getBoolean("pickup_drop_enabled", false)
        val running = configured && prefs.getBoolean("automation_running", configured)
        binding.statusText.text = if (enabled) {
            if (running) "Accessibility: ON • Pickup/Drop: RUNNING"
            else "Accessibility: ON • Pickup/Drop: STOPPED"
        } else {
            "Accessibility Service: OFF hai - ON karein"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${ClickerAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }

    private fun saveWords() {
        prefs.edit().putStringSet("target_words", words.toSet()).apply()
    }

    private fun loadLocationsFromAssets() {
        try {
            val json = assets.open("locations.json").bufferedReader().use { it.readText() }
            val obj = JSONObject(json)
            val cities = obj.getJSONObject("cities")
            var added = 0
            cities.keys().forEach { city ->
                val arr = cities.getJSONArray(city)
                for (i in 0 until arr.length()) {
                    val sector = arr.getString(i)
                    if (!words.contains(sector)) {
                        words.add(sector)
                        added++
                    }
                }
            }
            words.sort()
            saveWords()
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "$added locations add ho gaye", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
