package com.takeback.app

import android.content.Context
import androidx.appcompat.app.AlertDialog

/**
 * The small card shown when you tap an @mention: who that is, whether they're
 * around, and a shortcut to message them. The phone counterpart of the web
 * client's profile popover, built from state the chat screen already holds.
 */
object ProfileCard {
    fun show(ctx: Context, nick: String, subtitle: String, onMessage: (() -> Unit)? = null) {
        val b = AlertDialog.Builder(ctx)
            .setTitle("@$nick")
            .setMessage(subtitle)
            .setNegativeButton("Close", null)
        if (onMessage != null) b.setPositiveButton("Message $nick") { _, _ -> onMessage() }
        b.show()
    }
}
