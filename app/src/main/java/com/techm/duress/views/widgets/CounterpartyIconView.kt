package com.techm.duress.views.widgets

import android.content.Context
import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun CounterpartyIconView(
    counterpartyName: String,
    context: Context,
    color: Color = Color(0xFF006400),  // Compose Color (dark green)
    blinkMs: Int = 700
) {
    // Keep latest name even if recomposed while the loop is running
    val nameState by rememberUpdatedState(counterpartyName)

    // Pull the specific user from the simulated “nearby users” every 2s (cancellation-safe)
    var target by remember { mutableStateOf<UserModel?>(null) }
    LaunchedEffect(nameState) {
        while (isActive) {
            val users = withContext(Dispatchers.IO) {
                NearbyUsersProvider.getNearbyUsersLocation(context)
            }
            target = users.firstOrNull { it.user.equals(nameState, ignoreCase = true) }
            delay(2000)
        }
    }

    // Resolve the marker position from the beacon MAC -> beaconKey -> static position
    var position by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(target?.beaconId) {
        val key = target?.beaconId?.let { mac -> ZoneDetector.getBeacon(mac) }
        position = key?.let { k -> ZoneProvider.getMarkerForBeaconKey(k) }
    }

    // Gentle blink
    val blink = rememberInfiniteTransition(label = "blink2").animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(blinkMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha2"
    ).value

    // Android paint for text (uses android.graphics.Color Ints — not Compose Color)
    val textPaint = remember {
        android.graphics.Paint().apply {
            this.color = android.graphics.Color.BLACK
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 24f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val center = position ?: return@Canvas

        // Circle color is a Compose Color (correct type for drawCircle)
        drawCircle(
            color = color,
            radius = 6.dp.toPx(),
            center = center,
            alpha = blink
        )

        // Label is drawn with android.graphics.Paint (expects android.graphics.Color ints)
        drawContext.canvas.nativeCanvas.drawText(
            nameState,
            center.x,
            center.y + 15.dp.toPx(),
            textPaint
        )
    }
}
