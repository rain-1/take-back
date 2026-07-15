package com.takeback.app

import android.content.Context
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnection.IceServer
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap

/**
 * Signaler is how the engine sends messages back out. The activity backs this
 * with [SignalingClient].
 */
interface Signaler {
    fun sendOffer(to: String, sdp: JSONObject)
    fun sendAnswer(to: String, sdp: JSONObject)
    fun sendCandidate(to: String, candidate: JSONObject)
}

/**
 * Events the engine raises for the UI (delivered on the WebRTC signaling
 * thread; the activity marshals to the main thread).
 */
interface RtcEvents {
    fun onLocalVideo(track: VideoTrack)
    fun onRemoteVideo(peerId: String, nick: String, track: VideoTrack)
    fun onPeerClosed(peerId: String)
}

/**
 * RtcEngine owns the shared local media and one [PeerConnection] per remote
 * peer, forming a full mesh that matches the web client. Screen sharing swaps
 * the capturer feeding the single local video source, so no renegotiation is
 * needed — every peer's sender keeps the same track.
 */
class RtcEngine(
    private val appContext: Context,
    private val eglBase: EglBase,
    private val signaler: Signaler,
    private val events: RtcEvents,
) {
    private val factory: PeerConnectionFactory
    private val iceServers = listOf(
        IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
    )

    private lateinit var videoSource: VideoSource
    private var localVideo: VideoTrack? = null
    private var localAudio: AudioTrack? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var currentCapturer: VideoCapturer? = null
    private var cameraCapturer: VideoCapturer? = null

    private val peers = ConcurrentHashMap<String, PeerBox>()
    private val nicks = ConcurrentHashMap<String, String>()

    private class PeerBox(val pc: PeerConnection)

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )
        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
    }

    /** Acquire mic + front camera and publish local tracks. Call once. */
    fun startLocalMedia() {
        localAudio = factory.createAudioTrack(
            "audio0", factory.createAudioSource(MediaConstraints())
        )

        val capturer = createCameraCapturer() ?: return
        cameraCapturer = capturer
        currentCapturer = capturer

        videoSource = factory.createVideoSource(capturer.isScreencast)
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        capturer.initialize(surfaceHelper, appContext, videoSource.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        val track = factory.createVideoTrack("video0", videoSource)
        localVideo = track
        events.onLocalVideo(track)
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(appContext)
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull() ?: return null
        return enumerator.createCapturer(front, null)
    }

    // ---- Peer lifecycle (full mesh) ----

    /** As newcomer: create a connection and offer toward an existing peer. */
    fun offerTo(peerId: String, nick: String) {
        val box = createPeer(peerId, nick)
        box.pc.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                box.pc.setLocalDescription(SdpAdapter(), sdp)
                signaler.sendOffer(peerId, sdp.toJson())
            }
        }, MediaConstraints())
    }

    /** As responder: apply the remote offer and answer it. */
    fun onRemoteOffer(peerId: String, nick: String, sdpJson: JSONObject) {
        val box = createPeer(peerId, nick)
        box.pc.setRemoteDescription(SdpAdapter(), sdpJson.toSdp())
        box.pc.createAnswer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                box.pc.setLocalDescription(SdpAdapter(), sdp)
                signaler.sendAnswer(peerId, sdp.toJson())
            }
        }, MediaConstraints())
    }

    fun onRemoteAnswer(peerId: String, sdpJson: JSONObject) {
        peers[peerId]?.pc?.setRemoteDescription(SdpAdapter(), sdpJson.toSdp())
    }

    fun onRemoteCandidate(peerId: String, json: JSONObject) {
        peers[peerId]?.pc?.addIceCandidate(
            IceCandidate(
                json.optString("sdpMid"),
                json.optInt("sdpMLineIndex"),
                json.optString("candidate"),
            )
        )
    }

    fun removePeer(peerId: String) {
        peers.remove(peerId)?.pc?.close()
        events.onPeerClosed(peerId)
    }

    private fun createPeer(peerId: String, nick: String): PeerBox {
        peers[peerId]?.let { return it }
        nicks[peerId] = nick

        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(config, object : PcObserver() {
            override fun onIceCandidate(candidate: IceCandidate) {
                signaler.sendCandidate(peerId, candidate.toJson())
            }
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
                val t = transceiver.receiver.track()
                if (t is VideoTrack) {
                    events.onRemoteVideo(peerId, nicks[peerId] ?: "peer", t)
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (newState == PeerConnection.PeerConnectionState.FAILED ||
                    newState == PeerConnection.PeerConnectionState.CLOSED
                ) removePeer(peerId)
            }
        }) ?: error("failed to create peer connection")

        val streamIds = listOf("stream0")
        localAudio?.let { pc.addTrack(it, streamIds) }
        localVideo?.let { pc.addTrack(it, streamIds) }

        val box = PeerBox(pc)
        peers[peerId] = box
        return box
    }

    // ---- Screen sharing: swap the capturer feeding the shared video source ----

    /**
     * Start feeding video from [screenCapturer] (a ScreenCapturerAndroid built
     * by the activity from the MediaProjection permission result). The same
     * VideoSource/track is reused, so peers see the switch with no renegotiation.
     */
    fun switchToScreen(screenCapturer: VideoCapturer) {
        swapCapturer(screenCapturer, 1280, 720, 15)
    }

    /** Revert to the camera capturer. */
    fun switchToCamera() {
        val cam = cameraCapturer ?: return
        // The camera capturer was disposed of only on close(); it can be
        // re-started here because we never dispose it while the call is live.
        swapCapturer(cam, 1280, 720, 30)
    }

    private fun swapCapturer(next: VideoCapturer, w: Int, h: Int, fps: Int) {
        val helper = surfaceHelper ?: return
        try {
            currentCapturer?.stopCapture()
        } catch (_: InterruptedException) {
        }
        next.initialize(helper, appContext, videoSource.capturerObserver)
        next.startCapture(w, h, fps)
        currentCapturer = next
    }

    fun switchCamera() {
        (cameraCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    fun close() {
        try {
            currentCapturer?.stopCapture()
        } catch (_: InterruptedException) {
        }
        peers.values.forEach { it.pc.close() }
        peers.clear()
        cameraCapturer?.dispose()
        surfaceHelper?.dispose()
        factory.dispose()
    }
}

// ---- Small JSON <-> WebRTC adapters, matching the web client's wire shapes ----

private fun SessionDescription.toJson(): JSONObject =
    JSONObject().put("type", type.canonicalForm()).put("sdp", description)

private fun JSONObject.toSdp(): SessionDescription =
    SessionDescription(
        SessionDescription.Type.fromCanonicalForm(getString("type")),
        getString("sdp"),
    )

private fun IceCandidate.toJson(): JSONObject =
    JSONObject()
        .put("candidate", sdp)
        .put("sdpMid", sdpMid)
        .put("sdpMLineIndex", sdpMLineIndex)

/** SdpObserver with no-op defaults so callers override only what they need. */
private open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}
