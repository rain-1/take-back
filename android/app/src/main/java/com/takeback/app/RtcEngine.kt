package com.takeback.app

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import org.webrtc.MediaStream
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

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

    /**
     * A remote video arrived. [streamId] identifies which of the peer's streams
     * it belongs to — the caller matches it against the screen id the peer
     * announced to tell a screen share apart from a camera.
     */
    fun onRemoteVideo(peerId: String, nick: String, track: VideoTrack, streamId: String)
    fun onPeerClosed(peerId: String)

    /** Someone started/stopped speaking. [id] is [LOCAL_ID] or a peer id. */
    fun onSpeaking(id: String, speaking: Boolean) {}

    /** Our own screen capture started / stopped. */
    fun onLocalScreen(track: VideoTrack) {}
    fun onLocalScreenEnded() {}
}

/** Tile id used for our own camera/audio. */
const val LOCAL_ID = "local"

/** Tile id used for our own screen share. */
const val LOCAL_SCREEN_ID = "local-screen"

/** Stream ids we publish. The screen one is announced to peers in `state`. */
const val CAM_STREAM_ID = "tb-cam"
const val SCREEN_STREAM_ID = "tb-screen"

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

    /** Our own peer id, from the server's welcome. Used for the polite tiebreak. */
    var selfId: String? = null

    // The screen is a SECOND video track, so the camera keeps streaming while
    // you present (rather than being swapped out).
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private var screenCapturer: VideoCapturer? = null

    /** True while we're sharing our screen. */
    val sharingScreen: Boolean get() = screenTrack != null

    private class PeerBox(val pc: PeerConnection) {
        /**
         * Glare handling for renegotiation ("perfect negotiation"): if both
         * sides offer at once, only the impolite peer ignores the incoming
         * offer. The tiebreak must be deterministic and opposite on each side.
         */
        var polite = false
        var makingOffer = false
        var ignoreOffer = false
        var screenSender: org.webrtc.RtpSender? = null
    }

    // Speaking detection. Local level comes from the mic's raw samples; remote
    // levels come from each peer connection's inbound-rtp stats (Android WebRTC
    // has no Web Audio equivalent to tap a remote track directly).
    private val detectors = ConcurrentHashMap<String, SpeakingDetector>()
    private val statsHandler = Handler(Looper.getMainLooper())
    private var micEnabledFlag = true

    private fun detectorFor(id: String) =
        detectors.getOrPut(id) { SpeakingDetector { on -> events.onSpeaking(id, on) } }

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )

        // Tap the microphone's captured samples so we can show a level even with
        // no peers connected ("is my mic working?"). getStats can't do that —
        // it only reports once media is flowing to someone.
        val adm = JavaAudioDeviceModule.builder(appContext)
            .setSamplesReadyCallback { samples -> onMicSamples(samples) }
            .createAudioDeviceModule()

        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()

        startStatsPolling()
    }

    /** Compute RMS over a buffer of 16-bit PCM and feed the local detector. */
    private fun onMicSamples(samples: JavaAudioDeviceModule.AudioSamples) {
        if (!micEnabledFlag) return // muted: never imply we're transmitting
        val data = samples.data
        var sum = 0.0
        var n = 0
        var i = 0
        while (i + 1 < data.size) {
            val v = (((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xff)).toShort()).toInt() / 32768.0
            sum += v * v
            n++
            i += 2
        }
        if (n > 0) detectorFor(LOCAL_ID).update(sqrt(sum / n))
    }

    /**
     * Poll each peer connection for its inbound audio level. 200ms is a
     * compromise: fast enough to feel live, cheap enough to run per peer.
     */
    private fun startStatsPolling() {
        statsHandler.postDelayed(object : Runnable {
            override fun run() {
                for ((peerId, box) in peers) {
                    box.pc.getStats { report ->
                        var level = 0.0
                        for (s in report.statsMap.values) {
                            if (s.type != "inbound-rtp") continue
                            // Different WebRTC builds spell this "kind" or "mediaType".
                            val kind = s.members["kind"] ?: s.members["mediaType"]
                            if (kind != "audio") continue
                            (s.members["audioLevel"] as? Number)?.let { level = it.toDouble() }
                        }
                        detectorFor(peerId).update(level)
                    }
                }
                statsHandler.postDelayed(this, 200)
            }
        }, 200)
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

    /**
     * As responder: apply the remote offer and answer it. This handles both the
     * initial offer and later re-offers (e.g. a peer adding a screen track).
     */
    fun onRemoteOffer(peerId: String, nick: String, sdpJson: JSONObject) {
        val box = createPeer(peerId, nick)

        // Glare: if we're mid-offer ourselves, only the polite peer gives way.
        val collision = box.makingOffer ||
            box.pc.signalingState() != PeerConnection.SignalingState.STABLE
        box.ignoreOffer = !box.polite && collision
        if (box.ignoreOffer) return

        val applyRemote = {
            box.pc.setRemoteDescription(object : SdpAdapter() {
                override fun onSetSuccess() {
                    box.pc.createAnswer(object : SdpAdapter() {
                        override fun onCreateSuccess(sdp: SessionDescription) {
                            box.pc.setLocalDescription(SdpAdapter(), sdp)
                            signaler.sendAnswer(peerId, sdp.toJson())
                        }
                    }, MediaConstraints())
                }
            }, sdpJson.toSdp())
        }

        if (collision) {
            // Polite peer: roll our own offer back first, then take theirs.
            box.pc.setLocalDescription(object : SdpAdapter() {
                override fun onSetSuccess() = applyRemote()
                override fun onSetFailure(error: String?) = applyRemote()
            }, SessionDescription(SessionDescription.Type.ROLLBACK, ""))
        } else {
            applyRemote()
        }
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
            // onAddTrack (not onTrack) because it hands us the MediaStreams —
            // we need the stream id to tell a screen share from a camera.
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                val t = receiver.track()
                if (t is VideoTrack) {
                    val streamId = streams.firstOrNull()?.id ?: ""
                    events.onRemoteVideo(peerId, nicks[peerId] ?: "peer", t, streamId)
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                if (newState == PeerConnection.PeerConnectionState.FAILED ||
                    newState == PeerConnection.PeerConnectionState.CLOSED
                ) removePeer(peerId)
            }
        }) ?: error("failed to create peer connection")

        val box = PeerBox(pc)
        // Deterministic, opposite on each side — see PeerBox.polite.
        box.polite = (selfId ?: "") < peerId

        val camStream = listOf(CAM_STREAM_ID)
        localAudio?.let { pc.addTrack(it, camStream) }
        localVideo?.let { pc.addTrack(it, camStream) }
        // Already sharing when this peer joins? Send them the screen too.
        screenTrack?.let { box.screenSender = pc.addTrack(it, listOf(SCREEN_STREAM_ID)) }

        peers[peerId] = box
        return box
    }

    // ---- Screen sharing: swap the capturer feeding the shared video source ----

    /**
     * Start sharing [capturer] (a ScreenCapturerAndroid built by the activity
     * from the MediaProjection result) as an ADDITIONAL video track, so the
     * camera keeps streaming alongside it. Adding a track means the connection
     * must be renegotiated, which we do explicitly per peer.
     */
    fun startScreenShare(capturer: VideoCapturer) {
        if (screenTrack != null) return

        val src = factory.createVideoSource(true) // isScreencast
        val helper = SurfaceTextureHelper.create("ScreenCapture", eglBase.eglBaseContext)
        capturer.initialize(helper, appContext, src.capturerObserver)
        capturer.startCapture(1280, 720, 15)

        val track = factory.createVideoTrack("screen0", src)
        screenSource = src
        screenHelper = helper
        screenCapturer = capturer
        screenTrack = track

        for ((peerId, box) in peers) {
            box.screenSender = box.pc.addTrack(track, listOf(SCREEN_STREAM_ID))
            renegotiate(peerId, box)
        }
        events.onLocalScreen(track)
    }

    /** Stop sharing and drop the extra track from every peer. */
    fun stopScreenShare() {
        if (screenTrack == null) return
        for ((peerId, box) in peers) {
            box.screenSender?.let { sender ->
                runCatching { box.pc.removeTrack(sender) }
                renegotiate(peerId, box)
            }
            box.screenSender = null
        }
        try {
            screenCapturer?.stopCapture()
        } catch (_: InterruptedException) {
        }
        screenCapturer?.dispose()
        screenHelper?.dispose()
        screenSource?.dispose()
        screenCapturer = null
        screenHelper = null
        screenSource = null
        screenTrack = null
        events.onLocalScreenEnded()
    }

    /**
     * renegotiate re-offers to one peer after our track set changed. We only
     * initiate this for screen add/remove; incoming re-offers (e.g. a web peer
     * starting their own share) are handled by [onRemoteOffer].
     */
    private fun renegotiate(peerId: String, box: PeerBox) {
        box.makingOffer = true
        box.pc.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                box.pc.setLocalDescription(object : SdpAdapter() {
                    override fun onSetSuccess() {
                        signaler.sendOffer(peerId, sdp.toJson())
                        box.makingOffer = false
                    }
                    override fun onSetFailure(error: String?) {
                        box.makingOffer = false
                    }
                }, sdp)
            }
            override fun onCreateFailure(error: String?) {
                box.makingOffer = false
            }
        }, MediaConstraints())
    }

    /** Switch to a specific camera by enumerator device name. */
    fun setCameraDevice(deviceName: String) {
        (cameraCapturer as? CameraVideoCapturer)?.switchCamera(null, deviceName)
    }

    fun switchCamera() {
        (cameraCapturer as? CameraVideoCapturer)?.switchCamera(null)
    }

    // ---- Mic / camera toggles ----
    // Flipping track.enabled keeps the sender and track in place, so peers need
    // no renegotiation — the same trick the web client uses.

    /** Mute/unmute the microphone. Muting also drops our speaking ring. */
    fun setMicEnabled(on: Boolean) {
        micEnabledFlag = on
        localAudio?.setEnabled(on)
        if (!on) detectors[LOCAL_ID]?.reset()
    }

    /**
     * Turn the camera on/off. A disabled track still sends black frames, so the
     * caller must also tell peers (see SignalingClient.sendState) for them to
     * show our avatar instead of a black tile.
     */
    fun setCameraEnabled(on: Boolean) {
        localVideo?.setEnabled(on)
    }

    fun close() {
        statsHandler.removeCallbacksAndMessages(null)
        detectors.clear()
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
