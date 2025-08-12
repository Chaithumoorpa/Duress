package com.techm.duress.views.widgets

import android.graphics.Typeface
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
    // currentBeacon is a Compose State<String> like "IN_RECEPTION" / "Unknown"
    val currentBeaconKey by ZoneDetector.currentBeacon

    // Remember current position; keep last known if beacon doesn’t map
    var position by remember { mutableStateOf(Offset.Zero) }

    // Resolve position when beacon key changes
    LaunchedEffect(currentBeaconKey) {
        val pos = ZoneProvider.zonePositions[currentBeaconKey]
        if (pos != null) {
            position = pos
        }
        // If null, keep previous position (avoids snapping to 0,0)
    }

    // Reuse a single Paint for the label
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
        val dotColor = if (isDuressDetected && !giveHelpBtnPressed) Color.Red else Color.Blue

        drawCircle(
            color = dotColor,
            radius = 6.dp.toPx(),
            center = position
        )

        // Draw the user's name under the dot
        drawContext.canvas.nativeCanvas.drawText(
            viewModel.userName.toString(),
            position.x,
            position.y + 15.dp.toPx(),
            textPaint
        )
    }
}
