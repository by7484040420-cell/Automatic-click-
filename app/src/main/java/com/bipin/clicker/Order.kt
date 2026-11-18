package com.bipin.clicker

data class Order(
    val id: String,
    val pickupCity: String,
    val pickupArea: String,
    val dropCity: String,
    val dropArea: String,
    val itemInfo: String
) {
    override fun toString(): String {
        return "#$id | Pickup: $pickupArea -> Drop: $dropArea | $itemInfo"
    }
}
