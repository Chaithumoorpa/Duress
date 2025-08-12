package com.techm.duress.views.widgets

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MapView(context: Context) {
    var imageBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val loaded: ImageBitmap? = try {
            withContext(Dispatchers.IO) {
                context.assets.open("floor_map.png").use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }
        } catch (t: Throwable) {
            loadError = t.message ?: "Failed to load map image"
            null
        }
        imageBitmap = loaded
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        when {
            imageBitmap != null -> Image(
                bitmap = imageBitmap!!,
                contentDescription = "Floor map",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                alignment = Alignment.TopCenter
            )
            loadError != null -> Text("Unable to load floor map")
            else -> Text("Loading map…")
        }
    }
}
