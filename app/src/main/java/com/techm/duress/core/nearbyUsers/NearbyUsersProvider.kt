package com.techm.duress.core.nearbyUsers

import android.content.Context
import android.util.Log
import com.techm.duress.model.UserModel
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Loads nearby users from assets/nearby_users.json.
 * Returns an immutable List<UserModel>; logs and returns empty list on failure.
 */
object NearbyUsersProvider {

    private const val TAG = "DU/NEARBY"

    // Asset + JSON keys
    private const val ASSET_FILE = "nearby_users.json"
    private const val KEY_USER = "user"
    private const val KEY_BEACON_ID = "beaconId"
    private const val KEY_RSSI = "rssi"

    fun getNearbyUsersLocation(context: Context): List<UserModel> {
        Log.d(TAG, "Reading $ASSET_FILE from assets")

        // 1) Read file
        val jsonText = try {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Asset not found: $ASSET_FILE", e)
            return emptyList()
        } catch (e: IOException) {
            Log.w(TAG, "I/O error reading asset: $ASSET_FILE", e)
            return emptyList()
        }

        // 2) Parse array
        val array: JSONArray = try {
            JSONArray(jsonText)
        } catch (e: JSONException) {
            Log.w(TAG, "Malformed JSON in $ASSET_FILE: ${e.message}")
            return emptyList()
        }

        // 3) Extract items (classic for-loop; no inline-lambda continue/break)
        val items = ArrayList<UserModel>(array.length())
        for (i in 0 until array.length()) {
            val obj: JSONObject? = array.optJSONObject(i)
            if (obj == null) {
                Log.w(TAG, "Skipping non-object at index $i")
                continue
            }

            val user = obj.optString(KEY_USER, "").trim()
            if (user.isEmpty()) {
                Log.w(TAG, "Missing/blank \"$KEY_USER\" at index $i; skipping")
                continue
            }

            val beaconId = obj.optString(KEY_BEACON_ID, "").trim()
            if (beaconId.isEmpty()) {
                Log.w(TAG, "Missing/blank \"$KEY_BEACON_ID\" for user=$user; skipping")
                continue
            }

            if (!obj.has(KEY_RSSI)) {
                Log.w(TAG, "Missing \"$KEY_RSSI\" for user=$user; skipping")
                continue
            }

            val rssi: Int = try {
                obj.getInt(KEY_RSSI)
            } catch (e: JSONException) {
                Log.w(TAG, "Non-integer \"$KEY_RSSI\" for user=$user; skipping")
                continue
            }

            // (Optional) Validate plausible RSSI range if desired
            // if (rssi !in -120..0) { Log.w(TAG, "RSSI out of range for user=$user ($rssi)"); continue }

            items.add(UserModel(user = user, beaconId = beaconId, rssi = rssi))
        }

        return items.toList()
    }
}
