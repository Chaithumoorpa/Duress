package com.techm.duress.core.camera

import android.content.Context
import android.util.Log
import com.techm.duress.core.webrtc.SignalingSocket
import com.techm.duress.core.webrtc.WebRTCClient
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack


class CameraServiceProvider {

    private var signalingSocket: SignalingSocket? = null
    private var webRTCClient: WebRTCClient? = null

    private var currentRoomId: String? = null
    private var videoCallback: ((VideoTrack) -> Unit)? = null

    /**
     * Starts WebRTC streaming with signaling connection
     */
    fun startStream(
        wsUrl: String,
        context: Context,
        roomId: String,
        onRemoteVideo: (VideoTrack) -> Unit
    ) {
        Log.d("CameraServiceProvider", "Starting stream...")
        Log.d("CameraServiceProvider", "WebSocket URL: $wsUrl")
        Log.d("CameraServiceProvider", "Room ID: $roomId")

        currentRoomId = roomId
        videoCallback = onRemoteVideo

        signalingSocket = SignalingSocket(wsUrl, object : SignalingSocket.Listener {
            override fun onOfferReceived(sdp: String) {
                Log.d("CameraServiceProvider", "Offer received")
                webRTCClient?.setRemoteSDP(sdp)
            }

            override fun onAnswerReceived(sdp: String) {
                Log.d("CameraServiceProvider", "Answer received")
                webRTCClient?.setRemoteSDP(sdp)
            }

            override fun onIceCandidateReceived(candidate: String) {
                Log.d("CameraServiceProvider", "ICE candidate received")
                webRTCClient?.addRemoteIceCandidate(candidate)
            }

            override fun onSocketOpened() {
                Log.d("CameraServiceProvider", "WebSocket connection opened")
                Log.d("CameraServiceProvider", "Initializing WebRTC client")

                webRTCClient = WebRTCClient(context, object : WebRTCClient.SignalingCallback {
                    override fun onLocalSDPGenerated(sdp: SessionDescription) {
                        Log.d("CameraServiceProvider", "Sending SDP offer for room: $currentRoomId")
                        currentRoomId?.let {
                            signalingSocket?.sendOffer(sdp.description, it)
                        }
                    }

                    override fun onIceCandidateFound(candidate: IceCandidate) {
                        Log.d("CameraServiceProvider", "Sending ICE candidate for room: $currentRoomId")
                        currentRoomId?.let {
                            signalingSocket?.sendCandidate(candidate.sdp, it)
                        }
                    }

                    override fun onRemoteVideoTrackReceived(track: VideoTrack) {
                        Log.d("CameraServiceProvider", "Remote video track received")
                        videoCallback?.invoke(track)
                    }
                })

                webRTCClient?.initialize(answerMode = false)
                webRTCClient?.startLocalVideo(context)
            }

            override fun onSocketClosed() {
                Log.d("CameraServiceProvider", "WebSocket closed")
            }
        })

        Log.d("CameraServiceProvider", "Connecting WebSocket...")
        signalingSocket?.connect()
    }

    /**
     * Stops the WebRTC stream and disconnects signaling
     */
    fun stopStream() {
        Log.d("CameraServiceProvider", "Ending stream for room: $currentRoomId")

        webRTCClient?.release()
        webRTCClient = null

        signalingSocket?.disconnect()
        signalingSocket = null

        currentRoomId = null
        videoCallback = null

        Log.d("CameraServiceProvider", "Stream resources released cleanly")
    }

    /**
     * Checks if the stream is currently active
     */
    fun isStreamActive(): Boolean {
        return signalingSocket != null && webRTCClient != null
    }
}
