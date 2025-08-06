package com.techm.duress.views.widgets

import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.techm.duress.viewmodel.MainViewModel
import com.techm.duress.viewmodel.StreamingState
import org.webrtc.VideoTrack

@Composable
fun CameraView(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
    roomId: String = viewModel.roomId ?: "unknown-room"
) {
    val context = LocalContext.current
    val isHelpSessionActive by viewModel.isHelpSessionActive.collectAsState()
    val broadcasterWsUrl = viewModel.broadcasterWs
    val streamingState by viewModel.streamingState.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {

        // Manage stream lifecycle
        LaunchedEffect(isHelpSessionActive, broadcasterWsUrl) {
            if (isHelpSessionActive && broadcasterWsUrl != null) {
                if (!viewModel.isStreaming()) {
                    Log.d("CameraView", "Starting stream to $broadcasterWsUrl")
                    viewModel.startStreaming(
                        wsUrl = broadcasterWsUrl,
                        roomId = roomId,
                        onRemoteVideo = { videoTrack: VideoTrack ->
                            viewModel.setIncomingVideoTrack(videoTrack)
                        }
                    )
                } else {
                    Log.d("CameraView", "Stream already active")
                }
            } else {
                Log.d("CameraView", "Stopping stream")
                viewModel.stopStreaming()
            }
        }

        // Cleanup stream when composable is removed
        DisposableEffect(Unit) {
            onDispose {
                Log.d("CameraView", "Disposing CameraView: stopping stream")
                viewModel.stopStreaming()
            }
        }

        // Status message
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (isHelpSessionActive)
                    "Streaming to Helper..."
                else
                    "Waiting for Helper..."
            )
        }
    }
}
