package com.techm.duress.views.widgets

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.techm.duress.viewmodel.MainViewModel
import com.techm.duress.viewmodel.StreamingState
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Local camera preview for the Victim.
 * The renderer is attached to WebRTC local track via viewModel.attachLocalRenderer(renderer).
 * Includes a transparent flip-camera button overlay (bottom-right).
 */
@Composable
fun CameraView(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val streamingState by viewModel.streamingState.collectAsState()
    val isHelpSessionActive by viewModel.isHelpSessionActive.collectAsState()

    // One EGL base across recompositions
    val eglBase = remember { EglBase.create() }
    var rendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    Box(modifier = modifier.fillMaxWidth().fillMaxHeight()) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            factory = {
                SurfaceViewRenderer(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    init(eglBase.eglBaseContext, null)
                    // Mirror for front-facing preview
                    setMirror(true)
                    setEnableHardwareScaler(true)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    // Keep under overlays
                    setZOrderMediaOverlay(false)

                    rendererRef = this
                    // Attach the local preview sink
                    viewModel.attachLocalRenderer(this)
                }
            },
            update = {
                // If renderer was recreated, reattach
                if (rendererRef !== it) {
                    rendererRef = it
                    viewModel.attachLocalRenderer(it)
                }
            }
        )

        // Small "LIVE" watermark (top-left)
        Text(
            text = "LIVE",
            color = Color.Red,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .alpha(0.6f)
        )

        // Flip camera button (bottom-right)
        val btnSize: Dp = 44.dp
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.35f), CircleShape)
        ) {
            IconButton(
                onClick = { viewModel.flipCamera() },
                modifier = Modifier.size(btnSize)
            ) {
                Icon(
                    imageVector = Icons.Filled.Cameraswitch,
                    contentDescription = "Flip camera",
                    tint = Color.White
                )
            }
        }

        // Status text (bottom-center)
        val statusText = when {
            streamingState == StreamingState.Streaming -> "Streaming…"
            isHelpSessionActive                        -> "Helper assigned — ready to start"
            else                                       -> "Waiting for helper…"
        }
        Text(
            text = statusText,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(8.dp)
        )

        // Cleanup when leaving composition
        DisposableEffect(Unit) {
            onDispose {
                runCatching { viewModel.attachLocalRenderer(null) }
                runCatching { rendererRef?.release() }
                rendererRef = null
                runCatching { eglBase.release() }
            }
        }
    }
}
