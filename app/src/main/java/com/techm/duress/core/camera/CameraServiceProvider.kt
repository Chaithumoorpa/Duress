package com.techm.duress.core.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.techm.duress.core.webrtc.SignalingSocket
import com.techm.duress.core.webrtc.WebRTCClient
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import java.util.concurrent.atomic.AtomicBoolean

class CameraServiceProvider {

    enum class StreamRole { VICTIM, HELPER }

    private val started = AtomicBoolean(false)

    @Volatile private var role: StreamRole = StreamRole.VICTIM
    @Volatile private var roomId: String? = null
    @Volatile private var appContext: Context? = null

    private var signalingSocket: SignalingSocket? = null
    private var webRTCClient: WebRTCClient? = null

    private var remoteVideoCallback: ((VideoTrack) -> Unit)? = null
    private var localPreviewSink: VideoSink? = null
    private var localVideoTrack: VideoTrack? = null

    private val TAG_WS = "DU/WS"
    private val TAG_SDP = "DU/SDP"
    private val TAG_ICE = "DU/ICE"
    private val TAG_RTC = "DU/RTC"
    private val TAG_LIFE = "DU/LIFECYCLE"

    /** Public API **/
    @MainThread
    fun startStream(
        wsUrl: String,
        context: Context,
        roomId: String,
        role: StreamRole,
        onRemoteVideo: (VideoTrack) -> Unit
    ) {
        if (!started.compareAndSet(false, true)) {
            Log.d(TAG_LIFE, "startStream ignored; already active")
            return
        }

        this.role = role
        this.roomId = roomId
        this.remoteVideoCallback = onRemoteVideo
        this.appContext = context.applicationContext

        Log.d(TAG_LIFE, "Starting stream → role=$role room=$roomId")
        Log.d(TAG_WS, "WS URL: $wsUrl")

        // Create WebRTC client & connect WebSocket
        webRTCClient = createRtcClient(appContext!!)
        signalingSocket = SignalingSocket(wsUrl, createSocketListener()).apply {
            Log.d(TAG_WS, "Connecting WebSocket…")
            connect()
        }
    }

    @MainThread
    fun stopStream() {
        if (!started.compareAndSet(true, false)) {
            Log.d(TAG_LIFE, "stopStream ignored; not active")
            return
        }
        val rid = roomId
        Log.d(TAG_LIFE, "Stopping stream → role=$role room=$rid")

        signalingSocket?.runCatching { disconnect() }
        webRTCClient?.runCatching { release() }

        signalingSocket = null
        webRTCClient = null
        roomId = null
        remoteVideoCallback = null
        localPreviewSink = null
        localVideoTrack = null
        appContext = null

        Log.d(TAG_LIFE, "Stream resources released")
    }

    fun isStreamActive(): Boolean =
        started.get() && signalingSocket != null && webRTCClient != null

    /**
     * Victim self-preview setup — can be called anytime before or after local track creation.
     */
    fun setLocalPreviewSink(sink: VideoSink?) {
        localPreviewSink = sink
        localVideoTrack?.let { track ->
            runCatching { sink?.let { track.addSink(it) } }
        }
    }

    /** Internal helpers **/
    private fun createRtcClient(appContext: Context): WebRTCClient {
        val t0 = SystemClock.elapsedRealtime()
        Log.d(TAG_RTC, "Init WebRTCClient role=$role")

        val client = WebRTCClient(appContext, object : WebRTCClient.SignalingCallback {

            override fun onLocalSDPGenerated(sdp: SessionDescription) {
                val rid = roomId ?: return
                if (role == StreamRole.VICTIM) {
                    Log.d(TAG_SDP, "Local OFFER → send to room=$rid")
                    signalingSocket?.sendOffer(sdp.description, rid)
                } else {
                    Log.d(TAG_SDP, "Local ANSWER → send to room=$rid")
                    signalingSocket?.sendAnswer(sdp.description, rid)
                }
            }

            override fun onIceCandidateFound(candidate: IceCandidate) {
                val rid = roomId ?: return
                val payload = JSONObject()
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                Log.d(TAG_ICE, "Local ICE → send (mid=${candidate.sdpMid}, idx=${candidate.sdpMLineIndex})")
                signalingSocket?.sendCandidate(payload.toString(), rid)
            }

            override fun onRemoteVideoTrackReceived(track: VideoTrack) {
                Log.d(TAG_RTC, "Remote track received → dispatch to UI")
                remoteVideoCallback?.invoke(track)
            }

            override fun onLocalVideoTrackCreated(track: VideoTrack) {
                Log.d(TAG_RTC, "Local video track created — attaching preview sink")
                localVideoTrack = track
                localPreviewSink?.let { sink ->
                    runCatching { track.addSink(sink) }
                }
            }
        })

        client.initialize(answerMode = (role == StreamRole.HELPER))
        Log.d(TAG_RTC, "WebRTC ready in ${SystemClock.elapsedRealtime() - t0}ms")
        return client
    }

    private fun createSocketListener(): SignalingSocket.Listener = object : SignalingSocket.Listener {

        @WorkerThread
        override fun onSocketOpened() {
            Log.d(TAG_WS, "WebSocket opened → role=$role room=$roomId")
            if (role == StreamRole.VICTIM) {
                appContext?.let { ctx ->
                    runCatching {
                        Log.d(TAG_RTC, "Starting local camera (Victim) + sending OFFER")
                        webRTCClient?.startLocalVideo(ctx)
                    }.onFailure {
                        Log.w(TAG_RTC, "Failed to start local video: ${it.message}")
                    }
                } ?: Log.w(TAG_RTC, "App context null; cannot start local video")
            }
        }

        @WorkerThread
        override fun onOfferReceived(sdp: String) {
            Log.d(TAG_SDP, "Offer received → role=$role")
            runCatching {
                webRTCClient?.setRemoteSDP(sdp)
                if (role == StreamRole.HELPER) {
                    Log.d(TAG_SDP, "Creating ANSWER in response to offer…")
                    webRTCClient?.createAnswer()
                }
            }.onFailure {
                Log.w(TAG_SDP, "Failed to set remote SDP (offer): ${it.message}")
            }
        }

        @WorkerThread
        override fun onAnswerReceived(sdp: String) {
            Log.d(TAG_SDP, "Answer received → role=$role")
            runCatching { webRTCClient?.setRemoteSDP(sdp) }
                .onFailure { Log.w(TAG_SDP, "Failed to set remote SDP (answer): ${it.message}") }
        }

        @WorkerThread
        override fun onIceCandidateReceived(candidatePayload: String) {
            Log.d(TAG_ICE, "Remote ICE candidate received")
            runCatching {
                val js = runCatching { JSONObject(candidatePayload) }.getOrNull()
                if (js?.has("candidate") == true) {
                    val cand = js.getString("candidate")
                    val mid = js.optString("sdpMid", null)
                    val mline = js.optInt("sdpMLineIndex", 0)
                    webRTCClient?.addRemoteIceCandidate(mid, mline, cand)
                } else {
                    webRTCClient?.addRemoteIceCandidate(candidatePayload)
                }
            }.onFailure {
                Log.w(TAG_ICE, "Failed to add remote ICE: ${it.message}")
            }
        }

        @WorkerThread
        override fun onSocketClosed() {
            Log.d(TAG_WS, "WebSocket closed → role=$role room=$roomId")
        }
    }
}
