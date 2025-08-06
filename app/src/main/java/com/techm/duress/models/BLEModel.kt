package com.techm.duress.models

data class BLEModel(
    val beaconId: String,
    val rssi: Int,
    val timestamp: Long
)