package com.techm.duress.views.widgets

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.techm.duress.core.location.fetchFriendLocationAndUpdateDistance
import com.techm.duress.core.location.startLocationUpdates
import com.techm.duress.core.location.stopLocationUpdates

@Composable
fun DistanceMeter() {
    val context = LocalContext.current

    var distance by remember { mutableStateOf(0f) }
    var locationPermissionGranted by remember { mutableStateOf(false) }

    // Request either FINE or COARSE (accept whichever is granted)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarse = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        locationPermissionGranted = fine || coarse
        if (!locationPermissionGranted) {
            Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    // One-time permission request
    LaunchedEffect(Unit) {
        val alreadyGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            locationPermissionGranted = true
        } else {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Keep latest setter for callbacks
    val distanceSetter by rememberUpdatedState { v: Float -> distance = v }

    // Start updates only when permission is granted; stop on dispose
    DisposableEffect(locationPermissionGranted) {
        if (locationPermissionGranted) {
            try {
                startLocationUpdates(context) { myLocation ->
                    fetchFriendLocationAndUpdateDistance(myLocation) { newDistance ->
                        distanceSetter(newDistance)
                    }
                }
            } catch (_: SecurityException) {
                Toast.makeText(context, "Location permission missing", Toast.LENGTH_SHORT).show()
            }
        }
        onDispose {
            if (locationPermissionGranted) {
                try { stopLocationUpdates(context) } catch (_: SecurityException) {}
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        DistanceM(distance)
    }
}

@Composable
private fun DistanceM(currentDistance: Float) {
    var previousDistance by remember { mutableStateOf(currentDistance) }
    var gettingCloser by remember { mutableStateOf(false) }

    LaunchedEffect(currentDistance) {
        // Simple trend indicator; you can replace with EMA/Kalman later
        gettingCloser = currentDistance < previousDistance
        previousDistance = currentDistance
    }

    DistanceMeterSection(distanceInMeters = currentDistance, gettingCloser = gettingCloser)
}

@Composable
private fun DistanceMeterSection(distanceInMeters: Float, gettingCloser: Boolean) {
    val maxSpacing = 100.dp
    val clamped = distanceInMeters.coerceIn(0f, 100f)
    val target = (clamped / 100f) * maxSpacing.value
    val animatedSpacing by animateDpAsState(
        targetValue = target.dp,
        animationSpec = tween(500),
        label = "distance-spacing"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        if (gettingCloser) {
            Text("You are getting closer")
        }
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Person, contentDescription = "You")
            Spacer(Modifier.width(animatedSpacing))
            Icon(Icons.Default.ArrowForward, contentDescription = "Direction")
            Spacer(Modifier.width(animatedSpacing))
            Icon(Icons.Default.Person, contentDescription = "Friend")
        }
        Text("Distance: %.2f meters".format(distanceInMeters))
    }
}
