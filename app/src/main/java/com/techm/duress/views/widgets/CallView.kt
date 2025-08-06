package com.techm.duress.views.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

@Composable
fun SpeakView(modifier: Modifier = Modifier,name:String?) {
    println("debug/// SpeakView")
    CustomButton(
        backgroundColor = Color(0xFF00BFFF),
        text = "Speak To ${name?:"Requester"}",
        modifier = modifier
    )
}

@Composable
fun CallView(modifier: Modifier = Modifier) {
    println("debug/// CallView")
    CustomButton(
        backgroundColor = Color(0xFFB22222),
        text = "Call 911",
        modifier = modifier
    )
}
