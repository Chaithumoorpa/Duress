package com.techm.duress.views.widgets

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.techm.duress.core.nearbyUsers.NearbyUsersProvider
import com.techm.duress.core.zone.ZoneDetector
import com.techm.duress.core.zone.ZoneProvider
import com.techm.duress.model.UserModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun NearbyUsersIconView(
    isDuressDetected: Boolean,
    context: Context
) {
    var nearbyUsers by remember { mutableStateOf<List<UserModel>>(emptyList()) }

    // Poll nearby users only while duress is active
    LaunchedEffect(isDuressDetected) {
        if (!isDuressDetected) {
            nearbyUsers = emptyList()
            return@LaunchedEffect
        }
        while (isDuressDetected) {
            val users = withContext(Dispatchers.IO) {
                // Load from assets (provider is IO-bound)
                NearbyUsersProvider.getNearbyUsersLocation(context)
            }
            nearbyUsers = users
            delay(2000)
        }
    }

    // Reuse a single Paint instance for labels
    val textPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 24f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        // Draw each user if we can resolve their beacon → zone position
        nearbyUsers.forEach { user ->
            val beaconKey = ZoneDetector.getBeacon(user.beaconId)
            val zonePos = beaconKey?.let { ZoneProvider.zonePositions[it] } ?: return@forEach

            val center = Offset(zonePos.x + 50f, zonePos.y + 20f)

            // Dot
            drawCircle(
                color = Color(0xFF006400),
                radius = 6.dp.toPx(),
                center = center
            )

            // Label
            drawContext.canvas.nativeCanvas.drawText(
                user.user,
                center.x,
                center.y + 15.dp.toPx(),
                textPaint
            )
        }
    }
}
