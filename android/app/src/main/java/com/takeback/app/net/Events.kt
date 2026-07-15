package com.takeback.app.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** Listener for live events. All callbacks arrive on the OkHttp WS thread. */
interface EventsListener {
    fun onPresence(userId: Long, online: Boolean) {}
    fun onHello(onlineFriendIds: List<Long>) {}
    fun onMessage(message: Message) {}
    fun onFriendRequest(fromId: Long, fromNick: String) {}
}

/**
 * Events is the app-scoped connection to /api/events. It runs for the whole
 * logged-in session (independent of which screen is showing), fans events out
 * to registered listeners, and raises Android notifications for friend requests
 * and for messages whose chat isn't currently open.
 */
object Events {
    private const val CHANNEL = "takeback_events"
    private const val NOTIF_FRIEND = 1001
    private const val NOTIF_MESSAGE_BASE = 2000

    private lateinit var appContext: Context
    private var socket: WebSocket? = null
    private var running = false

    private val listeners = CopyOnWriteArraySet<EventsListener>()

    /** Id of the friend whose chat is open, so we suppress their notifications. */
    @Volatile var openFriendId: Long? = null

    fun addListener(l: EventsListener) = listeners.add(l)
    fun removeListener(l: EventsListener) = listeners.remove(l)

    fun start(context: Context) {
        appContext = context.applicationContext
        createChannel()
        running = true
        connect()
    }

    fun stop() {
        running = false
        socket?.close(1000, "bye")
        socket = null
    }

    private fun wsUrl(): String {
        val base = ApiClient.base
        val scheme = if (base.startsWith("https")) "wss" else "ws"
        return base.replaceFirst(Regex("^https?"), scheme).trimEnd('/') + "/api/events"
    }

    private fun connect() {
        if (!running) return
        val req = Request.Builder().url(wsUrl()).build()
        socket = ApiClient.http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = dispatch(JSONObject(text))
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reconnectLater()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reconnectLater()
        })
    }

    private fun reconnectLater() {
        if (!running) return
        // Simple fixed backoff; the app relaunches events on next foreground too.
        Thread {
            Thread.sleep(2000)
            connect()
        }.start()
    }

    private fun dispatch(msg: JSONObject) {
        when (msg.optString("type")) {
            "presence" -> {
                val id = msg.optLong("userId")
                val online = msg.optBoolean("online")
                listeners.forEach { it.onPresence(id, online) }
            }
            "hello" -> {
                val arr = msg.optJSONArray("onlineFriends")
                val ids = (0 until (arr?.length() ?: 0)).map { arr!!.getLong(it) }
                listeners.forEach { it.onHello(ids) }
            }
            "friend_request" -> {
                val fromId = msg.optLong("userId")
                val nick = msg.optString("nick")
                listeners.forEach { it.onFriendRequest(fromId, nick) }
                notifyFriendRequest(nick)
            }
            "message" -> {
                val m = parsePushedMessage(msg.getJSONObject("message"))
                listeners.forEach { it.onMessage(m) }
                if (openFriendId != m.senderId) notifyMessage(m)
            }
        }
    }

    // The pushed "message" payload uses the same field names as the REST view.
    private fun parsePushedMessage(o: JSONObject) = Message(
        id = o.getLong("id"),
        senderId = o.getLong("senderId"),
        recipientId = o.getLong("recipientId"),
        body = o.optString("body"),
        imageUrl = o.optString("imageUrl").ifEmpty { null }?.let { ApiClient.mediaUrl(it) },
        thumbUrl = o.optString("thumbUrl").ifEmpty { null }?.let { ApiClient.mediaUrl(it) },
        created = o.getLong("created"),
    )

    // ---- notifications ----

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = appContext.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Messages & friends", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun notifyFriendRequest(nick: String) =
        post(NOTIF_FRIEND, "Friend request", "$nick wants to be your friend")

    private fun notifyMessage(m: Message) {
        val preview = if (m.body.isNotEmpty()) m.body.take(80) else "📷 image"
        post(NOTIF_MESSAGE_BASE + m.senderId.toInt(), "New message", preview)
    }

    private fun post(id: Int, title: String, text: String) {
        val n = NotificationCompat.Builder(appContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        // POST_NOTIFICATIONS (API 33+) is requested by the UI; guard against denial.
        runCatching { NotificationManagerCompat.from(appContext).notify(id, n) }
    }
}
