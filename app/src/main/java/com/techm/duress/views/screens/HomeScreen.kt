package com.techm.duress.views.screens

import android.content.Context
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.techm.duress.core.ble.BleProvider
import com.techm.duress.core.camera.CameraServiceProvider
import com.techm.duress.core.fall.FallDetector
import com.techm.duress.core.zone.ZoneDetector
import com.techm.duress.core.webrtc.SignalingSocket
import com.techm.duress.core.webrtc.WebRTCClient
import com.techm.duress.viewmodel.MainViewModel
import com.techm.duress.viewmodel.StreamingState
import com.techm.duress.views.widgets.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

@Composable
fun HomeScreen(
    context2: Context,
    viewModel: MainViewModel,
    userName: String,
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val helpRequest by viewModel.helpRequest.collectAsState()
    val helperName by viewModel.helperName.collectAsState()
    val status by viewModel.status.collectAsState()
    val isHelpSessionActive by viewModel.isHelpSessionActive.collectAsState()
    val videoTrack by viewModel.incomingVideoTrack.collectAsState()

    var dialogVisible by remember { mutableStateOf(false) }
    var giveHelpBtnPressed by remember { mutableStateOf(false) }
    var isDuressDetected = remember { mutableStateOf(false) }
    var isCameraOn = remember { mutableStateOf(false) }
    var startPolling by remember { mutableStateOf(false) }

    val previewView = remember { PreviewView(context) }
    val fallDetector = remember { FallDetector(context) }
    val fallDetected by fallDetector.fallDetected.collectAsState()
    val bleData = remember { mutableStateOf(emptyList<Pair<String, Int>>()) }

    val signalingSocket = remember { mutableStateOf<SignalingSocket?>(null) }
    val webRTCClient = remember { mutableStateOf<WebRTCClient?>(null) }
    val socketRef = remember { mutableStateOf<SignalingSocket?>(null) }


    val requestingUser = viewModel.requestingUser

    val pollingSupervisor = remember { SupervisorJob() }
    val pollingScope = remember { CoroutineScope(Dispatchers.IO + pollingSupervisor) }

    var isSignalingReady by remember { mutableStateOf(false) }

    val helpRequestedState = remember { mutableStateOf(false) }
    val helpRequested by helpRequestedState

    // BLE zone detection
    LaunchedEffect(Unit) {
        bleData.value = BleProvider.loadBLE(context)
    }

    LaunchedEffect(isDuressDetected.value) {
        while (!isDuressDetected.value) {
            withContext(Dispatchers.Default) {
                bleData.value.forEach { (beaconId, rssi) ->
                    if (isDuressDetected.value) return@forEach
                    ZoneDetector.onBeaconDetected(beaconId, rssi)
                    delay(2000)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        fallDetector.start()
        onDispose { fallDetector.stop() }
    }

    LaunchedEffect(fallDetected) {
        if (fallDetected && !helpRequested) {
            Log.d("HomeScreen", "Fall detected. Sending help request.")
            viewModel.setStreamingState(StreamingState.Signaling)
            viewModel.sendHelpRequest(ZoneDetector.currentZone) {
                helpRequestedState.value = true
                isCameraOn.value = true
            }
        }
    }

    LaunchedEffect(true) {
        while (isActive) {
            viewModel.checkForHelpRequest(userName, ZoneDetector.currentZone)
            delay(10000)
        }
    }


    LaunchedEffect(startPolling) {
        pollingSupervisor.cancelChildren() // Cancel any previous polling
        if (startPolling) {
            pollingScope.launch {
                while (isActive) {
                    try {
                        withTimeoutOrNull(5000) {
                            viewModel.listenForHelper(userName)
                        }
                        delay(10000)
                    } catch (e: Exception) {
                        Log.e("HomeScreen", "Polling error: ${e.message}", e)
                    }
                }
            }
        }
    }

    LaunchedEffect(status) {
        if (status == "closed") {
            Log.d("HomeScreen", "Session closed. Cleaning up.")

            pollingSupervisor.cancelChildren()
            startPolling = false

            // These should already be handled inside CameraServiceProvider.stopStream()
            viewModel.stopStreaming()

            isCameraOn.value = false
            helpRequestedState.value = false
            giveHelpBtnPressed = false
            isDuressDetected.value = false
        }
    }



    DisposableEffect(Unit) {
        onDispose {
            Log.d("HomeScreen", "Disposing HomeScreen: stopping stream")
            viewModel.stopStreaming()
        }
    }


    // ❶ Broadcaster (Victim)
    LaunchedEffect(helpRequested) {
        if (helpRequested && viewModel.broadcasterWs != null) {
            viewModel.initBroadcasterSignaling(
                context = context,
                wsUrl = viewModel.broadcasterWs!!,
                roomId = viewModel.roomId ?: "unknown-room"
            )
        } else {
            viewModel.releaseWebRTC()
        }
    }

    LaunchedEffect(giveHelpBtnPressed) {
        if (giveHelpBtnPressed && viewModel.viewerWebsocketUrl != null) {
            viewModel.initViewerSignaling(
                context = context,
                wsUrl = viewModel.viewerWebsocketUrl!!,
                roomId = viewModel.roomId ?: "unknown-room"
            )
        } else {
            viewModel.releaseViewerWebRTC()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ReusableBoxWithHeader(height = 220.dp, title = "Live Video Feed") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isCameraOn.value && isSignalingReady) {
                        CameraView(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    HelperLiveStreamView(
                        helperAssigned = giveHelpBtnPressed,
                        sessionStatus = status,
                        videoTrack = videoTrack
                    )

                    val isStreaming = viewModel.isStreaming()
                    val isSignalingReady by viewModel.isSignalingReady.collectAsState()

                    if (isSignalingReady && giveHelpBtnPressed) {
                        Button(
                            onClick = {
                                if (isStreaming) {
                                    viewModel.stopStreaming()
                                } else {
                                    viewModel.startStreaming(
                                        wsUrl = viewModel.broadcasterWs ?: return@Button,
                                        roomId = viewModel.roomId ?: "unknown-room"
                                    ) { track ->
                                        viewModel.setIncomingVideoTrack(track)
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(text = if (isStreaming) "Stop Stream" else "Start Stream")
                        }
                    }
                }
            }


            ReusableBoxWithHeader(height = 300.dp, title = "Zone Map") {
                MapView(context)
                ZoneView(isDuressDetected = isDuressDetected.value, context = context)
                UserIconView(isDuressDetected.value, giveHelpBtnPressed, viewModel)
                if (isDuressDetected.value) {
                    NearbyUsersIconView(isDuressDetected.value, context)
                }
            }

            ReusableBoxWithHeader(height = 100.dp, title = "Real Time Distance Meter") {
                if (giveHelpBtnPressed && !helperName.isNullOrEmpty()) {
                    DistanceMeter(context2)
                } else {
                    Box {}
                }
            }

            ReusableBoxWithHeader(showHeader = false) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!helperName.isNullOrEmpty()) {
                        Text(
                            text = "$helperName is coming to help you!!",
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    if (giveHelpBtnPressed) {
                        Text(
                            text = "You Are Helping ${requestingUser?.name} in ${requestingUser?.zone}",
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    if (giveHelpBtnPressed) {
                        SpeakView(modifier = Modifier.fillMaxWidth(), requestingUser?.name)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DuressView(
                            modifier = Modifier.weight(1f),
                            giveHelpBtnPressed = giveHelpBtnPressed,
                            isDuressDetected = isDuressDetected.value,
                            onClick = {
                                isDuressDetected.value = !isDuressDetected.value
                                if (isDuressDetected.value) {
                                    viewModel.setStreamingState(StreamingState.Signaling)
                                    viewModel.sendHelpRequest(ZoneDetector.currentZone) {
                                        helpRequestedState.value = true
                                        startPolling = true
                                        isCameraOn.value = true
                                    }
                                } else {
                                    requestingUser?.let { user ->
                                        viewModel.sendHelpCompleted(
                                            name = user.name,
                                            helper = userName
                                        ) {
                                            giveHelpBtnPressed = false
                                            isCameraOn.value = false
                                        }
                                    }
                                }
                            },
                            isHelpSessionActive = isHelpSessionActive
                        )

                        if (giveHelpBtnPressed) {
                            CallView(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    // Incoming Help Request Dialog
    helpRequest?.let {
        if (it.name != userName && !dialogVisible) {
            dialogVisible = true
        }
        if (dialogVisible) {
            AlertDialog(
                onDismissRequest = {
                    dialogVisible = false
                    viewModel.clearHelpRequest()
                },
                title = { Text("${it.name} needs help!") },
                text = { Text("${it.name} in ${it.zone} needs assistance.") },
                confirmButton = {
                    Button(onClick = {
                        viewModel.setRequestingUser(it)
                        viewModel.sendGiveHelpRequest(it.name, userName) {}
                        giveHelpBtnPressed = true
                        isDuressDetected.value = true
                        dialogVisible = false
                        ZoneDetector.currentZone = it.zone
                    }) {
                        Text("Give Help")
                    }
                },
                dismissButton = {
                    Button(onClick = {
                        dialogVisible = false
                        viewModel.clearHelpRequest()
                    }) {
                        Text("Dismiss")
                    }
                }
            )
        }
    }
}
