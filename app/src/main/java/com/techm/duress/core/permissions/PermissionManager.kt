package com.techm.duress.core.permissions

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.CheckResult
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * PermissionManager — utility for checking/requesting runtime permissions.
 *
 * Covers:
 *  - Camera / Microphone
 *  - Fine/Coarse Location (either is OK)
 *  - Background Location (optional)
 *  - Notifications (Android 13+)
 *  - Sensors for fall detection (BODY_SENSORS)
 *  - BLE (Android 12+): BLUETOOTH_SCAN / CONNECT / ADVERTISE (optional)
 *
 * Also provides:
 *  - Group checks/requests
 *  - Rationale helpers
 *  - Result parsing
 *  - App settings deep link
 */
object PermissionManager {

    // ---- Request codes (if using ActivityCompat.requestPermissions) ----
    const val REQ_CAMERA            = 1001
    const val REQ_AUDIO             = 1002
    const val REQ_LOCATION          = 1003
    const val REQ_BACKGROUND_LOC    = 1004
    const val REQ_NOTIFICATIONS     = 1005
    const val REQ_SENSORS           = 1006
    const val REQ_BLE               = 1007
    const val REQ_STREAMING_BUNDLE  = 1010
    const val REQ_FULL_APP_BUNDLE   = 1011

    // ---- Canonical permission sets ----

    /** Core for streaming audio/video + location. */
    fun streamingRequiredPermissions(context: Context): Array<String> {
        val list = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            // Request both; Android grants whichever is appropriate
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.POST_NOTIFICATIONS
        }
        return list.toTypedArray()
    }

    /** Sensors for fall detection (optional but used in this app). */
    fun sensorPermissions(): Array<String> =
        arrayOf(Manifest.permission.BODY_SENSORS)

    /** Background location (separate step per Play policy). */
    fun backgroundLocationPermission(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else emptyArray()

    /** BLE (only needed if you actually scan/connect at runtime; Android 12+). */
    fun blePermissions(): Array<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyArray()
        val list = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        // Add ADVERTISE only if you advertise beacons
        // list += Manifest.permission.BLUETOOTH_ADVERTISE
        return list.toTypedArray()
    }

    /** Convenience: everything your app typically needs. */
    fun fullAppPermissionBundle(context: Context): Array<String> =
        (streamingRequiredPermissions(context) +
                sensorPermissions() +
                blePermissions()).distinct().toTypedArray()

    // ---- Single checks ----
    @CheckResult
    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @CheckResult fun hasCameraPermission(context: Context) = hasPermission(context, Manifest.permission.CAMERA)
    @CheckResult fun hasAudioPermission(context: Context) = hasPermission(context, Manifest.permission.RECORD_AUDIO)

    @CheckResult
    fun hasAnyLocationPermission(context: Context): Boolean =
        hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)

    @CheckResult
    fun hasBackgroundLocation(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else true

    @CheckResult
    fun hasPostNotifications(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        else true

    @CheckResult
    fun hasBodySensors(context: Context): Boolean =
        hasPermission(context, Manifest.permission.BODY_SENSORS)

    @CheckResult
    fun hasBleScanConnect(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            hasPermission(context, Manifest.permission.BLUETOOTH_SCAN) &&
                    hasPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
        else true

    // ---- Group checks ----
    @CheckResult
    fun hasAll(context: Context, permissions: Array<String>): Boolean =
        permissions.all { hasPermission(context, it) }

    // ---- Requests (ActivityCompat path). Prefer ActivityResultContracts in Compose/Fragments. ----
    fun requestPermissions(activity: Activity, permissions: Array<String>, requestCode: Int) {
        if (permissions.isEmpty()) return
        ActivityCompat.requestPermissions(activity, permissions, requestCode)
    }

    fun requestCamera(activity: Activity) =
        requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)

    fun requestAudio(activity: Activity) =
        requestPermissions(activity, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_AUDIO)

    fun requestLocation(activity: Activity) =
        requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            REQ_LOCATION
        )

    fun requestBackgroundLocation(activity: Activity) {
        val perms = backgroundLocationPermission()
        if (perms.isNotEmpty()) requestPermissions(activity, perms, REQ_BACKGROUND_LOC)
    }

    fun requestNotifications(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
        }
    }

    fun requestSensors(activity: Activity) =
        requestPermissions(activity, sensorPermissions(), REQ_SENSORS)

    fun requestBle(activity: Activity) =
        requestPermissions(activity, blePermissions(), REQ_BLE)

    fun requestStreamingBundle(activity: Activity) =
        requestPermissions(activity, streamingRequiredPermissions(activity), REQ_STREAMING_BUNDLE)

    fun requestFullAppBundle(activity: Activity) =
        requestPermissions(activity, fullAppPermissionBundle(activity), REQ_FULL_APP_BUNDLE)

    // ---- Rationale helpers ----
    fun shouldShowRationale(activity: Activity, permission: String): Boolean =
        ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)

    fun shouldShowAnyRationale(activity: Activity, permissions: Array<String>): Boolean =
        permissions.any { shouldShowRationale(activity, it) }

    // ---- Handle results (legacy onRequestPermissionsResult path) ----
    data class Result(val granted: Set<String>, val denied: Set<String>) {
        fun allGranted() = denied.isEmpty()
        fun isGranted(permission: String) = permission in granted
    }

    fun parseResult(permissions: Array<out String>, grantResults: IntArray): Result {
        val granted = mutableSetOf<String>()
        val denied = mutableSetOf<String>()
        permissions.forEachIndexed { i, p ->
            if (i < grantResults.size && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                granted += p
            } else {
                denied += p
            }
        }
        return Result(granted, denied)
    }

    // ---- App settings deep link ----
    fun openAppSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }
}
