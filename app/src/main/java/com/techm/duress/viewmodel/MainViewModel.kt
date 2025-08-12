package com.techm.duress.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.techm.duress.core.camera.CameraServiceProvider
import com.techm.duress.core.camera.CameraServiceProvider.StreamRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class HelpRequest(val name: String, val zone: String)

enum class StreamingState { Idle, Signaling, Streaming }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // --- Config ---------------------------------------------------------------
    var baseUrl: String = "http://10.246.34.42:8080/duress" // TODO: inject via settings/env

    // --- User/session model ---------------------------------------------------
    private val _userName = MutableStateFlow("")
    val userName: StateFlow<String> = _userName

    private val _role = MutableStateFlow(StreamRole.VICTIM)
    val role: StateFlow<StreamRole> = _role

    private val _roomId = MutableStateFlow<String?>(null)
    val roomId: StateFlow<String?> = _roomId

    private val _broadcasterWs = MutableStateFlow<String?>(null)
    val broadcasterWs: StateFlow<String?> = _broadcasterWs

    private val _viewerWs = MutableStateFlow<String?>(null)
    val viewerWs: StateFlow<String?> = _viewerWs

    private val _helperName = MutableStateFlow<String?>(null)      // who’s helping me (Victim path)
    val helperName: StateFlow<String?> = _helperName

    private val _helpRequest = MutableStateFlow<HelpRequest?>(null) // incoming open request I can accept
    val helpRequest: StateFlow<HelpRequest?> = _helpRequest

    private val _sessionStatus = MutableStateFlow<String?>(null)   // "open" | "taken" | "closed"
    val sessionStatus: StateFlow<String?> = _sessionStatus

    private val _isHelpSessionActive = MutableStateFlow(false)
    val isHelpSessionActive: StateFlow<Boolean> = _isHelpSessionActive

    private val _isSignalingReady = MutableStateFlow(false)        // turns true when helper is assigned
    val isSignalingReady: StateFlow<Boolean> = _isSignalingReady

    private val _streamingState = MutableStateFlow(StreamingState.Idle)
    val streamingState: StateFlow<StreamingState> = _streamingState

    private val _incomingRemoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val incomingRemoteVideoTrack: StateFlow<VideoTrack?> = _incomingRemoteVideoTrack

    private val _streamError = MutableStateFlow<String?>(null)
    val streamError: StateFlow<String?> = _streamError

    // For helper status text & finish action
    private val _requestingUser = MutableStateFlow<HelpRequest?>(null)
    val requestingUser: HelpRequest? get() = _requestingUser.value
    fun setRequestingUser(req: HelpRequest) { _requestingUser.value = req }

    // --- RTC encapsulated in provider ----------------------------------------
    private val cameraProvider = CameraServiceProvider()

    fun attachLocalRenderer(renderer: SurfaceViewRenderer?) {
        cameraProvider.setLocalPreviewSink(renderer)
    }

    fun setIncomingVideoTrack(track: VideoTrack) {
        _incomingRemoteVideoTrack.value = track
    }

    fun setUserInfo(name: String) { _userName.value = name }
    fun setRole(newRole: StreamRole) { _role.value = newRole }
    fun setStreamingState(state: StreamingState) { _streamingState.value = state }
    fun setRoom(id: String?) { _roomId.value = id }
    fun setSockets(broadcaster: String?, viewer: String?) {
        _broadcasterWs.value = broadcaster
        _viewerWs.value = viewer
    }
    fun markSignalingReady(ready: Boolean) { _isSignalingReady.value = ready }

    // --- Victim: create duress ------------------------------------------------
    fun sendHelpRequest(
        currentUserZone: String,
        mobile: String = "123456789",
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/help")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                }
                val payload = JSONObject().apply {
                    // Server currently expects "name" & "zone" per your existing code
                    put("name", _userName.value)
                    put("zone", currentUserZone)
                    put("mobile", mobile)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val js = JSONObject(body)
                    val room = js.optString("roomId").ifBlank { null }
                    val bws = js.optString("broadcasterWs").ifBlank { null }
                    val vws = js.optString("viewerWebsocketUrl").ifBlank { null }

                    withContext(Dispatchers.Main) {
                        _roomId.value = room
                        _broadcasterWs.value = bws
                        _viewerWs.value = vws
                        _isHelpSessionActive.value = true
                        _sessionStatus.value = "open"
                        _isSignalingReady.value = false
                        onComplete()
                    }
                } else {
                    Log.e("DU/HTTP", "help: ${conn.responseCode} ${conn.responseMessage}")
                }
                conn.disconnect()
            } catch (t: Throwable) {
                Log.w("DU/HTTP", "help failed: ${t.message}")
            }
        }
    }

    // --- Helper: poll for open requests --------------------------------------
    fun checkForHelpRequest(currentUserName: String, currentUserZone: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/listen")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val js = body.takeIf { it.isNotBlank() && it != "null" }?.let { JSONObject(it) }

                    val statusValue = js?.optString("status") ?: "none"
                    val victim = js?.optString("name").orEmpty()
                    val zone = js?.optString("zone").orEmpty()

                    withContext(Dispatchers.Main) {
                        if (statusValue == "closed" ||  victim == currentUserName) {
                            _helpRequest.value = null
                        } else {
                            _helpRequest.value = HelpRequest(victim, zone)
                        }
                    }
                } else {
                    Log.e("DU/HTTP", "listen: ${conn.responseCode} ${conn.responseMessage}")
                }
                conn.disconnect()
            } catch (t: Throwable) {
                Log.w("DU/HTTP", "listen failed: ${t.message}")
            }
        }
    }

    // --- Helper: accept request (single-helper guarantee on server) -----------
    fun sendGiveHelpRequest(victimName: String, helper: String, onSuccess: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/give_help")
                val post = "name=${URLEncoder.encode(victimName, "UTF-8")}" +
                        "&helper=${URLEncoder.encode(helper, "UTF-8")}"
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("Accept", "application/json")
                    doOutput = true
                }
                conn.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val js = JSONObject(body)
                    if (js.optString("status") == "success") {
                        withContext(Dispatchers.Main) { onSuccess() }
                    } else {
                        Log.w("DU/HTTP", "give_help unexpected: $body")
                    }
                } else {
                    Log.e("DU/HTTP", "give_help: ${conn.responseCode} ${conn.responseMessage}")
                }
                conn.disconnect()
            } catch (t: Throwable) {
                Log.w("DU/HTTP", "give_help failed: ${t.message}")
            }
        }
    }


    // --- Victim: wait for helper assignment -----------------------------------
    fun listenForHelper(victimName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/listen_for_helper")
                val post = "name=${URLEncoder.encode(victimName, "UTF-8")}"
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("Accept", "application/json")
                    doOutput = true
                }
                conn.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val js = body.takeIf { it.isNotBlank() && it != "null" }?.let { JSONObject(it) }

                    val helper = js?.optString("helper")?.ifBlank { null }
                    val statusValue = js?.optString("status") // may be null

                    withContext(Dispatchers.Main) {
                        if (statusValue == "closed") {
                            _helperName.value = null
                            _sessionStatus.value = "closed"
                            _isHelpSessionActive.value = false
                            _isSignalingReady.value = false
                            Log.d("DU/HTTP", "listen_for_helper → closed")
                        } else {
                            if (!helper.isNullOrBlank()) {
                                _helperName.value = helper
                                _isHelpSessionActive.value = true
                                _sessionStatus.value = statusValue ?: "open"
                                _isSignalingReady.value = true
                                Log.d("DU/HTTP", "listen_for_helper → helper=$helper, status=${statusValue ?: "open"}")
                            } else {
                                Log.d("DU/HTTP", "listen_for_helper → no change")
                            }
                        }
                    }
                } else {
                    Log.e("DU/HTTP", "listen_for_helper: ${conn.responseCode} ${conn.responseMessage}")
                }
                conn.disconnect()
            } catch (t: Throwable) {
                Log.w("DU/HTTP", "listen_for_helper failed: ${t.message}")
            }
        }
    }



    // --- Either role: finish session ------------------------------------------
    fun sendHelpCompleted(victimName: String, helper: String, onSuccess: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/help_completed")
                val post = "name=${URLEncoder.encode(victimName, "UTF-8")}" +
                        "&helper=${URLEncoder.encode(helper, "UTF-8")}"
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("Accept", "application/json")
                    doOutput = true
                }
                conn.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val js = JSONObject(body)
                    if (js.optString("status") == "success") {
                        withContext(Dispatchers.Main) { onSuccess(); teardownSessionState() }
                    } else {
                        Log.w("DU/HTTP", "help_completed unexpected: $body")
                    }
                } else {
                    Log.e("DU/HTTP", "help_completed: ${conn.responseCode} ${conn.responseMessage}")
                }
                conn.disconnect()
            } catch (t: Throwable) {
                Log.w("DU/HTTP", "help_completed failed: ${t.message}")
            }
        }
    }


    // --- Streaming control (both roles) ---------------------------------------
    // MainViewModel.kt (replace your startStreaming with this version)
    fun startStreaming(
        wsUrl: String?,              // may be null; we’ll pick from state
        roomId: String,
        role: StreamRole,
        onRemoteVideo: (VideoTrack) -> Unit
    ) {
        val ctx: Context = getApplication<Application>().applicationContext
        _streamError.value = null
        _streamingState.value = StreamingState.Signaling
        _role.value = role

        viewModelScope.launch(Dispatchers.Default) {
            try {
                // pick the correct websocket from args or state (populated by /help)
                val socketUrl = wsUrl
                    ?: when (role) {
                        StreamRole.VICTIM -> _broadcasterWs.value
                        StreamRole.HELPER -> _viewerWs.value
                    }
                    ?: throw IllegalStateException("No websocket URL for role=$role (room=$roomId)")

                Log.d("DU/RTC", "startStreaming → role=$role room=$roomId ws=$socketUrl")

                cameraProvider.startStream(
                    wsUrl = socketUrl,
                    context = ctx,
                    roomId = roomId,
                    role = role,
                    onRemoteVideo = onRemoteVideo
                )

                withContext(Dispatchers.Main) {
                    _streamingState.value = StreamingState.Streaming
                    _isSignalingReady.value = true
                }
            } catch (t: Throwable) {
                Log.w("DU/RTC", "startStreaming error: ${t.message}", t)
                withContext(Dispatchers.Main) {
                    _streamError.value = t.message
                    _streamingState.value = StreamingState.Idle
                }
            }
        }
    }


    fun stopStreaming() {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                cameraProvider.stopStream()
            } catch (t: Throwable) {
                Log.w("DU/RTC", "stopStream error: ${t.message}")
            } finally {
                withContext(Dispatchers.Main) { _streamingState.value = StreamingState.Idle }
            }
        }
    }

    fun isStreaming(): Boolean = cameraProvider.isStreamActive()

    // --- Helpers ---------------------------------------------------------------
    private fun teardownSessionState() {
        _roomId.value = null
        _broadcasterWs.value = null
        _viewerWs.value = null
        _incomingRemoteVideoTrack.value = null
        _isHelpSessionActive.value = false
        _isSignalingReady.value = false
        _streamingState.value = StreamingState.Idle
        _sessionStatus.value = "closed"
        _helperName.value = null
        _requestingUser.value = null
    }

    fun clearHelpRequest() { _helpRequest.value = null }
    fun resetHelperName() { _helperName.value = null }

    override fun onCleared() {
        super.onCleared()
        try { cameraProvider.stopStream() } catch (_: Throwable) {}
    }
}
