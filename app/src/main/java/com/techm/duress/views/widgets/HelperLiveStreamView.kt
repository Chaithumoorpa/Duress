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
 * Renders only when a helper is assigned, the session is open, and a valid VideoTrack is available.
 * Shows a fallback loading UI if video track is missing.
 */
@Composable
fun HelperLiveStreamView(
    helperAssigned: Boolean,
    sessionStatus: String?,
    videoTrack: VideoTrack?
) {
    val context = LocalContext.current
    val eglBase = remember { EglBase.create() }

    // hold a reference to the renderer so we can detach/release cleanly
    var rendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    val shouldRender = helperAssigned && sessionStatus == "open" && videoTrack != null

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp)
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
                        rendererRef = this
                    }
                },
                update = { /* no-op; sink is handled via DisposableEffect below */ }
            )

            // Attach/detach sink exactly when track or renderer changes
            DisposableEffect(videoTrack, rendererRef) {
                val r = rendererRef
                if (videoTrack != null && r != null) {
                    videoTrack.addSink(r)
                }
                onDispose {
                    if (videoTrack != null && r != null) {
                        videoTrack.removeSink(r)
                    }
                }
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Waiting for live stream to start...",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    // Release EGL + renderer when this composable leaves the composition
    DisposableEffect(Unit) {
        onDispose {
            try { rendererRef?.release() } catch (_: Throwable) {}
            rendererRef = null
            try { eglBase.release() } catch (_: Throwable) {}
        }
    }
}
