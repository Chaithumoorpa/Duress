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
import androidx.compose.ui.graphics.Color
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

    // ---------- VM state (Flows -> Compose) ----------
    val helpRequest by viewModel.helpRequest.collectAsState()
    val helperName by viewModel.helperName.collectAsState()
    val status by viewModel.sessionStatus.collectAsState()             // "open" | "taken" | "closed"
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
    var giveHelpBtnPressed by remember { mutableStateOf(false) }
    val isDuressDetected = remember { mutableStateOf(false) }
    val isCameraOn = remember { mutableStateOf(false) }
    var startPolling by remember { mutableStateOf(false) }

    // Fall detector
    val fallDetector = remember { FallDetector(context) }
    val fallDetected by fallDetector.fallDetected.collectAsState()

    // BLE demo data
    val bleData = remember { mutableStateOf(emptyList<BleProvider.BleDevice>()) }

    // Polling scope for listen_for_helper
    val pollingSupervisor = remember { SupervisorJob() }
    val pollingScope = remember { CoroutineScope(Dispatchers.IO + pollingSupervisor) }

//    val role by viewModel.role.collectAsState()
    val isVictim = role == CameraServiceProvider.StreamRole.VICTIM
    val showDistance = if (isVictim) {
        // Victim should see meter once a helper is assigned
        !helperName.isNullOrEmpty()
    } else {
        // Helper should see meter after they pressed “Give Help”
        giveHelpBtnPressed
    }


    // ---------- BLE zone “sim” ----------
    LaunchedEffect(Unit) {
        Log.d(TAG, "BLE load start")
        withContext(Dispatchers.IO) { bleData.value = BleProvider.loadBLE(context) }
        Log.d(TAG, "BLE loaded: ${bleData.value.size} items")
    }

    LaunchedEffect(isDuressDetected.value) {
        Log.d(TAG, "BLE drive loop started: duress=${isDuressDetected.value}")
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

    // ---------- Fall sensor lifecycle ----------
    DisposableEffect(Unit) {
        Log.d(TAG, "FallDetector.start()")
        fallDetector.start()
        onDispose {
            Log.d(TAG, "FallDetector.stop()")
            fallDetector.stop()
        }
    }

    // Fall → send help request (Victim path) — DO NOT start WebRTC here
    LaunchedEffect(fallDetected) {
        if (fallDetected && !isHelpSessionActive) {
            Log.d(TAG, "Fall detected → sendHelpRequest (zone=${ZoneDetector.currentZone.value})")
            viewModel.setStreamingState(StreamingState.Signaling)
            viewModel.sendHelpRequest(ZoneDetector.currentZone.value) { _, _, _ ->
                startPolling = true
                isCameraOn.value = true
                viewModel.setStreamingState(StreamingState.Signaling)
                // Wait for helper assignment to start streaming
            }
        }
    }

    // Periodic discovery (Helper path) – see other users' duress; stop once we accepted
    LaunchedEffect(Unit) {
        while (isActive && !giveHelpBtnPressed) {
            Log.d(TAG, "checkForHelpRequest() as helper=$userName")
            viewModel.checkForHelpRequest(currentUserName = userName)
            delay(10_000)
        }
    }

    // Victim: poll for helper assignment while waiting
    LaunchedEffect(startPolling) {
        pollingSupervisor.cancelChildren()
        if (startPolling) {
            Log.d(TAG, "Start polling for helper")
            pollingScope.launch {
                while (isActive) {
                    try {
                        withTimeoutOrNull(5_000) { viewModel.listenForHelper(userName) }
                        delay(10_000)
                    } catch (e: Exception) {
                        Log.e(TAG, "Polling error: ${e.message}", e)
                    }
                }
            }
        }
    }

    // Start victim streaming only AFTER helper accepts & session ready
    LaunchedEffect(helperName, status, streamingState) {
        val rid = roomId
        val bws = broadcasterWs
        val helperAssigned = !helperName.isNullOrBlank()
        val sessionReady = status == "taken" || status == "open" // your server uses open/taken
        val notStreaming = streamingState == StreamingState.Signaling || streamingState == StreamingState.Idle

        if (helperAssigned && sessionReady && notStreaming && !rid.isNullOrBlank() && !bws.isNullOrBlank()) {
            // stop victim-side polling now; we have a helper
            startPolling = false
            Log.d(TAG, "Helper accepted → starting victim stream (room=$rid)")
            viewModel.startStreaming(
                wsUrl = bws,
                roomId = rid!!,
                role = CameraServiceProvider.StreamRole.VICTIM
            ) { /* Victim ignores remote */ }
        }
    }

    // Helper: when Give Help is pressed, auto start HELPER stream (once URLs known)
    LaunchedEffect(giveHelpBtnPressed) {
        if (!giveHelpBtnPressed) return@LaunchedEffect
        Log.d(TAG, "Helper join flow started")
        repeat(10) { attempt ->
            val vws = viewModel.viewerWs.value
            val rid = viewModel.roomId.value
            if (!vws.isNullOrBlank() && !rid.isNullOrBlank()) {
                Log.d(TAG, "Helper join: viewerWs=$vws roomId=$rid")
                if (viewModel.streamingState.value == StreamingState.Idle ||
                    viewModel.streamingState.value == StreamingState.Signaling
                ) {
                    viewModel.startStreaming(
                        wsUrl = vws,
                        roomId = rid!!,
                        role = CameraServiceProvider.StreamRole.HELPER
                    ) { track: VideoTrack ->
                        Log.d(TAG, "Helper received remote track")
                        viewModel.setIncomingVideoTrack(track)
                    }
                }
                return@LaunchedEffect
            } else {
                Log.w(TAG, "Helper join pending (attempt ${attempt + 1}): viewerWs/roomId missing")
                delay(3000)
            }
        }
        Log.w(TAG, "Helper join timed out waiting for viewerWs/roomId")
    }

    // Session status change → cleanup if closed
    LaunchedEffect(status) {
        if (status == "closed") {
            Log.d(TAG, "Status=closed → cleanup")
            dialogVisible = false
            viewModel.clearHelpRequest()

            pollingSupervisor.cancelChildren()
            startPolling = false
            viewModel.stopStreaming()
            isCameraOn.value = false
            giveHelpBtnPressed = false
            isDuressDetected.value = false
        } else if (status != null) {
            Log.d(TAG, "Session status: $status")
        }
    }

    // Dispose: stop stream if screen leaves
    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG, "HomeScreen dispose → stopStreaming()")
            viewModel.stopStreaming()
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
                    // Victim local preview UI — only when streaming has started
                    if (
                        role == CameraServiceProvider.StreamRole.VICTIM &&
                        streamingState != StreamingState.Idle &&
                        isSignalingReady
                    ) {
                        Log.d(
                            TAG,
                            "Render CameraView (role=VICTIM, isCameraOn=${isCameraOn.value}, signaling=$isSignalingReady)"
                        )
                        CameraView(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // Helper remote view
                    HelperLiveStreamView(
                        helperAssigned = giveHelpBtnPressed,
                        sessionStatus = status,
                        videoTrack = videoTrack
                    )

                    // Victim Start/Stop button (manual control, optional)
                    val isStreaming = streamingState == StreamingState.Streaming
                    val canStartVictim = (status == "open" || status == "taken") && !giveHelpBtnPressed

                    if (canStartVictim) {
                        Button(
                            onClick = {
                                if (isStreaming) {
                                    Log.d(TAG, "Victim: Stop stream tapped")
                                    viewModel.stopStreaming()
                                } else {
                                    val ws = broadcasterWs
                                    val rid = roomId
                                    Log.d(TAG, "Victim: Start stream tapped ws=$ws room=$rid")
                                    if (!ws.isNullOrBlank() && !rid.isNullOrBlank()) {
                                        viewModel.startStreaming(
                                            wsUrl = ws,
                                            roomId = rid!!,
                                            role = CameraServiceProvider.StreamRole.VICTIM
                                        ) { /* Victim ignores remote */ }
                                    } else {
                                        Log.w(TAG, "Cannot start: broadcasterWs or roomId is null")
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

            // ---------------------- Zone Map -----------------------------------
            ReusableBoxWithHeader(height = 300.dp, title = "Zone Map") {
                MapView(context)
                ZoneView(isDuressDetected = isDuressDetected.value, context = context)
                UserIconView(isDuressDetected.value, giveHelpBtnPressed, viewModel)
                // Counterparty marker:
                // Victim: show helper once known
                val helper = helperName
                if (!helper.isNullOrBlank()) {
                    CounterpartyIconView(counterpartyName = helper, context = context)
                }

                // Helper: before/after giving help, show the victim (requesting user)
                val victim = viewModel.requestingUser?.name
                if (!victim.isNullOrBlank()) {
                    CounterpartyIconView(counterpartyName = victim, context = context, color = Color(0xFFB22222)) // firebrick for victim
                }
            }

            // ---------------------- Distance Meter ------------------------------
            ReusableBoxWithHeader(height = 100.dp, title = "Real Time Distance Meter") {
                if (showDistance) {
                    DistanceMeter() // (see next section: no need to pass context)
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
                        Text(
                            text = "$helperName is coming to help you!!",
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    if (giveHelpBtnPressed) {
                        val req = viewModel.requestingUser
                        Text(
                            text = "You Are Helping ${req?.name ?: "Unknown"} in ${req?.zone ?: "Unknown"}",
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (isVictim) {
                            // Victim-only Duress/Finish toggle
                            DuressView(
                                modifier = Modifier.weight(1f),
                                giveHelpBtnPressed = giveHelpBtnPressed,
                                isDuressDetected = isDuressDetected.value,
                                onClick = {
                                    isDuressDetected.value = !isDuressDetected.value
                                    if (isDuressDetected.value) {
                                        Log.d(TAG, "Duress pressed → sendHelpRequest")
                                        viewModel.setStreamingState(StreamingState.Signaling)
                                        viewModel.sendHelpRequest(ZoneDetector.currentZone.value) { rid, bWS, _ ->
                                            startPolling = true
                                            viewModel.setStreamingState(StreamingState.Signaling)
                                            viewModel.startStreaming(
                                                wsUrl = bWS,
                                                roomId = rid,
                                                role = CameraServiceProvider.StreamRole.VICTIM
                                            ) { /* Victim ignores remote */ }
                                        }
                                    } else {
                                        Log.d(TAG, "Finish (victim) → help_completed")
                                        viewModel.sendHelpCompleted(
                                            victimName = userName,
                                            helper = viewModel.helperName.value ?: ""
                                        ) {
                                            giveHelpBtnPressed = false
                                        }
                                    }
                                },
                                isHelpSessionActive = isHelpSessionActive
                            )
                        } else if (giveHelpBtnPressed) {
                            // Helper-only Finish button
                            Button(
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val victim = viewModel.requestingUser?.name ?: ""
                                    Log.d(TAG, "Finish (helper) → help_completed for $victim")
                                    viewModel.sendHelpCompleted(
                                        victimName = victim,
                                        helper = userName
                                    ) {
                                        giveHelpBtnPressed = false
                                        viewModel.stopStreaming()
                                    }
                                }
                            ) { Text("Finish Help") }
                        }

                        if (giveHelpBtnPressed && !isVictim) {
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
                    Log.d(TAG, "Help dialog dismissed")
                    dialogVisible = false
                    viewModel.clearHelpRequest() // prevent immediate re-open on next poll
                },
                title = { Text("${it.name} needs help!") },
                text = { Text("${it.name} in ${it.zone} needs assistance.") },
                confirmButton = {
                    Button(onClick = {
                        Log.d(TAG, "Give Help confirmed for ${it.name}")
                        viewModel.setRequestingUser(it)
                        viewModel.sendGiveHelpRequest(it.name, userName) { /* server enforces single-helper */ }
                        giveHelpBtnPressed = true
                        isDuressDetected.value = true
                        dialogVisible = false
                        viewModel.clearHelpRequest() // <- key
                    }) { Text("Give Help") }
                },
                dismissButton = {
                    Button(onClick = {
                        Log.d(TAG, "Help dialog dismissed via button")
                        dialogVisible = false
                        viewModel.clearHelpRequest()
                    }) { Text("Dismiss") }
                }
            )
        }
    }
}
