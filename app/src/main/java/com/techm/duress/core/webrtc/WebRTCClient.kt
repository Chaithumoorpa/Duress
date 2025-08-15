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
    private var isFrontFacing = true


    init {
        val initOptions = PeerConnectionFactory.InitializationOptions
            .builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        // BEFORE
        // val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        // val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            /* enableIntelVp8Encoder = */ true,
            /* enableH264HighProfile = */ false   // don't push H.264 high profile
        )
        val decoderFactory = SoftwareVideoDecoderFactory()  // emulator-safe VP8 decode


        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun initialize(answerMode: Boolean = false) {
        this.answerMode = answerMode

        CoroutineScope(Dispatchers.Default).launch {
            val iceServers = listOf(
//                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
                PeerConnection.IceServer.builder("stun:192.168.0.101:3478").createIceServer(),

                // TURN over UDP
                PeerConnection.IceServer.builder("turn:192.168.0.101:3478")
                    .setUsername("akhil")
                    .setPassword("sharma")
                    .createIceServer(),

                // TURN over TCP (helps with strict Wi-Fi/NATs)
                PeerConnection.IceServer.builder("turn:192.168.0.101:3478?transport=tcp")
                    .setUsername("akhil")
                    .setPassword("sharma")
                    .createIceServer()
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

            // Put VP8 first so offers/answers naturally negotiate VP8
            try {
                val caps = peerConnectionFactory.getRtpSenderCapabilities(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO
                )
                val vp8First = caps.codecs.sortedBy { if (it.mimeType.equals("video/VP8", true)) 0 else 1 }
                videoTransceiver?.setCodecPreferences(vp8First)
            } catch (_: Throwable) {
                // Older builds may not support codec preferences — SDP munging will cover it.
            }


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
        videoCapturer?.startCapture(1280, 720, 30)
        localVideoSource?.adaptOutputFormat(1280, 720, 30)

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

                    // --- PATCH: prefer VP8 in the local SDP we set/send ---
                    val vp8Sdp  = preferVideoCodec(sdp.description, "VP8")
                    val patched = SessionDescription(sdp.type, vp8Sdp)

                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local OFFER set (VP8 preferred) → send to server")
                            // Send the patched SDP (not the original)
                            signalingCallback.onLocalSDPGenerated(patched)
                        }
                        override fun onSetFailure(error: String?) {
                            Log.e(TAG, "setLocal(offer) failed: $error")
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, patched)
                }

                override fun onCreateFailure(error: String?) {
                    Log.e(TAG, "createOffer failed: $error")
                }
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

                    val vp8Sdp  = preferVideoCodec(sdp.description, "VP8")
                    val patched = SessionDescription(sdp.type, vp8Sdp)

                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d(TAG, "Local ANSWER set (VP8 preferred) → send to server")
                            flushPendingCandidates()
                            signalingCallback.onLocalSDPGenerated(patched)
                        }
                        override fun onSetFailure(error: String?) { Log.e(TAG, "setLocal(answer) failed: $error") }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, patched)
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
        val t = localVideoTrack ?: return
        if (sink != null) {
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            h.post { runCatching { t.addSink(sink) }.onFailure { /* ignore */ } }
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

        // Prefer front, fall back to back
        enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }?.let { name ->
            isFrontFacing = true
            return enumerator.createCapturer(name, null)
                ?: throw IllegalStateException("No front camera capturer")
        }
        enumerator.deviceNames.firstOrNull { enumerator.isBackFacing(it) }?.let { name ->
            isFrontFacing = false
            return enumerator.createCapturer(name, null)
                ?: throw IllegalStateException("No back camera capturer")
        }
        throw IllegalStateException("No usable camera device found.")
    }

    fun switchCamera(onDone: ((Boolean) -> Unit)? = null) {
        val cam = videoCapturer as? CameraVideoCapturer ?: return
        try {
            cam.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                    isFrontFacing = isFrontCamera
                    Log.d(TAG, "Camera switched. Front=$isFrontFacing")
                    onDone?.invoke(isFrontFacing)
                }
                override fun onCameraSwitchError(errorDescription: String?) {
                    Log.w(TAG, "Camera switch error: $errorDescription")
                    onDone?.invoke(isFrontFacing) // unchanged
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "Camera switch threw: ${t.message}")
            onDone?.invoke(isFrontFacing)
        }
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

    private fun preferVideoCodec(sdp: String, codec: String): String {
        // Move desired codec to front of m=video line's payload list
        val lines = sdp.split("\r\n").toMutableList()
        val mLineIndex = lines.indexOfFirst { it.startsWith("m=video") }
        if (mLineIndex == -1) return sdp

        // Find payload type for the codec (from a=rtpmap)
        val ptRegex = Regex("""a=rtpmap:(\d+)\s+$codec/""", RegexOption.IGNORE_CASE)
        val pts = lines.mapNotNull { ln ->
            ptRegex.find(ln)?.groupValues?.getOrNull(1)
        }
        if (pts.isEmpty()) return sdp

        val m = lines[mLineIndex].split(" ").toMutableList()
        if (m.size > 3) {
            // m=video <port> <proto> <pt list...>
            val header = m.subList(0, 3)
            val rest = m.subList(3, m.size).toMutableList()
            // move our payload types to the front, keep order otherwise
            val reordered = pts + rest.filterNot { it in pts }
            lines[mLineIndex] = (header + reordered).joinToString(" ")
        }
        return lines.joinToString("\r\n")
    }

}
