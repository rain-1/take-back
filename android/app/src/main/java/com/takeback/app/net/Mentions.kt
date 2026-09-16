package com.takeback.app.net

import android.content.Context

/**
 * Which conversations have mentioned me since I last opened them — the phone's
 * version of the web client's red pip.
 *
 * Detection lives here rather than in a screen because [Events] sees every
 * incoming message whichever screen is showing (or none). The flag latches until
 * the conversation is opened, so a later ordinary message can't quietly
 * downgrade a request for my attention. It's persisted, so unlike the web
 * client's it also survives the app being closed and reopened.
 *
 * Keys are "dm:<friendId>" and "group:<groupId>".
 */
object Mentions {
    private const val PREFS = "tb_mentions"
    private const val KEY = "pending"

    private var appContext: Context? = null
    private val pending = mutableSetOf<String>()

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        pending.addAll(prefs()?.getStringSet(KEY, emptySet()).orEmpty())
    }

    fun dmKey(friendId: Long) = "dm:$friendId"
    fun groupKey(groupId: Long) = "group:$groupId"

    @Synchronized fun has(key: String) = key in pending

    /** Returns true if this changed anything (so the caller knows to repaint). */
    @Synchronized fun mark(key: String): Boolean = pending.add(key).also { if (it) save() }

    @Synchronized fun clear(key: String): Boolean = pending.remove(key).also { if (it) save() }

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun save() { prefs()?.edit()?.putStringSet(KEY, pending.toSet())?.apply() }

    /**
     * Does [body] mention [nick] with an @? Same rule as the web client: the @
     * must not follow a word character, another @ or a slash — so an email
     * address or a URL path isn't a mention — and the name must end there.
     */
    fun mentions(body: String, nick: String): Boolean {
        if (nick.isEmpty() || body.isEmpty()) return false
        return Regex("(^|[^\\w@/])@" + Regex.escape(nick) + "\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(body)
    }
}
