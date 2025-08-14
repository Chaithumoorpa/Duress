package com.techm.duress.views.widgets

import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Displays the incoming video stream to the helper.
 * Renders only when a helper is assigned, the session is OPEN or TAKEN, and a valid VideoTrack exists.
 * Sink attach/detach is handled exactly once via DisposableEffect.
 */
@Composable
fun HelperLiveStreamView(
    helperAssigned: Boolean,
    sessionStatus: String?,
    videoTrack: VideoTrack?
) {
    val context = LocalContext.current
    // Keep one EGL context across recompositions
    val eglBase = remember { EglBase.create() }

    // We keep a single renderer instance reference to manage sinks/lifecycle safely
    var rendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    // Allow both "open" and "taken"
//    val sessionReady = sessionStatus == "open" || sessionStatus == "taken"
//    val shouldRender = helperAssigned && sessionReady && videoTrack != null

    val shouldRender = videoTrack != null

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (shouldRender) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    SurfaceViewRenderer(context).apply {
                        init(eglBase.eglBaseContext, /* rendererEvents = */ null)
                        setEnableHardwareScaler(true)
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                        setMirror(false)
                        // Optional: this can help layering when you also show local preview
                        setZOrderMediaOverlay(true)
                        rendererRef = this
                    }
                },
                update = { /* no-op: sink wiring is in DisposableEffect below */ }
            )

            // Attach/detach sink exactly when [videoTrack] or [rendererRef] changes
            DisposableEffect(videoTrack, rendererRef) {
                val r = rendererRef
                if (videoTrack != null && r != null) {
                    try {
                        videoTrack.setEnabled(true)
                        videoTrack.addSink(r)
                    } catch (_: Throwable) { /* ignore */ }
                }
                onDispose {
                    if (videoTrack != null && r != null) {
                        try { videoTrack.removeSink(r) } catch (_: Throwable) { /* ignore */ }
                    }
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text(
                    text = when {
                        !helperAssigned     -> "Waiting for a helper to accept…"
//                        !sessionReady       -> "Setting up session…"
                        videoTrack == null  -> "Waiting for live stream to start…"
                        else                -> "Preparing video…"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    // Release EGL + renderer when this composable leaves composition
    DisposableEffect(Unit) {
        onDispose {
            try { rendererRef?.release() } catch (_: Throwable) {}
            rendererRef = null
            try { eglBase.release() } catch (_: Throwable) {}
        }
    }
}
