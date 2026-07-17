package com.takeback.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.takeback.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.random.Random

/**
 * MainActivity drives the three-step flow — nickname, lobby (host/join), call —
 * and hosts the video grid. It wires [SignalingClient] to [RtcEngine] and mirrors
 * the web client: newcomers offer to everyone already in the room (full mesh),
 * and a share toggle swaps the camera for the screen.
 */
class MainActivity : AppCompatActivity(), SignalingListener, Signaler, RtcEvents {

    companion object {
        /** Optional call-code extra; when present, the screen auto-joins. */
        const val EXTRA_ROOM = "room"

        /** Ring colour for "this person is speaking". */
        private val SPEAK_GREEN: Int = Color.parseColor("#22C55E")
    }

    private lateinit var binding: ActivityMainBinding
    private val eglBase: EglBase by lazy { EglBase.create() }

    private var signaling: SignalingClient? = null
    private var engine: RtcEngine? = null
    private var nick: String = ""
    private var roomCode: String = ""
    private var sharing = false
    private var micOn = true
    private var camOn = true

    private val tiles = HashMap<String, Tile>()
    // peerId -> (video, audio, screenId). screenId names the stream carrying
    // their screen share, so we can tell it apart from their camera.
    private val peerState = HashMap<String, Triple<Boolean, Boolean, String>>()
    // peerId -> their video tracks and the stream each arrived on.
    private val remoteTracks = HashMap<String, MutableList<Pair<VideoTrack, String>>>()

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) beginCall() else toast("Camera & mic permission required")
    }

    private val mediaProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) startScreenCapture(data)
        else toast("Screen share cancelled")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.nickBtn.setOnClickListener {
            val v = binding.nickInput.text.toString().trim()
            if (v.isEmpty()) return@setOnClickListener
            nick = v
            binding.nickStep.visibility = View.GONE
            binding.lobbyStep.visibility = View.VISIBLE
        }
        binding.hostBtn.setOnClickListener { requestCall(randomCode()) }
        binding.joinBtn.setOnClickListener {
            val c = binding.joinInput.text.toString().trim().uppercase()
            if (c.isNotEmpty()) requestCall(c)
        }
        binding.copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("code", roomCode))
            toast("Call code copied")
        }
        binding.flipBtn.setOnClickListener { engine?.switchCamera() }
        binding.shareBtn.setOnClickListener { if (sharing) stopScreenShare() else requestScreenShare() }
        binding.leaveBtn.setOnClickListener { leaveCall() }
        binding.settingsBtn.setOnClickListener { toggleSettings() }
        setupSettingsPanel()

        binding.micBtn.setOnClickListener {
            micOn = !micOn
            engine?.setMicEnabled(micOn)
            binding.micBtn.text = if (micOn) "🎤" else "🔇"
            tiles[LOCAL_ID]?.muted = !micOn
            refreshTile(LOCAL_ID)
            broadcastState()
        }
        binding.camBtn.setOnClickListener {
            camOn = !camOn
            engine?.setCameraEnabled(camOn)
            binding.camBtn.text = if (camOn) "📷" else "🚫"
            tiles[LOCAL_ID]?.videoOn = camOn
            refreshTile(LOCAL_ID)
            broadcastState()
        }

        // Launched from a chat with a call code: use the logged-in nick and join
        // straight away, skipping the nickname/lobby steps.
        val room = intent.getStringExtra(EXTRA_ROOM)
        if (room != null) {
            binding.nickStep.visibility = View.GONE
            lifecycleScope.launch {
                nick = runCatching { com.takeback.app.net.ApiClient.me().nick }.getOrDefault("guest")
                requestCall(room.uppercase())
            }
        }
    }

    // ---- Call setup ----

    private fun requestCall(code: String) {
        roomCode = code
        permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }

    private fun beginCall() {
        binding.lobbyStep.visibility = View.GONE
        binding.callStep.visibility = View.VISIBLE
        binding.callCode.text = roomCode

        val engine = RtcEngine(applicationContext, eglBase, this, this).also { this.engine = it }
        engine.startLocalMedia()

        val signalUrl = BuildConfig.BASE_URL.replaceFirst(Regex("^http"), "ws").trimEnd('/') + "/ws"
        signaling = SignalingClient(signalUrl, roomCode, nick, this).also { it.connect() }
    }

    private fun leaveCall() {
        signaling?.close(); signaling = null
        engine?.close(); engine = null
        stopService(Intent(this, ScreenCaptureService::class.java))
        tiles.values.forEach { it.renderer.release() }
        tiles.clear()
        peerState.clear()
        remoteTracks.clear()
        binding.videoGrid.removeAllViews()
        binding.callStep.visibility = View.GONE
        binding.lobbyStep.visibility = View.VISIBLE
    }

    // ---- Settings panel ----

    private fun toggleSettings() {
        val p = binding.settingsPanel
        p.visibility = if (p.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun setupSettingsPanel() {
        // Mirror: self-view only, remembered across calls.
        binding.mirrorChk.isChecked = CallSettings.mirror(this)
        binding.mirrorChk.setOnCheckedChangeListener { _, on ->
            CallSettings.setMirror(this, on)
            applyMirror()
        }

        // Cameras.
        val cams = CallSettings.cameras(this)
        binding.cameraSelect.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, cams.map { it.second })
        val savedCam = CallSettings.cameraName(this)
        cams.indexOfFirst { it.first == savedCam }.takeIf { it >= 0 }
            ?.let { binding.cameraSelect.setSelection(it) }
        binding.cameraSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val name = cams.getOrNull(pos)?.first ?: return
                if (name == CallSettings.cameraName(this@MainActivity)) return
                CallSettings.setCameraName(this@MainActivity, name)
                engine?.setCameraDevice(name)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Audio route (Android 12+ only; see CallSettings).
        val audio = CallSettings.audioOptions(this)
        binding.audioSelect.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, audio.map { it.label })
        binding.audioSelect.isEnabled = audio.size > 1
        binding.audioSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val opt = audio.getOrNull(pos) ?: return
                if (!CallSettings.applyAudioOption(this@MainActivity, opt.id) && opt.id >= 0) {
                    toast("Couldn't switch audio device")
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /** Mirror only our own camera tile — never the screen, never what peers get. */
    private fun applyMirror() {
        tiles[LOCAL_ID]?.renderer?.setMirror(CallSettings.mirror(this))
    }

    // ---- Screen sharing ----

    private fun requestScreenShare() {
        // A mediaProjection-typed foreground service must be live first (API 29+).
        androidx.core.content.ContextCompat.startForegroundService(
            this, Intent(this, ScreenCaptureService::class.java)
        )
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection.launch(mgr.createScreenCaptureIntent())
    }

    private fun startScreenCapture(data: Intent) {
        val capturer = ScreenCapturerAndroid(data, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() = runOnUiThread { stopScreenShare() }
        })
        // Tell peers our screen's stream id BEFORE the track arrives, so they can
        // tell it apart from our camera when it does.
        sharing = true
        broadcastState()
        engine?.startScreenShare(capturer) // adds a 2nd track; camera keeps running
        binding.shareBtn.text = getString(R.string.stop_sharing)
    }

    private fun stopScreenShare() {
        if (!sharing) return
        engine?.stopScreenShare()
        stopService(Intent(this, ScreenCaptureService::class.java))
        sharing = false
        binding.shareBtn.text = getString(R.string.share_screen)
        broadcastState()
    }

    // ---- SignalingListener (WebSocket thread) ----

    override fun onWelcome(selfId: String, peers: List<RemotePeer>) = runOnUiThread {
        engine?.selfId = selfId // needed for the renegotiation polite tiebreak
        broadcastState() // announce our initial mic/camera state to the room
        peers.forEach { engine?.offerTo(it.id, it.nick) }
        binding.status.text = if (peers.isEmpty()) getString(R.string.waiting) else getString(R.string.connecting)
    }

    override fun onHello(fromId: String, nick: String) {
        // A newcomer arrived: re-announce so they render us correctly at once.
        broadcastState()
    }

    override fun onState(fromId: String, video: Boolean, audio: Boolean, screenId: String) =
        runOnUiThread {
            peerState[fromId] = Triple(video, audio, screenId)
            applyState(fromId, video, audio)
            // The screen id may have only just arrived — re-route their tracks.
            routeTracks(fromId, tiles[fromId]?.label?.text?.toString() ?: "peer")
            if (screenId.isEmpty()) { // they stopped sharing
                tiles.remove("$fromId-screen")?.let { t ->
                    binding.videoGrid.removeView(t.root); t.renderer.release()
                }
            }
        }

    override fun onSpeaking(id: String, speaking: Boolean) = runOnUiThread {
        tiles[id]?.speaking = speaking
        refreshTile(id)
    }

    private fun broadcastState() {
        // Camera and screen are independent tracks now, so `video` is just the
        // camera; the screen is identified by its stream id.
        signaling?.sendState(camOn, micOn, if (sharing) SCREEN_STREAM_ID else "")
    }

    override fun onOffer(fromId: String, nick: String, sdpJson: JSONObject) =
        runOnUiThread { engine?.onRemoteOffer(fromId, nick, sdpJson) }

    override fun onAnswer(fromId: String, sdpJson: JSONObject) =
        runOnUiThread { engine?.onRemoteAnswer(fromId, sdpJson) }

    override fun onCandidate(fromId: String, candidateJson: JSONObject) =
        runOnUiThread { engine?.onRemoteCandidate(fromId, candidateJson) }

    override fun onLeave(fromId: String) = runOnUiThread { engine?.removePeer(fromId) }

    override fun onClosed(reason: String) = runOnUiThread { binding.status.text = getString(R.string.disconnected) }

    // ---- RtcEvents (signaling thread) ----

    override fun onLocalVideo(track: VideoTrack) = runOnUiThread {
        attachRenderer(LOCAL_ID, nick, track)
    }

    override fun onRemoteVideo(peerId: String, nick: String, track: VideoTrack, streamId: String) =
        runOnUiThread {
            // Remember it: a peer's `state` (which names their screen stream) can
            // arrive after the track, so we may need to re-decide later.
            remoteTracks.getOrPut(peerId) { mutableListOf() }.add(track to streamId)
            routeTracks(peerId, nick)
            binding.status.text = getString(R.string.connected)
        }

    /** Decide which of a peer's tracks is their camera and which is their screen. */
    private fun routeTracks(peerId: String, nick: String) {
        val screenId = peerState[peerId]?.third.orEmpty()
        for ((track, streamId) in remoteTracks[peerId].orEmpty()) {
            if (streamId.isNotEmpty() && streamId == screenId) {
                attachRenderer("$peerId-screen", "$nick's screen", track)
            } else {
                attachRenderer(peerId, nick, track)
            }
        }
    }

    override fun onLocalScreen(track: VideoTrack) = runOnUiThread {
        attachRenderer(LOCAL_SCREEN_ID, "Your screen", track)
    }

    override fun onLocalScreenEnded() = runOnUiThread {
        tiles.remove(LOCAL_SCREEN_ID)?.let { t ->
            binding.videoGrid.removeView(t.root)
            t.renderer.release()
        }
    }

    override fun onPeerClosed(peerId: String) = runOnUiThread {
        tiles.remove(peerId)?.let { t ->
            binding.videoGrid.removeView(t.root)
            t.renderer.release()
        }
        tiles.remove("$peerId-screen")?.let { t ->
            binding.videoGrid.removeView(t.root)
            t.renderer.release()
        }
        peerState.remove(peerId)
        remoteTracks.remove(peerId)
    }

    // ---- Video grid ----

    /**
     * One participant's tile: their video, or their profile picture when the
     * camera is off, plus a green ring while they're speaking and a badge when
     * their mic is muted.
     */
    private class Tile(
        val root: FrameLayout,
        val renderer: SurfaceViewRenderer,
        val avatar: TextView,
        val micBadge: TextView,
        val label: TextView,
    ) {
        var speaking = false
        var videoOn = true
        var muted = false
    }

    private fun attachRenderer(key: String, nick: String, track: VideoTrack) {
        tiles[key]?.let { track.addSink(it.renderer); return }

        val renderer = SurfaceViewRenderer(this).apply {
            init(eglBase.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setMirror(key == LOCAL_ID && CallSettings.mirror(this@MainActivity))
        }
        val avatar = TextView(this).apply {
            text = initialsOf(nick)
            setTextColor(Color.WHITE)
            textSize = 26f
            gravity = Gravity.CENTER
            visibility = View.GONE
            val d = (96 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(d, d, Gravity.CENTER)
        }
        val micBadge = TextView(this).apply {
            text = "🔇"
            visibility = View.GONE
            setPadding(8, 4, 8, 4)
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP)
        }
        val label = TextView(this).apply {
            text = nick
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(12, 4, 12, 4)
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.BOTTOM)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(renderer, FrameLayout.LayoutParams(-1, -1))
            addView(avatar); addView(micBadge); addView(label)
        }

        val params = android.widget.GridLayout.LayoutParams().apply {
            width = 0
            height = resources.displayMetrics.heightPixels / 3
            columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            setMargins(8, 8, 8, 8)
        }
        binding.videoGrid.addView(root, params)

        val tile = Tile(root, renderer, avatar, micBadge, label)
        tiles[key] = tile
        avatar.background = avatarBg(nick, speaking = false)
        track.addSink(renderer)

        // State may have arrived before this peer's track did.
        peerState[key]?.let { (video, audio, _) -> applyState(key, video, audio) }
        refreshTile(key)
    }

    /** Circle behind the initials; gains a green stroke while speaking. */
    private fun avatarBg(nick: String, speaking: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(colorFor(nick))
            if (speaking) setStroke((3 * resources.displayMetrics.density).toInt(), SPEAK_GREEN)
        }

    /** Repaint a tile from its current speaking/video/muted state. */
    private fun refreshTile(key: String) {
        val t = tiles[key] ?: return
        t.renderer.visibility = if (t.videoOn) View.VISIBLE else View.GONE
        t.avatar.visibility = if (t.videoOn) View.GONE else View.VISIBLE
        t.micBadge.visibility = if (t.muted) View.VISIBLE else View.GONE

        // A muted mic must never look like it's transmitting.
        val ringing = t.speaking && !t.muted
        if (t.videoOn) {
            // Video: ring the whole tile.
            t.root.foreground = if (ringing) GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke((3 * resources.displayMetrics.density).toInt(), SPEAK_GREEN)
            } else null
        } else {
            t.root.foreground = null
            t.avatar.background = avatarBg(nickOf(key), ringing)
        }
    }

    private fun nickOf(key: String): String =
        if (key == LOCAL_ID) nick else (tiles[key]?.label?.text?.toString() ?: "?")

    private fun initialsOf(nick: String) = (nick.ifEmpty { "?" }).take(2).uppercase()

    /** Same colour-from-nickname hash as the web client, so avatars match. */
    private fun colorFor(nick: String): Int {
        var h = 0L
        for (c in nick) h = (h * 31 + c.code) and 0xFFFFFFFFL
        return Color.HSVToColor(floatArrayOf((h % 360).toFloat(), 0.55f, 0.42f))
    }

    private fun applyState(id: String, video: Boolean, audio: Boolean) {
        tiles[id]?.let { it.videoOn = video; it.muted = !audio }
        refreshTile(id)
    }

    // ---- Signaler (out) ----

    override fun sendOffer(to: String, sdp: JSONObject) { signaling?.sendOffer(to, sdp) }
    override fun sendAnswer(to: String, sdp: JSONObject) { signaling?.sendAnswer(to, sdp) }
    override fun sendCandidate(to: String, candidate: JSONObject) { signaling?.sendCandidate(to, candidate) }

    // ---- misc ----

    private fun randomCode(): String {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return (1..6).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        engine?.close()
        eglBase.release()
    }
}
