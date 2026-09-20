package com.takeback.app

import android.content.Context
import android.content.Intent

/**
 * The conversation you had open last, reopened when you come back — the phone's
 * version of what web and desktop do on reload.
 *
 * Only on a fresh start: navigating back to the list is a deliberate move, and
 * bouncing you into the chat again would trap you there.
 */
object LastChat {
    private const val PREFS = "tb_lastchat"

    private var reopened = false

    fun remember(ctx: Context, kind: String, id: Long, name: String, extra: Long = 0, extraName: String = "") {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("kind", kind).putLong("id", id).putString("name", name)
            .putLong("extra", extra).putString("extraName", extraName)
            .apply()
    }

    fun forget(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Reopen it, once per app start. Returns true if a screen was opened.
     */
    fun reopen(ctx: Context): Boolean {
        if (reopened) return false
        reopened = true
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = p.getLong("id", 0)
        if (id == 0L) return false
        val name = p.getString("name", "") ?: ""
        val extra = p.getLong("extra", 0)
        val extraName = p.getString("extraName", "") ?: ""
        val intent = when (p.getString("kind", "")) {
            "dm" -> Intent(ctx, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_FRIEND_ID, id)
                .putExtra(ChatActivity.EXTRA_FRIEND_NICK, name)
            "group" -> Intent(ctx, GroupChatActivity::class.java)
                .putExtra(GroupChatActivity.EXTRA_GROUP_ID, id)
                .putExtra(GroupChatActivity.EXTRA_GROUP_NAME, name)
                .putExtra(GroupChatActivity.EXTRA_CALL_CODE, extraName)
            "channel" -> Intent(ctx, ChannelChatActivity::class.java)
                .putExtra(ChannelChatActivity.EXTRA_CHANNEL_ID, id)
                .putExtra(ChannelChatActivity.EXTRA_CHANNEL_NAME, name)
                .putExtra(ChannelChatActivity.EXTRA_SERVER_ID, extra)
                .putExtra(ChannelChatActivity.EXTRA_SERVER_NAME, extraName)
            else -> return false
        }
        ctx.startActivity(intent)
        return true
    }
}
