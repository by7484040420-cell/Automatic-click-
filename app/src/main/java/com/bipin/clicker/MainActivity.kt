package com.bipin.clicker

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bipin.clicker.databinding.ActivityMainBinding
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.MobileAds

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var allEntries: List<LocationEntry>
    private val itemSamples = listOf("Food Order", "Grocery Bag", "Document Packet", "Medicine", "Parcel")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        MobileAds.initialize(this) {}
        binding.adView.loadAd(AdRequest.Builder().build())

        allEntries = LocationRepository.loadAllEntries(this)

        binding.btnSetArea.setOnClickListener {
            startActivity(Intent(this, AreaPreferenceActivity::class.java))
        }

        binding.btnRefresh.setOnClickListener {
            showMatchingOrders()
        }
    }

    override fun onResume() {
        super.onResume()
        updateAreaSummary()
        showMatchingOrders()
    }

    private fun updateAreaSummary() {
        val pickupCount = AreaPrefs.getPickupCities(this).size
        val dropCount = AreaPrefs.getDropCities(this).size
        binding.areaSummaryText.text = if (pickupCount == 0 || dropCount == 0)
            "Abhi koi city set nahi hai - neeche button se set karein"
        else
            "Aapka area: $pickupCount pickup city, $dropCount drop city"
    }

    /**
     * NOTE: Ye sample/demo orders hain, sirf filtering dikhane ke liye.
     * Jab aapka delivery-app backend ready ho, is function ki jagah apne
     * real order API se data laana - baaki filtering logic waisa hi chalega.
     */
    private fun generateSampleOrders(): List<Order> {
        if (allEntries.size < 2) return emptyList()
        val orders = mutableListOf<Order>()
        for (i in 1..15) {
            val pickup = allEntries.random()
            val drop = allEntries.random()
            val item = itemSamples.random()
            orders.add(
                Order(
                    id = "ORD$i",
                    pickupCity = pickup.city,
                    pickupArea = pickup.label,
                    dropCity = drop.city,
                    dropArea = drop.label,
                    itemInfo = item
                )
            )
        }
        return orders
    }

    private fun showMatchingOrders() {
        val allOrders = generateSampleOrders()
        val matching = allOrders.filter { AreaPrefs.isOrderInMyArea(this, it) }

        // City match hote hi, order khud accept ho jata hai - manually tap karne ki zaroorat nahi.
        matching.forEach { autoAcceptOrder(it) }

        val display = if (matching.isEmpty())
            listOf("Abhi koi order match nahi hua. Apna area check karein ya thoda wait karein.")
        else
            matching.map { "✅ AUTO-ACCEPTED  ${it}" }

        binding.listOrders.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, display)
    }

    /**
     * Pickup/Drop city match hote hi ye function order ko "accept" kar deta hai -
     * koi button tap nahi karna padta.
     *
     * TODO (real backend jodte waqt): yahan apne server ka "accept order" API call
     * karein, jaise:
     *   api.acceptOrder(order.id) { success -> ... }
     * Abhi demo me sirf Toast dikha rahe hain.
     */
    private fun autoAcceptOrder(order: Order) {
        Toast.makeText(this, "Order ${order.id} khud accept ho gaya", Toast.LENGTH_SHORT).show()
    }
}
