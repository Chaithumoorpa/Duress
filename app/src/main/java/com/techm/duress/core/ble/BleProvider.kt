package com.techm.duress.core.ble

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException

object BleProvider {

    private const val TAG = "DU/BLE"

    // Asset + JSON keys
    private const val ASSET_FILE = "walking_ble.json"
    private const val KEY_BEACON_ID = "beaconId"
    private const val KEY_RSSI = "rssi"

    data class BleDevice(val beaconId: String, val rssi: Int)

    /**
     * Preferred API: returns List<BleDevice>.
     * Safe on Kotlin < 2.2 (no break/continue in inline lambdas).
     */
    fun loadBLE(context: Context): List<BleDevice> {
        Log.d(TAG, "loadBLE: reading $ASSET_FILE from assets")

        // 1) Read file
        val jsonText: String = try {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Asset not found: $ASSET_FILE", e)
            return emptyList()
        } catch (e: IOException) {
            Log.w(TAG, "I/O error reading asset: $ASSET_FILE", e)
            return emptyList()
        }

        // 2) Parse JSONArray
        val array: JSONArray = try {
            JSONArray(jsonText)
        } catch (e: JSONException) {
            Log.w(TAG, "Malformed JSON in $ASSET_FILE: ${e.message}")
            return emptyList()
        }

        // 3) Extract items (classic for-loop; no lambdas)
        val items = ArrayList<BleDevice>(array.length())
        for (i in 0 until array.length()) {
            val obj: JSONObject? = array.optJSONObject(i)
            if (obj == null) {
                Log.w(TAG, "Skipping entry at index $i: not a JSON object")
                continue
            }

            val beaconIdRaw = obj.optString(KEY_BEACON_ID, "").trim()
            if (beaconIdRaw.isEmpty()) {
                Log.w(TAG, "Skipping index $i: missing or blank \"$KEY_BEACON_ID\"")
                continue
            }

            // Ensure RSSI exists and is an integer
            if (!obj.has(KEY_RSSI)) {
                Log.w(TAG, "Skipping $beaconIdRaw at index $i: missing \"$KEY_RSSI\"")
                continue
            }

            // optInt returns 0 when invalid; ensure the value is actually an int
            val rssiValue: Int = try {
                obj.getInt(KEY_RSSI)
            } catch (e: JSONException) {
                Log.w(TAG, "Skipping $beaconIdRaw at index $i: \"$KEY_RSSI\" is not an integer")
                continue
            }

            // (Optional) Validate plausible RSSI range; keep if you want strictness
            // if (rssiValue !in -120..0) {
            //     Log.w(TAG, "Skipping $beaconIdRaw at index $i: RSSI out of range ($rssiValue)")
            //     continue
            // }

            items.add(BleDevice(beaconId = beaconIdRaw, rssi = rssiValue))
        }

        // 4) Return immutable list
        return items.toList()
    }

    /**
     * Backward-compat API: returns List<Pair<String, Int>>.
     * Prefer loadBLE(context): List<BleDevice> for clarity.
     */
    @Deprecated("Use loadBLE(context): List<BleDevice> for clarity")
    fun loadBLEPairs(context: Context): List<Pair<String, Int>> {
        val devices = loadBLE(context)
        // map without lambdas that need continue/break semantics
        val pairs = ArrayList<Pair<String, Int>>(devices.size)
        for (d in devices) {
            pairs.add(Pair(d.beaconId, d.rssi))
        }
        return pairs.toList()
    }
}
