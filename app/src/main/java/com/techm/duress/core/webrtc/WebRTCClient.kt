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
    private var answerMode = false // true for HELPER (viewer), false for VICTIM (broadcaster)

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
     * @param answerMode true for HELPER (viewer/answerer), false for VICTIM (broadcaster/offerer)
     */
    fun initialize(answerMode: Boolean = false) {
        this.answerMode = answerMode

        CoroutineScope(Dispatchers.Default).launch {
            val iceServers = listOf(
                // Tune these to your deployment
                PeerConnection.IceServer.builder("stun:turn.localhost:3478").createIceServer(),
                PeerConnection.IceServer.builder("turn:turn.localhost:3478")
                    .setUsername("akhil")
                    .setPassword("sharma")
                    .createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                // Prefer relay so TURN is actually used in hotspot tests
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
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

                    override fun onIceCandidatesRemoved(cands: Array<out IceCandidate>?) { }

                    override fun onAddStream(p0: MediaStream?) { } // deprecated in Unified Plan

                    override fun onRemoveStream(p0: MediaStream?) { }

                    override fun onDataChannel(p0: DataChannel?) { }

                    override fun onRenegotiationNeeded() {
                        // Avoid auto-offer loops; negotiate only when you intend to (Start/Stop)
                        Log.d(TAG, "Renegotiation needed (no-op)")
                    }

                    override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                        val track = receiver?.track()
                        if (track is VideoTrack) {
                            Log.d(TAG, "Remote VideoTrack: ${track.id()}")
                            signalingCallback.onRemoteVideoTrackReceived(track)
                        }
                    }

                    override fun onTrack(transceiver: RtpTransceiver?) {
                        // Also fires in Unified Plan; onAddTrack above covers video delivery as well.
                    }

                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                        Log.d(TAG, "PeerConnectionState=$newState")
                    }

                    override fun onIceConnectionReceivingChange(p0: Boolean) { }
                }
            )

            // Transceivers (Unified Plan)
            // Victim sends video; Helper only receives video
            val videoDir = if (answerMode) // helper
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            else
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY

            peerConnection?.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(videoDir)
            )

            // If you want audio, mirror the same pattern; currently wired as SEND_RECV to keep flexibility
            val audioDir = if (answerMode)
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            else
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY

            peerConnection?.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(audioDir)
            )

            // Local audio (optional). Comment out if not needed.
            localAudioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track", localAudioSource)
            if (!answerMode) {
                // For SEND_ONLY audio (Victim), attach track
                try { peerConnection?.addTrack(localAudioTrack) } catch (_: Throwable) { }
            }

            // Victim auto-generates OFFER after local video starts (called from provider)
            // Helper waits for OFFER and then createAnswer() in setRemoteSDP()
        }
    }

    /**
     * Victim-only: start local capture and publish track.
     */
    fun startLocalVideo(context: Context) {
        if (answerMode) {
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
        // 640x360 @ 15fps is usually enough; your code used 1280x720 @ 15
        videoCapturer?.startCapture(640, 360, 15)

        localVideoTrack = peerConnectionFactory.createVideoTrack("video_track", localVideoSource)
        localVideoTrack?.setEnabled(true)

        // attach preview if present
        localPreviewSink?.let { sink -> localVideoTrack?.addSink(sink) }

        // publish to PC
        try {
            peerConnection?.addTrack(localVideoTrack)
        } catch (t: Throwable) {
            Log.w(TAG, "addTrack(video) failed: ${t.message}")
        }

        Log.d(TAG, "Local video started")
        // Now that video is ready, Victim can create an Offer
        createOfferIfOfferer()
    }

    fun stopLocalVideo() {
        try {
            videoCapturer?.stopCapture()
        } catch (_: Throwable) { }
        try { videoCapturer?.dispose() } catch (_: Throwable) { }
        surfaceTextureHelper?.dispose()

        videoCapturer = null
        surfaceTextureHelper = null

        localVideoTrack?.let { track ->
            try { track.setEnabled(false) } catch (_: Throwable) { }
            try { localPreviewSink?.let { track.removeSink(it) } } catch (_: Throwable) { }
            try { track.dispose() } catch (_: Throwable) { }
        }
        localVideoTrack = null

        try { localVideoSource?.dispose() } catch (_: Throwable) { }
        localVideoSource = null
    }

    /**
     * Provider/VM will call this exactly once per role:
     * - Helper (answerMode=true): Remote is an OFFER → set remote, then createAnswer()
     * - Victim (answerMode=false): Remote is an ANSWER → set remote and finish
     */
    fun setRemoteSDP(sdp: String) {
        val type = if (answerMode) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        Log.d(TAG, "Setting remote $type (len=${sdp.length})")

        val remote = SessionDescription(type, sdp)
        val pc = peerConnection ?: return

        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteSdpSet = true
                Log.d(TAG, "Remote $type set")
                // Drain any ICE that arrived early
                pendingCandidates.forEach { cand ->
                    try { pc.addIceCandidate(cand) } catch (_: Throwable) { }
                }
                pendingCandidates.clear()

                if (type == SessionDescription.Type.OFFER) {
                    createAnswer()
                }
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription failed: $error")
            }

            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, remote)
    }

    fun addRemoteIceCandidate(candidate: String) {
        // We only receive a raw candidate string (no mid/index from your signaling)
        val ice = IceCandidate(/* sdpMid */ "", /* sdpMLineIndex */ -1, candidate)
        val pc = peerConnection ?: return
        if (remoteSdpSet) {
            try {
                pc.addIceCandidate(ice)
                Log.d(TAG, "Remote ICE added immediately")
            } catch (t: Throwable) {
                Log.w(TAG, "addIceCandidate failed: ${t.message}")
            }
        } else {
            pendingCandidates.add(ice)
            Log.d(TAG, "Queued ICE (remote SDP not set yet)")
        }
    }

    fun setLocalPreviewSink(sink: VideoSink?) {
        localPreviewSink = sink
        // If track already exists, attach immediately
        if (sink != null && localVideoTrack != null) {
            try { localVideoTrack?.addSink(sink) } catch (_: Throwable) { }
        }
    }

    private fun createOfferIfOfferer() {
        if (answerMode) return
        createOffer()
    }

     fun createOffer() {
        val pc = peerConnection ?: return
        CoroutineScope(Dispatchers.Default).launch {
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp == null) { Log.e(TAG, "createOffer returned null SDP"); return }
                    Log.d(TAG, "Local OFFER created")
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local OFFER set")
                            signalingCallback.onLocalSDPGenerated(sdp)
                        }
                        override fun onSetFailure(error: String?) { Log.e(TAG, "setLocal(offer) failed: $error") }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, sdp)
                }
                override fun onCreateFailure(error: String?) { Log.e(TAG, "createOffer failed: $error") }
                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
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

            try {
                localAudioTrack?.dispose()
            } catch (_: Throwable) { }
            localAudioTrack = null

            try {
                localAudioSource?.dispose()
            } catch (_: Throwable) { }
            localAudioSource = null

            try {
                peerConnection?.close()
            } catch (_: Throwable) { }
            try {
                peerConnection?.dispose()
            } catch (_: Throwable) { }
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

        // Prefer front camera
        for (name in cameraNames) {
            if (enumerator.isFrontFacing(name)) {
                enumerator.createCapturer(name, null)?.let {
                    Log.d(TAG, "Using front camera: $name")
                    return it
                }
            }
        }
        // Fallback to back camera
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
