package com.techm.duress.core.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.*
import java.util.*

class WebRTCClient(
    private val context: Context,
    private val signalingCallback: SignalingCallback
) {

    interface SignalingCallback {
        fun onLocalSDPGenerated(sdp: SessionDescription)
        fun onIceCandidateFound(candidate: IceCandidate)
        fun onRemoteVideoTrackReceived(track: VideoTrack)
    }

    private val eglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory

    private var peerConnection: PeerConnection? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private val pendingCandidates = mutableListOf<IceCandidate>()
    private var remoteSdpSet = false

    init {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun getEglBase(): EglBase = eglBase

    fun initialize(answerMode: Boolean = false) {
        CoroutineScope(Dispatchers.Default).launch {
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:turn.localhost:3478").createIceServer(),
                PeerConnection.IceServer.builder("turn:turn.localhost:3478")
                    .setUsername("akhil")
                    .setPassword("sharma")
                    .createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
            }

            peerConnection = peerConnectionFactory.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
                        Log.d("WebRTCClient", "SignalingState: $newState")
                    }

                    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                        Log.d("WebRTCClient", "ICEConnectionState: $newState")
                    }

                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}

                    override fun onIceCandidate(candidate: IceCandidate?) {
                        candidate?.let {
                            Log.d("WebRTCClient", "Local ICE Candidate: ${it.sdp}")
                            signalingCallback.onIceCandidateFound(it)
                        }
                    }

                    override fun onAddTrack(
                        receiver: RtpReceiver?,
                        mediaStreams: Array<out MediaStream>?
                    ) {
                        val track = receiver?.track()
                        if (track is VideoTrack) {
                            Log.d("WebRTCClient", "Remote VideoTrack received: ${track.id()}")
                            signalingCallback.onRemoteVideoTrackReceived(track)
                        }
                    }

                    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                    override fun onAddStream(p0: MediaStream?) {}
                    override fun onRemoveStream(p0: MediaStream?) {}
                    override fun onDataChannel(p0: DataChannel?) {}
                    override fun onRenegotiationNeeded() {
                        Log.d("WebRTCClient", "Renegotiation needed")
                        createOffer()
                    }

                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                        Log.d("WebRTCClient", "Connection state changed: $newState")
                    }

                    override fun onIceConnectionReceivingChange(p0: Boolean) {
                        TODO("Not yet implemented")
                    }

                    override fun onTrack(transceiver: RtpTransceiver?) {}
                })

            // Add transceivers
            peerConnection?.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            )
            peerConnection?.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            )

            localAudioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
            localAudioTrack =
                peerConnectionFactory.createAudioTrack("audio_track", localAudioSource)

            if (!answerMode) {
                createOffer()
            }
        }
    }

    fun startLocalVideo(context: Context) {
        localVideoSource = peerConnectionFactory.createVideoSource(false)
        localVideoSource?.adaptOutputFormat(640, 360, 15)

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        try {
            videoCapturer = createCameraCapturer()
        } catch (e: IllegalStateException) {
            Log.e("WebRTCClient", "Camera capturer setup failed: ${e.message}")
            return // Abort video setup
        }

        videoCapturer?.initialize(surfaceTextureHelper, context, localVideoSource?.capturerObserver)
        videoCapturer?.startCapture(640, 360, 15)

        localVideoTrack = peerConnectionFactory.createVideoTrack("video_track", localVideoSource)
        localVideoTrack?.setEnabled(true)

        peerConnection?.addTrack(localVideoTrack)
        Log.d("WebRTCClient", "Local video track started")
    }

    fun registerLocalRenderer(renderer: VideoSink) {
        localVideoTrack?.addSink(renderer)
    }

    fun stopLocalVideo() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()

        videoCapturer = null
        surfaceTextureHelper = null

        localVideoTrack?.dispose()
        localVideoTrack = null

        localVideoSource?.dispose()
        localVideoSource = null
    }

    fun setRemoteSDP(sdp: String) {
        Log.d("WebRTCClient", "Setting remote SDP: $sdp")
        val sdpType = if (sdp.contains("a=recvonly") || sdp.contains("a=sendrecv") || sdp.contains("a=sendonly"))
            SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER

        val sessionDescription = SessionDescription(sdpType, sdp)
        if (peerConnection?.signalingState() == PeerConnection.SignalingState.STABLE ||
            peerConnection?.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {

            peerConnection?.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    remoteSdpSet = true
                    Log.d("WebRTCClient", "Remote SDP set: ${sdpType.name}")
                    pendingCandidates.forEach { peerConnection?.addIceCandidate(it) }
                    pendingCandidates.clear()

                    if (sdpType == SessionDescription.Type.OFFER) {
                        createAnswer()
                    }
                }


                override fun onSetFailure(error: String?) {
                    Log.e("WebRTCClient", "Remote SDP set failed: $error")
                }

                override fun onCreateSuccess(sdp: SessionDescription?) {}
                override fun onCreateFailure(error: String?) {}
            }, sessionDescription)
        } else {
            Log.w("WebRTCClient", "Skipping remote SDP set due to signaling state: ${peerConnection?.signalingState()}")
        }

    }

    fun addRemoteIceCandidate(candidate: String) {
        try {
            val iceCandidate = IceCandidate("", -1, candidate)
            if (peerConnection?.remoteDescription != null && remoteSdpSet) {
                peerConnection?.addIceCandidate(iceCandidate)
                Log.d("WebRTCClient", "Remote ICE Candidate added immediately: $candidate")
            } else {
                pendingCandidates.add(iceCandidate)
                Log.d("WebRTCClient", "Queued ICE Candidate: $candidate")
            }
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error adding ICE candidate: ${e.message}", e)
        }
    }


    private fun createOffer() {
        CoroutineScope(Dispatchers.Default).launch {
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    Log.d("WebRTCClient", "Generated local SDP: ${sdp?.description}")

                    sdp?.let {
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d("WebRTCClient", "Local offer set")
                                signalingCallback.onLocalSDPGenerated(it)
                            }

                            override fun onSetFailure(p0: String?) {
                                Log.e("WebRTCClient", "SetLocalDescription failed: $p0")
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, it)
                    }
                }

                override fun onCreateFailure(error: String?) {
                    Log.e("WebRTCClient", "Offer creation failed: $error")
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }
    }

    private fun createAnswer() {
        CoroutineScope(Dispatchers.Default).launch {
            peerConnection?.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    Log.d("WebRTCClient", "Generated local SDP: ${sdp?.description}")

                    sdp?.let {
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                Log.d("WebRTCClient", "Local answer set")
                                signalingCallback.onLocalSDPGenerated(it)
                            }

                            override fun onSetFailure(p0: String?) {
                                Log.e("WebRTCClient", "SetLocalDescription failed: $p0")
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, it)
                    }
                }

                override fun onCreateFailure(error: String?) {
                    Log.e("WebRTCClient", "Answer creation failed: $error")
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, MediaConstraints())
        }
    }

    private var isFactoryDisposed = false

    fun release() {
        try {
            if (!isFactoryDisposed) {
                try {
                    peerConnectionFactory.dispose()
                    isFactoryDisposed = true
                    Log.d("WebRTCClient", "PeerConnectionFactory disposed.")
                } catch (e: IllegalStateException) {
                    Log.w("WebRTCClient", "PeerConnectionFactory already disposed: ${e.message}")
                }
            }

            peerConnection?.dispose()
            peerConnection = null

            videoCapturer?.let {
                try {
                    it.stopCapture()
                } catch (e: Exception) {
                    Log.w("WebRTCClient", "Error stopping video capturer: ${e.message}")
                }
                it.dispose()
            }
            videoCapturer = null

            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null

            localVideoSource = null
            localVideoTrack = null
            localAudioSource = null
            localAudioTrack = null

            Log.d("WebRTCClient", "WebRTC resources released")
        } catch (e: Exception) {
            Log.e("WebRTCClient", "Error during WebRTC release: ${e.message}", e)
        }
    }



    private fun createCameraCapturer(): VideoCapturer {
        val enumerator = Camera2Enumerator(context)
        val cameraNames = enumerator.deviceNames
        for (deviceName in cameraNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                enumerator.createCapturer(deviceName, null)?.let {
                    Log.d("WebRTCClient", "Using front camera: $deviceName")
                    return it
                }
            }
        }
        for (deviceName in cameraNames) {
            if (enumerator.isBackFacing(deviceName)) {
                enumerator.createCapturer(deviceName, null)?.let {
                    Log.d("WebRTCClient", "Using back camera: $deviceName")
                    return it
                }
            }
        }
        throw IllegalStateException("No usable camera device found.")
    }

}
