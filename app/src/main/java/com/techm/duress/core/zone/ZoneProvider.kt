package com.techm.duress.core.zone

import android.content.Context
import android.util.Log
import androidx.compose.ui.geometry.Offset
import com.techm.duress.models.ZoneModel
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.FileNotFoundException
import java.io.IOException

object ZoneProvider {

    private const val TAG = "DU/ZONES"
    private const val ASSET_FILE = "zones_position.json"
    private const val KEY_ID = "id"          // JSON uses "id"; we map it to ZoneModel.name
    private const val KEY_POINTS = "points"
    private const val KEY_X = "x"
    private const val KEY_Y = "y"

    /**
     * Load polygon zones from assets/zones_position.json.
     * JSON schema (your sample):
     * [
     *   {"id":"RECEPTION","points":[{"x":105,"y":645}, ...]},
     *   ...
     * ]
     * Mapped to ZoneModel(points=..., name=<id>)
     */
    fun loadZonePositions(context: Context): List<ZoneModel> {
        Log.d(TAG, "Reading $ASSET_FILE from assets")

        val jsonText = try {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Asset not found: $ASSET_FILE", e); return emptyList()
        } catch (e: IOException) {
            Log.w(TAG, "I/O error reading asset: $ASSET_FILE", e); return emptyList()
        }

        val array: JSONArray = try {
            JSONArray(jsonText)
        } catch (e: JSONException) {
            Log.w(TAG, "Malformed JSON in $ASSET_FILE: ${e.message}"); return emptyList()
        }

        val zones = ArrayList<ZoneModel>(array.length())
        for (i in 0 until array.length()) {
            val zoneObj: JSONObject? = array.optJSONObject(i)
            if (zoneObj == null) { Log.w(TAG, "Skipping non-object at [$i]"); continue }

            val idFromJson = zoneObj.optString(KEY_ID, "").trim()
            if (idFromJson.isEmpty()) { Log.w(TAG, "Missing \"$KEY_ID\" at [$i]"); continue }

            val ptsArray = zoneObj.optJSONArray(KEY_POINTS)
            if (ptsArray == null) { Log.w(TAG, "Missing \"$KEY_POINTS\" for id=$idFromJson"); continue }

            val points = ArrayList<Offset>(ptsArray.length())
            var valid = true
            for (j in 0 until ptsArray.length()) {
                val p = ptsArray.optJSONObject(j)
                if (p == null) { Log.w(TAG, "Point not an object at [$i][$j] (id=$idFromJson)"); valid = false; break }
                if (!p.has(KEY_X) || !p.has(KEY_Y)) { Log.w(TAG, "Point missing x/y at [$i][$j] (id=$idFromJson)"); valid = false; break }
                try {
                    val x = p.getDouble(KEY_X).toFloat()
                    val y = p.getDouble(KEY_Y).toFloat()
                    points.add(Offset(x, y))
                } catch (_: JSONException) {
                    Log.w(TAG, "Invalid x/y number at [$i][$j] (id=$idFromJson)"); valid = false; break
                }
            }
            if (!valid || points.isEmpty()) { Log.w(TAG, "Skipping id=$idFromJson due to invalid/empty points"); continue }

            // Map JSON "id" -> ZoneModel.name
            zones.add(ZoneModel(points = points, name = idFromJson))
        }

        return zones.toList()
    }

    /** Convenience map for quick lookup by ZoneModel.name (from JSON "id"). */
    fun loadZonePositionsMap(context: Context): Map<String, ZoneModel> {
        val list = loadZonePositions(context)
        val map = HashMap<String, ZoneModel>(list.size)
        for (z in list) map[z.name] = z
        return map.toMap()
    }

    /** Static marker positions for beacon keys (e.g., "IN_RECEPTION"). */
    val zonePositions: Map<String, Offset> = mapOf(
        "IN_RECEPTION" to Offset(250f, 550f),
        "OUT_RECEPTION" to Offset(400f, 520f),
        "IN_OT" to Offset(550f, 150f),
        "OUT_OT" to Offset(480f, 270f),
        "IN_CORRIDOR" to Offset(680f, 500f),
        "OUT_CORRIDOR" to Offset(600f, 500f)
    )

    fun getMarkerForBeaconKey(key: String): Offset? = zonePositions[key]
}
