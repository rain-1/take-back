package com.takeback.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.lang.ref.WeakReference

/**
 * Calls keeps the phone to one call at a time, like the web client: there is
 * one microphone and one camera. Joining a different call first ends the one in
 * progress; joining the call you're already in brings it back up.
 *
 * It also remembers which voice channel, if any, the current call is, so a
 * server's screen can show you as connected there.
 */
object Calls {
    /** The voice channel a call belongs to. */
    data class Voice(val serverId: Long, val channelId: Long, val name: String)

    private var current: WeakReference<MainActivity>? = null

    /** The voice channel of the call in progress, or null. */
    @Volatile var voice: Voice? = null
        internal set

    internal fun attach(a: MainActivity) { current = WeakReference(a) }
    internal fun detach(a: MainActivity) { if (current?.get() === a) current = null }

    fun join(ctx: Context, room: String, voice: Voice? = null) {
        val code = room.uppercase()
        val active = current?.get()?.takeIf { it.inCallRoom != null && !it.isFinishing }
        if (active != null && active.inCallRoom == code) {
            bringToFront(ctx, active)
            return
        }
        active?.endCall()
        ctx.startActivity(Intent(ctx, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROOM, code)
            .apply {
                if (voice != null) {
                    putExtra(MainActivity.EXTRA_VOICE_SERVER, voice.serverId)
                    putExtra(MainActivity.EXTRA_VOICE_CHANNEL, voice.channelId)
                    putExtra(MainActivity.EXTRA_VOICE_NAME, voice.name)
                }
            })
    }

    private fun bringToFront(ctx: Context, a: MainActivity) {
        runCatching {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).moveTaskToFront(a.taskId, 0)
        }.onFailure { Toast.makeText(ctx, "You're already in this call", Toast.LENGTH_SHORT).show() }
    }
}
