package com.techm.duress.core.ble

import android.content.Context
import org.json.JSONArray

object BleProvider {
    fun loadBLE(context: Context): List<Pair<String, Int>> {
        println("debug/// loadBLE")
        val list = mutableListOf<Pair<String, Int>>()
        val inputStream = context.assets.open("walking_ble.json")
        val json = inputStream.bufferedReader().use { it.readText() }
        val jsonArray = JSONArray(json)

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val beaconId = obj.getString("beaconId")
            val rssi = obj.getInt("rssi")
            list.add(Pair(beaconId, rssi))
        }
        return list
    }
}
