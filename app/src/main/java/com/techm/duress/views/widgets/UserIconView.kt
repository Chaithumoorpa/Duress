package com.techm.duress.views.widgets

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.techm.duress.core.zone.ZoneDetector
import com.techm.duress.core.zone.ZoneProvider
import com.techm.duress.viewmodel.MainViewModel


@Composable
fun UserIconView(isDuressDetected: Boolean,giveHelpBtnPressed: Boolean,viewModel:MainViewModel) {
    val position = remember { mutableStateOf(Offset(0f, 0f)) }


    val currentBeacon by ZoneDetector.currentBeacon
    LaunchedEffect(currentBeacon) {
        ZoneProvider.zonePositions[currentBeacon]?.let {
            position.value = it
        }
    }


    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            color = if (isDuressDetected && !giveHelpBtnPressed) Color.Red else Color.Blue,
            radius = 6.dp.toPx(),
            center = position.value
        )
        drawContext.canvas.nativeCanvas.drawText(
            viewModel.userName,
            position.value.x,
            position.value.y + 15.dp.toPx(),
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
