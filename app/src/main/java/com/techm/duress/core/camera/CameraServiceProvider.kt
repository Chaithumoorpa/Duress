package com.techm.duress.core.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import com.techm.duress.core.webrtc.SignalingSocket
import com.techm.duress.core.webrtc.WebRTCClient
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
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

    private val TAG_WS = "DU/WS"
    private val TAG_SDP = "DU/SDP"
    private val TAG_ICE = "DU/ICE"
    private val TAG_RTC = "DU/RTC"
    private val TAG_LIFE = "DU/LIFECYCLE"

    @MainThread
    fun startStream(
        wsUrl: String,
        context: Context,
        roomId: String,
        role: StreamRole,
        onRemoteVideo: (VideoTrack) -> Unit
    ) {
        if (!started.compareAndSet(false, true)) {
            Log.d(TAG_LIFE, "startStream ignored; already started")
            return
        }

        this.role = role
        this.roomId = roomId
        this.remoteVideoCallback = onRemoteVideo
        this.appContext = context.applicationContext

        Log.d(TAG_LIFE, "Starting stream role=$role room=$roomId")
        Log.d(TAG_WS, "WS URL: $wsUrl")

        // Prepare RTC BEFORE opening WS
        webRTCClient = createRtcClient(this.appContext!!)

        signalingSocket = SignalingSocket(wsUrl, createSocketListener()).also {
            Log.d(TAG_WS, "Connecting WS…")
            it.connect()
        }
    }

    @MainThread
    fun stopStream() {
        if (!started.compareAndSet(true, false)) {
            Log.d(TAG_LIFE, "stopStream ignored; not started")
            return
        }

        val rid = roomId
        Log.d(TAG_LIFE, "Stopping stream role=$role room=$rid")

        try { signalingSocket?.disconnect() } catch (t: Throwable) {
            Log.w(TAG_WS, "WS disconnect error: ${t.message}")
        } finally { signalingSocket = null }

        try { webRTCClient?.release() } catch (t: Throwable) {
            Log.w(TAG_RTC, "WebRTC release error: ${t.message}")
        } finally { webRTCClient = null }

        roomId = null
        remoteVideoCallback = null
        appContext = null

        Log.d(TAG_LIFE, "Stream resources released")
    }

    fun isStreamActive(): Boolean =
        started.get() && signalingSocket != null && webRTCClient != null

    fun setLocalPreviewSink(sink: org.webrtc.VideoSink?) {
        webRTCClient?.setLocalPreviewSink(sink)
    }

    private fun createRtcClient(appContext: Context): WebRTCClient {
        val t0 = SystemClock.elapsedRealtime()
        Log.d(TAG_RTC, "Init WebRTCClient role=$role")

        val client = WebRTCClient(appContext, object : WebRTCClient.SignalingCallback {
            override fun onLocalSDPGenerated(sdp: SessionDescription) {
                val rid = roomId ?: return
                // IMPORTANT: with SFU, both roles ANSWER the server
                Log.d(TAG_SDP, "Local ${sdp.type} → send ANSWER room=$rid")
                signalingSocket?.sendAnswer(sdp.description, rid)
            }

            override fun onIceCandidateFound(candidate: IceCandidate) {
                val rid = roomId ?: return
                Log.d(TAG_ICE, "Local ICE → send room=$rid (mid=${candidate.sdpMid}, idx=${candidate.sdpMLineIndex})")
                signalingSocket?.sendCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp, rid)
            }

            override fun onRemoteVideoTrackReceived(track: VideoTrack) {
                Log.d(TAG_RTC, "Remote track received → UI")
                remoteVideoCallback?.invoke(track)
            }
        })

        // With an SFU the server sends offers to everyone; clients answer.
        // Victim must SEND video, Helper RECV video.
        val sendVideo = (role == StreamRole.VICTIM)
        client.initialize(sendVideo = sendVideo, isAnswerer = true)

        if (sendVideo) {
            Log.d(TAG_RTC, "Victim: will start local camera AFTER WS opens")
        } else {
            Log.d(TAG_RTC, "Helper: view-only (no local capture)")
        }

        Log.d(TAG_RTC, "WebRTC ready in ${SystemClock.elapsedRealtime() - t0}ms")
        return client
    }

    private fun createSocketListener(): SignalingSocket.Listener = object : SignalingSocket.Listener {

        @WorkerThread
        override fun onSocketOpened() {
            Log.d(TAG_WS, "WS opened role=$role room=$roomId")
            if (role == StreamRole.VICTIM) {
                val ctx = appContext ?: run {
                    Log.w(TAG_RTC, "App context is null; cannot start local video")
                    return
                }
                try {
                    Log.d(TAG_RTC, "WS OPEN → startLocalVideo() (wait for server OFFER)")
                    webRTCClient?.startLocalVideo(ctx) // bind local track; DO NOT create offer
                } catch (t: Throwable) {
                    Log.w(TAG_RTC, "Failed to start local video after WS open: ${t.message}")
                }
            }
        }

        @WorkerThread
        override fun onOfferReceived(sdp: String) {
            Log.d(TAG_SDP, "Offer received (len=${sdp.length}) role=$role")
            try { webRTCClient?.setRemoteSDP(sdp) }  // will create/send ANSWER
            catch (t: Throwable) { Log.w(TAG_SDP, "setRemoteSDP(offer) failed: ${t.message}") }
        }

        @WorkerThread
        override fun onAnswerReceived(sdp: String) {
            Log.d(TAG_SDP, "Answer received (len=${sdp.length}) role=$role")
            try { webRTCClient?.setRemoteSDP(sdp) }  // not typical with SFU, but keep
            catch (t: Throwable) { Log.w(TAG_SDP, "setRemoteSDP(answer) failed: ${t.message}") }
        }

        @WorkerThread
        override fun onIceCandidateReceived(candidatePayload: String) {
            Log.d(TAG_ICE, "Remote ICE received")
            try {
                val js = try { org.json.JSONObject(candidatePayload) } catch (_: Throwable) { null }
                if (js != null && js.has("candidate")) {
                    val cand = js.optString("candidate")
                    val mid = js.optString("sdpMid", null)
                    val mline = if (js.has("sdpMLineIndex")) js.optInt("sdpMLineIndex", 0) else 0
                    webRTCClient?.addRemoteIceCandidate(mid, mline, cand)
                } else {
                    // Legacy: plain string
                    webRTCClient?.addRemoteIceCandidate(candidatePayload)
                }
            } catch (t: Throwable) {
                Log.w(TAG_ICE, "addRemoteIceCandidate failed: ${t.message}")
            }
        }

        @WorkerThread
        override fun onSocketClosed() {
            Log.d(TAG_WS, "WS closed role=$role room=$roomId")
        }
    }
}
