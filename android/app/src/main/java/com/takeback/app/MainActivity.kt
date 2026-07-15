package com.takeback.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.takeback.app.databinding.ActivityMainBinding
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

    private lateinit var binding: ActivityMainBinding
    private val eglBase: EglBase by lazy { EglBase.create() }

    private var signaling: SignalingClient? = null
    private var engine: RtcEngine? = null
    private var nick: String = ""
    private var roomCode: String = ""
    private var sharing = false

    private val renderers = HashMap<String, SurfaceViewRenderer>()

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

        signaling = SignalingClient(BuildConfig.SIGNAL_URL, roomCode, nick, this).also { it.connect() }
    }

    private fun leaveCall() {
        signaling?.close(); signaling = null
        engine?.close(); engine = null
        stopService(Intent(this, ScreenCaptureService::class.java))
        renderers.values.forEach { it.release() }
        renderers.clear()
        binding.videoGrid.removeAllViews()
        binding.callStep.visibility = View.GONE
        binding.lobbyStep.visibility = View.VISIBLE
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
        engine?.switchToScreen(capturer)
        sharing = true
        binding.shareBtn.text = getString(R.string.stop_sharing)
    }

    private fun stopScreenShare() {
        if (!sharing) return
        engine?.switchToCamera()
        stopService(Intent(this, ScreenCaptureService::class.java))
        sharing = false
        binding.shareBtn.text = getString(R.string.share_screen)
    }

    // ---- SignalingListener (WebSocket thread) ----

    override fun onWelcome(selfId: String, peers: List<RemotePeer>) = runOnUiThread {
        peers.forEach { engine?.offerTo(it.id, it.nick) }
        binding.status.text = if (peers.isEmpty()) getString(R.string.waiting) else getString(R.string.connecting)
    }

    override fun onHello(fromId: String, nick: String) { /* wait for their offer */ }

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
        attachRenderer("local", track)
    }

    override fun onRemoteVideo(peerId: String, nick: String, track: VideoTrack) = runOnUiThread {
        attachRenderer(peerId, track)
        binding.status.text = getString(R.string.connected)
    }

    override fun onPeerClosed(peerId: String) = runOnUiThread {
        renderers.remove(peerId)?.let { r ->
            binding.videoGrid.removeView(r.parent as View)
            r.release()
        }
    }

    // ---- Video grid ----

    private fun attachRenderer(key: String, track: VideoTrack) {
        renderers[key]?.let { track.addSink(it); return }

        val renderer = SurfaceViewRenderer(this).apply {
            init(eglBase.eglBaseContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setMirror(key == "local")
        }
        val tile = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(0, 0) // sized by grid weights below
            addView(renderer)
        }
        // Two columns; each tile takes half width and a fixed-ish height.
        val params = android.widget.GridLayout.LayoutParams().apply {
            width = 0
            height = resources.displayMetrics.heightPixels / 3
            columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            setMargins(8, 8, 8, 8)
        }
        binding.videoGrid.addView(tile, params)
        renderers[key] = renderer
        track.addSink(renderer)
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
