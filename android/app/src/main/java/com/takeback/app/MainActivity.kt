package com.takeback.app

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
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

        /** Set when the call is a server's voice channel: microphone only. */
        const val EXTRA_VOICE_SERVER = "voiceServer"
        const val EXTRA_VOICE_CHANNEL = "voiceChannel"
        const val EXTRA_VOICE_NAME = "voiceName"

        /** Ring colour for "this person is speaking". */
        private val SPEAK_GREEN: Int = tbColor(R.color.tb_online)
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
    private var inCall = false // true between beginCall() and leaveCall(); gates PiP
    private var defaultNetwork: Network? = null
    private var networkCallbackRegistered = false
    private var audioCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = runOnUiThread {
            if (!inCall) return@runOnUiThread
            val previous = defaultNetwork
            defaultNetwork = network
            if (previous != null && previous != network) {
                binding.status.text = getString(R.string.connecting)
                signaling?.networkChanged()
                engine?.restartIce()
            }
        }
        override fun onLost(network: Network) = runOnUiThread {
            if (inCall && defaultNetwork == network) binding.status.text = getString(R.string.disconnected)
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refreshAutomaticAudioRoute()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refreshAutomaticAudioRoute()
    }
    /** Whether this call has a microphone track (permission granted). */
    private var micAvailable = false
    /** Whether a camera is open in this call (false in a voice channel). */
    private var cameraOpen = false

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startVideo()
        else toast("Camera permission was denied. Allow it in Settings to use video.")
    }
    /** The voice channel this call is, or null for an ordinary call. */
    private var voice: Calls.Voice? = null
    /** Launched to join a specific call, so leaving it closes this screen. */
    private var launchedForRoom = false

    /** The room of the call in progress, or null. Read by [Calls]. */
    val inCallRoom: String? get() = if (inCall) roomCode else null

    private val tiles = HashMap<String, Tile>()
    // peerId -> (video, audio, screenId). screenId names the stream carrying
    // their screen share, so we can tell it apart from their camera.
    private val peerState = HashMap<String, Triple<Boolean, Boolean, String>>()
    // peerId -> their video tracks and the stream each arrived on.
    private val remoteTracks = HashMap<String, MutableList<Pair<VideoTrack, String>>>()
    /** peerId -> playback volume (1.0 = as sent). Kept so it survives re-render. */
    private val peerVolumes = HashMap<String, Double>()
    /** peerId -> their profile picture, as announced in their state. */
    private val peerAvatars = HashMap<String, String>()
    /** My own profile picture, sent to everyone in the call. */
    private var myAvatarUrl = ""

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Join with whatever we were allowed, like the web client: a denied
        // camera shouldn't keep you out of a call, and neither should a denied
        // microphone. You can still listen, watch and share your screen.
        val mic = result[Manifest.permission.RECORD_AUDIO] == true
        val cam = voice == null && result[Manifest.permission.CAMERA] == true
        val missing = listOfNotNull(
            "microphone".takeIf { !mic },
            "camera".takeIf { !cam && voice == null },
        )
        if (missing.isNotEmpty()) {
            toast("Joined without your ${missing.joinToString(" and ")}: permission was denied. Allow it in Settings and rejoin to use it.")
        }
        beginCall(mic, cam)
    }

    private val mediaProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) startScreenCapture(data)
        else {
            // The service is started before the picker (Android requires it to
            // already be running), so turning the picker down has to take it
            // away again — otherwise "Sharing your screen" stays in the shade
            // while nothing is shared, and stops meaning anything.
            stopService(Intent(this, ScreenCaptureService::class.java))
            toast("Screen share cancelled")
        }
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
        binding.shareBtn.setOnClickListener { if (sharing) stopScreenShare() else askShareAudio() }
        binding.leaveBtn.setOnClickListener { endCall() }
        binding.settingsBtn.setOnClickListener { toggleSettings() }
        setupSettingsPanel()

        binding.micBtn.setOnClickListener {
            micOn = !micOn
            engine?.setMicEnabled(micOn)
            binding.micBtn.setImageResource(if (micOn) R.drawable.ic_mic_on else R.drawable.ic_mic_off)
            tiles[LOCAL_ID]?.muted = !micOn
            refreshTile(LOCAL_ID)
            broadcastState()
        }
        binding.camBtn.setOnClickListener {
            // No camera track yet (a voice channel, or we joined without one):
            // ask for it and turn it on, rather than toggling nothing.
            if (!cameraOpen) { startVideo(); return@setOnClickListener }
            camOn = !camOn
            engine?.setCameraEnabled(camOn)
            binding.camBtn.setImageResource(R.drawable.ic_camera)
            binding.camBtn.imageAlpha = if (camOn) 255 else 110
            tiles[LOCAL_ID]?.videoOn = camOn
            refreshTile(LOCAL_ID)
            broadcastState()
        }

        // Launched from a chat with a call code: use the logged-in nick and join
        // straight away, skipping the nickname/lobby steps.
        Calls.attach(this)
        val room = intent.getStringExtra(EXTRA_ROOM)
        if (intent.hasExtra(EXTRA_VOICE_CHANNEL)) {
            voice = Calls.Voice(
                intent.getLongExtra(EXTRA_VOICE_SERVER, 0),
                intent.getLongExtra(EXTRA_VOICE_CHANNEL, 0),
                intent.getStringExtra(EXTRA_VOICE_NAME) ?: "voice",
            )
        }
        if (room != null) {
            launchedForRoom = true
            binding.nickStep.visibility = View.GONE
            lifecycleScope.launch {
                val me = runCatching { com.takeback.app.net.ApiClient.me() }.getOrNull()
                nick = me?.nick ?: "guest"
                myAvatarUrl = me?.avatarUrl.orEmpty()
                requestCall(room.uppercase())
            }
        }
    }

    // ---- Call setup ----

    private fun requestCall(code: String) {
        roomCode = code
        // A voice channel never opens the camera, so don't ask for it.
        permissions.launch(
            if (voice != null) arrayOf(Manifest.permission.RECORD_AUDIO)
            else arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        )
    }

    private fun beginCall(mic: Boolean, camera: Boolean) {
        inCall = true
        binding.lobbyStep.visibility = View.GONE
        binding.callStep.visibility = View.VISIBLE
        val v = voice
        // A channel gets the speaker glyph and ordinary type; a call code stays
        // monospace, because it is something you read out character by character.
        binding.callCode.text = v?.name ?: roomCode
        binding.callCode.typeface =
            if (v != null) android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.MONOSPACE
        binding.callCode.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (v != null) R.drawable.ic_voice_channel else 0, 0, 0, 0
        )
        binding.callCode.compoundDrawableTintList =
            android.content.res.ColorStateList.valueOf(tbColor(R.color.tb_muted))
        binding.callCode.compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
        // A voice channel is identified by its name; its room code is a secret
        // that only admits members anyway, so there's nothing to copy.
        binding.copyBtn.visibility = if (v != null) View.GONE else View.VISIBLE
        Calls.voice = v

        val engine = RtcEngine(applicationContext, eglBase, this, this).also { this.engine = it }
        // The settings panel is wired up in onCreate, before this engine exists,
        // so the saved gain has to be applied here or it would never take effect.
        engine.micGain = CallSettings.micGain(this)
        val cameraOpened = engine.startLocalMedia(mic = mic, camera = camera)

        micOn = mic
        micAvailable = mic
        cameraOpen = cameraOpened
        camOn = cameraOpened
        binding.micBtn.isEnabled = mic
        binding.micBtn.setImageResource(if (mic) R.drawable.ic_mic_on else R.drawable.ic_mic_off)
        // With no camera the button OPENS one instead of being hidden: that's
        // how video gets turned on inside a voice channel.
        binding.camBtn.setImageResource(R.drawable.ic_camera)
        binding.flipBtn.visibility = if (cameraOpened) View.VISIBLE else View.GONE
        if (!cameraOpened) showLocalAvatarTile()

        // Hold the call open when you leave the app: without a foreground
        // service Android suspends us and the audio stops.
        CallService.start(this, if (v != null) "In ${v.name}" else "In a call")
        startCallSystemListeners()

        // The session cookie rides along (ApiClient.http), which a voice channel
        // requires; and use the server the app is pointed at, not the default.
        val signalUrl = com.takeback.app.net.ApiClient.base.replaceFirst(Regex("^http"), "ws").trimEnd('/') + "/ws"
        signaling = SignalingClient(signalUrl, roomCode, nick, this, com.takeback.app.net.ApiClient.http)
            .also { it.connect() }
    }

    /** Turn the camera on mid-call, asking for permission first if needed. */
    private fun startVideo() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        if (engine?.startCamera() != true) { toast("No camera on this device"); return }
        toast("Camera on")
        cameraOpen = true
        camOn = true
        binding.flipBtn.visibility = View.VISIBLE
        tiles[LOCAL_ID]?.videoOn = true
        refreshTile(LOCAL_ID)
        broadcastState()
    }

    /**
     * Without a camera there's no local video track to hang a tile on, so show
     * our avatar tile anyway: you should always see yourself in the call.
     */
    private fun showLocalAvatarTile() {
        attachRenderer(LOCAL_ID, nick, null)
        tiles[LOCAL_ID]?.let { it.videoOn = false; it.muted = !micOn }
        refreshTile(LOCAL_ID)
    }

    /** Leave the call and, if this screen was opened for it, close the screen. */
    fun endCall() {
        leaveCall()
        if (launchedForRoom) finish()
    }

    private fun leaveCall() {
        if (Calls.voice == voice) Calls.voice = null
        inCall = false
        stopCallSystemListeners()
        CallService.stop(this)
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

    private fun startCallSystemListeners() {
        if (!networkCallbackRegistered) {
            runCatching {
                getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
                networkCallbackRegistered = true
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !audioCallbackRegistered) {
            getSystemService(AudioManager::class.java).registerAudioDeviceCallback(
                audioDeviceCallback, Handler(Looper.getMainLooper()))
            audioCallbackRegistered = true
            CallSettings.applySavedAudioOption(this)
        }
    }

    private fun stopCallSystemListeners() {
        if (networkCallbackRegistered) {
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
            defaultNetwork = null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && audioCallbackRegistered) {
            getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(audioDeviceCallback)
            getSystemService(AudioManager::class.java).clearCommunicationDevice()
            audioCallbackRegistered = false
        }
    }

    private fun refreshAutomaticAudioRoute() = runOnUiThread {
        if (inCall && CallSettings.audioDeviceType(this) < 0) CallSettings.applySavedAudioOption(this)
    }

    // ---- Settings panel ----

    private fun toggleSettings() {
        val p = binding.settingsPanel
        val show = p.visibility != View.VISIBLE
        p.visibility = if (show) View.VISIBLE else View.GONE
        if (show) { renderVolumes(); startMeter() } else stopMeter()
    }

    // The meter only runs while the panel is open — no point polling otherwise.
    private val meterHandler = Handler(Looper.getMainLooper())
    private var meterRunning = false

    private fun startMeter() {
        if (meterRunning) return
        meterRunning = true
        meterHandler.post(object : Runnable {
            override fun run() {
                if (!meterRunning) return
                // Same scaling as the web client so the bars feel alike.
                val pct = ((engine?.micLevel ?: 0.0) / 0.25 * 100).toInt().coerceIn(0, 100)
                binding.micMeter.progress = pct
                meterHandler.postDelayed(this, 80)
            }
        })
    }

    private fun stopMeter() {
        meterRunning = false
        meterHandler.removeCallbacksAndMessages(null)
    }

    /** One volume slider per remote participant. */
    private fun renderVolumes() {
        binding.volumes.removeAllViews()
        val others = tiles.keys.filter { it != LOCAL_ID && it != LOCAL_SCREEN_ID && !it.endsWith("-screen") }
        if (others.isEmpty()) {
            binding.volumes.addView(TextView(this).apply {
                text = getString(R.string.nobody_else)
                setTextColor(tbColor(R.color.tb_dim)); textSize = 12f
            })
            return
        }
        for (peerId in others) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = tiles[peerId]?.label?.text ?: peerId
                setTextColor(tbColor(R.color.tb_text)); textSize = 13f
                width = (90 * resources.displayMetrics.density).toInt()
                maxLines = 1
            })
            val valueLabel = TextView(this).apply {
                setTextColor(tbColor(R.color.tb_muted)); textSize = 12f
                width = (48 * resources.displayMetrics.density).toInt()
                gravity = Gravity.END
            }
            val current = peerVolumes[peerId] ?: 1.0
            row.addView(SeekBar(this).apply {
                // 0–200%: WebRTC's AudioTrack takes 0..10, so unlike the web we
                // can actually boost a quiet talker past 100%.
                max = 200
                progress = (current * 100).toInt()
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                        if (!fromUser) return
                        val v = value / 100.0
                        peerVolumes[peerId] = v
                        engine?.setPeerVolume(peerId, v)
                        valueLabel.text = "$value%"
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {}
                })
            })
            valueLabel.text = "${(current * 100).toInt()}%"
            row.addView(valueLabel)
            binding.volumes.addView(row)
        }
    }

    private fun setupSettingsPanel() {
        // Mic gain: scales the captured buffer before it's encoded, so this is
        // what peers actually hear. Remembered across calls.
        val savedGain = CallSettings.micGain(this)
        binding.micGain.progress = (savedGain * 100).toInt()
        binding.micGainVal.text = "${(savedGain * 100).toInt()}%"
        engine?.micGain = savedGain
        binding.micGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val g = value / 100f
                binding.micGainVal.text = "$value%"
                engine?.micGain = g
                CallSettings.setMicGain(this@MainActivity, g)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Video scaling: fit (whole frame, letterboxed) or fill (cropped).
        // Stereo: read when the engine is built, so it applies to the next call.
        binding.stereoChk.isChecked = CallSettings.stereo(this)
        binding.stereoChk.setOnCheckedChangeListener { _, on -> CallSettings.setStereo(this, on) }

        binding.fillChk.isChecked = CallSettings.videoFill(this)
        binding.fillChk.setOnCheckedChangeListener { _, on ->
            CallSettings.setVideoFill(this, on)
            applyScaling()
        }

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

        val qualities = CallSettings.VideoQuality.entries
        binding.videoQualitySelect.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, qualities.map { it.label })
        binding.videoQualitySelect.setSelection(qualities.indexOf(CallSettings.videoQuality(this)))
        binding.videoQualitySelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val quality = qualities.getOrNull(pos) ?: return
                if (quality == CallSettings.videoQuality(this@MainActivity)) return
                CallSettings.setVideoQuality(this@MainActivity, quality)
                engine?.applyVideoQuality()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Audio route (Android 12+ only; see CallSettings).
        val audio = CallSettings.audioOptions(this)
        binding.audioSelect.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, audio.map { it.label })
        binding.audioSelect.isEnabled = audio.size > 1
        val savedAudioType = CallSettings.audioDeviceType(this)
        audio.indexOfFirst { it.type == savedAudioType }.takeIf { it >= 0 }
            ?.let { binding.audioSelect.setSelection(it) }
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

    /** Whether to share app sound along with the screen, asked before the system prompt. */
    private var shareAudioWanted = false

    /**
     * Ask whether to share sound too, like the web client's "Share audio" box.
     * Skipped where it can't work: Android 9 and older, or no microphone in this
     * call (the sound travels on the microphone's track).
     */
    private fun askShareAudio() {
        if (!AppAudioCapture.supported || !micAvailable) { shareAudioWanted = false; requestScreenShare(); return }
        var checked = CallSettings.shareAudio(this)
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Share your screen")
            .setMultiChoiceItems(arrayOf("Also share sound from apps"), booleanArrayOf(checked)) { _, _, on -> checked = on }
            .setPositiveButton("Continue") { _, _ ->
                CallSettings.setShareAudio(this, checked)
                shareAudioWanted = checked
                requestScreenShare()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
        binding.shareBtn.imageAlpha = 255   // presenting
        if (shareAudioWanted) {
            // The projection exists once capture has started.
            val projection = capturer.mediaProjection
            val ok = projection != null && engine?.startAppAudio(projection) == true
            toast(if (ok) "Sharing your screen and its sound. Apps that block capture stay silent."
                  else "Sharing your screen without sound: this phone couldn't capture it.")
        }
    }

    private fun stopScreenShare() {
        if (!sharing) return
        engine?.stopScreenShare()
        stopService(Intent(this, ScreenCaptureService::class.java))
        sharing = false
        binding.shareBtn.imageAlpha = 160   // not presenting
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

    override fun onState(fromId: String, video: Boolean, audio: Boolean, screenId: String, avatarUrl: String) =
        runOnUiThread {
            if (avatarUrl.isNotEmpty()) {
                peerAvatars[fromId] = avatarUrl
                paintAvatar(fromId, avatarUrl)
            }
            peerState[fromId] = Triple(video, audio, screenId)
            applyState(fromId, video, audio)
            // The screen id may have only just arrived — re-route their tracks.
            routeTracks(fromId, tiles[fromId]?.label?.text?.toString() ?: "peer")
            if (screenId.isEmpty()) { // they stopped sharing
                tiles.remove("$fromId-screen")?.let { t ->
                    binding.videoGrid.removeView(t.root); t.renderer.release()
                }
                applySpotlight()
            }
        }

    override fun onSpeaking(id: String, speaking: Boolean) = runOnUiThread {
        tiles[id]?.speaking = speaking
        refreshTile(id)
    }

    private fun broadcastState() {
        // Camera and screen are independent tracks now, so `video` is just the
        // camera; the screen is identified by its stream id.
        signaling?.sendState(camOn, micOn, if (sharing) SCREEN_STREAM_ID else "", myAvatarUrl)
    }

    override fun onOffer(fromId: String, nick: String, sdpJson: JSONObject) =
        runOnUiThread { engine?.onRemoteOffer(fromId, nick, sdpJson) }

    override fun onAnswer(fromId: String, sdpJson: JSONObject) =
        runOnUiThread { engine?.onRemoteAnswer(fromId, sdpJson) }

    override fun onCandidate(fromId: String, candidateJson: JSONObject) =
        runOnUiThread { engine?.onRemoteCandidate(fromId, candidateJson) }

    override fun onLeave(fromId: String) = runOnUiThread { engine?.removePeer(fromId) }

    override fun onClosed(reason: String) = runOnUiThread {
        if (inCall && !isDestroyed) binding.status.text = getString(R.string.disconnected)
    }

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
        applySpotlight()
    }

    override fun onRemoteAudio(peerId: String) = runOnUiThread {
        // Re-apply any volume already chosen for them, then refresh the panel.
        peerVolumes[peerId]?.let { engine?.setPeerVolume(peerId, it) }
        if (binding.settingsPanel.visibility == View.VISIBLE) renderVolumes()
    }

    override fun onPeerReconnecting(peerId: String, reconnecting: Boolean) = runOnUiThread {
        // Grey the frozen frame and show "Reconnecting…" on both their tiles.
        for (key in listOf(peerId, "$peerId-screen")) {
            tiles[key]?.let { t ->
                t.reconnect.visibility = if (reconnecting) View.VISIBLE else View.GONE
                t.renderer.alpha = if (reconnecting) 0.4f else 1f
            }
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
        applySpotlight()
        peerState.remove(peerId)
        remoteTracks.remove(peerId)
        peerVolumes.remove(peerId)
        if (binding.settingsPanel.visibility == View.VISIBLE) renderVolumes()
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
        val photo: android.widget.ImageView,
        val micBadge: android.widget.ImageView,
        val label: TextView,
        val reconnect: TextView,
    ) {
        var speaking = false
        var videoOn = true
        var muted = false
    }

    /** The scaling a tile should use: screens always fit, cameras follow the setting. */
    private fun scalingFor(key: String): RendererCommon.ScalingType =
        if (!key.endsWith("-screen") && CallSettings.videoFill(this))
            RendererCommon.ScalingType.SCALE_ASPECT_FILL
        else
            RendererCommon.ScalingType.SCALE_ASPECT_FIT

    /** Re-apply the scaling preference to every tile already on screen. */
    private fun applyScaling() {
        for ((key, t) in tiles) t.renderer.setScalingType(scalingFor(key))
    }

    /** A tile for [key], showing [track] — or just the avatar when there's no video. */
    private fun attachRenderer(key: String, nick: String, track: VideoTrack?) {
        tiles[key]?.let { track?.addSink(it.renderer); return }

        val renderer = SurfaceViewRenderer(this).apply {
            init(eglBase.eglBaseContext, null)
            // Scale to fit by default — the whole frame, letterboxed — rather
            // than cropping it to the tile. A shared screen is ALWAYS fitted:
            // cropping the edge off someone's slides makes the share useless.
            setScalingType(scalingFor(key))
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
        // Their profile picture, over the initials, whenever we have one.
        val photo = android.widget.ImageView(this).apply {
            visibility = View.GONE
            val d = (96 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(d, d, Gravity.CENTER)
        }
        val micBadge = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_mic_off)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setBackgroundColor(tbColor(R.color.tb_scrim))
            visibility = View.GONE
            val pad = (4 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            val side = (28 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(side, side, Gravity.END or Gravity.TOP)
        }
        val label = TextView(this).apply {
            text = nick
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(12, 4, 12, 4)
            setBackgroundColor(tbColor(R.color.tb_scrim))
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.BOTTOM)
        }
        val reconnect = TextView(this).apply {
            text = "Reconnecting…"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            visibility = View.GONE
            setBackgroundColor(tbColor(R.color.tb_scrim_soft))
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(renderer, FrameLayout.LayoutParams(-1, -1))
            addView(avatar); addView(photo); addView(micBadge); addView(label); addView(reconnect)
        }

        val params = android.widget.GridLayout.LayoutParams().apply {
            width = 0
            height = resources.displayMetrics.heightPixels / 3
            columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            setMargins(8, 8, 8, 8)
        }
        binding.videoGrid.addView(root, params)
        // Tap to fill the call area with this feed; tap again for everyone.
        root.setOnClickListener { toggleSpotlight(key) }

        val tile = Tile(root, renderer, avatar, photo, micBadge, label, reconnect)
        tiles[key] = tile
        // Our own picture, and anyone whose state already told us theirs.
        val url = if (key == LOCAL_ID) myAvatarUrl else peerAvatars[key].orEmpty()
        if (url.isNotEmpty()) paintAvatar(key, url)
        avatar.background = avatarBg(nick, speaking = false)
        track?.addSink(renderer)

        // State may have arrived before this peer's track did.
        peerState[key]?.let { (video, audio, _) -> applyState(key, video, audio) }
        refreshTile(key)
        applySpotlight() // someone joining mid-spotlight stays out of the way
    }

    // ---- Spotlight ----
    // One tile blown up across both columns, the others hidden. Hiding is safe
    // here in a way it isn't on web: Android plays call audio through the audio
    // device module, not through each tile's view, so a hidden peer is still
    // heard. Matches the web client's click-to-maximise.

    /** Key of the spotlit tile, or null for the normal grid. */
    private var spotlight: String? = null

    private fun toggleSpotlight(key: String) {
        val entering = spotlight != key
        spotlight = if (entering) key else null
        applySpotlight()
        if (entering && tiles.size > 1) {
            android.widget.Toast.makeText(this, R.string.spotlight_hint, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Lay the grid out for the current spotlight (or none). */
    private fun applySpotlight() {
        // The spotlit person left or stopped sharing: back to everyone.
        if (spotlight != null && spotlight !in tiles) spotlight = null
        val screenH = resources.displayMetrics.heightPixels
        for ((key, t) in tiles) {
            val lp = t.root.layoutParams as android.widget.GridLayout.LayoutParams
            when (spotlight) {
                null -> {
                    t.root.visibility = View.VISIBLE
                    // On your own — waiting for someone, or the last one left —
                    // one wide tile beats a small one above an empty screen.
                    val alone = tiles.size == 1
                    lp.columnSpec = android.widget.GridLayout.spec(
                        android.widget.GridLayout.UNDEFINED, if (alone) 2 else 1, 1f
                    )
                    lp.height = if (alone) (screenH * 0.55).toInt() else screenH / 3
                }
                key -> {
                    t.root.visibility = View.VISIBLE
                    lp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 2, 1f)
                    lp.height = (screenH * 0.68).toInt()
                }
                else -> t.root.visibility = View.GONE
            }
            t.root.layoutParams = lp
        }
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
        val hasPhoto = t.photo.drawable != null
        t.renderer.visibility = if (t.videoOn) View.VISIBLE else View.GONE
        t.photo.visibility = if (!t.videoOn && hasPhoto) View.VISIBLE else View.GONE
        t.avatar.visibility = if (!t.videoOn && !hasPhoto) View.VISIBLE else View.GONE
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

    /**
     * Show someone's profile picture on their tile instead of their initials.
     * A screen-share tile keeps the initials: it isn't a person.
     */
    private fun paintAvatar(key: String, url: String) {
        if (url.isEmpty() || key.endsWith("-screen")) return
        // A peer names their own picture over signaling, so this URL is whatever
        // they say it is. The image loader would also honour file:// and
        // content://, which would make a call participant able to point this
        // phone at its own storage; a picture on a server is all it may be.
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val t = tiles[key] ?: return
        t.photo.load(url) { transformations(coil.transform.CircleCropTransformation()) }
        t.photo.visibility = if (t.videoOn) View.GONE else View.VISIBLE
        t.avatar.visibility = View.GONE
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

    // ---- Keep the call alive when you navigate away (Picture-in-Picture) ----
    // Pressing Back / Home during a call shrinks it into a floating window and
    // brings the screen underneath (the chat you came from) forward, instead of
    // tearing the call down. Only the Leave button actually ends the call. If PiP
    // isn't available we fall back to the normal Back behaviour — a clean return,
    // never the crash/logout this replaced.

    override fun onBackPressed() {
        if (inCall && enterPipIfPossible()) return
        super.onBackPressed()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (inCall) enterPipIfPossible()
    }

    private fun enterPipIfPossible(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        return try {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            )
        } catch (e: Exception) {
            false
        }
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        // In the tiny PiP window show only the video; hide all the call chrome.
        val chrome = if (isInPip) View.GONE else View.VISIBLE
        binding.callHeader.visibility = chrome
        binding.status.visibility = chrome
        binding.callControls.visibility = chrome
        if (isInPip) binding.settingsPanel.visibility = View.GONE
    }

    override fun onDestroy() {
        Calls.detach(this)
        stopCallSystemListeners()
        if (inCall) {
            inCall = false
            if (Calls.voice == voice) Calls.voice = null
            signaling?.close(); signaling = null
            stopService(Intent(this, ScreenCaptureService::class.java))
        }
        CallService.stop(this)
        engine?.close()
        eglBase.release()
        super.onDestroy()
    }
}
