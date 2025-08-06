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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.nativeCanvas
import com.techm.duress.core.zone.ZoneDetector
import com.techm.duress.core.zone.ZoneProvider
import com.techm.duress.models.ZoneModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ZoneView(isDuressDetected: Boolean,context:Context) {
    println("debug/// ZoneView")
    val zones = remember { mutableStateOf<List<ZoneModel>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            zones.value = ZoneProvider.loadZonePositions(context)
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        zones.value.forEach { zone ->
            val path = Path().apply {
                zone.points.firstOrNull()?.let {
                    moveTo(it.x, it.y)
                }
                zone.points.drop(1).forEach {
                    lineTo(it.x, it.y)
                }
                close()
            }

            drawPath(
                path = path,
                color = if (isDuressDetected && zone.name == ZoneDetector.currentZone) Color(
                    0xFFB22222
                ) else Color.Cyan,
//                    alpha = 0.4f
            )

            val centroidX = zone.points.map { it.x }.average().toFloat()
            val centroidY = zone.points.map { it.y }.average().toFloat()

            drawContext.canvas.nativeCanvas.drawText(
                zone.name,
                centroidX,
                centroidY,
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
