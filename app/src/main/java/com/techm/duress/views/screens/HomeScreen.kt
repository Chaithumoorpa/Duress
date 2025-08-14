package com.techm.duress.views.screens

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.techm.duress.core.ble.BleProvider
import com.techm.duress.core.camera.CameraServiceProvider
import com.techm.duress.core.fall.FallDetector
import com.techm.duress.core.zone.ZoneDetector
import com.techm.duress.viewmodel.MainViewModel
import com.techm.duress.viewmodel.StreamingState
import com.techm.duress.views.widgets.*
import kotlinx.coroutines.*
import org.webrtc.VideoTrack

private const val TAG = "DU/HOME"

@Composable
fun HomeScreen(
    context2: Context,
    viewModel: MainViewModel,
    userName: String,
) {
    val context = LocalContext.current

    // ---------- VM state ----------
    val helpRequest by viewModel.helpRequest.collectAsState()
    val helperName by viewModel.helperName.collectAsState()
    val status by viewModel.sessionStatus.collectAsState()         // "open" | "taken" | "closed"
    val isHelpSessionActive by viewModel.isHelpSessionActive.collectAsState()
    val videoTrack by viewModel.incomingRemoteVideoTrack.collectAsState()
    val isSignalingReady by viewModel.isSignalingReady.collectAsState()
    val streamingState by viewModel.streamingState.collectAsState()
    val roomId by viewModel.roomId.collectAsState()
    val broadcasterWs by viewModel.broadcasterWs.collectAsState()
    val viewerWs by viewModel.viewerWs.collectAsState()
    val role by viewModel.role.collectAsState()

    // ---------- Local UI state ----------
    var dialogVisible by remember { mutableStateOf(false) }
    var giveHelpBtnPressed by remember { mutableStateOf(false) } // true once helper accepts
    val isDuressDetected = remember { mutableStateOf(false) }
    val isCameraOn = remember { mutableStateOf(false) }
    var startPolling by remember { mutableStateOf(false) }

    // Fall detector
    val fallDetector = remember { FallDetector(context) }
    val fallDetected by fallDetector.fallDetected.collectAsState()

    // BLE data
    val bleData = remember { mutableStateOf(emptyList<BleProvider.BleDevice>()) }

    // Polling scope for listen_for_helper
    val pollingSupervisor = remember { SupervisorJob() }
    val pollingScope = remember { CoroutineScope(Dispatchers.IO + pollingSupervisor) }

    // ---------- BLE ----------
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            bleData.value = BleProvider.loadBLE(context)
        }
    }
    LaunchedEffect(isDuressDetected.value) {
        while (!isDuressDetected.value) {
            withContext(Dispatchers.Default) {
                for (item in bleData.value) {
                    if (isDuressDetected.value) break
                    ZoneDetector.onBeaconDetected(item.beaconId, item.rssi)
                    delay(2000)
                }
            }
        }
    }

    // ---------- Fall lifecycle ----------
    DisposableEffect(Unit) {
        fallDetector.start()
        onDispose { fallDetector.stop() }
    }

    // Auto send help on fall (Victim)
    LaunchedEffect(fallDetected) {
        if (fallDetected && !isHelpSessionActive) {
            viewModel.setStreamingState(StreamingState.Signaling)
            viewModel.sendHelpRequest(ZoneDetector.currentZone.value) { rid, bWS, _ ->
                startPolling = true
                isCameraOn.value = true
                viewModel.setStreamingState(StreamingState.Signaling)
                viewModel.startStreaming(
                    wsUrl = bWS,
                    roomId = rid,
                    role = CameraServiceProvider.StreamRole.VICTIM
                ) { /* Victim doesn't render remote */ }
            }
        }
    }

    // Helper periodically discovers open requests
    LaunchedEffect(giveHelpBtnPressed, status) {
        if (!giveHelpBtnPressed && status != "open") {
            while (isActive) {
                viewModel.checkForHelpRequest(currentUserName = userName)
                delay(10_000)
            }
        }
    }


    // Close handling
    LaunchedEffect(status) {
        if (status == "closed") {
            dialogVisible = false
            viewModel.clearHelpRequest()
            pollingSupervisor.cancelChildren()
            startPolling = false
            viewModel.stopStreaming()
            isCameraOn.value = false
            giveHelpBtnPressed = false
            isDuressDetected.value = false
        }
    }

    // Dispose: stop stream if screen leaves
    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopStreaming()
        }
    }

    // Victim waiting → poll for helper assignment
    LaunchedEffect(startPolling, status) {
        pollingSupervisor.cancelChildren()
        if (startPolling && status != "taken" && status != "closed") {
            pollingScope.launch {
                while (isActive) {
                    withTimeoutOrNull(5_000) { viewModel.listenForHelper(userName) }
                    delay(10_000)
                }
            }
        }
    }


    // Helper: after accepting, auto-join viewer WebSocket
    LaunchedEffect(giveHelpBtnPressed) {
        if (!giveHelpBtnPressed) return@LaunchedEffect
        repeat(10) {
            val vws = viewModel.viewerWs.value
            val rid = viewModel.roomId.value
            if (!vws.isNullOrBlank() && !rid.isNullOrBlank()) {
                if (viewModel.streamingState.value == StreamingState.Idle) {
                    viewModel.startStreaming(
                        wsUrl = vws,
                        roomId = rid,
                        role = CameraServiceProvider.StreamRole.HELPER
                    ) { track: VideoTrack ->
                        viewModel.setIncomingVideoTrack(track)
                    }
                }
                return@LaunchedEffect
            } else {
                delay(3000)
            }
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

            // ---------------------- Live Video Feed -----------------------------
            ReusableBoxWithHeader(height = 220.dp, title = "Live Video Feed") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Victim local preview
                    if (role == CameraServiceProvider.StreamRole.VICTIM &&
                        streamingState != StreamingState.Idle &&
                        isSignalingReady
                    ) {
                        CameraView(viewModel = viewModel, modifier = Modifier.fillMaxWidth())
                    }

                    // Helper remote view (now tolerant of "taken")
                    HelperLiveStreamView(
                        helperAssigned = giveHelpBtnPressed,
                        sessionStatus = status,
                        videoTrack = videoTrack
                    )

                    // Victim Start/Stop control (only before helper accept)
                    val isStreaming = streamingState == StreamingState.Streaming
                    val canStartVictim = (status == "open") && !giveHelpBtnPressed

                    if (canStartVictim) {
                        Button(
                            onClick = {
                                if (isStreaming) {
                                    viewModel.stopStreaming()
                                } else {
                                    val ws = broadcasterWs
                                    val rid = roomId
                                    if (!ws.isNullOrBlank() && !rid.isNullOrBlank()) {
                                        viewModel.startStreaming(
                                            wsUrl = ws,
                                            roomId = rid,
                                            role = CameraServiceProvider.StreamRole.VICTIM
                                        ) { }
                                    }
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp)
                        ) {
                            Text(if (isStreaming) "Stop Stream" else "Start Stream")
                        }
                    }
                }
            }

            // ---------------------- Zone Map -----------------------------------
            ReusableBoxWithHeader(height = 300.dp, title = "Zone Map") {
                MapView(context)
                ZoneView(isDuressDetected = isDuressDetected.value, context = context)
                UserIconView(isDuressDetected.value, giveHelpBtnPressed, viewModel)
                if (isDuressDetected.value) {
                    NearbyUsersIconView(isDuressDetected.value, context)
                }
            }

            // ---------------------- Distance Meter ------------------------------
            ReusableBoxWithHeader(height = 100.dp, title = "Real Time Distance Meter") {
                if (giveHelpBtnPressed && !helperName.isNullOrEmpty()) {
                    DistanceMeter(context2)
                } else {
                    Box {}
                }
            }

            // ---------------------- CTAs / Status -------------------------------
            ReusableBoxWithHeader(showHeader = false) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (!helperName.isNullOrEmpty()) {
                        Text(text = "$helperName is coming to help you!!", modifier = Modifier.padding(top = 4.dp))
                    }

                    if (giveHelpBtnPressed) {
                        val req = viewModel.requestingUser
                        Text(
                            text = "You Are Helping ${req?.name ?: "Unknown"} in ${req?.zone ?: "Unknown"}",
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {

                        // VICTIM control: Duress / Finish toggle
                        if (!giveHelpBtnPressed) {
                            DuressView(
                                modifier = Modifier.weight(1f),
                                giveHelpBtnPressed = giveHelpBtnPressed,
                                isDuressDetected = isDuressDetected.value,
                                onClick = {
                                    isDuressDetected.value = !isDuressDetected.value
                                    if (isDuressDetected.value) {
                                        viewModel.setStreamingState(StreamingState.Signaling)
                                        viewModel.sendHelpRequest(ZoneDetector.currentZone.value) { rid, bWS, _ ->
                                            startPolling = true
                                            isCameraOn.value = true
                                            viewModel.setStreamingState(StreamingState.Signaling)
                                            viewModel.startStreaming(
                                                wsUrl = bWS,
                                                roomId = rid,
                                                role = CameraServiceProvider.StreamRole.VICTIM
                                            ) { }
                                        }
                                    } else {
                                        // Victim finishes session
                                        viewModel.sendHelpCompleted(
                                            victimName = userName,
                                            helper = helperName ?: ""
                                        ) {
                                            isCameraOn.value = false
                                        }
                                    }
                                },
                                isHelpSessionActive = isHelpSessionActive
                            )
                        }

                        // HELPER control: Finish button once they've accepted
                        if (giveHelpBtnPressed) {
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val req = viewModel.requestingUser
                                    val victim = req?.name
                                    if (!victim.isNullOrBlank()) {
                                        viewModel.sendHelpCompleted(victimName = victim, helper = userName) {
                                            // Teardown handled by VM
                                        }
                                    }
                                }
                            ) {
                                Text("Finish")
                            }
                        }

                        if (giveHelpBtnPressed) {
                            CallView(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }

    // ---------------- Incoming Help Request Dialog (Helper) --------------------
    helpRequest?.let {
        // Only show if it's not me AND I haven't already accepted someone
        val shouldShow = (it.name != userName) && !giveHelpBtnPressed
        if (shouldShow && !dialogVisible) dialogVisible = true
        if (!shouldShow && dialogVisible) dialogVisible = false

        if (dialogVisible && shouldShow) {
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
                        viewModel.sendGiveHelpRequest(it.name, userName) { }
                        giveHelpBtnPressed = true
                        isDuressDetected.value = true
                        dialogVisible = false
                        viewModel.clearHelpRequest()
                    }) { Text("Give Help") }
                },
                dismissButton = {
                    Button(onClick = {
                        dialogVisible = false
                        viewModel.clearHelpRequest()
                    }) { Text("Dismiss") }
                }
            )
        }
    }
}
