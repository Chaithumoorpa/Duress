package com.techm.duress.views.widgets

import android.Manifest
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.techm.duress.core.location.fetchFriendLocationAndUpdateDistance
import com.techm.duress.core.location.startLocationUpdates

@Composable
fun DistanceMeter(context: Context) {
    var distance by remember { mutableStateOf(0f) }
    var locationPermissionGranted by remember { mutableStateOf(false) }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            locationPermissionGranted = granted
            if (!granted) {
                Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    if (locationPermissionGranted) {
        startLocationUpdates(context) { myLocation ->
            fetchFriendLocationAndUpdateDistance(myLocation) { newDistance ->
                distance = newDistance
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        DistanceM(distance)
    }
}


@Composable
fun DistanceM(currentDistance: Float) {
    var previousDistance by remember { mutableStateOf(currentDistance) }
    var gettingCloser by remember { mutableStateOf(false) }

    LaunchedEffect(currentDistance) {
        println("Current: $currentDistance, Previous: $previousDistance")
        gettingCloser = currentDistance < previousDistance
        previousDistance = currentDistance
    }

    DistanceMeterSection(distanceInMeters = currentDistance, gettingCloser = gettingCloser)
}

@Composable
fun DistanceMeterSection(distanceInMeters: Float, gettingCloser: Boolean) {
    val maxSpacing = 100.dp
    val spacing = ((distanceInMeters.coerceIn(0f, 100f)) / 100f) * maxSpacing.value
    val animatedSpacing by animateDpAsState(targetValue = spacing.dp, animationSpec = tween(500))


    Column(
        modifier = Modifier.fillMaxSize(),
//        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement =Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        if (gettingCloser) {
            Text("You are getting closer")
        }
        Row(horizontalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Person, contentDescription = "You")
            Spacer(modifier = Modifier.width(animatedSpacing))
            Icon(Icons.Default.ArrowForward, contentDescription = "Direction")
            Spacer(modifier = Modifier.width(animatedSpacing))
            Icon(Icons.Default.Person, contentDescription = "Friend")
        }
        Text("Distance: %.2f meters".format(distanceInMeters))
    }
}
