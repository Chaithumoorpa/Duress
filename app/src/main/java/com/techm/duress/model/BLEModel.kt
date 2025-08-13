package com.techm.duress.model

data class BLEModel(
    val beaconId: String,
    val rssi: Int,
    val timestamp: Long
)