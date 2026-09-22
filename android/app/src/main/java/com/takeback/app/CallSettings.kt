package com.takeback.app

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import org.webrtc.Camera2Enumerator

/**
 * CallSettings holds the "set once and keep" call preferences and the device
 * lists behind the in-call settings panel.
 *
 * Audio source works differently from the web: WebRTC's audio device module
 * always captures from the system's *communication* device, so we don't pick a
 * mic directly — we tell Android which communication device to route to
 * (built-in, wired headset, Bluetooth…). That API only exists on Android 12+;
 * older devices get the system default and nothing to choose.
 */
object CallSettings {
    private const val PREFS = "tb_call"
    private const val KEY_MIRROR = "mirror"
    private const val KEY_CAMERA = "cameraName"
    private const val KEY_MIC_GAIN = "micGain"
    private const val KEY_VIDEO_FILL = "videoFill"
    private const val KEY_STEREO = "stereo"
    private const val KEY_VIDEO_QUALITY = "videoQuality"
    private const val KEY_AUDIO_DEVICE_TYPE = "audioDeviceType"

    enum class VideoQuality(
        val label: String,
        val cameraBitrateBps: Int?,
        val screenBitrateBps: Int?,
    ) {
        LOW("Low data — 0.35 / 0.7 Mbps", 350_000, 700_000),
        BALANCED("Balanced — 1.2 / 2.5 Mbps", 1_200_000, 2_500_000),
        HIGH("High — 2.5 / 6 Mbps", 2_500_000, 6_000_000),
        MAXIMUM("Maximum — use available connection", null, null),
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Self-view mirroring. Defaults to on, like most video apps. */
    fun mirror(c: Context): Boolean = prefs(c).getBoolean(KEY_MIRROR, true)
    fun setMirror(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_MIRROR, on).apply()

    /**
     * Video scaling. Fit (the default) letterboxes each tile so the whole frame
     * is visible; fill crops it to the tile's shape. Fill was the old fixed
     * behaviour, and cropping people out of their own video is the wrong default.
     */
    fun videoFill(c: Context): Boolean = prefs(c).getBoolean(KEY_VIDEO_FILL, false)
    fun setVideoFill(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_VIDEO_FILL, on).apply()

    /**
     * Transmit stereo instead of mono (the default — most phone mics are mono,
     * and stereo roughly doubles the audio bitrate). Read when a call starts,
     * because the capture channel count is fixed when the audio device module is
     * built; changing it applies from the next call. Mirrors the web toggle.
     */
    fun stereo(c: Context): Boolean = prefs(c).getBoolean(KEY_STEREO, false)
    fun setStereo(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_STEREO, on).apply()

    /** Video bitrate ceilings for camera / screen; null means WebRTC's default. */
    fun videoQuality(c: Context): VideoQuality = runCatching {
        VideoQuality.valueOf(prefs(c).getString(KEY_VIDEO_QUALITY, null) ?: "BALANCED")
    }.getOrDefault(VideoQuality.BALANCED)

    fun setVideoQuality(c: Context, quality: VideoQuality) =
        prefs(c).edit().putString(KEY_VIDEO_QUALITY, quality.name).apply()

    /** Mic gain (1.0 = untouched). Applied to the captured buffer before encoding. */
    fun micGain(c: Context): Float = prefs(c).getFloat(KEY_MIC_GAIN, 1.0f)
    fun setMicGain(c: Context, g: Float) = prefs(c).edit().putFloat(KEY_MIC_GAIN, g).apply()

    /** Preferred camera (an enumerator device name), or null for the default. */
    fun cameraName(c: Context): String? = prefs(c).getString(KEY_CAMERA, null)
    fun setCameraName(c: Context, name: String) = prefs(c).edit().putString(KEY_CAMERA, name).apply()

    /** Cameras available, as (deviceName, label) pairs. */
    fun cameras(c: Context): List<Pair<String, String>> {
        val e = Camera2Enumerator(c)
        return e.deviceNames.map { name ->
            val label = when {
                e.isFrontFacing(name) -> "Front camera"
                e.isBackFacing(name) -> "Back camera"
                else -> name
            }
            name to label
        }
    }

    /** An audio route the user can pick. [id] of -1 means automatic routing. */
    data class AudioOption(val id: Int, val type: Int, val label: String)

    fun audioOptions(c: Context): List<AudioOption> {
        val out = mutableListOf(AudioOption(-1, -1, "Automatic (headset preferred)"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = c.getSystemService(AudioManager::class.java)
            for (d in am.availableCommunicationDevices) {
                out.add(AudioOption(d.id, d.type, labelFor(d)))
            }
        }
        return out
    }

    fun audioDeviceType(c: Context): Int = prefs(c).getInt(KEY_AUDIO_DEVICE_TYPE, -1)
    fun setAudioDeviceType(c: Context, type: Int) =
        prefs(c).edit().putInt(KEY_AUDIO_DEVICE_TYPE, type).apply()

    /** Apply the saved route, resolving a fresh device id after reconnects. */
    fun applySavedAudioOption(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val type = audioDeviceType(c)
        val devices = c.getSystemService(AudioManager::class.java).availableCommunicationDevices
        val chosen = if (type >= 0) devices.firstOrNull { it.type == type }
        else devices.firstOrNull { isBluetoothHeadset(it.type) }
            ?: devices.firstOrNull { isWiredHeadset(it.type) }
        val am = c.getSystemService(AudioManager::class.java)
        return if (chosen != null) am.setCommunicationDevice(chosen)
        else { am.clearCommunicationDevice(); type < 0 }
    }

    /** Route call audio to [id], or clear back to the system default. */
    fun applyAudioOption(c: Context, id: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val am = c.getSystemService(AudioManager::class.java)
        if (id < 0) {
            setAudioDeviceType(c, -1)
            return applySavedAudioOption(c)
        }
        val device = am.availableCommunicationDevices.firstOrNull { it.id == id } ?: return false
        setAudioDeviceType(c, device.type)
        return am.setCommunicationDevice(device)
    }

    private fun isBluetoothHeadset(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    private fun isWiredHeadset(type: Int) = type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        type == AudioDeviceInfo.TYPE_USB_HEADSET || type == AudioDeviceInfo.TYPE_USB_DEVICE

    private fun labelFor(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        else -> d.productName?.toString()?.ifBlank { "Audio device" } ?: "Audio device"
    }

    /** Last answer to "Also share sound from apps" when screen sharing. */
    fun shareAudio(ctx: Context): Boolean = prefs(ctx).getBoolean("shareAudio", true)
    fun setShareAudio(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("shareAudio", on).apply()
}
