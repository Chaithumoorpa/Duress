package com.techm.duress.core.zone

import android.content.Context
import androidx.compose.ui.geometry.Offset
import com.techm.duress.models.ZoneModel
import org.json.JSONArray



object ZoneProvider {
    fun loadZonePositions(context: Context): List<ZoneModel> {
        val zones = mutableListOf<ZoneModel>()
        val inputStream =  context.assets.open("zones_position.json")
        val json = inputStream.bufferedReader().use { it.readText() }
        val jsonArray = JSONArray(json)

        for (i in 0 until jsonArray.length()) {
            val zone = jsonArray.getJSONObject(i)
            val pointsArray = zone.getJSONArray("points")
            val points = mutableListOf<Offset>()

            for (j in 0 until pointsArray.length()) {
                val point = pointsArray.getJSONObject(j)
                points.add(Offset(point.getDouble("x").toFloat(), point.getDouble("y").toFloat()))
            }
            zones.add(ZoneModel(points, zone.getString("id")))
        }
        return zones
    }

    val zonePositions = mapOf(
        "IN_RECEPTION" to Offset(250f, 550f),
        "OUT_RECEPTION" to Offset(400f, 520f),
        "IN_OT" to Offset(550f, 150f),
        "OUT_OT" to Offset(480f, 270f),
        "IN_CORRIDOR" to Offset(680f, 500f),
        "OUT_CORRIDOR" to Offset(600f, 500f)
    )
}
