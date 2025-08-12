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

    private var signalingSocket: SignalingSocket? = null
    private var webRTCClient: WebRTCClient? = null

    private var remoteVideoCallback: ((VideoTrack) -> Unit)? = null

    private val TAG_WS = "DU/WS"
    private val TAG_SDP = "DU/SDP"
    private val TAG_ICE = "DU/ICE"
    private val TAG_RTC = "DU/RTC"
    private val TAG_LIFE = "DU/LIFECYCLE"

    /**
     * Start signaling/RTC for the given role.
     * Victim: starts local capture, creates OFFER, sends to server.
     * Helper: waits for OFFER, sets remote, creates ANSWER, sends to server (no local capture).
     */
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

        Log.d(TAG_LIFE, "Starting stream role=$role room=$roomId")
        Log.d(TAG_WS, "WS URL: $wsUrl")

        // Prepare RTC first so WS callbacks can use it immediately.
        webRTCClient = createRtcClient(context)

        signalingSocket = SignalingSocket(wsUrl, createSocketListener()).also {
            Log.d(TAG_WS, "Connecting WS…")
            it.connect()
        }
    }

    /** Stop and fully release resources (idempotent). */
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
        Log.d(TAG_LIFE, "Stream resources released")
    }

    fun isStreamActive(): Boolean = started.get() && signalingSocket != null && webRTCClient != null

    /** Victim-only: UI passes its SurfaceViewRenderer via VM → provider. */
    fun setLocalPreviewSink(sink: org.webrtc.VideoSink?) {
        webRTCClient?.setLocalPreviewSink(sink)
    }

    // -------------------- Internals --------------------

    private fun createRtcClient(appContext: Context): WebRTCClient {
        val t0 = SystemClock.elapsedRealtime()
        Log.d(TAG_RTC, "Init WebRTCClient role=$role")

        val client = WebRTCClient(appContext, object : WebRTCClient.SignalingCallback {
            override fun onLocalSDPGenerated(sdp: SessionDescription) {
                val rid = roomId ?: return
                if (role == StreamRole.VICTIM) {
                    Log.d(TAG_SDP, "Local OFFER → send room=$rid")
                    signalingSocket?.sendOffer(sdp.description, rid)
                } else {
                    Log.d(TAG_SDP, "Local ANSWER → send room=$rid")
                    signalingSocket?.sendAnswer(sdp.description, rid)
                }
            }

            override fun onIceCandidateFound(candidate: IceCandidate) {
                val rid = roomId ?: return
                Log.d(TAG_ICE, "Local ICE → send room=$rid")
                // Your SignalingSocket takes only the SDP string + roomId (no mid/index).
                signalingSocket?.sendCandidate(candidate.sdp, rid)
            }

            override fun onRemoteVideoTrackReceived(track: VideoTrack) {
                Log.d(TAG_RTC, "Remote track received → UI")
                remoteVideoCallback?.invoke(track)
            }
        })

        // Helper acts as ANSWERER; Victim as OFFERER
        client.initialize(answerMode = (role == StreamRole.HELPER))

        if (role == StreamRole.VICTIM) {
            Log.d(TAG_RTC, "Victim: start local camera")
            client.startLocalVideo(appContext)
        } else {
            Log.d(TAG_RTC, "Helper: no local capture")
        }

        Log.d(TAG_RTC, "WebRTC ready in ${SystemClock.elapsedRealtime() - t0}ms")
        return client
    }

    private fun createSocketListener(): SignalingSocket.Listener = object : SignalingSocket.Listener {

        @WorkerThread
        override fun onSocketOpened() {
            Log.d(TAG_WS, "WS opened role=$role room=$roomId")
            // Victim will createOffer after local video starts; Helper waits for Offer.
        }

        @WorkerThread
        override fun onOfferReceived(sdp: String) {
            Log.d(TAG_SDP, "Offer received (${sdp.length}) role=$role")
            // Helper: set remote → create/send Answer inside WebRTCClient
            try { webRTCClient?.setRemoteSDP(sdp) }
            catch (t: Throwable) { Log.w(TAG_SDP, "setRemoteSDP(offer) failed: ${t.message}") }
        }

        @WorkerThread
        override fun onAnswerReceived(sdp: String) {
            Log.d(TAG_SDP, "Answer received (${sdp.length}) role=$role")
            // Victim: set remote Answer
            try { webRTCClient?.setRemoteSDP(sdp) }
            catch (t: Throwable) { Log.w(TAG_SDP, "setRemoteSDP(answer) failed: ${t.message}") }
        }

        @WorkerThread
        override fun onIceCandidateReceived(candidate: String) {
            Log.d(TAG_ICE, "Remote ICE received")
            try { webRTCClient?.addRemoteIceCandidate(candidate) }
            catch (t: Throwable) { Log.w(TAG_ICE, "addRemoteIceCandidate failed: ${t.message}") }
        }

        @WorkerThread
        override fun onSocketClosed() {
            Log.d(TAG_WS, "WS closed role=$role room=$roomId")
            // VM decides on retry; provider keeps state consistent.
        }
    }
}
