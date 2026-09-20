package com.takeback.app.net

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    /** A DM I can see was edited by its sender. [message] is the fresh version. */
    fun onMessageEdited(message: Message) {}
    fun onFriendRequest(fromId: Long, fromNick: String) {}
    fun onFriendUpdate() {}
    fun onGroupMessage(message: GroupMessage) {}
    /** A group message was edited by its sender. [message] is the fresh version. */
    fun onGroupMessageEdited(message: GroupMessage) {}
    fun onGroupUpdate(groupId: Long) {}
    /** A message's reactions changed. [reactions] is the fresh aggregate. */
    fun onReaction(scope: String, messageId: Long, reactions: List<Reaction>) {}

    /** A conversation gained or lost its "you were mentioned" flag. */
    fun onMentionsChanged() {}

    /**
     * Someone withdrew a message. [scope] is "dm", "group" or "channel";
     * [containerId] is the group's or the channel's id (0 for a DM).
     */
    fun onMessageDeleted(scope: String, messageId: Long, containerId: Long) {}

    fun onChannelMessage(message: ChannelMessage) {}
    fun onChannelMessageEdited(message: ChannelMessage) {}
    /** A server's details, channels or membership changed, or it was [deleted]. */
    fun onServerUpdate(serverId: Long, deleted: Boolean) {}
    /** Who's active in a server, and who's in its voice channels, changed. */
    fun onServerActivity(activity: ServerActivity) {}

    /** A call in a conversation started, was answered, ended, or was declined. */
    fun onCallState(call: CallState, incoming: Boolean) {}
    /** Someone invited me to a group — it needs an accept/decline. */
    fun onGroupInvite(groupId: Long, groupName: String, invitedBy: String) {}
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
    private const val NOTIF_CALL = 1500

    private lateinit var appContext: Context
    private var socket: WebSocket? = null
    private var running = false

    private val listeners = CopyOnWriteArraySet<EventsListener>()

    /** Id of the friend whose chat is open, so we suppress their notifications. */
    @Volatile var openFriendId: Long? = null

    /** Id of the group whose chat is open, so we suppress its notifications. */
    @Volatile var openGroupId: Long? = null

    /** Id of the text channel that's open, so we suppress its notifications. */
    @Volatile var openChannelId: Long? = null

    /** Server names by id, for notification titles. Filled by the screens that load servers. */
    val serverNames = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** How many of the app's activities are started; 0 means the app is in the background. */
    @Volatile var startedActivities = 0

    fun addListener(l: EventsListener) = listeners.add(l)
    fun removeListener(l: EventsListener) = listeners.remove(l)

    // Exactly one socket may be live. Every connect() bumps `generation`, and a
    // socket's callbacks check it and go inert once they're stale.
    //
    // Without that, start() — which runs every time the login screen launches,
    // including reopening the app — opened ANOTHER socket over the old one, the
    // old one kept delivering, and its own reconnect loop kept it alive forever.
    // The server fans each event out to every socket a user has, so each one
    // was dispatched once per leaked connection: "I'm seeing everything twice".
    @Volatile private var generation = 0
    @Volatile private var reconnectPending = false

    @Synchronized
    fun start(context: Context) {
        appContext = context.applicationContext
        Mentions.init(appContext)
        createChannel()
        // Already connected (or about to reconnect): reopening the app must not
        // add a second connection.
        if (running && (socket != null || reconnectPending)) return
        running = true
        connect()
    }

    @Synchronized
    fun stop() {
        running = false
        generation++ // strands any in-flight callbacks and scheduled reconnect
        socket?.close(1000, "bye")
        socket = null
    }

    private fun wsUrl(): String {
        val base = ApiClient.base
        val scheme = if (base.startsWith("https")) "wss" else "ws"
        return base.replaceFirst(Regex("^https?"), scheme).trimEnd('/') + "/api/events"
    }

    @Synchronized
    private fun connect() {
        if (!running) return
        reconnectPending = false
        val gen = ++generation
        // Shut whatever was there before opening its replacement.
        socket?.close(1000, "replaced")
        val req = Request.Builder().url(wsUrl()).build()
        socket = ApiClient.http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // A fresh socket views nothing until told; repeat what we're looking at.
                if (gen == generation) synchronized(this@Events) { viewSent = 0L; sendView(currentView()) }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen == generation) dispatch(JSONObject(text)) // stale socket: drop
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reconnectLater(gen)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reconnectLater(gen)
        })
    }

    /** Reconnect after [gen]'s socket dropped — unless it has since been replaced. */
    @Synchronized
    private fun reconnectLater(gen: Int) {
        // A socket we already replaced or stopped closing is expected, not a
        // reason to open yet another one; and one scheduled retry is enough.
        if (!running || gen != generation || reconnectPending) return
        socket = null
        reconnectPending = true
        Thread {
            Thread.sleep(2000)
            // Only if nothing else (stop, or a fresh start) happened meanwhile.
            synchronized(this) { if (gen == generation) connect() }
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
            "friend_update" -> listeners.forEach { it.onFriendUpdate() }
            "message" -> {
                val m = parsePushedMessage(msg.getJSONObject("message"))
                listeners.forEach { it.onMessage(m) }
                if (openFriendId != m.senderId) {
                    val mentioned = Mentions.mentions(m.body, ApiClient.myNick)
                    if (mentioned && Mentions.mark(Mentions.dmKey(m.senderId))) {
                        listeners.forEach { it.onMentionsChanged() }
                    }
                    notifyMessage(m, mentioned)
                }
            }
            "message_edited" -> {
                val m = parsePushedMessage(msg.getJSONObject("message"))
                listeners.forEach { it.onMessageEdited(m) }
            }
            "group_message" -> {
                val m = ApiClient.parseGroupMessage(msg.getJSONObject("message"))
                listeners.forEach { it.onGroupMessage(m) }
                if (openGroupId != m.groupId) {
                    val mentioned = Mentions.mentions(m.body, ApiClient.myNick)
                    if (mentioned && Mentions.mark(Mentions.groupKey(m.groupId))) {
                        listeners.forEach { it.onMentionsChanged() }
                    }
                    notifyGroupMessage(m, mentioned)
                }
            }
            "group_message_edited" -> {
                val m = ApiClient.parseGroupMessage(msg.getJSONObject("message"))
                listeners.forEach { it.onGroupMessageEdited(m) }
            }
            "group_invite" -> {
                val gid = msg.optLong("groupId")
                val name = msg.optString("groupName")
                val by = msg.optString("nick")
                listeners.forEach { it.onGroupInvite(gid, name, by) }
                post(NOTIF_FRIEND + 1, "Group invite", "$by invited you to $name")
            }
            "group_update" -> {
                val groupId = msg.optLong("userId") // group id is carried in userId
                listeners.forEach { it.onGroupUpdate(groupId) }
            }
            "message_deleted" -> {
                val m = msg.getJSONObject("message")
                val scope = m.optString("scope")
                val container = if (scope == "channel") m.optLong("channelId") else m.optLong("groupId")
                listeners.forEach { it.onMessageDeleted(scope, m.optLong("id"), container) }
            }
            "channel_message" -> {
                val m = ApiClient.parseChannelMessage(msg.getJSONObject("message"))
                listeners.forEach { it.onChannelMessage(m) }
                if (openChannelId != m.channelId && m.senderId != ApiClient.myId) {
                    val mentioned = Mentions.mentions(m.body, ApiClient.myNick)
                    if (mentioned && Mentions.markChannel(m.serverId, m.channelId)) {
                        listeners.forEach { it.onMentionsChanged() }
                    }
                    notifyChannelMessage(m, mentioned)
                }
            }
            "channel_message_edited" -> {
                val m = ApiClient.parseChannelMessage(msg.getJSONObject("message"))
                listeners.forEach { it.onChannelMessageEdited(m) }
            }
            "server_update" -> {
                val m = msg.optJSONObject("message") ?: return
                val id = m.optLong("serverId")
                val deleted = m.optBoolean("deleted")
                listeners.forEach { it.onServerUpdate(id, deleted) }
            }
            "call_incoming", "call_state" -> {
                val c = ApiClient.parseCall(msg.optJSONObject("message") ?: return)
                val incoming = msg.optString("type") == "call_incoming"
                listeners.forEach { it.onCallState(c, incoming) }
                com.takeback.app.IncomingCalls.update(c)
                if (incoming && c.callerId != ApiClient.myId) {
                    post(NOTIF_CALL, "Incoming call", "${c.callerNick} is calling")
                }
                if (!c.live) cancel(NOTIF_CALL)
            }
            "server_active" -> {
                val a = ApiClient.parseActivity(msg.optJSONObject("message") ?: return)
                listeners.forEach { it.onServerActivity(a) }
            }
            "reaction" -> {
                val m = msg.getJSONObject("message")
                val scope = m.optString("scope")
                val mid = m.optLong("messageId")
                // The event carries the raw per-user list; aggregate it here so
                // the UI gets the same shape as the REST message views.
                val reactions = ApiClient.aggregateReactions(m.optJSONArray("reactions"))
                listeners.forEach { it.onReaction(scope, mid, reactions) }
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
        attachment = ApiClient.attachmentOf(o),
        created = o.getLong("created"),
        editedAt = o.optLong("editedAt"),
        // Reply fields, so a live-received DM reply shows its quote immediately
        // (not only after a reload). Groups already use the full parser.
        replyTo = o.optLong("replyTo"),
        replySender = o.optLong("replySender"),
        replyBody = o.optString("replyBody"),
    )

    // ---- which server I'm looking at ----
    //
    // A server counts me as active while I'm viewing it (see the server's
    // presence/activity.go). Every screen that shows a server registers itself
    // while started; the most recent one wins. Moving between two of them
    // (server -> channel) never reports "nothing" in between, because the new
    // screen starts before the old one stops. When the last one stops: back
    // inside the app means I've left the server straight away, while the app
    // going to the background gets a minute's grace, like a hidden browser tab.

    private val viewers = LinkedHashMap<Any, Long>()
    private var viewSent = 0L
    private val main = Handler(Looper.getMainLooper())
    private val clearView = Runnable { synchronized(this) { sendView(currentView()) } }
    private const val BACKGROUND_GRACE_MS = 60_000L

    @Synchronized
    fun viewStart(owner: Any, serverId: Long) {
        viewers.remove(owner)
        viewers[owner] = serverId
        main.removeCallbacks(clearView)
        sendView(serverId)
    }

    /** Call after super.onStop(), so [startedActivities] already counts this screen out. */
    @Synchronized
    fun viewStop(owner: Any) {
        viewers.remove(owner)
        val next = currentView()
        if (next != 0L) { sendView(next); return }
        main.removeCallbacks(clearView)
        main.postDelayed(clearView, if (startedActivities > 0) 300L else BACKGROUND_GRACE_MS)
    }

    private fun currentView(): Long = viewers.values.lastOrNull() ?: 0L

    private fun sendView(serverId: Long) {
        if (serverId == viewSent) return
        val s = socket ?: return
        if (s.send(JSONObject().put("type", "view").put("serverId", serverId).toString())) viewSent = serverId
    }

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

    private fun notifyMessage(m: Message, mentioned: Boolean) {
        val preview = if (m.body.isNotEmpty()) m.body.take(80) else attachmentPreview(m.attachment)
        post(NOTIF_MESSAGE_BASE + m.senderId.toInt(),
            if (mentioned) "You were mentioned" else "New message", preview)
    }

    private fun notifyGroupMessage(m: GroupMessage, mentioned: Boolean) {
        val preview = if (m.body.isNotEmpty()) m.body.take(80) else attachmentPreview(m.attachment)
        post(NOTIF_MESSAGE_BASE + 100000 + m.groupId.toInt(),
            if (mentioned) "You were mentioned in a group" else "New group message", preview)
    }

    private fun notifyChannelMessage(m: ChannelMessage, mentioned: Boolean) {
        val preview = if (m.body.isNotEmpty()) m.body.take(80) else attachmentPreview(m.attachment)
        val where = serverNames[m.serverId] ?: "a server"
        post(NOTIF_MESSAGE_BASE + 200000 + m.channelId.toInt(),
            if (mentioned) "You were mentioned in $where" else "New message in $where", preview)
    }

    fun clearChannelMessageNotification(channelId: Long) =
        cancel(NOTIF_MESSAGE_BASE + 200000 + channelId.toInt())

    /** Notification text for a message that is nothing but an attachment. */
    private fun attachmentPreview(a: Attachment?): String = when (a?.kind) {
        "image" -> "\uD83D\uDCF7 image"
        "video" -> "\uD83C\uDFAC " + a.name
        "audio" -> "\uD83C\uDFB5 " + a.name
        null -> "message"
        else -> "\uD83D\uDCCE " + a.name
    }

    /**
     * Dismiss the tray notification for a conversation once you open/view it.
     * (Notification ids mirror notifyMessage/notifyGroupMessage above.) Opening a
     * chat only suppressed *future* notifications; the already-posted one lingered.
     */
    fun clearMessageNotification(friendId: Long) = cancel(NOTIF_MESSAGE_BASE + friendId.toInt())

    fun clearGroupMessageNotification(groupId: Long) =
        cancel(NOTIF_MESSAGE_BASE + 100000 + groupId.toInt())

    private fun cancel(id: Int) {
        if (::appContext.isInitialized) {
            runCatching { NotificationManagerCompat.from(appContext).cancel(id) }
        }
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
