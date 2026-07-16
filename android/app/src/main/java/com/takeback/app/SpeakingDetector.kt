package com.takeback.app

import android.os.SystemClock

/**
 * SpeakingDetector turns a stream of audio levels into a stable "is speaking"
 * flag. Thresholds mirror the web client (see call.html) so both platforms feel
 * the same.
 *
 * The hysteresis matters: a single threshold flickers between syllables and
 * makes the ring strobe. We latch on quickly, then require the level to stay
 * below a lower bar for [HANG_MS] before dropping.
 */
class SpeakingDetector(private val onChange: (Boolean) -> Unit) {

    private var speaking = false
    private var quietSince = 0L

    /** Feed a level in 0..1 (RMS). Safe to call from any thread at any rate. */
    fun update(level: Double) {
        val now = SystemClock.elapsedRealtime()
        when {
            !speaking && level > ON -> {
                speaking = true
                quietSince = 0
                onChange(true)
            }
            speaking && level < OFF -> {
                if (quietSince == 0L) quietSince = now
                if (now - quietSince > HANG_MS) {
                    speaking = false
                    onChange(false)
                }
            }
            speaking -> quietSince = 0 // still above the floor
        }
    }

    /** Force the flag off (e.g. when the mic is muted). */
    fun reset() {
        if (speaking) {
            speaking = false
            onChange(false)
        }
        quietSince = 0
    }

    private companion object {
        const val ON = 0.035
        const val OFF = 0.020
        const val HANG_MS = 350L
    }
}
