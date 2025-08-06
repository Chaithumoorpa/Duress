package com.techm.duress.views.widgets

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.techm.duress.core.nearbyUsers.NearbyUsersProvider
import com.techm.duress.core.zone.ZoneDetector
import com.techm.duress.core.zone.ZoneProvider
import com.techm.duress.models.UserModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext


@Composable
fun NearbyUsersIconView(isDuressDetected: Boolean, context: Context) {
    val nearbyUsers = remember { mutableStateOf<List<UserModel>>(emptyList()) }


    LaunchedEffect(isDuressDetected) {
        while (isDuressDetected) {
            withContext(Dispatchers.Default) {
                nearbyUsers.value = NearbyUsersProvider.getNearbyUsersLocation(context)
                delay(2000)
            }
        }
    }


    nearbyUsers.value.forEach { it ->
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = ZoneProvider.zonePositions[ZoneDetector.getBeacon(it.beaconId)]?.let {
                Offset(it.x + 50f, it.y + 20f)
            }
                ?: Offset(0f, 0f)
            drawCircle(
                color = Color(0xFF006400),
                radius = 6.dp.toPx(),
                center = center
            )
            drawContext.canvas.nativeCanvas.drawText(
                it.user,
                center.x,
                center.y + 15.dp.toPx(),
                android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textAlign = android.graphics.Paint.Align.CENTER
                    textSize = 24f
                    isAntiAlias = true
                    typeface = Typeface.DEFAULT_BOLD
                }
            )
        }
    }
}
