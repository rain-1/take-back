package com.takeback.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sound from other apps, captured while you share your screen (Android 10+).
 *
 * The web and desktop clients send it as its own track on the screen share.
 * Android's WebRTC audio module only records the microphone, so here the app
 * audio is MIXED into the microphone buffer just before it's encoded: the
 * people in the call hear it on your normal audio.
 *
 * Android only captures sound that apps allow to be captured (music, video and
 * games, by default), and never voice calls, so the call's own audio isn't
 * sent back into it.
 */
@RequiresApi(Build.VERSION_CODES.Q)
class AppAudioCapture private constructor(
    private val record: AudioRecord,
    private val channels: Int,
) {
    // A second of samples. The reader fills it; the mixer drains it.
    private val ring = ShortArray(48_000 * channels)
    private var readPos = 0
    private var writePos = 0
    private var filled = 0
    @Volatile private var running = true

    private val thread = Thread({
        val chunk = ShortArray(960 * channels) // 20 ms at 48 kHz
        while (running) {
            val n = record.read(chunk, 0, chunk.size)
            if (n <= 0) { if (n < 0) break else continue }
            synchronized(ring) {
                for (i in 0 until n) {
                    ring[writePos] = chunk[i]
                    writePos = (writePos + 1) % ring.size
                    if (filled == ring.size) readPos = (readPos + 1) % ring.size // full: drop the oldest
                    else filled++
                }
            }
        }
    }, "AppAudioCapture")

    init {
        record.startRecording()
        thread.start()
    }

    /**
     * Add captured app audio into [buffer], 16-bit little-endian PCM with
     * [channelCount] channels. Anything not captured yet stays as it was.
     */
    fun mixInto(buffer: ByteBuffer, channelCount: Int) {
        if (channelCount != channels) return // formats agreed at start; never guess
        val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        synchronized(ring) {
            val n = minOf(shorts.limit(), filled)
            for (i in 0 until n) {
                val mixed = shorts.get(i) + ring[readPos]
                shorts.put(i, mixed.coerceIn(-32768, 32767).toShort())
                readPos = (readPos + 1) % ring.size
            }
            filled -= n
        }
    }

    fun stop() {
        running = false
        runCatching { record.stop() }
        runCatching { thread.join(500) }
        record.release()
    }

    companion object {
        /** Whether this phone can capture app audio at all. */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        /**
         * Start capturing at the call's own format, or null if it can't (no
         * microphone permission, which Android requires for this too, or the
         * projection refused).
         */
        fun start(ctx: Context, projection: MediaProjection, sampleRate: Int, channels: Int): AppAudioCapture? {
            if (!supported) return null
            if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
            return runCatching {
                val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO)
                    .build()
                val record = AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(sampleRate / 5 * channels * 2) // 200 ms
                    .setAudioPlaybackCaptureConfig(config)
                    .build()
                if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); null }
                else AppAudioCapture(record, channels)
            }.getOrNull()
        }
    }
}
