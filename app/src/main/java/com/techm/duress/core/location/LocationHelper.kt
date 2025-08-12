package com.techm.duress.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * LocationHelper – manages FusedLocationProvider updates with clean start/stop lifecycle,
 * and a helper to compute distance to a "friend" location (dummy JSON or real API).
 *
 * Primary API (object methods) and top-level wrappers (to keep your existing imports working).
 */
object LocationHelper {

    private const val TAG = "DU/LOC"

    // Defaults (tweak as needed)
    private const val DEFAULT_INTERVAL_MS = 5_000L
    private const val DEFAULT_FASTEST_MS = 3_000L
    private const val DEFAULT_PRIORITY = Priority.PRIORITY_HIGH_ACCURACY

    // Android / Google
    @Volatile private var fusedClient: FusedLocationProviderClient? = null
    @Volatile private var callback: LocationCallback? = null

    // Coroutines
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var friendJob: Job? = null

    // ---- Public API ---------------------------------------------------------

    /**
     * Start continuous location updates. Call [stopLocationUpdates] when done.
     *
     * Returns true if updates started, false if permissions are missing or provider unavailable.
     */
    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun startLocationUpdates(
        context: Context,
        intervalMs: Long = DEFAULT_INTERVAL_MS,
        fastestMs: Long = DEFAULT_FASTEST_MS,
        priority: Int = DEFAULT_PRIORITY,
        onLocationUpdate: (Location) -> Unit
    ): Boolean {
        val app = context.applicationContext

        if (!hasLocationPermission(app)) {
            Log.w(TAG, "Missing location permission (FINE or COARSE).")
            return false
        }

        val fused = LocationServices.getFusedLocationProviderClient(app)
        fusedClient = fused

        val request = LocationRequest.Builder(intervalMs)
            .setMinUpdateIntervalMillis(fastestMs)
            .setPriority(priority)
            .build()

        if (callback == null) {
            callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val loc = result.lastLocation ?: return
                    Log.d(TAG, "lat=${loc.latitude}, lon=${loc.longitude}, acc=${loc.accuracy}")
                    onLocationUpdate(loc)
                }
            }
        }

        return try {
            // Safe because we've checked permission above. Also guard SecurityException.
            fused.requestLocationUpdates(
                request,
                callback!!,
                Looper.getMainLooper()
            )
            Log.d(TAG, "Started location updates (interval=$intervalMs, fastest=$fastestMs, priority=$priority)")
            true
        } catch (se: SecurityException) {
            Log.w(TAG, "SecurityException starting updates: ${se.message}")
            false
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start location updates: ${t.message}")
            false
        }
    }

    /**
     * Stop continuous location updates. Always safe to call.
     * Accepts a Context to match existing call sites; not required internally.
     */
    fun stopLocationUpdates(@Suppress("UNUSED_PARAMETER") context: Context? = null) {
        try {
            fusedClient?.let { client ->
                callback?.let { cb ->
                    client.removeLocationUpdates(cb)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed removing location updates: ${t.message}")
        } finally {
            callback = null
            fusedClient = null
            Log.d(TAG, "Stopped location updates")
        }
    }

    /**
     * Optionally get a single "best effort" current location (or last known) once.
     * Requires one of FINE/COARSE permission.
     */
    @SuppressLint("MissingPermission")
    fun getOneShotLocation(
        context: Context,
        priority: Int = DEFAULT_PRIORITY,
        onLocation: (Location?) -> Unit
    ) {
        val app = context.applicationContext
        if (!hasLocationPermission(app)) {
            Log.w(TAG, "Missing location permission for getOneShotLocation")
            onLocation(null)
            return
        }
        val fused = LocationServices.getFusedLocationProviderClient(app)
        fused.getCurrentLocation(priority, null)
            .addOnSuccessListener { loc ->
                if (loc != null) {
                    Log.d(TAG, "getOneShotLocation: ${loc.latitude}, ${loc.longitude}")
                    onLocation(loc)
                } else {
                    fused.lastLocation
                        .addOnSuccessListener { last ->
                            Log.d(TAG, "getOneShotLocation (fallback last): $last")
                            onLocation(last)
                        }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "getOneShotLocation fallback failed: ${e.message}")
                            onLocation(null)
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "getOneShotLocation failed: ${e.message}")
                onLocation(null)
            }
    }

    /**
     * Fetch friend location (dummy or real API) and compute distance (meters) from myLocation.
     * Cancels the previous in-flight job to avoid overlap.
     */
    fun fetchFriendLocationAndUpdateDistance(
        myLocation: Location,
        onDistanceCalculated: (Float) -> Unit,
        useDummy: Boolean = true
    ) {
        friendJob?.cancel()
        friendJob = scope.launch {
            try {
                val friendLoc: Location = if (useDummy) {
                    parseFriendFromDummy()
                } else {
                    parseFriendFromApi("https://mocki.io/v1/a89b2eef-122a-402a-9b22-306bf878cd2a")
                }
                val distance = myLocation.distanceTo(friendLoc)
                withContext(Dispatchers.Main) { onDistanceCalculated(distance) }
            } catch (_: CancellationException) {
                // no-op
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch friend location: ${e.message}")
            }
        }
    }

    /** Cancel any in-flight friend distance calculation. */
    fun cancelFriendDistanceJob() {
        friendJob?.cancel()
        friendJob = null
    }

    // ---- Internals ----------------------------------------------------------

    private fun hasLocationPermission(context: Context): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun parseFriendFromDummy(): Location {
        // Dummy JSON response (Googleplex)
        val jsonResponse = """{"latitude":37.42227560826259,"longitude":-122.08551732255219}"""
        val json = JSONObject(jsonResponse)
        val friendLat = json.getDouble("latitude")
        val friendLng = json.getDouble("longitude")
        return Location("dummy").apply {
            latitude = friendLat
            longitude = friendLng
        }
    }

    @Throws(IOException::class)
    private fun parseFriendFromApi(urlStr: String): Location {
        val url = URL(urlStr)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
        }
        return try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP $code from $urlStr")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val friendLat = json.getDouble("latitude")
            val friendLng = json.getDouble("longitude")
            Location("api").apply {
                latitude = friendLat
                longitude = friendLng
            }
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }
}

/* -----------------------------------------------------------------------------
 * Top-level wrappers to preserve your existing imports:
 *   import com.techm.duress.core.location.startLocationUpdates
 *   import com.techm.duress.core.location.stopLocationUpdates
 *   import com.techm.duress.core.location.fetchFriendLocationAndUpdateDistance
 * ---------------------------------------------------------------------------*/

@RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
fun startLocationUpdates(
    context: Context,
    onLocationUpdate: (Location) -> Unit
): Boolean = LocationHelper.startLocationUpdates(
    context = context,
    onLocationUpdate = onLocationUpdate
)

fun stopLocationUpdates(context: Context) {
    LocationHelper.stopLocationUpdates(context)
}

fun fetchFriendLocationAndUpdateDistance(
    myLocation: Location,
    onDistanceCalculated: (Float) -> Unit
) = LocationHelper.fetchFriendLocationAndUpdateDistance(
    myLocation = myLocation,
    onDistanceCalculated = onDistanceCalculated,
    useDummy = true // matches your current usage
)
