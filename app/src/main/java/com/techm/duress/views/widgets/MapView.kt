package com.techm.duress.views.widgets

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MapView(context: Context) {
    println("debug/// MapView")
    val imageBitmap = remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val assetManager = context.assets
            val inputStream = assetManager.open("floor_map.png")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            imageBitmap.value = bitmap?.asImageBitmap()
        }
    }

    imageBitmap.value?.let {
        Image(
            bitmap = it,
            contentDescription = "Image from assets",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
            alignment = Alignment.TopCenter
        )
    }

}
