package com.techm.duress.views.widgets

import android.graphics.Typeface
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.techm.duress.core.zone.ZoneDetector
import com.techm.duress.core.zone.ZoneProvider
import com.techm.duress.viewmodel.MainViewModel

@Composable
fun UserIconView(
    isDuressDetected: Boolean,
    giveHelpBtnPressed: Boolean,
    viewModel: MainViewModel
) {
    //  Use the *value* of the name, not toString() on the Flow
    val myName by viewModel.userName.collectAsState()

    // Current beacon key ("IN_RECEPTION" / "Unknown" …)
    val currentBeaconKey by ZoneDetector.currentBeacon

    // Keep last known position so we don't jump to (0,0) between updates
    var position by remember { mutableStateOf<Offset?>(null) }

    // Resolve position whenever beacon changes
    LaunchedEffect(currentBeaconKey) {
        ZoneProvider.zonePositions[currentBeaconKey]?.let { position = it }
    }

    // Nice little blink (flicker). Faster if duress; slower otherwise.
    val blinkSpecMs = if (isDuressDetected) 550 else 900
    val blink by rememberInfiniteTransition(label = "blink").animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(blinkSpecMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // Single reusable Paint for label
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
        val center = position ?: return@Canvas

        val dotColor =
            if (isDuressDetected && !giveHelpBtnPressed) Color.Red else Color.Blue

        drawCircle(
            color = dotColor,
            radius = 6.dp.toPx(),
            center = center,
            alpha = blink //  flicker
        )

        // Label (real user name)
        drawContext.canvas.nativeCanvas.drawText(
            if (myName.isNotBlank()) myName else "Me",
            center.x,
            center.y + 15.dp.toPx(),
            textPaint
        )
    }
}
