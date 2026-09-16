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
import org.webrtc.RtpTransceiver
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

    /**
     * A peer's connection dropped to "disconnected" (true) or recovered (false).
     * The UI greys their tile with a "Reconnecting…" overlay while it's true;
     * if it doesn't recover within the grace period the peer is closed outright.
     */
    fun onPeerReconnecting(peerId: String, reconnecting: Boolean) {}

    /** Someone started/stopped speaking. [id] is [LOCAL_ID] or a peer id. */
    fun onSpeaking(id: String, speaking: Boolean) {}

    /** A peer's audio arrived — their volume can now be set. */
    fun onRemoteAudio(peerId: String) {}

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

// How long a peer may sit "disconnected" (frozen) before we drop them. Long
// enough to ride out a brief blip, short enough that a real drop clears fast.
const val DROP_GRACE_MS = 6000L

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

    /** Stereo transmit, fixed for the life of this call (see CallSettings.stereo). */
    private val stereo = CallSettings.stereo(appContext)
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
        // Pending "drop this peer" runnable, scheduled while it's disconnected.
        var dropRunnable: Runnable? = null
    }

    // Speaking detection. Local level comes from the mic's raw samples; remote
    // levels come from each peer connection's inbound-rtp stats (Android WebRTC
    // has no Web Audio equivalent to tap a remote track directly).
    private val detectors = ConcurrentHashMap<String, SpeakingDetector>()
    private val statsHandler = Handler(Looper.getMainLooper())
    private var micEnabledFlag = true

    /** The microphone's recording format, learned from the first captured buffer. */
    @Volatile private var recordChannels = if (stereo) 2 else 1
    @Volatile private var recordRate = 48_000

    /** Sound from other apps being mixed into what we send, while screen sharing. */
    @Volatile private var appAudio: AppAudioCapture? = null

    /**
     * Start mixing other apps' sound into our audio, using the screen share's
     * projection. Returns false when it can't (Android < 10, no microphone to
     * carry it, or capture refused).
     */
    fun startAppAudio(projection: android.media.projection.MediaProjection): Boolean {
        if (localAudio == null || appAudio != null) return appAudio != null
        val capture = AppAudioCapture.start(appContext, projection, recordRate, recordChannels) ?: return false
        appAudio = capture
        localAudio?.setEnabled(true) // mute is applied to the samples instead (see the record callback)
        return true
    }

    fun stopAppAudio() {
        val capture = appAudio ?: return
        appAudio = null
        capture.stop()
        localAudio?.setEnabled(micEnabledFlag)
    }

    val sharingAppAudio: Boolean get() = appAudio != null

    private fun silence(buffer: java.nio.ByteBuffer) {
        for (i in buffer.position() until buffer.limit()) buffer.put(i, 0)
    }

    /** Mic gain applied to the captured buffer (1.0 = untouched). */
    @Volatile
    var micGain: Float = 1.0f

    /** Live mic level (RMS 0..1) of what peers hear — read by the level meter. */
    @Volatile
    var micLevel: Double = 0.0
        private set

    /** Remote audio tracks, so each peer's volume can be set independently. */
    private val remoteAudio = ConcurrentHashMap<String, org.webrtc.AudioTrack>()

    private fun detectorFor(id: String) =
        detectors.getOrPut(id) { SpeakingDetector { on -> events.onSpeaking(id, on) } }

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )

        // Two mic hooks, doing different jobs:
        //  - setAudioRecordDataCallback hands us the real capture buffer BEFORE
        //    it's encoded, so scaling the samples there is a true mic gain —
        //    it changes what peers actually receive.
        //  - setSamplesReadyCallback is monitoring only (it gets a copy), which
        //    is all the level meter needs.
        val adm = JavaAudioDeviceModule.builder(appContext)
            // Capture in stereo only when asked; always PLAY stereo, so a web
            // peer who transmits stereo is heard that way.
            .setUseStereoInput(stereo)
            .setUseStereoOutput(true)
            .setAudioRecordDataCallback { _, channelCount, sampleRate, buffer ->
                recordChannels = channelCount
                recordRate = sampleRate
                // While sharing app sound the mic "mute" is silence here rather
                // than a disabled track, so the shared sound keeps flowing.
                if (appAudio != null && !micEnabledFlag) silence(buffer) else applyMicGain(buffer)
                appAudio?.mixInto(buffer, channelCount)
            }
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

    /**
     * Scale the captured samples in place — this buffer is what gets encoded and
     * sent, so this is a real mic gain rather than a local-only meter trick.
     * Runs on the audio thread for every buffer, so it stays allocation-free,
     * and clamps to avoid wrapping 16-bit samples into loud distortion.
     */
    private fun applyMicGain(buffer: java.nio.ByteBuffer) {
        val g = micGain
        if (g == 1.0f) return // no-op at unity: don't touch the audio at all
        val shorts = buffer.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until shorts.limit()) {
            val scaled = (shorts.get(i) * g).toInt()
            shorts.put(i, scaled.coerceIn(-32768, 32767).toShort())
        }
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
        if (n > 0) {
            val rms = sqrt(sum / n)
            micLevel = rms // post-gain: SamplesReady runs after the data callback
            detectorFor(LOCAL_ID).update(rms)
        }
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

    /**
     * Acquire the microphone and/or front camera and publish local tracks. Call
     * once. Either can be left out — no permission, or a voice channel — and the
     * call still sends and receives everything else.
     *
     * Returns whether a camera was actually opened (a device may have none).
     */
    fun startLocalMedia(mic: Boolean = true, camera: Boolean = true): Boolean {
        if (mic) {
            localAudio = factory.createAudioTrack(
                "audio0", factory.createAudioSource(MediaConstraints())
            )
        }
        if (!camera) return false

        val capturer = createCameraCapturer() ?: return false
        cameraCapturer = capturer
        currentCapturer = capturer

        videoSource = factory.createVideoSource(capturer.isScreencast)
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        capturer.initialize(surfaceHelper, appContext, videoSource.capturerObserver)
        capturer.startCapture(1280, 720, 30)

        val track = factory.createVideoTrack("video0", videoSource)
        localVideo = track
        events.onLocalVideo(track)
        return true
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
        // Nothing of our own to send still has to leave room in OUR offer to
        // receive it, or a phone without a mic or camera would never get anyone's
        // audio or video (the web client adds recvonly transceivers the same way).
        // Only the offerer needs this: an answerer's m-lines come from the offer.
        val recvOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        if (localAudio == null) box.pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, recvOnly)
        if (localVideo == null) box.pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, recvOnly)
        box.pc.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(created: SessionDescription) {
                val sdp = tuned(created)
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
                        override fun onCreateSuccess(created: SessionDescription) {
                            val sdp = tuned(created)
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
        remoteAudio.remove(peerId)
        peers.remove(peerId)?.let { box ->
            box.dropRunnable?.let { statsHandler.removeCallbacks(it) }
            box.pc.close()
        }
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
                when (val t = receiver.track()) {
                    is VideoTrack -> {
                        val streamId = streams.firstOrNull()?.id ?: ""
                        events.onRemoteVideo(peerId, nicks[peerId] ?: "peer", t, streamId)
                    }
                    is org.webrtc.AudioTrack -> {
                        remoteAudio[peerId] = t // needed for per-peer volume
                        events.onRemoteAudio(peerId)
                    }
                }
            }
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                when (newState) {
                    PeerConnection.PeerConnectionState.FAILED,
                    PeerConnection.PeerConnectionState.CLOSED -> removePeer(peerId)
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        // A blip that can recover — don't yank them instantly (that's
                        // the "frozen" state). Grey the tile and give it a few seconds.
                        events.onPeerReconnecting(peerId, true)
                        peers[peerId]?.let { box ->
                            box.dropRunnable?.let { statsHandler.removeCallbacks(it) }
                            val r = Runnable {
                                if (box.pc.connectionState() == PeerConnection.PeerConnectionState.DISCONNECTED) {
                                    removePeer(peerId)
                                }
                            }
                            box.dropRunnable = r
                            statsHandler.postDelayed(r, DROP_GRACE_MS)
                        }
                    }
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        peers[peerId]?.let { box ->
                            box.dropRunnable?.let { statsHandler.removeCallbacks(it) }
                            box.dropRunnable = null
                        }
                        events.onPeerReconnecting(peerId, false)
                    }
                    else -> {}
                }
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
        stopAppAudio()
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

    /** [sdp] with the Opus stereo flags applied when this call transmits stereo. */
    private fun tuned(sdp: SessionDescription): SessionDescription =
        if (stereo) SessionDescription(sdp.type, tuneOpusStereo(sdp.description)) else sdp

    /**
     * renegotiate re-offers to one peer after our track set changed. We only
     * initiate this for screen add/remove; incoming re-offers (e.g. a web peer
     * starting their own share) are handled by [onRemoteOffer].
     */
    private fun renegotiate(peerId: String, box: PeerBox) {
        box.makingOffer = true
        box.pc.createOffer(object : SdpAdapter() {
            override fun onCreateSuccess(created: SessionDescription) {
                val sdp = tuned(created)
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
        // Sharing app sound keeps the track on; the callback silences the mic.
        localAudio?.setEnabled(on || appAudio != null)
        if (!on) {
            detectors[LOCAL_ID]?.reset()
            micLevel = 0.0
        }
    }

    /**
     * Set one peer's playback volume. WebRTC's AudioTrack takes 0..10, so unlike
     * the web (capped at 1.0 by the media element) we can boost a quiet talker.
     */
    fun setPeerVolume(peerId: String, volume: Double) {
        remoteAudio[peerId]?.setVolume(volume.coerceIn(0.0, 10.0))
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
        stopAppAudio()
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

/**
 * tuneOpusStereo adds `stereo=1;sprop-stereo=1` to the Opus fmtp line so the
 * far side decodes (and sends) two channels. Same rewrite as the web client's
 * tuneAudio, so a phone and a browser agree. Mono calls never come here, which
 * keeps the default SDP byte-for-byte what it was before stereo existed.
 */
internal fun tuneOpusStereo(sdp: String): String {
    val pt = Regex("a=rtpmap:(\\d+) opus/48000", RegexOption.IGNORE_CASE).find(sdp)?.groupValues?.get(1)
        ?: return sdp
    val params = "stereo=1;sprop-stereo=1"
    val lines = sdp.split("\r\n").toMutableList()
    val fmtp = lines.indexOfFirst { it.startsWith("a=fmtp:$pt ") }
    if (fmtp >= 0) {
        if (!lines[fmtp].contains("stereo=1")) lines[fmtp] = lines[fmtp] + ";" + params
    } else {
        // No fmtp line for Opus yet: add one straight after its rtpmap.
        val rtpmap = lines.indexOfFirst { it.startsWith("a=rtpmap:$pt ") }
        if (rtpmap >= 0) lines.add(rtpmap + 1, "a=fmtp:$pt $params")
    }
    return lines.joinToString("\r\n")
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
