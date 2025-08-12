package com.techm.duress.views.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CustomButton(
    modifier: Modifier = Modifier,
    text: String,
    backgroundColor: Color,
    onClick: (() -> Unit)? = null,
    height: Dp = 40.dp,
    enabled: Boolean,
) {
    println("debug/// CustomButton")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .border(
                width = 1.dp,
                color = Color.Black,
                shape = RoundedCornerShape(12.dp)
            )
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .let { if (onClick != null) it.clickable { onClick() } else it },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

        )
    }
}
