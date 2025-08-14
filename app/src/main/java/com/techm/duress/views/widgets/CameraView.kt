package com.techm.duress.views.widgets

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.techm.duress.viewmodel.MainViewModel
import com.techm.duress.viewmodel.StreamingState
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Local camera preview for the Victim.
 * The renderer is attached to WebRTC local track via viewModel.attachLocalRenderer(renderer).
 */
@Composable
fun CameraView(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val streamingState by viewModel.streamingState.collectAsState()
    val isHelpSessionActive by viewModel.isHelpSessionActive.collectAsState()

    // Keep one EGL base across recompositions
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
                    // Optional: ensure it sits under the remote if overlapping is used
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

        // Clean up when composable leaves the screen
        DisposableEffect(Unit) {
            onDispose {
                try { viewModel.attachLocalRenderer(null) } catch (_: Throwable) {}
                try { rendererRef?.release() } catch (_: Throwable) {}
                rendererRef = null
                try { eglBase.release() } catch (_: Throwable) {}
            }
        }

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
    }
}
