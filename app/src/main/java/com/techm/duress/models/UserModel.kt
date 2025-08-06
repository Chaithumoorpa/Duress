package com.techm.duress.models

data class UserModel(
    val user: String,
    val beaconId: String,
    val rssi: Int
)