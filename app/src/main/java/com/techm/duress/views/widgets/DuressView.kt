package com.techm.duress.views.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun DuressView(
    isDuressDetected: Boolean,
    giveHelpBtnPressed: Boolean,
    isHelpSessionActive: Boolean, // synced with Go backend: status == "open"
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonText = when {
        !isDuressDetected -> "Duress" // Ready to trigger
        isHelpSessionActive && !giveHelpBtnPressed -> "Cancel Request" // Help open but not accepted
        isHelpSessionActive && giveHelpBtnPressed -> "Finish" // Helper accepted
        else -> "Duress" // fallback
    }

    val buttonColor = when {
        !isDuressDetected -> Color(0xFFB22222) // Firebrick red
        isHelpSessionActive && giveHelpBtnPressed -> Color(0xFF228B22) // Forest green
        else -> Color(0xFFFFA500) // Orange for pending
    }

    CustomButton(
        onClick = onClick,
        backgroundColor = buttonColor,
        text = buttonText,
        modifier = modifier
    )
}
