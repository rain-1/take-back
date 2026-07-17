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

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Self-view mirroring. Defaults to on, like most video apps. */
    fun mirror(c: Context): Boolean = prefs(c).getBoolean(KEY_MIRROR, true)
    fun setMirror(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_MIRROR, on).apply()

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

    /** An audio route the user can pick. [id] of -1 means "system default". */
    data class AudioOption(val id: Int, val label: String)

    fun audioOptions(c: Context): List<AudioOption> {
        val out = mutableListOf(AudioOption(-1, "System default"))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = c.getSystemService(AudioManager::class.java)
            for (d in am.availableCommunicationDevices) {
                out.add(AudioOption(d.id, labelFor(d)))
            }
        }
        return out
    }

    /** Route call audio to [id], or clear back to the system default. */
    fun applyAudioOption(c: Context, id: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        val am = c.getSystemService(AudioManager::class.java)
        if (id < 0) {
            am.clearCommunicationDevice()
            return true
        }
        val device = am.availableCommunicationDevices.firstOrNull { it.id == id } ?: return false
        return am.setCommunicationDevice(device)
    }

    private fun labelFor(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Phone microphone"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
        else -> d.productName?.toString()?.ifBlank { "Audio device" } ?: "Audio device"
    }
}
