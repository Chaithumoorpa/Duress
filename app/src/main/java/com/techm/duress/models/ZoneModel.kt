package com.techm.duress.models

import androidx.compose.ui.geometry.Offset

data class ZoneModel(
    val points: List<Offset>,
    val name: String
)