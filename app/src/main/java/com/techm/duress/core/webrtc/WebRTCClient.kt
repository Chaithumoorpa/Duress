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

        /** NEW: Called when local video track is ready so preview can bind immediately */
        fun onLocalVideoTrackCreated(track: VideoTrack)
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
    private var answerMode = false // true=HELPER (answerer), false=VICTIM (offerer)

    private var videoTransceiver: RtpTransceiver? = null

    init {
        val initOptions = PeerConnectionFactory.InitializationOptions
            .builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun initialize(answerMode: Boolean = false) {
        this.answerMode = answerMode

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

                    override fun onAddStream(p0: MediaStream?) {} // deprecated

                    override fun onRemoveStream(p0: MediaStream?) {}

                    override fun onDataChannel(p0: DataChannel?) {}

                    override fun onRenegotiationNeeded() {
                        Log.d(TAG, "Renegotiation needed")
                    }

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
            val videoDir = if (!answerMode) RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            else RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            videoTransceiver = peerConnection?.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(videoDir)
            )

            val audioDir = if (!answerMode) RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            else RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            peerConnection?.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(audioDir)
            )

            // Local audio (victim only)
            localAudioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track", localAudioSource)
            if (!answerMode) {
                try { peerConnection?.addTrack(localAudioTrack) } catch (_: Throwable) {}
            }
        }
    }

    fun startLocalVideo(context: Context) {
        if (answerMode) {
            Log.d(TAG, "Helper mode: startLocalVideo() ignored")
            return
        }

        localVideoSource = peerConnectionFactory.createVideoSource(false)
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)

        videoCapturer = createCameraCapturer()
        videoCapturer?.initialize(surfaceTextureHelper, context, localVideoSource?.capturerObserver)
        videoCapturer?.startCapture(640, 360, 15)

        localVideoTrack = peerConnectionFactory.createVideoTrack("video_track", localVideoSource)
        localVideoTrack?.setEnabled(true)
        localVideoTrack?.let { signalingCallback.onLocalVideoTrackCreated(it) }
        localPreviewSink?.let { sink -> localVideoTrack?.addSink(sink) }

        try {
            val sender = videoTransceiver?.sender
            if (sender != null) {
                sender.setTrack(localVideoTrack, true)
            } else {
                peerConnection?.addTrack(localVideoTrack)
            }
        } catch (_: Throwable) {
            try { peerConnection?.addTrack(localVideoTrack) } catch (_: Throwable) {}
        }

        Log.d(TAG, "Local video started → createOffer()")
        createOffer()
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

    fun setRemoteSDP(sdp: String) {
        val type = if (answerMode) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        Log.d(TAG, "Setting remote $type (len=${sdp.length})")

        val remote = SessionDescription(type, sdp)
        val pc = peerConnection ?: return

        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                remoteSdpSet = true
                Log.d(TAG, "Remote $type set")
                if (type == SessionDescription.Type.OFFER) {
                    createAnswer()
                } else {
                    pendingCandidates.forEach { cand ->
                        try { pc.addIceCandidate(cand) } catch (_: Throwable) {}
                    }
                    pendingCandidates.clear()
                }
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "setRemoteDescription failed: $error")
            }

            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
        }, remote)
    }

    /** Public helper-callable Answer creation */
    fun createAnswer() {
        val pc = peerConnection ?: return
        Log.d(TAG, "createAnswer() called explicitly")
        CoroutineScope(Dispatchers.Default).launch {
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp == null) { Log.e(TAG, "createAnswer returned null"); return }
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local ANSWER set → send to server")
                            pendingCandidates.forEach { cand ->
                                try { pc.addIceCandidate(cand) } catch (_: Throwable) {}
                            }
                            pendingCandidates.clear()
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

    private fun createOffer() {
        if (answerMode) return
        val pc = peerConnection ?: return
        CoroutineScope(Dispatchers.Default).launch {
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp == null) { Log.e(TAG, "createOffer returned null"); return }
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local OFFER set → send to server")
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

    fun addRemoteIceCandidate(mid: String?, index: Int, candidate: String) {
        val pc = peerConnection ?: return
        val ice = IceCandidate(mid ?: "", if (index >= 0) index else 0, candidate)
        if (remoteSdpSet) {
            try { pc.addIceCandidate(ice) } catch (t: Throwable) { Log.w(TAG, "addIce failed: ${t.message}") }
        } else {
            pendingCandidates.add(ice)
            Log.d(TAG, "Queued ICE (remote SDP not set yet)")
        }
    }

    fun addRemoteIceCandidate(candidate: String) = addRemoteIceCandidate("", 0, candidate)

    fun setLocalPreviewSink(sink: VideoSink?) {
        localPreviewSink = sink
        if (sink != null && localVideoTrack != null) {
            try { localVideoTrack?.addSink(sink) } catch (_: Throwable) {}
        }
    }

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

            try { peerConnectionFactory.dispose() } catch (_: Throwable) {}
            Log.d(TAG, "WebRTC resources released")
        } catch (e: Exception) {
            Log.e(TAG, "Release error: ${e.message}", e)
        }
    }

    private fun createCameraCapturer(): VideoCapturer {
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        for (n in names) if (enumerator.isFrontFacing(n)) {
            enumerator.createCapturer(n, null)?.let { Log.d(TAG, "Using front camera: $n"); return it }
        }
        for (n in names) if (enumerator.isBackFacing(n)) {
            enumerator.createCapturer(n, null)?.let { Log.d(TAG, "Using back camera: $n"); return it }
        }
        throw IllegalStateException("No usable camera device found.")
    }
}
