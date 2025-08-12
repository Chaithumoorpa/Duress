package com.techm.duress.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.techm.duress.core.camera.CameraServiceProvider
import com.techm.duress.core.webrtc.SignalingSocket
import com.techm.duress.core.webrtc.WebRTCClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class HelpRequest(val name: String, val zone: String)

enum class StreamingState {
    Idle, Previewing, Signaling, Streaming
}


class MainViewModel(application: Application) : AndroidViewModel(application) {


    // IMPORTANT: Replace with your Go server's IP address and port
    var baseUrl: String = "http://10.246.34.245:8080/duress"

    var userName: String = ""
        private set

    private val _helpRequest = MutableStateFlow<HelpRequest?>(null)
    val helpRequest: StateFlow<HelpRequest?> = _helpRequest

    private val _helperName = MutableStateFlow<String?>(null)
    val helperName: StateFlow<String?> = _helperName

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    var requestingUser: HelpRequest? = null
        private set

    var roomId: String? = null
    var streamId: String? = null
    var broadcasterWs: String? = null
    var viewerWebsocketUrl: String? = null

    private val _incomingVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val incomingVideoTrack: StateFlow<VideoTrack?> = _incomingVideoTrack

    private val _isHelpSessionActive = MutableStateFlow(false)
    val isHelpSessionActive: StateFlow<Boolean> = _isHelpSessionActive

    private val _helpRequested = MutableStateFlow(false)
    val helpRequested: StateFlow<Boolean> = _helpRequested

    private val _streamError = MutableStateFlow<String?>(null)
    val streamError: StateFlow<String?> = _streamError

    private val _isSignalingReady = MutableStateFlow(false)
    val isSignalingReady: StateFlow<Boolean> = _isSignalingReady

    private var signalingSocket: SignalingSocket? = null
    private var webRTCClient: WebRTCClient? = null

    private var viewerSocket: SignalingSocket? = null
    private var viewerWebRTCClient: WebRTCClient? = null

    private val _streamingState = MutableStateFlow(StreamingState.Idle)
    val streamingState: StateFlow<StreamingState> = _streamingState

    fun setStreamingState(state: StreamingState) {
        _streamingState.value = state
    }

    fun setIncomingVideoTrack(track: VideoTrack) {
        _incomingVideoTrack.value = track
    }


    fun setUserInfo(name: String) {
        userName = name
    }

    fun setRequestingUser(user: HelpRequest) {
        requestingUser = user
    }

