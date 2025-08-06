package com.techm.duress.core.zone

import com.techm.duress.models.BLEModel

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

object ZoneDetector {
    private val beaconMap = mapOf(
        "IN_RECEPTION" to "AA:BB:CC:DD:EE:01",
        "OUT_RECEPTION" to "AA:BB:CC:DD:EE:02",
        "IN_OT" to "AA:BB:CC:DD:EE:03",
        "OUT_OT" to "AA:BB:CC:DD:EE:04",
        "IN_CORRIDOR" to "AA:BB:CC:DD:EE:05",
        "OUT_CORRIDOR" to "AA:BB:CC:DD:EE:06"
    )

    private val recentBeacons = mutableMapOf<String, BLEModel>()
    private const val DETECTION_WINDOW = 8000

    private val _currentBeacon = mutableStateOf("Unknown")
    val currentBeacon: State<String> get() = _currentBeacon
    var currentZone: String = "Unknown"


    fun onBeaconDetected(beaconId: String, rssi: Int) {
        val now = System.currentTimeMillis()
        recentBeacons[beaconId] = BLEModel(beaconId, rssi, now)
        recentBeacons.entries.removeIf { now - it.value.timestamp > DETECTION_WINDOW }


        beaconMap.entries.find { it.value == beaconId }?.key?.let { beaconKey ->
            if (_currentBeacon.value != beaconKey) {
                _currentBeacon.value = beaconKey
            }
            currentZone = if (beaconKey.contains("IN")) {
                beaconKey.split("_").last()
            } else {
                "UNKNOWN"
            }
        }



//        for (room in rooms) {
//            val inBeacon = recentBeacons[beaconMap["IN_$room"]]
//            val outBeacon = recentBeacons[beaconMap["OUT_$room"]]
//            onZoneChanged(beaconId)
//            if (inBeacon != null && outBeacon != null) {
////                _currentZone = room
//                val timeDiff = inBeacon.timestamp - outBeacon.timestamp
//                val rssiDiff = inBeacon.rssi - outBeacon.rssi
//
//                if (timeDiff in 0..detectionWindow && rssiDiff > 5) {
//                    if (_currentZone != room) {
//                        _currentZone = room
////                        onZoneChanged(currentZone)
//                    }
//                } else if (timeDiff in 0..detectionWindow && rssiDiff < -5) {
//                    if (_currentZone == room) {
//                        _currentZone = "Unknown"
////                        onZoneChanged(currentZone)
//                    }
//                }
//            }
//        }
    }

    fun getBeacon(beaconId: String): String? {
        return beaconMap.entries.find { it.value == beaconId }?.key
    }
}
