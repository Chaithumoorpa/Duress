package com.techm.duress.core.location

import android.content.Context
import android.location.Location
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import android.Manifest
import android.content.pm.PackageManager
import android.os.Looper

private lateinit var locationCallback: LocationCallback
private var updateJob: Job? = null

fun startLocationUpdates(context: Context, onLocationUpdate: (Location) -> Unit) {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

    val request = LocationRequest.create().apply {
        interval = 5000
        fastestInterval = 3000
        priority = Priority.PRIORITY_HIGH_ACCURACY
    }

    locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                Log.d("MyLocation", "Latitude: ${it.latitude}, Longitude: ${it.longitude}")
                onLocationUpdate(it)
            }
        }
    }

    if (ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    ) {
        fusedLocationClient.requestLocationUpdates(
            request,
            locationCallback,
            Looper.getMainLooper()
        )
    }
}

fun fetchFriendLocationAndUpdateDistance(myLocation: Location, onDistanceCalculated: (Float) -> Unit) {
    updateJob?.cancel()
    updateJob = CoroutineScope(Dispatchers.IO).launch {

        try {
            // Dummy JSON response
            //val jsonResponse = """{"latitude":12.849128,"longitude":77.678313}""" //TechM Office
            val jsonResponse = """{"latitude":37.42227560826259,"longitude":-122.08551732255219}"""
            val json = JSONObject(jsonResponse)

            val friendLat = json.getDouble("latitude")
            val friendLng = json.getDouble("longitude")

            val friendLocation = Location("").apply {
                latitude = friendLat
                longitude = friendLng
            }

            val distance = myLocation.distanceTo(friendLocation)

            withContext(Dispatchers.Main) {
                onDistanceCalculated(distance)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        //Using Real API
//        try {
//            val url = URL("https://mocki.io/v1/a89b2eef-122a-402a-9b22-306bf878cd2a")
//            val connection = url.openConnection() as HttpURLConnection
//            connection.connectTimeout = 5000
//            connection.readTimeout = 5000
//            connection.requestMethod = "GET"
//
//            if (connection.responseCode == 200) {
//                val response = connection.inputStream.bufferedReader().readText()
//                val json = JSONObject(response)
//                val friendLat = json.getDouble("latitude")
//                val friendLng = json.getDouble("longitude")
//
//                val friendLocation = Location("").apply {
//                    latitude = friendLat
//                    longitude = friendLng
//                }
//
//                val distance = myLocation.distanceTo(friendLocation)
//
//                withContext(Dispatchers.Main) {
//                    onDistanceCalculated(distance)
//                }
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//        }
    }
}