    fun sendHelpRequest(currentUserZone: String, onComplete: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/help")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true

                val json = JSONObject().apply {
                    put("name", userName)
                    put("zone", currentUserZone)
                    put("mobile", "123456789") // Placeholder, ideally from user input
                }

                OutputStreamWriter(conn.outputStream).use { writer ->
                    writer.write(json.toString())
                    writer.flush()
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = conn.inputStream.bufferedReader().readText()
                    Log.d("HelpRequest Response", response)

                    val jsonResponse = JSONObject(response)
                    roomId = jsonResponse.optString("roomId")
                    streamId = jsonResponse.optString("streamId")
                    broadcasterWs = jsonResponse.optString("broadcasterWs")
                    viewerWebsocketUrl = jsonResponse.optString("viewerWebsocketUrl")

                    Log.d("DuressMeta", "roomId=$roomId, streamId=$streamId")
                    Log.d("DuressMeta", "broadcasterWs=$broadcasterWs")
                    Log.d("DuressMeta", "viewerWebsocketUrl=$viewerWebsocketUrl")
                    withContext(Dispatchers.Main) {
                        _helpRequested.value = true
                        onComplete()
                    }
                } else {
                    Log.e("HelpRequest", "Error: ${conn.responseCode} - ${conn.responseMessage}")
                }

                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    fun checkForHelpRequest(currentUserName: String, currentUserZone: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/listen")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/json")

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = conn.inputStream.bufferedReader().readText()
                    if (response.isNotEmpty() && response!= "null") {
                        val json = JSONObject(response)
                        val name = json.optString("name")
                        val zone = json.optString("zone")
                        val statusValue = json.optString("status")

                        if (statusValue == "closed") {
                            _helpRequest.value = null
                        } else if (name!= currentUserName) { // Only show requests from others
                            _helpRequest.value = HelpRequest(name, zone)
                        } else {
                            _helpRequest.value = null
                        }
                    } else {
                        _helpRequest.value = null
                    }
                } else {
                    Log.e("CheckHelpRequest", "Error: ${conn.responseCode} - ${conn.responseMessage}")
                }
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun sendGiveHelpRequest(name: String, helper: String, onSuccess: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/give_help")
                val postData = "name=${URLEncoder.encode(name, "UTF-8")}&helper=${URLEncoder.encode(helper, "UTF-8")}"
                val conn = url.openConnection() as HttpURLConnection

                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true

                conn.outputStream.use {
                    it.write(postData.toByteArray(Charsets.UTF_8))
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    Log.d("GiveHelpRequest", "Response: $response")
                    if (json.optString("status") == "success") {
                        withContext(Dispatchers.Main) { onSuccess() }
                    }
                } else {
                    Log.e("GiveHelpRequest", "Error: ${conn.responseCode} - ${conn.responseMessage}")
                }

                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun listenForHelper(userName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/listen_for_helper")
                val postData = "name=${URLEncoder.encode(userName, "UTF-8")}"
                val conn = url.openConnection() as HttpURLConnection

                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true

                conn.outputStream.use {
                    it.write(postData.toByteArray(Charsets.UTF_8))
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = conn.inputStream.bufferedReader().readText()
                    if (response.isNotEmpty() && response!= "null") {
                        val json = JSONObject(response)
                        val helper = json.optString("helper")
                        val statusValue = json.optString("status")

                        _status.value = statusValue
                        _isHelpSessionActive.value = statusValue == "open" // Correctly reflects active session

                        if (statusValue == "closed") {
                            _helperName.value = null
                        } else {
                            _helperName.value = helper
                        }

                        Log.d("HelperInfo", "Helper: $helper, Status: $statusValue")
                    }
                } else {
                    Log.e("ListenForHelper", "Error: ${conn.responseCode} - ${conn.responseMessage}")
                }

                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun sendHelpCompleted(name: String, helper: String, onSuccess: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val url = URL("$baseUrl/help_completed")
                val postData = "name=${URLEncoder.encode(name, "UTF-8")}&helper=${URLEncoder.encode(helper, "UTF-8")}"
                val conn = url.openConnection() as HttpURLConnection

                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.setRequestProperty("Accept", "application/json")
                conn.doOutput = true

                conn.outputStream.use {
                    it.write(postData.toByteArray(Charsets.UTF_8))
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val response = conn.inputStream.bufferedReader().readText()
                    val json = JSONObject(response)
                    if (json.optString("status") == "success") {
                        withContext(Dispatchers.Main) {
                            onSuccess()
                            roomId = null
                            streamId = null
                            broadcasterWs = null
                            viewerWebsocketUrl = null
                            _isHelpSessionActive.value = false
                            _incomingVideoTrack.value = null // Clear video track on completion
                            _streamingState.value = StreamingState.Idle
                        }
                    }
                } else {
                    Log.e("HelpCompleted", "Error: ${conn.responseCode} - ${conn.responseMessage}")
                }

                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private var cameraServiceProvider: CameraServiceProvider? = null

    fun startStreaming(
        wsUrl: String,
        roomId: String,
        onRemoteVideo: (VideoTrack) -> Unit
    ) {
        val context = getApplication<Application>().applicationContext
        cameraServiceProvider = CameraServiceProvider()
        viewModelScope.launch {
            cameraServiceProvider?.startStream(wsUrl, context, roomId, onRemoteVideo)
        }
    }

    fun stopStreaming() {
        viewModelScope.launch {
            cameraServiceProvider?.stopStream()
            cameraServiceProvider = null
        }
    }

    fun isStreaming(): Boolean {
        return cameraServiceProvider?.isStreamActive() == true
    }

    fun initBroadcasterSignaling(context: Context, wsUrl: String, roomId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d("MainViewModel", "Broadcaster: Initializing WebRTC client and signaling socket.")

            val socket = SignalingSocket(wsUrl, object : SignalingSocket.Listener {
                override fun onAnswerReceived(sdp: String) {
                    Log.d("MainViewModel", "Broadcaster: Received answer from helper.")
                    webRTCClient?.setRemoteSDP(sdp)
                    _isSignalingReady.value = true
                }

                override fun onIceCandidateReceived(candidate: String) {
                    Log.d("MainViewModel", "Broadcaster: Received ICE candidate from helper.")
                    webRTCClient?.addRemoteIceCandidate(candidate)
                }

                override fun onSocketOpened() {
                    Log.d("MainViewModel", "Broadcaster: Signaling socket opened. Initializing WebRTC client.")

                    val client = WebRTCClient(context, object : WebRTCClient.SignalingCallback {
                        override fun onLocalSDPGenerated(sdp: SessionDescription) {
                            Log.d("MainViewModel", "Broadcaster: Generated local offer, sending to server.")
                            signalingSocket?.sendOffer(sdp.description, roomId)
                        }

                        override fun onIceCandidateFound(c: IceCandidate) {
                            Log.d("MainViewModel", "Broadcaster: Found local ICE candidate, sending to server.")
                            signalingSocket?.sendCandidate(c.sdp, roomId)
                        }

                        override fun onRemoteVideoTrackReceived(track: VideoTrack) {
                            Log.d("MainViewModel", "Broadcaster: Received remote video track.")
                            // Optional: handle preview or feedback
                        }
                    })

                    client.initialize()
                    webRTCClient = client
                    client.startLocalVideo(context)
                }

                override fun onOfferReceived(sdp: String) {
                    Log.d("MainViewModel", "Broadcaster: Unexpected offer received.")
                }

                override fun onSocketClosed() {
                    Log.d("MainViewModel", "Broadcaster Socket Disconnected")
                    releaseWebRTC()
                }
            })

            socket.connect()
            signalingSocket = socket
        }
    }

    fun releaseWebRTC() {
        webRTCClient?.let {
            try {
                it.release()
                Log.d("MainViewModel", "WebRTCClient released successfully.")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error releasing WebRTCClient: ${e.message}", e)
            }
        }
        webRTCClient = null
        signalingSocket?.disconnect()
        signalingSocket = null
        _isSignalingReady.value = false
    }

    fun initViewerSignaling(context: Context, wsUrl: String, roomId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d("MainViewModel", "Helper: Initializing WebRTC client and signaling socket.")

            val socket = SignalingSocket(wsUrl, object : SignalingSocket.Listener {
                override fun onOfferReceived(sdp: String) {
                    Log.d("MainViewModel", "Helper: Received offer from broadcaster. Creating answer.")

                    val client = WebRTCClient(context, object : WebRTCClient.SignalingCallback {
                        override fun onLocalSDPGenerated(sdp: SessionDescription) {
                            Log.d("MainViewModel", "Helper: Generated local answer, sending to server.")
                            viewerSocket?.sendAnswer(sdp.description, roomId)
                        }

                        override fun onIceCandidateFound(c: IceCandidate) {
                            Log.d("MainViewModel", "Helper: Found local ICE candidate, sending to server.")
                            viewerSocket?.sendCandidate(c.sdp, roomId)
                        }

                        override fun onRemoteVideoTrackReceived(track: VideoTrack) {
                            Log.d("MainViewModel", "Helper: Received remote video track from broadcaster.")
                            setIncomingVideoTrack(track)
                        }
                    })

                    client.initialize(answerMode = true)
                    client.setRemoteSDP(sdp)
                    viewerWebRTCClient = client
                }

                override fun onIceCandidateReceived(candidate: String) {
                    Log.d("MainViewModel", "Helper: Received ICE candidate from broadcaster.")
                    viewerWebRTCClient?.addRemoteIceCandidate(candidate)
                }

                override fun onSocketOpened() {
                    Log.d("MainViewModel", "Helper: Signaling socket opened.")
                }

                override fun onAnswerReceived(sdp: String) {
                    Log.d("MainViewModel", "Helper: Unexpected answer received.")
                }

                override fun onSocketClosed() {
                    Log.d("MainViewModel", "Viewer Socket Disconnected")
                    releaseViewerWebRTC()
                }
            })

            socket.connect()
            viewerSocket = socket
        }
    }

    fun releaseViewerWebRTC() {
        viewerWebRTCClient?.let {
            try {
                it.release()
                Log.d("MainViewModel", "Viewer WebRTCClient released successfully.")
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error releasing Viewer WebRTCClient: ${e.message}", e)
            }
        }
        viewerWebRTCClient = null
        viewerSocket?.disconnect()
        viewerSocket = null
    }


    fun clearHelpRequest() {
        _helpRequest.value = null
    }

    fun resetHelperName() {
        _helperName.value = null
    }
}
