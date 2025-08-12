package com.techm.duress.views.widgets

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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

@Composable
fun CameraView(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val streamingState by viewModel.streamingState.collectAsState()
    val isHelpSessionActive by viewModel.isHelpSessionActive.collectAsState()

    // We create a renderer and give it to the VM as the local preview sink
    val eglBase = remember { EglBase.create() }

    // Keep a strong ref to the renderer for proper disposal
    var rendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                SurfaceViewRenderer(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // Initialize this renderer with its own EGL context
                    init(eglBase.eglBaseContext, /* rendererEvents = */ null)
                    setMirror(true)
                    setEnableHardwareScaler(true)
                    setScalingType(
                        RendererCommon.ScalingType.SCALE_ASPECT_FIT
                    )

                    rendererRef = this
                    // Hand this surface to the VM so WebRTCClient can addSink(track)
                    viewModel.attachLocalRenderer(this)
                }
            },
            update = { /* nothing, preview is driven by WebRTC track */ }
        )

        // Clean up when the composable leaves the composition
        DisposableEffect(Unit) {
            onDispose {
                // Detach from VM first so no more frames target this surface
                try { viewModel.attachLocalRenderer(null) } catch (_: Throwable) {}

                // Release renderer & EGL
                try { rendererRef?.release() } catch (_: Throwable) {}
                rendererRef = null

                try { eglBase.release() } catch (_: Throwable) {}
            }
        }

        // Lightweight status line
        val statusText = when {
            streamingState == StreamingState.Streaming -> "Streaming…"
            isHelpSessionActive                        -> "Helper assigned — ready to start"
            else                                       -> "Waiting for helper…"
        }

        Text(
            text = statusText,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
        )
    }
}
