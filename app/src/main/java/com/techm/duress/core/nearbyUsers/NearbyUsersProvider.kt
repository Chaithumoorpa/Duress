package com.techm.duress.core.nearbyUsers


import android.content.Context
import com.techm.duress.models.UserModel
import org.json.JSONArray

object NearbyUsersProvider {
    fun getNearbyUsersLocation(context: Context): List<UserModel> {
        val list = mutableListOf<UserModel>()
        val inputStream = context.assets.open("nearby_users.json")
        val json = inputStream.bufferedReader().use { it.readText() }
        val jsonArray = JSONArray(json)

        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            val user = obj.getString("user")
            val beaconId = obj.getString("beaconId")
            val rssi = obj.getInt("rssi")
            list.add(UserModel(user = user, beaconId = beaconId, rssi = rssi))
        }
        return list
    }
}