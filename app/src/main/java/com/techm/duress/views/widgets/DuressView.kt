package com.techm.duress.views.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Duress button with dynamic label/color based on session state.
 *
 * @param isDuressDetected  true after user triggers duress locally
 * @param giveHelpBtnPressed true if the helper has accepted and is actively helping
 * @param isHelpSessionActive true when backend session status == "open"
 * @param onClick action to perform on press (toggle/cancel/finish)
 * @param modifier Compose modifier
 * @param enabled allow caller to disable the button (default true)
 */
@Composable
fun DuressView(
    isDuressDetected: Boolean,
    giveHelpBtnPressed: Boolean,
    isHelpSessionActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val buttonText = when {
        !isDuressDetected -> "Duress"                 // ready to trigger
        isHelpSessionActive && !giveHelpBtnPressed -> "Cancel Request" // open, not accepted
        isHelpSessionActive && giveHelpBtnPressed  -> "Finish"         // helper accepted
        else -> "Duress"
    }

    val buttonColor = when {
        !isDuressDetected -> Color(0xFFB22222) // Firebrick red
        isHelpSessionActive && giveHelpBtnPressed -> Color(0xFF228B22) // Forest green
        else -> Color(0xFFFFA500) // Orange for pending
    }

    CustomButton(
        modifier = modifier,
        text = buttonText,
        backgroundColor = buttonColor,
        onClick = onClick,
        enabled = enabled
    )
}
