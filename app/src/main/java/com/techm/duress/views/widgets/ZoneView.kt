package com.techm.duress.views.widgets

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
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
fun ZoneView(
    isDuressDetected: Boolean,
    context: Context
) {
    var zones by remember { mutableStateOf<List<ZoneModel>>(emptyList()) }

    // Load zones from assets once
    LaunchedEffect(Unit) {
        zones = try {
            withContext(Dispatchers.IO) { ZoneProvider.loadZonePositions(context) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // Cache paths so they aren't rebuilt every draw
    val zonePaths = remember(zones) {
        zones.map { zone ->
            val path = Path().apply {
                val pts = zone.points
                if (pts.isNotEmpty()) {
                    moveTo(pts.first().x, pts.first().y)
                    for (i in 1 until pts.size) {
                        lineTo(pts[i].x, pts[i].y)
                    }
                    close()
                }
            }
            zone to path
        }
    }

    // Current zone name as state
    val currentZone by ZoneDetector.currentZone

    // Reuse label paint
    val labelPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = 24f
            isAntiAlias = true
            typeface = Typeface.DEFAULT_BOLD
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        zonePaths.forEach { (zone, path) ->
            // Fill color: red highlight when duress and this is the active zone, else cyan
            val fillColor = if (isDuressDetected && zone.name == currentZone) {
                Color(0xFFB22222)
            } else {
                Color.Cyan
            }

            drawPath(
                path = path,
                color = fillColor
                // If you want translucency, uncomment:
                // alpha = if (fillColor == Color.Cyan) 0.35f else 0.55f
            )

            // Label at centroid
            val centroidX = zone.points.map { it.x }.average().toFloat()
            val centroidY = zone.points.map { it.y }.average().toFloat()
            drawContext.canvas.nativeCanvas.drawText(
                zone.name,
                centroidX,
                centroidY,
                labelPaint
            )
        }
    }
}
