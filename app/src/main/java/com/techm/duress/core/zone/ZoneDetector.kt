package com.techm.duress.core.zone

import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.techm.duress.model.BLEModel
import kotlin.math.abs

/**
 * ZoneDetector
 *
 * Heuristic:
 *  - Each room has two beacons: IN_* and OUT_*
 *  - We keep a sliding window of recent sightings (RSSI + timestamp)
 *  - If both IN and OUT are seen within [DETECTION_WINDOW_MS], decide:
 *      * Entered:  IN is newer than OUT  AND (IN.rssi - OUT.rssi) >= MIN_RSSI_DIFF_DB
 *      * Exited:   OUT is newer than IN  AND (OUT.rssi - IN.rssi) >= MIN_RSSI_DIFF_DB
 *  - Hysteresis: once a zone is set, we won’t change it again within [ZONE_HYSTERESIS_MS]
 *
 * NOTE: Call from a single dispatcher (e.g., main). Not thread-safe.
 */
object ZoneDetector {

    private const val TAG = "DU/ZONES"

    // --- Tunables ---
    private const val DETECTION_WINDOW_MS = 8_000L        // consider both IN/OUT within 8s
    private const val MIN_RSSI_DIFF_DB = 6                // minimum dB difference to decide
    private const val ZONE_HYSTERESIS_MS = 3_000L         // avoid flapping

    // Beacon map (key → MAC). Keys must be "IN_ROOM" / "OUT_ROOM"
    private val beaconMap: Map<String, String> = mapOf(
        "IN_RECEPTION" to "AA:BB:CC:DD:EE:01",
        "OUT_RECEPTION" to "AA:BB:CC:DD:EE:02",
        "IN_OT" to "AA:BB:CC:DD:EE:03",
        "OUT_OT" to "AA:BB:CC:DD:EE:04",
        "IN_CORRIDOR" to "AA:BB:CC:DD:EE:05",
        "OUT_CORRIDOR" to "AA:BB:CC:DD:EE:06"
    )

    // Reverse lookup: MAC → key ("IN_ROOM"/"OUT_ROOM")
    private val macToKey: Map<String, String> = run {
        val m = HashMap<String, String>(beaconMap.size)
        for (e in beaconMap.entries) m[e.value] = e.key
        m
    }

    // Keep most recent sightings by MAC
    private val recentBeacons = HashMap<String, BLEModel>(beaconMap.size * 2)

    // Compose-facing state
    private val _currentBeaconKey = mutableStateOf("Unknown") // e.g., "IN_RECEPTION"
    val currentBeacon: State<String> get() = _currentBeaconKey

    private val _currentZone = mutableStateOf("Unknown")      // e.g., "RECEPTION"
    val currentZone: State<String> get() = _currentZone

    // Back-compat: raw String getters for non-Compose call sites
    val currentZoneName: String get() = _currentZone.value
    val currentBeaconKeyName: String get() = _currentBeaconKey.value

    private var lastZoneChangeAt = 0L

    /**
     * Feed a new BLE sighting.
     * @param beaconId MAC address of the beacon
     * @param rssi RSSI in dBm (negative; stronger signals are closer to 0)
     */
    fun onBeaconDetected(beaconId: String, rssi: Int) {
        val now = System.currentTimeMillis()

        // 1) track recent beacon
        recentBeacons[beaconId] = BLEModel(beaconId, rssi, now)

        // 2) evict stale (no inline-lambda continue; classic iterator)
        val it = recentBeacons.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.timestamp > DETECTION_WINDOW_MS) {
                it.remove()
            }
        }

        // 3) resolve key for this MAC, update currentBeacon state
        val thisKey = macToKey[beaconId]
        if (thisKey != null && _currentBeaconKey.value != thisKey) {
            _currentBeaconKey.value = thisKey
        }

        // 4) Try to infer zone only if this beacon is mapped
        if (thisKey != null) {
            val parts = thisKey.split('_', limit = 2)
            if (parts.size == 2) {
                val room = parts[1]    // "RECEPTION", "OT", "CORRIDOR"
                inferZoneForRoom(room, now)
            }
        }
    }

    /** Get key by MAC, e.g., "IN_RECEPTION". */
    fun getBeaconKeyForMac(mac: String): String? = macToKey[mac]

    /** Back-compat alias for older call sites (e.g., NearbyUsersIconView). */
    fun getBeacon(beaconId: String): String? = getBeaconKeyForMac(beaconId)

    /** Clear all internal state (useful for tests or session reset). */
    fun reset() {
        recentBeacons.clear()
        _currentBeaconKey.value = "Unknown"
        _currentZone.value = "Unknown"
        lastZoneChangeAt = 0L
    }

    // --- Internals ---

    private fun inferZoneForRoom(room: String, now: Long) {
        // Hysteresis: avoid rapid toggling
        if (now - lastZoneChangeAt < ZONE_HYSTERESIS_MS) return

        val inMac = beaconMap["IN_$room"] ?: return
        val outMac = beaconMap["OUT_$room"] ?: return

        val inHit = recentBeacons[inMac]
        val outHit = recentBeacons[outMac]

        if (inHit == null && outHit == null) return

        // Seen only IN? Bias towards entry if RSSI is reasonably strong
        if (inHit != null && outHit == null) {
            maybeEnter(room, inHit, now)
            return
        }

        // Seen only OUT? Bias towards exit (unknown) if RSSI is strong at OUT
        if (outHit != null && inHit == null) {
            maybeExit(room, outHit, now)
            return
        }

        // Both seen: decide by time + RSSI difference
        if (inHit != null && outHit != null) {
            val dt = inHit.timestamp - outHit.timestamp // >0 → IN newer; <0 → OUT newer
            val rssiDiff = inHit.rssi - outHit.rssi     // RSSI is negative; more positive is stronger

            if (abs(dt) <= DETECTION_WINDOW_MS) {
                if (dt >= 0 && rssiDiff >= MIN_RSSI_DIFF_DB) {
                    // Entered room
                    setZone(room, now, "ENTER dt=$dt rssiΔ=$rssiDiff")
                } else if (dt < 0 && -rssiDiff >= MIN_RSSI_DIFF_DB) {
                    // Exited room (OUT newer and stronger)
                    clearZone(now, "EXIT dt=$dt rssiΔ=$rssiDiff")
                } else {
                    Log.d(TAG, "Inconclusive for $room (dt=$dt, rssiΔ=$rssiDiff)")
                }
            }
        }
    }

    private fun maybeEnter(room: String, inHit: BLEModel, now: Long) {
        // Heuristic: strong IN (>-60dBm) ⇒ likely inside
        if (inHit.rssi >= -60) {
            setZone(room, now, "ENTER(single IN rssi=${inHit.rssi})")
        }
    }

    private fun maybeExit(room: String, outHit: BLEModel, now: Long) {
        // Heuristic: strong OUT (>-60dBm) ⇒ likely outside
        if (outHit.rssi >= -60) {
            clearZone(now, "EXIT(single OUT rssi=${outHit.rssi})")
        }
    }

    private fun setZone(room: String, now: Long, reason: String) {
        if (_currentZone.value != room) {
            _currentZone.value = room
            lastZoneChangeAt = now
            Log.d(TAG, "Zone → $room ($reason)")
        }
    }

    private fun clearZone(now: Long, reason: String) {
        if (_currentZone.value != "Unknown") {
            _currentZone.value = "Unknown"
            lastZoneChangeAt = now
            Log.d(TAG, "Zone → Unknown ($reason)")
        }
    }
}
