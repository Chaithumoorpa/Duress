package com.techm.duress.views.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun SpeakView(
    modifier: Modifier = Modifier,
    name: String?,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    CustomButton(
        modifier = modifier,
        text = "Speak To ${name ?: "Requester"}",
        backgroundColor = Color(0xFF00BFFF),
        enabled = enabled,
        onClick = onClick
    )
}

@Composable
fun CallView(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    CustomButton(
        modifier = modifier,
        text = "Call 911",
        backgroundColor = Color(0xFFB22222),
        enabled = enabled,
        onClick = onClick
    )
}
