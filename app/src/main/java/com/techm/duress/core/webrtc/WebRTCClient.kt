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
        fun onLocalVideoTrackCreated(track: VideoTrack)
    }

    private val TAG = "DU/RTC"

    private val eglBase: EglBase = EglBase.create()
    private val peerConnectionFactory: PeerConnectionFactory

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
    private var answerMode = false

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
                    override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                        Log.d(TAG, "SignalingState=$state")
                    }

                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                        Log.d(TAG, "IceConnectionState=$state")
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

                    override fun onAddTrack(
                        receiver: RtpReceiver?,
                        mediaStreams: Array<out MediaStream>?
                    ) {
                        // Some devices still fire this — keep it as a fallback.
                        (receiver?.track() as? VideoTrack)?.let {
                            Log.d(TAG, "Remote VideoTrack received via onAddTrack()")
                            it.setEnabled(true)
                            signalingCallback.onRemoteVideoTrackReceived(it)
                        }
                    }

                    override fun onTrack(transceiver: RtpTransceiver?) {
                        // Unified Plan prefers onTrack()
                        val track = transceiver?.receiver?.track()
                        if (track is VideoTrack) {
                            Log.d(TAG, "Remote VideoTrack received via onTrack()")
                            track.setEnabled(true)
                            signalingCallback.onRemoteVideoTrackReceived(track)
                        }
                    }
                    override fun onConnectionChange(state: PeerConnection.PeerConnectionState?) {
                        Log.d(TAG, "PeerConnectionState=$state")
                    }

                    // Unused old callbacks
                    override fun onIceCandidatesRemoved(cands: Array<out IceCandidate>?) {}
                    override fun onAddStream(p0: MediaStream?) {}
                    override fun onRemoveStream(p0: MediaStream?) {}
                    override fun onDataChannel(p0: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onIceConnectionReceivingChange(p0: Boolean) {}
                }
            )

            // Transceivers: victim SEND_ONLY, helper RECV_ONLY
            val videoDir = if (!answerMode)
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            else
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY

            videoTransceiver = peerConnection?.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(videoDir)
            )

            val audioDir = if (!answerMode)
                RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
            else
                RtpTransceiver.RtpTransceiverDirection.RECV_ONLY

            peerConnection?.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(audioDir)
            )

            // Always create audio track; add to PC in victim mode
            localAudioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track", localAudioSource)
            if (!answerMode) {
                try { peerConnection?.addTrack(localAudioTrack) } catch (_: Throwable) {}
            }
        }
    }

    fun startLocalVideo(context: Context) {
        if (answerMode) {
            Log.d(TAG, "Helper mode: no local camera start")
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

        // Bind to sender
        try {
            val sender = videoTransceiver?.sender
            if (sender != null) sender.setTrack(localVideoTrack, true)
            else peerConnection?.addTrack(localVideoTrack)
        } catch (_: Throwable) {
            try { peerConnection?.addTrack(localVideoTrack) } catch (_: Throwable) {}
        }

        Log.d(TAG, "Local video started — creating OFFER")
        createOffer()
    }

    fun stopLocalVideo() {
        try { videoCapturer?.stopCapture() } catch (_: Throwable) {}
        try { videoCapturer?.dispose() } catch (_: Throwable) {}
        surfaceTextureHelper?.dispose()
        videoCapturer = null
        surfaceTextureHelper = null

        localVideoTrack?.apply {
            setEnabled(false)
            localPreviewSink?.let { removeSink(it) }
            dispose()
        }
        localVideoTrack = null

        localVideoSource?.dispose()
        localVideoSource = null
    }

    fun setRemoteSDP(sdp: String) {
        val type = if (answerMode) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        val remote = SessionDescription(type, sdp)
        val pc = peerConnection ?: return

        pc.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote SDP set: $type")
                remoteSdpSet = true
                flushPendingCandidates()
                if (type == SessionDescription.Type.OFFER) {
                    createAnswer()
                }
            }
            override fun onSetFailure(error: String?) { Log.e(TAG, "setRemoteDescription failed: $error") }
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, remote)
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

    fun createAnswer() {
        val pc = peerConnection ?: return
        CoroutineScope(Dispatchers.Default).launch {
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp == null) { Log.e(TAG, "createAnswer returned null"); return }
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local ANSWER set → send to server")
                            flushPendingCandidates()
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

    fun addRemoteIceCandidate(mid: String?, index: Int, candidate: String) {
        val ice = IceCandidate(mid ?: "", if (index >= 0) index else 0, candidate)
        if (remoteSdpSet) {
            try { peerConnection?.addIceCandidate(ice) } catch (t: Throwable) { Log.w(TAG, "addIce failed: ${t.message}") }
        } else {
            pendingCandidates.add(ice)
            Log.d(TAG, "Queued ICE (remote SDP not set yet)")
        }
    }

    private fun flushPendingCandidates() {
        val pc = peerConnection ?: return
        if (pendingCandidates.isEmpty()) return
        Log.d(TAG, "Flushing ${pendingCandidates.size} pending ICE candidates")
        pendingCandidates.forEach { pc.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    fun setLocalPreviewSink(sink: VideoSink?) {
        localPreviewSink = sink
        if (sink != null && localVideoTrack != null) {
            try { localVideoTrack?.addSink(sink) } catch (_: Throwable) {}
        }
    }

    fun release() {
        stopLocalVideo()
        try { localAudioTrack?.dispose() } catch (_: Throwable) {}
        localAudioTrack = null
        try { localAudioSource?.dispose() } catch (_: Throwable) {}
        localAudioSource = null
        try { peerConnection?.close() } catch (_: Throwable) {}
        try { peerConnection?.dispose() } catch (_: Throwable) {}
        peerConnection = null
        peerConnectionFactory.dispose()
    }

    private fun createCameraCapturer(): VideoCapturer {
        val enumerator = Camera2Enumerator(context)
        enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }?.let {
            return enumerator.createCapturer(it, null) ?: throw IllegalStateException("No camera")
        }
        enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }?.let {
            return enumerator.createCapturer(it, null) ?: throw IllegalStateException("No camera")
        }
        throw IllegalStateException("No usable camera device found.")
    }

    fun startInboundStatsDebug(tag: String = "DU/STATS") {
        val pc = peerConnection ?: return
        CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                try {
                    pc.getStats { report: RTCStatsReport ->
                        // report.statsMap: Map<String, RTCStats>
                        for ((_, s) in report.statsMap) {
                            if (s.type == "inbound-rtp") {
                                val kind = s.members["kind"] as? String
                                if (kind == "video") {
                                    val bytes = (s.members["bytesReceived"] as? Number)?.toLong()
                                    val frames = (s.members["framesDecoded"] as? Number)?.toLong()
                                    Log.d(tag, "inbound-rtp(video) bytes=$bytes framesDecoded=$frames")
                                }
                            }
                        }
                    }
                } catch (_: Throwable) { /* ignore */ }
                kotlinx.coroutines.delay(1000)
            }
        }
    }


}
