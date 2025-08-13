package com.techm.duress.core.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.*

class WebRTCClient(
    private val context: Context,
    private val signalingCallback: SignalingCallback
) {

    interface SignalingCallback {
        fun onLocalSDPGenerated(sdp: SessionDescription)
        fun onIceCandidateFound(candidate: IceCandidate)
        fun onRemoteVideoTrackReceived(track: VideoTrack)
    }

    private val TAG = "DU/RTC"

    private val eglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory

    private var peerConnection: PeerConnection? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    private var localPreviewSink: VideoSink? = null

    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteSdpSet = false

    // Transceiver and role flags
    private var videoTransceiver: RtpTransceiver? = null
    private var isAnswerer: Boolean = true
    private var sendVideo: Boolean = false

    init {
        val initOptions = PeerConnectionFactory.InitializationOptions
            .builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext, /* enableIntelVp8Encoder */ true, /* enableH264HighProfile */ true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    /**
     * @param sendVideo  Victim=true (SEND_ONLY), Helper=false (RECV_ONLY)
     * @param isAnswerer Always true for SFU (server sends offers)
     */
    fun initialize(sendVideo: Boolean, isAnswerer: Boolean = true) {
        this.sendVideo = sendVideo
        this.isAnswerer = isAnswerer

        CoroutineScope(Dispatchers.Default).launch {
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

            peerConnection = peerConnectionFactory.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
                        Log.d(TAG, "SignalingState=$newState")
                    }
                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                        Log.d(TAG, "IceConnectionState=$newState")
                    }
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                        Log.d(TAG, "IceGatheringState=$state")
                    }
                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate?.let {
                            Log.d(TAG, "Local ICE: mid=${it.sdpMid} idx=${it.sdpMLineIndex}")
                            signalingCallback.onIceCandidateFound(it)
                        }
                    }
                    override fun onIceCandidatesRemoved(cands: Array<out IceCandidate>?) {}
                    override fun onAddStream(p0: MediaStream?) {} // unified plan
                    override fun onRemoveStream(p0: MediaStream?) {}
                    override fun onDataChannel(p0: DataChannel?) {}
                    override fun onRenegotiationNeeded() { Log.d(TAG, "Renegotiation needed (no-op)") }
                    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                        val track = receiver?.track()
                        if (track is VideoTrack) {
                            Log.d(TAG, "Remote VideoTrack: ${track.id()}")
                            signalingCallback.onRemoteVideoTrackReceived(track)
                        }
                    }
                    override fun onTrack(transceiver: RtpTransceiver?) {}
                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                        Log.d(TAG, "PeerConnectionState=$newState")
                    }
                    override fun onIceConnectionReceivingChange(p0: Boolean) {}
                }
            )

            // Transceivers
            val videoDir = if (sendVideo) {
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            } else {
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            }
            videoTransceiver = peerConnection?.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(videoDir)
            )

            val audioDir = if (sendVideo) {
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            } else {
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            }
            peerConnection?.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(audioDir)
            )

            // Local audio – only if we are sending
            localAudioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track", localAudioSource)
            if (sendVideo) {
                try { peerConnection?.addTrack(localAudioTrack) } catch (_: Throwable) {}
            }

            Log.d(TAG, if (sendVideo) "Victim: SEND_ONLY" else "Helper: RECV_ONLY")
        }
    }

    /** Victim-only: start local capture and publish track. Do NOT create an offer (SFU will). */
    fun startLocalVideo(context: Context) {
        if (!sendVideo) {
            Log.d(TAG, "Helper mode: startLocalVideo() ignored (view-only)")
            return
        }

        localVideoSource = peerConnectionFactory.createVideoSource(false)
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

        try {
            videoCapturer = createCameraCapturer()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Camera capturer setup failed: ${e.message}")
            return
        }

        videoCapturer?.initialize(surfaceTextureHelper, context, localVideoSource?.capturerObserver)
        videoCapturer?.startCapture(640, 360, 15)

        localVideoTrack = peerConnectionFactory.createVideoTrack("video_track", localVideoSource)
        localVideoTrack?.setEnabled(true)

        localPreviewSink?.let { sink -> localVideoTrack?.addSink(sink) }

        try {
            val sender = videoTransceiver?.sender
            if (sender != null) {
                sender.setTrack(localVideoTrack, /* takeOwnership = */ true)
                Log.d(TAG, "Bound local video track to sender")
            } else {
                Log.w(TAG, "videoTransceiver sender is null; fallback addTrack()")
                peerConnection?.addTrack(localVideoTrack)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sender.setTrack(video) failed: ${t.message}; fallback addTrack()")
            try { peerConnection?.addTrack(localVideoTrack) } catch (_: Throwable) {}
        }

        Log.d(TAG, "Local video started (waiting for server offer)")
    }

    fun stopLocalVideo() {
        try { videoCapturer?.stopCapture() } catch (_: Throwable) {}
        try { videoCapturer?.dispose() } catch (_: Throwable) {}
        surfaceTextureHelper?.dispose()
        videoCapturer = null
        surfaceTextureHelper = null

        localVideoTrack?.let { track ->
            try { track.setEnabled(false) } catch (_: Throwable) {}
            try { localPreviewSink?.let { track.removeSink(it) } } catch (_: Throwable) {}
            try { track.dispose() } catch (_: Throwable) {}
        }
        localVideoTrack = null

        try { localVideoSource?.dispose() } catch (_: Throwable) {}
        localVideoSource = null
    }

    /** Set remote SDP from server (offer for both roles), then create/send ANSWER. */
    fun setRemoteSDP(sdpOrJson: String) {
        val pc = peerConnection ?: return

        // Accept either raw SDP or {"type":"offer|answer","sdp":"..."}
        var remoteType: SessionDescription.Type? = null
        var remoteSdp: String = sdpOrJson
        try {
            if (sdpOrJson.trim().startsWith("{")) {
                val js = org.json.JSONObject(sdpOrJson)
                val t = js.optString("type", "")
                remoteSdp = js.optString("sdp", sdpOrJson)
                remoteType = when (t.lowercase()) {
                    "offer" -> SessionDescription.Type.OFFER
                    "answer" -> SessionDescription.Type.ANSWER
                    "pranswer" -> SessionDescription.Type.PRANSWER
                    else -> null
                }
            }
        } catch (_: Throwable) { /* fallback */ }

        if (remoteType == null) {
            // Defaulting based on our role with SFU: we are answerers; remote should be OFFER
            remoteType = if (isAnswerer) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        }

        Log.d(TAG, "Setting remote $remoteType (len=${remoteSdp.length})")

        val remote = SessionDescription(remoteType, remoteSdp)
        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteSdpSet = true
                Log.d(TAG, "Remote $remoteType set")
                // Drain any ICE that arrived early
                pendingCandidates.forEach { cand ->
                    try { pc.addIceCandidate(cand) } catch (_: Throwable) {}
                }
                pendingCandidates.clear()

                if (remoteType == SessionDescription.Type.OFFER) {
                    createAnswer()
                }
            }
            override fun onSetFailure(error: String?) { Log.e(TAG, "setRemoteDescription failed: $error") }
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, remote)
    }

    // Back-compat helper
    fun addRemoteIceCandidate(candidateString: String) {
        addRemoteIceCandidate(null, 0, candidateString)
    }

    fun addRemoteIceCandidate(mid: String?, index: Int, candidate: String) {
        val pc = peerConnection ?: return
        val ice = IceCandidate(mid ?: "", if (index >= 0) index else 0, candidate)
        if (remoteSdpSet) {
            try { pc.addIceCandidate(ice); Log.d(TAG, "Remote ICE added (mid=${ice.sdpMid}, index=${ice.sdpMLineIndex})") }
            catch (t: Throwable) { Log.w(TAG, "addIceCandidate failed: ${t.message}") }
        } else {
            pendingCandidates.add(ice)
            Log.d(TAG, "Queued ICE (remote SDP not set yet)")
        }
    }

    fun setLocalPreviewSink(sink: VideoSink?) {
        localPreviewSink = sink
        if (sink != null && localVideoTrack != null) {
            try { localVideoTrack?.addSink(sink) } catch (_: Throwable) {}
        }
    }

    private fun createAnswer() {
        val pc = peerConnection ?: return
        CoroutineScope(Dispatchers.Default).launch {
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp == null) { Log.e(TAG, "createAnswer returned null SDP"); return }
                    Log.d(TAG, "Local ANSWER created")
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local ANSWER set")
                            signalingCallback.onLocalSDPGenerated(sdp)
                        }
                        override fun onSetFailure(error: String?) { Log.e(TAG, "setLocal(answer) failed: $error") }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, sdp)
                }
                override fun onCreateFailure(error: String?) { Log.e(TAG, "createAnswer failed: $error") }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }
    }

    private var isFactoryDisposed = false

    fun release() {
        try {
            stopLocalVideo()

            try { localAudioTrack?.dispose() } catch (_: Throwable) {}
            localAudioTrack = null

            try { localAudioSource?.dispose() } catch (_: Throwable) {}
            localAudioSource = null

            try { peerConnection?.close() } catch (_: Throwable) {}
            try { peerConnection?.dispose() } catch (_: Throwable) {}
            peerConnection = null

            if (!isFactoryDisposed) {
                try {
                    peerConnectionFactory.dispose()
                    isFactoryDisposed = true
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "Factory already disposed: ${e.message}")
                }
            }
            Log.d(TAG, "WebRTC resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Release error: ${e.message}", e)
        }
    }

    private fun createCameraCapturer(): VideoCapturer {
        val enumerator = Camera2Enumerator(context)
        val cameraNames = enumerator.deviceNames

        for (name in cameraNames) {
            if (enumerator.isFrontFacing(name)) {
                enumerator.createCapturer(name, null)?.let {
                    Log.d(TAG, "Using front camera: $name")
                    return it
                }
            }
        }
        for (name in cameraNames) {
            if (enumerator.isBackFacing(name)) {
                enumerator.createCapturer(name, null)?.let {
                    Log.d(TAG, "Using back camera: $name")
                    return it
                }
            }
        }
        throw IllegalStateException("No usable camera device found.")
    }
}
