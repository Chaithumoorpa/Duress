package com.techm.duress.views.widgets

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
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

    val shouldRender = helperAssigned && sessionStatus == "open" && videoTrack != null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        if (shouldRender) {
            AndroidView(
                factory = {
                    SurfaceViewRenderer(context).apply {
                        init(eglBase.eglBaseContext, null)
                        videoTrack.addSink(this)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = {
                    videoTrack.removeSink(it)
                    it.release()
                }
            )
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
}
