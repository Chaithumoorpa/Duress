package com.techm.duress.views.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ReusableBoxWithHeader(
    showHeader: Boolean = true,
    title: String = "",
    height: Dp? = null,
    content: @Composable () -> Unit
) {
    Column {
        if (showHeader) Box(
            modifier = Modifier
                .width(380.dp)
                .background(Color.Black)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                color = Color.White
            )
        }
        Box(
            modifier = Modifier
                .then(if (height != null) Modifier.height(height) else Modifier.wrapContentHeight())
                .width(380.dp)
                .border(1.dp, Color.Black)
                .clipToBounds()
                .padding(4.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            content()
        }
    }
}
