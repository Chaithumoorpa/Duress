package com.techm.duress.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.techm.duress.core.camera.CameraServiceProvider
import com.techm.duress.core.camera.CameraServiceProvider.StreamRole
import com.techm.duress.core.webrtc.WebRTCClient
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

// ---------------------------- Data ----------------------------

data class HelpRequest(val name: String, val zone: String)

enum class StreamingState { Idle, Signaling, Streaming }

// ------------------------ ViewModel ---------------------------

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // --- Config ---------------------------------------------------------------
    var baseUrl: String = "http://192.168.0.101:8080/duress" // TODO: inject/env for prod

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

    private val _helperName = MutableStateFlow<String?>(null) // who’s helping me (Victim path)
    val helperName: StateFlow<String?> = _helperName

    private val _helpRequest = MutableStateFlow<HelpRequest?>(null) // incoming open request I can accept
    val helpRequest: StateFlow<HelpRequest?> = _helpRequest

    private val _sessionStatus = MutableStateFlow<String?>(null)   // "open" | "taken" | "closed"
    val sessionStatus: StateFlow<String?> = _sessionStatus

    private val _isHelpSessionActive = MutableStateFlow(false)
    val isHelpSessionActive: StateFlow<Boolean> = _isHelpSessionActive

    private val _isSignalingReady = MutableStateFlow(false)
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

    private var webrtcClient: WebRTCClient? = null

    // AFTER (correct target: the provider that owns the live WebRTC client)
    fun attachLocalRenderer(renderer: SurfaceViewRenderer?) {
        try {
            cameraProvider.setLocalPreviewSink(renderer)
            Log.d("DU/VM", "Local preview renderer attached to CameraServiceProvider")
        } catch (e: Exception) {
            Log.e("DU/VM", "Error attaching local renderer: ${e.message}", e)
        }
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

    // ---------------- Victim: create duress (JSON) ----------------------------
    fun sendHelpRequest(
        currentUserZone: String,
        mobile: String = "123456789",
        onCreated: (roomId: String, broadcasterWs: String, viewerWs: String) -> Unit = { _, _, _ -> }
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val tag = "DU/HTTP"
            try {
                val url = URL("$baseUrl/help")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                }

                val json = JSONObject().apply {
                    put("name", _userName.value)
                    put("zone", currentUserZone)
                    put("mobile", mobile)
                }
                Log.d(tag, "-> POST /help (json) $json")
                OutputStreamWriter(conn.outputStream).use { it.write(json.toString()) }

                val code = conn.responseCode
                val body = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } catch (_: Throwable) {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                Log.d(tag, "<- $code /help body=${if (body.isBlank()) "<empty>" else body}")

                if (code == HttpURLConnection.HTTP_OK) {
                    val js = JSONObject(body)

                    val room = js.optString("roomId").orEmpty()
                    val bws  = js.optString("broadcasterWs").orEmpty()
                    val vws  = js.optString("viewerWebsocketUrl").orEmpty()

                    if (room.isNotBlank() && bws.isNotBlank() && vws.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            _roomId.value = room
                            _broadcasterWs.value = bws
                            _viewerWs.value = vws
                            _isHelpSessionActive.value = true
                            _sessionStatus.value = "open"
                            _isSignalingReady.value = false

                            // keep a local copy so victim can call /help_completed
                            _requestingUser.value = HelpRequest(_userName.value, currentUserZone)

                            Log.d("DU/HOME", "Help created roomId=$room bWS=$bws vWS=$vws")
                            onCreated(room, bws, vws)
                        }
                    } else {
                        Log.e(tag, "Missing fields in /help response: roomId='$room', bWS='$bws', vWS='$vws'")
                    }
                } else {
                    Log.e(tag, "help: $code ${conn.responseMessage}")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(tag, "help failed: ${e.message}", e)
            }
        }
    }

    // ---------------- Helper: poll for open requests --------------------------
    // Also captures roomId + websocket URLs if the server includes them.
    fun checkForHelpRequest(currentUserName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val tag = "DU/HTTP"
            try {
                val url = URL("$baseUrl/listen")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                }

                val code = conn.responseCode
                val body = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } catch (_: Throwable) {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                Log.d(tag, "<- $code /listen body=${if (body.isBlank()) "<empty>" else body}")

                if (code == HttpURLConnection.HTTP_OK && body.isNotBlank() && body != "null") {
                    val js = JSONObject(body)
                    val victim = js.optString("name").orEmpty()
                    val zone   = js.optString("zone").orEmpty()

                    // Optional fields (present after your server refactor)
                    val room = js.optString("roomId").orEmpty()
                    val vws  = js.optString("viewerWebsocketUrl").orEmpty()
                    val bws  = js.optString("broadcasterWs").orEmpty()

                    withContext(Dispatchers.Main) {
                        if (victim.isNotBlank() && victim != currentUserName) {
                            _helpRequest.value = HelpRequest(victim, zone)
                            Log.d("DU/HOME", "Incoming request from $victim in $zone")

                            // If server gave us session info, store it so helper can auto-join later.
                            if (room.isNotBlank()) _roomId.value = room
                            if (vws.isNotBlank()) _viewerWs.value = vws
                            if (bws.isNotBlank()) _broadcasterWs.value = bws
                        } else {
                            _helpRequest.value = null
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) { _helpRequest.value = null }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(tag, "listen failed: ${e.message}", e)
            }
        }
    }

    // --------------- Helper: accept request (form-encoded) --------------------
    fun sendGiveHelpRequest(victimName: String, helper: String, onSuccess: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val tag = "DU/HTTP"
            try {
                val url = URL("$baseUrl/give_help")
                val post = "name=${URLEncoder.encode(victimName, "UTF-8")}" +
                        "&helper=${URLEncoder.encode(helper, "UTF-8")}"
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                }
                Log.d(tag, "-> POST /give_help body=$post")
                conn.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val body = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } catch (_: Throwable) {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                Log.d(tag, "<- $code /give_help body=${if (body.isBlank()) "<empty>" else body}")

                if (code == HttpURLConnection.HTTP_OK) {
                    val js = JSONObject(body)
                    if (js.optString("status") == "success") {
                        withContext(Dispatchers.Main) { onSuccess() }
                    } else {
                        Log.w(tag, "give_help unexpected: $body")
                    }
                } else {
                    Log.e(tag, "give_help: $code ${conn.responseMessage}")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(tag, "give_help failed: ${e.message}", e)
            }
        }
    }

    // --------------- Victim: wait for helper assignment (form) ----------------
    // Also captures roomId + websocket URLs if the server includes them.
    fun listenForHelper(victimName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val tag = "DU/HTTP"
            try {
                val url = URL("$baseUrl/listen_for_helper")
                val post = "name=${URLEncoder.encode(victimName, "UTF-8")}"
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                }
                Log.d(tag, "-> POST /listen_for_helper body=$post")
                conn.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val body = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } catch (_: Throwable) {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                Log.d(tag, "<- $code /listen_for_helper body=${if (body.isBlank()) "<empty>" else body}")

                if (code == HttpURLConnection.HTTP_OK && body.isNotBlank() && body != "null") {
                    val js = JSONObject(body)
                    val helper = js.optString("helper").orEmpty()
                    val statusValue = js.optString("status").orEmpty()

                    // Optional fields (present after your server refactor)
                    val room = js.optString("roomId").orEmpty()
                    val vws  = js.optString("viewerWebsocketUrl").orEmpty()
                    val bws  = js.optString("broadcasterWs").orEmpty()

                    withContext(Dispatchers.Main) {
                        if (room.isNotBlank()) _roomId.value = room
                        if (vws.isNotBlank()) _viewerWs.value = vws
                        if (bws.isNotBlank()) _broadcasterWs.value = bws

                        if (helper.isNotBlank() && (statusValue.equals("open", true) || statusValue.equals("taken", true))) {
                            _helperName.value = helper
                            _isHelpSessionActive.value = true
                            _isSignalingReady.value = true
                            _sessionStatus.value = "open"   // treat 'taken' as active session
                            Log.d(tag, "listen_for_helper → helper=$helper, status=$statusValue")
                        } else if (statusValue.equals("closed", true) && helper.isBlank()) {
                            // Only close if no helper is present
                            _helperName.value = null
                            _sessionStatus.value = "closed"
                            _isHelpSessionActive.value = false
                            _isSignalingReady.value = false
                            Log.d(tag, "listen_for_helper → closed (no helper)")
                        } else {
                            Log.d(tag, "listen_for_helper → no change (helper='$helper', status='$statusValue')")
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(tag, "listen_for_helper failed: ${e.message}", e)
            }
        }
    }

    // ---------------- Either role: finish session (form) ----------------------
    fun sendHelpCompleted(victimName: String, helper: String, onSuccess: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val tag = "DU/HTTP"
            try {
                val url = URL("$baseUrl/help_completed")
                val post = "name=${URLEncoder.encode(victimName, "UTF-8")}" +
                        "&helper=${URLEncoder.encode(helper, "UTF-8")}"
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                }
                Log.d(tag, "-> POST /help_completed body=$post")
                conn.outputStream.use { it.write(post.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val body = try {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } catch (_: Throwable) {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                Log.d(tag, "<- $code /help_completed body=${if (body.isBlank()) "<empty>" else body}}")

                if (code == HttpURLConnection.HTTP_OK) {
                    val js = JSONObject(body)
                    if (js.optString("status") == "success") {
                        withContext(Dispatchers.Main) { onSuccess(); teardownSessionState() }
                    } else {
                        Log.w(tag, "help_completed unexpected: $body")
                    }
                } else {
                    Log.e(tag, "help_completed: $code ${conn.responseMessage}")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(tag, "help_completed failed: ${e.message}", e)
            }
        }
    }

    // ---------------- Streaming control (both roles) --------------------------
    fun startStreaming(
        wsUrl: String?,
        roomId: String,
        role: StreamRole,
        onRemoteVideo: (VideoTrack) -> Unit
    ) {
        val ctx: Context = getApplication<Application>().applicationContext
        _streamError.value = null
        _streamingState.value = StreamingState.Signaling
        _role.value = role

        viewModelScope.launch(Dispatchers.Default) {
            val tag = "DU/RTC"
            try {
                val socketUrl = wsUrl ?: when (role) {
                    StreamRole.VICTIM -> _broadcasterWs.value
                    StreamRole.HELPER -> _viewerWs.value
                } ?: throw IllegalStateException("Missing WebSocket URL for role=$role")

                require(roomId.isNotBlank()) { "Room ID missing" }

                cameraProvider.startStream(
                    wsUrl = socketUrl,
                    context = ctx,
                    roomId = roomId,
                    role = role,
                    onRemoteVideo = { track ->
                        setIncomingVideoTrack(track)
                        onRemoteVideo(track)
                    }
                )

                withContext(Dispatchers.Main) {
                    _streamingState.value = StreamingState.Streaming
                    _isSignalingReady.value = true
                }
            } catch (t: Throwable) {
                Log.e(tag, "startStreaming error", t)
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
                Log.e("DU/RTC", "stopStreaming error", t)
            } finally {
                withContext(Dispatchers.Main) {
                    _streamingState.value = StreamingState.Idle
                }
            }
        }
    }

    // MainViewModel.kt  (add)
    fun flipCamera() {
        try { cameraProvider.flipCamera() } catch (_: Throwable) {}
    }


    // ---------------- Helpers ---------------------------------
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
        Log.d("DU/HOME", "Session teardown complete")
    }




    fun clearHelpRequest() { _helpRequest.value = null }
    fun resetHelperName() { _helperName.value = null }

    fun isStreaming(): Boolean = cameraProvider.isStreamActive()

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch(Dispatchers.Default) {
            try { cameraProvider.stopStream() } catch (_: Throwable) {}
        }
    }
}
