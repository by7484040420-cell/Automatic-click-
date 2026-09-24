package com.bipin.clicker

import android.content.Context

object AreaPrefs {

    private const val PREFS_NAME = "clicker_area_prefs"
    private const val KEY_PICKUP = "pickup_cities"
    private const val KEY_DROP = "drop_cities"

    fun savePickupCities(context: Context, cities: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_PICKUP, cities).apply()
    }

    fun saveDropCities(context: Context, cities: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_DROP, cities).apply()
    }

    fun getPickupCities(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_PICKUP, emptySet()) ?: emptySet()
    }

    fun getDropCities(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(KEY_DROP, emptySet()) ?: emptySet()
    }

    /**
     * Order sirf tab match hota hai jab uska PICKUP CITY, rider ki pickup-city list me ho
     * AUR uska DROP CITY, rider ki drop-city list me ho - chahe city ka koi bhi sector/area ho.
     * Jaise: Gurugram select kiya to Gurugram ka koi bhi sector (14, 29, 56...) match karega.
     */
    fun isOrderInMyArea(context: Context, order: Order): Boolean {
        val pickupCities = getPickupCities(context)
        val dropCities = getDropCities(context)
        if (pickupCities.isEmpty() || dropCities.isEmpty()) return false
        return pickupCities.contains(order.pickupCity) && dropCities.contains(order.dropCity)
    }
}
