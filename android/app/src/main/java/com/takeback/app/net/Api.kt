package com.takeback.app.net

import android.content.Context
import com.takeback.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Thrown when the server returns a non-2xx response; carries its error message. */
class ApiException(message: String) : Exception(message)

// ---- Models (mirror the server's JSON) ----

data class User(val id: Long, val nick: String, val avatarUrl: String = "")

/** Server identity + wire-protocol version, from GET /api/version. */
/**
 * [openRegistration] is null for a server that predates the field — those all
 * accepted signups, so the app keeps offering Register in that case.
 */
data class ServerVersion(
    val name: String,
    val version: String,
    val protocol: Int,
    val openRegistration: Boolean? = null,
) {
    /** True when this app can talk to that server (see internal/version). */
    val compatible: Boolean get() = protocol == BuildConfig.PROTOCOL
}

data class Friend(
    val user: User,
    val status: String,     // pending | accepted
    val direction: String,  // incoming | outgoing
    val online: Boolean,
    val unread: Int = 0,
    val lastActivity: Long = 0, // unix time of the last message, 0 if none
)

/** One emoji aggregated across everyone who used it on a message. */
data class Reaction(val emoji: String, val count: Int, val nicks: List<String>, val mine: Boolean)

/**
 * An attachment on a message. `kind` decides how it renders: an image shows its
 * thumbnail, video and audio get a player, anything else gets a download chip.
 * Messages sent before attachments were generalised carry no kind, and are read
 * back as images (see [attachmentOf]).
 */
data class Attachment(
    val url: String,
    val kind: String,   // image | video | audio | file
    val name: String,
    val size: Long,
    val thumbUrl: String?,
)

data class Message(
    val id: Long,
    val senderId: Long,
    val recipientId: Long,
    val body: String,
    val imageUrl: String?,   // absolute URL, or null
    val thumbUrl: String?,
    val attachment: Attachment? = null,
    val created: Long,
    val reactions: List<Reaction> = emptyList(),
    val replyTo: Long = 0,
    val replySender: Long = 0,
    val replyBody: String = "",
    val editedAt: Long = 0,
    val deletedAt: Long = 0,
)

data class Group(
    val id: Long,
    val name: String,
    val ownerId: Long,
    val callCode: String,
    val memberCount: Int,
    val unread: Int = 0,
    val lastActivity: Long = 0,
)

data class GroupMember(val id: Long, val nick: String, val online: Boolean, val owner: Boolean, val avatarUrl: String = "")

/** A pending group invitation awaiting my answer. */
data class GroupInvite(val groupId: Long, val groupName: String, val invitedBy: String)

data class GroupMessage(
    val id: Long,
    val groupId: Long,
    val senderId: Long,
    val body: String,
    val imageUrl: String?,
    val thumbUrl: String?,
    val attachment: Attachment? = null,
    val created: Long,
    val reactions: List<Reaction> = emptyList(),
    val replyTo: Long = 0,
    val replySender: Long = 0,
    val replyBody: String = "",
    val editedAt: Long = 0,
    val deletedAt: Long = 0,
)

// ---- Servers (communities with channels) ----

/** A server as seen by me: [role] is "admin" or "user". */
data class Server(
    val id: Long,
    val name: String,
    val iconUrl: String,
    val ownerId: Long,
    val role: String,
    val memberCount: Int,
    val unread: Int = 0,
    /** How many people are in this server's voice channels right now. */
    val voiceCount: Int = 0,
) {
    val isAdmin: Boolean get() = role == "admin"
}

data class ServerMember(val user: User, val role: String)

/** A text or voice channel. [callCode] is the voice channel's call room. */
data class Channel(
    val id: Long,
    val serverId: Long,
    val name: String,
    val kind: String, // text | voice
    val callCode: String = "",
    val unread: Int = 0,
)

data class ChannelMessage(
    val id: Long,
    val channelId: Long,
    val serverId: Long,
    val senderId: Long,
    val body: String,
    val attachment: Attachment? = null,
    val imageUrl: String? = null,
    val thumbUrl: String? = null,
    val created: Long,
    val reactions: List<Reaction> = emptyList(),
    val replyTo: Long = 0,
    val replySender: Long = 0,
    val replyBody: String = "",
    val editedAt: Long = 0,
    val deletedAt: Long = 0,
)

/**
 * What became of a call announced in a conversation. [outcome] is "" while it
 * is live, then "missed", "declined" or "ended".
 */
data class CallState(
    val code: String,
    val callerId: Long,
    val callerNick: String,
    val outcome: String,
    val answered: Long,
    val ended: Long,
    val declinedBy: Long,
    val participants: List<Long>,
    val here: Int,
) {
    val live: Boolean get() = outcome.isEmpty()
    val seconds: Long get() = if (answered > 0 && ended > 0) ended - answered else 0
}

/** What an invite code leads to, before joining. */
data class InvitePreview(val server: Server, val alreadyMember: Boolean)

/**
 * Who is active in a server: viewing it or in one of its voice channels, and
 * who sits in each voice channel. [seq] orders snapshots (see the server's
 * presence.Activity).
 */
data class ServerActivity(
    val serverId: Long,
    /** Which run of the server this came from; [seq] restarts with it. */
    val epoch: String,
    val seq: Long,
    val active: Set<Long>,
    val voice: Map<Long, List<Long>>,
)

/**
 * ApiClient is the app-wide HTTP client for the take-back REST API. It persists
 * the session cookie so logins survive process restarts, and exposes suspend
 * functions that run on Dispatchers.IO.
 */
object ApiClient {
    private const val PREFS = "tb_config"
    private const val KEY_SERVER = "server_url"

    private lateinit var appContext: Context

    @Volatile
    private var baseUrl: String = BuildConfig.BASE_URL

    /** Current server base URL (no trailing slash). */
    val base: String get() = baseUrl

    lateinit var http: OkHttpClient
        private set

    private lateinit var cookieJar: PersistentCookieJar

    fun init(context: Context) {
        if (::http.isInitialized) return
        appContext = context.applicationContext
        baseUrl = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SERVER, BuildConfig.BASE_URL)!!
        rebuild()
    }

    private fun rebuild() {
        cookieJar = PersistentCookieJar(appContext)
        http = OkHttpClient.Builder().cookieJar(cookieJar).build()
    }

    /** The default server compiled into the app. */
    fun defaultServer(): String = BuildConfig.BASE_URL

    /**
     * Point the app at a different server. Clears the session (a cookie for the
     * old host is meaningless on the new one) and rebuilds the HTTP client.
     */
    fun setServer(url: String) {
        val norm = url.trim().trimEnd('/')
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVER, norm).apply()
        baseUrl = norm
        if (::cookieJar.isInitialized) cookieJar.clear()
        rebuild()
    }

    /** Whether a session is stored — i.e. whether there's anything to listen for. */
    fun hasSession(): Boolean =
        ::cookieJar.isInitialized && cookieJar.loadForRequest(base.toHttpUrl("/")).isNotEmpty()

    /** Absolute URL for a server-relative media path (e.g. "/media/x.jpg"). */
    fun mediaUrl(path: String): String = if (path.startsWith("http")) path else base + path

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun jsonBody(obj: JSONObject): RequestBody = obj.toString().toRequestBody(jsonType)

    // ---- version ----

    /** Ask the server what it is and whether we speak its protocol. */
    suspend fun serverVersion(): ServerVersion {
        val o = JSONObject(get("/api/version"))
        return ServerVersion(
            o.optString("name"), o.optString("version"), o.optInt("protocol"),
            if (o.has("openRegistration")) o.optBoolean("openRegistration") else null,
        )
    }

    // ---- auth ----

    suspend fun register(nick: String, password: String): User =
        userCall("/api/register", nick, password)

    suspend fun login(nick: String, password: String): User =
        userCall("/api/login", nick, password)

    private suspend fun userCall(path: String, nick: String, password: String): User {
        val body = JSONObject().put("nick", nick).put("password", password)
        return parseUser(post(path, jsonBody(body))).also { myId = it.id; myNick = it.nick }
    }

    suspend fun logout() {
        post("/api/logout", FormBody.Builder().build())
        cookieJar.clear()
    }

    /** Our own user id, cached so reaction events can compute the `mine` flag. */
    @Volatile var myId: Long = 0
        private set

    /** Our own nick, cached for mention detection on incoming messages. */
    @Volatile var myNick: String = ""
        private set

    suspend fun me(): User = parseUser(get("/api/me")).also { myId = it.id; myNick = it.nick }

    /**
     * Aggregate a raw per-user reaction list (as pushed in a reaction event)
     * into per-emoji groups, matching the server's message-view shape.
     */
    fun aggregateReactions(arr: JSONArray?): List<Reaction> {
        if (arr == null) return emptyList()
        val order = mutableListOf<String>()
        data class Acc(var count: Int, val nicks: MutableList<String>, var mine: Boolean)
        val byEmoji = HashMap<String, Acc>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val emoji = r.getString("emoji")
            val acc = byEmoji.getOrPut(emoji) { order.add(emoji); Acc(0, mutableListOf(), false) }
            acc.count++
            acc.nicks.add(r.optString("nick"))
            if (r.optLong("userId") == myId) acc.mine = true
        }
        return order.map { e -> byEmoji[e]!!.let { Reaction(e, it.count, it.nicks, it.mine) } }
    }

    // ---- friends ----

    suspend fun friends(): List<Friend> {
        val arr = JSONArray(get("/api/friends"))
        return (0 until arr.length()).map { parseFriend(arr.getJSONObject(it)) }
    }

    suspend fun sendFriendRequest(nick: String) =
        post("/api/friends/request", jsonBody(JSONObject().put("nick", nick))).let {}

    suspend fun respondFriend(userId: Long, accept: Boolean) =
        post("/api/friends/respond", jsonBody(JSONObject().put("userId", userId).put("accept", accept))).let {}

    suspend fun removeFriend(userId: Long) =
        post("/api/friends/remove", jsonBody(JSONObject().put("userId", userId))).let {}

    // ---- messages ----

    suspend fun conversation(withUser: Long, before: Long = 0): List<Message> {
        val url = base.toHttpUrl("/api/messages").newBuilder()
            .addQueryParameter("with", withUser.toString())
            .apply { if (before > 0) addQueryParameter("before", before.toString()) }
            .build()
        val arr = JSONArray(get(url))
        return (0 until arr.length()).map { parseMessage(arr.getJSONObject(it)) }
    }

    suspend fun sendText(withUser: Long, body: String, replyTo: Long = 0): Message =
        parseMessage(post("/api/messages", jsonBody(
            JSONObject().put("with", withUser).put("body", body).put("replyTo", replyTo))))

    /**
     * Withdraw one of my own messages, for everyone. [scope] is "dm" or "group".
     *
     * The server soft-deletes: the row survives so replies quoting it still
     * resolve, but its text and any attachment (including the stored file) are
     * gone.
     */
    suspend fun deleteMessage(id: Long, scope: String) {
        post("/api/messages/delete", jsonBody(
            JSONObject().put("id", id).put("scope", scope)))
    }

    /** Edit the text of one of my own DMs. Returns the updated message. */
    suspend fun editMessage(id: Long, body: String): Message =
        parseMessage(post("/api/messages/edit", jsonBody(
            JSONObject().put("id", id).put("scope", "dm").put("body", body))))

    /**
     * Post an attachment to a DM — an image, a video, an audio clip or any other
     * file. The server decides how to store it: images are re-encoded and
     * thumbnailed, everything else is kept byte-for-byte.
     */
    suspend fun sendAttachment(withUser: Long, filename: String, bytes: ByteArray, caption: String): Message {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("with", withUser.toString())
            .addFormDataPart("body", caption)
            .addFormDataPart("name", filename)
            .addFormDataPart("file", filename, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        return parseMessage(post("/api/messages/media", body))
    }

    /** Upload a new profile picture (thumbnailed server-side). Returns its URL. */
    suspend fun setAvatar(filename: String, bytes: ByteArray): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("image", filename, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        val o = JSONObject(post("/api/me/avatar", body))
        return o.optString("avatarUrl").let { if (it.isEmpty()) "" else mediaUrl(it) }
    }

    // ---- groups ----

    suspend fun createGroup(name: String): Group =
        parseGroup(JSONObject(post("/api/groups", jsonBody(JSONObject().put("name", name)))))

    suspend fun groups(): List<Group> {
        val arr = JSONArray(get("/api/groups"))
        return (0 until arr.length()).map { parseGroup(arr.getJSONObject(it)) }
    }

    suspend fun groupMembers(groupId: Long): List<GroupMember> {
        val url = base.toHttpUrl("/api/groups/members").newBuilder()
            .addQueryParameter("group", groupId.toString()).build()
        val arr = JSONArray(get(url))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            GroupMember(o.getLong("id"), o.getString("nick"), o.optBoolean("online"), o.optBoolean("owner"),
                o.optString("avatarUrl").ifEmpty { "" }.let { a -> if (a.isEmpty()) "" else mediaUrl(a) })
        }
    }

    /** Invite someone: they join only if they accept. */
    suspend fun inviteGroupMember(groupId: Long, nick: String) =
        post("/api/groups/invite", jsonBody(JSONObject().put("group", groupId).put("nick", nick))).let {}

    suspend fun groupInvites(): List<GroupInvite> {
        val arr = JSONArray(get("/api/groups/invites"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            GroupInvite(o.getLong("groupId"), o.getString("groupName"), o.optString("invitedBy"))
        }
    }

    suspend fun respondGroupInvite(groupId: Long, accept: Boolean) =
        post("/api/groups/respond", jsonBody(JSONObject().put("group", groupId).put("accept", accept))).let {}

    suspend fun leaveGroup(groupId: Long) =
        post("/api/groups/leave", jsonBody(JSONObject().put("group", groupId))).let {}

    suspend fun groupConversation(groupId: Long, before: Long = 0): List<GroupMessage> {
        val url = base.toHttpUrl("/api/groups/messages").newBuilder()
            .addQueryParameter("group", groupId.toString())
            .apply { if (before > 0) addQueryParameter("before", before.toString()) }
            .build()
        val arr = JSONArray(get(url))
        return (0 until arr.length()).map { parseGroupMessage(arr.getJSONObject(it)) }
    }

    suspend fun sendGroupText(groupId: Long, body: String, replyTo: Long = 0): GroupMessage =
        parseGroupMessage(JSONObject(post("/api/groups/messages",
            jsonBody(JSONObject().put("group", groupId).put("body", body).put("replyTo", replyTo)))))

    /** Edit the text of one of my own group messages. Returns the updated message. */
    suspend fun editGroupMessage(id: Long, body: String): GroupMessage =
        parseGroupMessage(JSONObject(post("/api/messages/edit", jsonBody(
            JSONObject().put("id", id).put("scope", "group").put("body", body)))))

    suspend fun sendGroupAttachment(groupId: Long, filename: String, bytes: ByteArray, caption: String): GroupMessage {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("group", groupId.toString())
            .addFormDataPart("body", caption)
            .addFormDataPart("name", filename)
            .addFormDataPart("file", filename, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        return parseGroupMessage(JSONObject(post("/api/groups/messages/media", body)))
    }

    /**
     * Mark a conversation read up to [lastId], clearing its unread count on the
     * server (and so the pip + notification badge). kind is "dm" or "group",
     * id is the friend or group id. Without this, unread counts never cleared.
     */
    suspend fun markRead(kind: String, id: Long, lastId: Long) {
        if (lastId <= 0) return
        post("/api/read", jsonBody(JSONObject().put("kind", kind).put("id", id).put("lastId", lastId)))
    }

    /** Add or remove your emoji on a message. scope is "dm", "group" or "channel". */
    suspend fun react(scope: String, messageId: Long, emoji: String, add: Boolean) =
        post("/api/reactions", jsonBody(JSONObject()
            .put("scope", scope).put("messageId", messageId)
            .put("emoji", emoji).put("add", add))).let {}

    // ---- servers ----

    suspend fun servers(): List<Server> {
        val arr = JSONArray(get("/api/servers"))
        return (0 until arr.length()).map { parseServer(arr.getJSONObject(it)) }
    }

    suspend fun createServer(name: String): Server =
        parseServer(JSONObject(post("/api/servers", jsonBody(JSONObject().put("name", name)))))

    suspend fun renameServer(serverId: Long, name: String) =
        post("/api/servers/update", jsonBody(JSONObject().put("server", serverId).put("name", name))).let {}

    suspend fun setServerIcon(serverId: Long, filename: String, bytes: ByteArray) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("server", serverId.toString())
            .addFormDataPart("image", filename, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        post("/api/servers/icon", body)
    }

    suspend fun deleteServer(serverId: Long) =
        post("/api/servers/delete", jsonBody(JSONObject().put("server", serverId))).let {}

    suspend fun leaveServer(serverId: Long) =
        post("/api/servers/leave", jsonBody(JSONObject().put("server", serverId))).let {}

    suspend fun serverMembers(serverId: Long): List<ServerMember> {
        val arr = JSONArray(get(query("/api/servers/members", "server", serverId)))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ServerMember(parseUserObj(o.getJSONObject("user")), o.optString("role"))
        }
    }

    /** A new invite code for a server; the link is [base]/?invite=CODE. */
    suspend fun createInvite(serverId: Long): String =
        JSONObject(post("/api/servers/invites", jsonBody(JSONObject().put("server", serverId)))).getString("code")

    fun inviteLink(code: String): String = "$base/?invite=$code"

    suspend fun previewInvite(code: String): InvitePreview {
        val url = base.toHttpUrl("/api/invites").newBuilder().addQueryParameter("code", code).build()
        val o = JSONObject(get(url))
        return InvitePreview(parseServer(o.getJSONObject("server")), o.optBoolean("member"))
    }

    /** Join by invite. Returns the server and whether I was newly added. */
    suspend fun joinInvite(code: String): Pair<Server, Boolean> {
        val o = JSONObject(post("/api/invites/join", jsonBody(JSONObject().put("code", code))))
        return parseServer(o.getJSONObject("server")) to o.optBoolean("joined")
    }

    suspend fun channels(serverId: Long): List<Channel> {
        val arr = JSONArray(get(query("/api/servers/channels", "server", serverId)))
        return (0 until arr.length()).map { parseChannel(arr.getJSONObject(it)) }
    }

    suspend fun createChannel(serverId: Long, name: String, kind: String): Channel =
        parseChannel(JSONObject(post("/api/servers/channels", jsonBody(
            JSONObject().put("server", serverId).put("name", name).put("kind", kind)))))

    suspend fun renameChannel(channelId: Long, name: String) =
        post("/api/channels/update", jsonBody(JSONObject().put("channel", channelId).put("name", name))).let {}

    suspend fun deleteChannel(channelId: Long) =
        post("/api/channels/delete", jsonBody(JSONObject().put("channel", channelId))).let {}

    suspend fun channelConversation(channelId: Long): List<ChannelMessage> {
        val arr = JSONArray(get(query("/api/channels/messages", "channel", channelId)))
        return (0 until arr.length()).map { parseChannelMessage(arr.getJSONObject(it)) }
    }

    suspend fun sendChannelText(channelId: Long, body: String, replyTo: Long = 0): ChannelMessage =
        parseChannelMessage(JSONObject(post("/api/channels/messages", jsonBody(
            JSONObject().put("channel", channelId).put("body", body).put("replyTo", replyTo)))))

    suspend fun sendChannelAttachment(channelId: Long, filename: String, bytes: ByteArray, caption: String): ChannelMessage {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("channel", channelId.toString())
            .addFormDataPart("body", caption)
            .addFormDataPart("name", filename)
            .addFormDataPart("file", filename, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        return parseChannelMessage(JSONObject(post("/api/channels/messages/media", body)))
    }

    suspend fun editChannelMessage(id: Long, body: String): ChannelMessage =
        parseChannelMessage(JSONObject(post("/api/messages/edit", jsonBody(
            JSONObject().put("id", id).put("scope", "channel").put("body", body)))))

    /** What became of these calls (the codes a conversation mentions). */
    suspend fun calls(codes: List<String>): List<CallState> {
        if (codes.isEmpty()) return emptyList()
        val url = base.toHttpUrl("/api/calls").newBuilder()
            .addQueryParameter("codes", codes.joinToString(",")).build()
        val arr = JSONArray(get(url))
        return (0 until arr.length()).map { parseCall(arr.getJSONObject(it)) }
    }

    /** Turn a call down, so the caller isn't left waiting. */
    suspend fun declineCall(code: String) =
        post("/api/calls/decline", jsonBody(JSONObject().put("code", code))).let {}

    fun parseCall(o: JSONObject): CallState {
        val arr = o.optJSONArray("participants")
        return CallState(
            code = o.optString("code"),
            callerId = o.optLong("callerId"),
            callerNick = o.optString("callerNick"),
            outcome = o.optString("outcome"),
            answered = o.optLong("answered"),
            ended = o.optLong("ended"),
            declinedBy = o.optLong("declinedBy"),
            participants = (0 until (arr?.length() ?: 0)).map { arr!!.getLong(it) },
            here = o.optInt("here"),
        )
    }

    suspend fun serverActivity(serverId: Long): ServerActivity =
        parseActivity(JSONObject(get(query("/api/servers/active", "server", serverId))))

    private fun query(path: String, key: String, id: Long): HttpUrl =
        base.toHttpUrl(path).newBuilder().addQueryParameter(key, id.toString()).build()

    // ---- low-level ----

    private suspend fun get(path: String): String = get(base.toHttpUrl(path))

    private suspend fun get(url: HttpUrl): String = execute(Request.Builder().url(url).get().build())

    private suspend fun post(path: String, body: RequestBody): String =
        execute(Request.Builder().url(base.toHttpUrl(path)).post(body).build())

    private suspend fun execute(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw ApiException(msg?.takeIf { it.isNotEmpty() } ?: "HTTP ${resp.code}")
            }
            text
        }
    }

    // ---- parsing ----

    private fun parseUser(json: String) = JSONObject(json).let {
        User(it.getLong("id"), it.getString("nick"), it.optString("avatarUrl").ifEmpty { "" }.let { u -> if (u.isEmpty()) "" else mediaUrl(u) })
    }

    private fun parseFriend(o: JSONObject): Friend {
        val u = o.getJSONObject("user")
        return Friend(
            user = User(u.getLong("id"), u.getString("nick"),
                u.optString("avatarUrl").ifEmpty { "" }.let { a -> if (a.isEmpty()) "" else mediaUrl(a) }),
            status = o.getString("status"),
            direction = o.optString("direction"),
            online = o.optBoolean("online"),
            unread = o.optInt("unread"),
            lastActivity = o.optLong("lastActivity"),
        )
    }

    private fun parseMessage(json: String) = parseMessage(JSONObject(json))

    private fun parseMessage(o: JSONObject) = Message(
        id = o.getLong("id"),
        senderId = o.getLong("senderId"),
        recipientId = o.getLong("recipientId"),
        body = o.optString("body"),
        imageUrl = o.optString("imageUrl").ifEmpty { null }?.let { mediaUrl(it) },
        thumbUrl = o.optString("thumbUrl").ifEmpty { null }?.let { mediaUrl(it) },
        attachment = attachmentOf(o),
        created = o.getLong("created"),
        reactions = parseReactions(o.optJSONArray("reactions")),
        replyTo = o.optLong("replyTo"),
        replySender = o.optLong("replySender"),
        replyBody = o.optString("replyBody"),
        editedAt = o.optLong("editedAt"),
        deletedAt = o.optLong("deletedAt"),
    )

    /**
     * Build an [Attachment] from a message payload, if it has one.
     *
     * `mediaUrl` and `mediaKind` arrived with generalised attachments; older
     * messages only have `imageUrl`/`thumbUrl`, and every one of those was an
     * image, so they read back as one.
     */
    fun attachmentOf(o: JSONObject): Attachment? {
        val raw = o.optString("mediaUrl").ifEmpty { o.optString("imageUrl") }
        if (raw.isEmpty()) return null
        val thumb = o.optString("thumbUrl").ifEmpty { null }?.let { mediaUrl(it) }
        return Attachment(
            url = mediaUrl(raw),
            kind = o.optString("mediaKind").ifEmpty { if (thumb != null) "image" else "file" },
            name = o.optString("mediaName").ifEmpty { "file" },
            size = o.optLong("mediaSize"),
            thumbUrl = thumb,
        )
    }

    private fun parseReactions(arr: JSONArray?): List<Reaction> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map {
            val r = arr.getJSONObject(it)
            val nicksArr = r.optJSONArray("nicks") ?: JSONArray()
            Reaction(
                emoji = r.getString("emoji"),
                count = r.optInt("count"),
                nicks = (0 until nicksArr.length()).map { i -> nicksArr.getString(i) },
                mine = r.optBoolean("mine"),
            )
        }
    }

    private fun parseUserObj(o: JSONObject) = User(
        o.getLong("id"), o.getString("nick"),
        o.optString("avatarUrl").let { a -> if (a.isEmpty()) "" else mediaUrl(a) })

    fun parseServer(o: JSONObject) = Server(
        id = o.getLong("id"),
        name = o.optString("name"),
        iconUrl = o.optString("iconUrl").let { if (it.isEmpty()) "" else mediaUrl(it) },
        ownerId = o.optLong("ownerId"),
        role = o.optString("role"),
        memberCount = o.optInt("memberCount"),
        unread = o.optInt("unread"),
        voiceCount = o.optInt("voiceCount"),
    )

    private fun parseChannel(o: JSONObject) = Channel(
        id = o.getLong("id"),
        serverId = o.optLong("serverId"),
        name = o.optString("name"),
        kind = o.optString("kind"),
        callCode = o.optString("callCode"),
        unread = o.optInt("unread"),
    )

    fun parseChannelMessage(o: JSONObject) = ChannelMessage(
        id = o.getLong("id"),
        channelId = o.optLong("channelId"),
        serverId = o.optLong("serverId"),
        senderId = o.getLong("senderId"),
        body = o.optString("body"),
        attachment = attachmentOf(o),
        imageUrl = o.optString("imageUrl").ifEmpty { null }?.let { mediaUrl(it) },
        thumbUrl = o.optString("thumbUrl").ifEmpty { null }?.let { mediaUrl(it) },
        created = o.getLong("created"),
        reactions = parseReactions(o.optJSONArray("reactions")),
        replyTo = o.optLong("replyTo"),
        replySender = o.optLong("replySender"),
        replyBody = o.optString("replyBody"),
        editedAt = o.optLong("editedAt"),
        deletedAt = o.optLong("deletedAt"),
    )

    fun parseActivity(o: JSONObject): ServerActivity {
        val active = o.optJSONArray("active")
        val voice = o.optJSONObject("voice")
        val seats = HashMap<Long, List<Long>>()
        voice?.keys()?.forEach { k ->
            val arr = voice.getJSONArray(k)
            seats[k.toLong()] = (0 until arr.length()).map { arr.getLong(it) }
        }
        return ServerActivity(
            serverId = o.optLong("serverId"),
            epoch = o.optString("epoch"),
            seq = o.optLong("seq"),
            active = (0 until (active?.length() ?: 0)).map { active!!.getLong(it) }.toSet(),
            voice = seats,
        )
    }

    private fun parseGroup(o: JSONObject) = Group(
        id = o.getLong("id"), name = o.getString("name"),
        ownerId = o.getLong("ownerId"), callCode = o.getString("callCode"),
        memberCount = o.optInt("memberCount"),
        unread = o.optInt("unread"),
        lastActivity = o.optLong("lastActivity"),
    )

    fun parseGroupMessage(o: JSONObject) = GroupMessage(
        id = o.getLong("id"),
        groupId = o.getLong("groupId"),
        senderId = o.getLong("senderId"),
        body = o.optString("body"),
        imageUrl = o.optString("imageUrl").ifEmpty { null }?.let { mediaUrl(it) },
        thumbUrl = o.optString("thumbUrl").ifEmpty { null }?.let { mediaUrl(it) },
        attachment = attachmentOf(o),
        created = o.getLong("created"),
        reactions = parseReactions(o.optJSONArray("reactions")),
        replyTo = o.optLong("replyTo"),
        replySender = o.optLong("replySender"),
        replyBody = o.optString("replyBody"),
        editedAt = o.optLong("editedAt"),
        deletedAt = o.optLong("deletedAt"),
    )
}

/** Build an HttpUrl from a base + path, tolerating a trailing slash on base. */
private fun String.toHttpUrl(path: String): HttpUrl =
    (this.trimEnd('/') + path).toHttpUrl()

/**
 * PersistentCookieJar stores cookies (really just the session cookie) in
 * SharedPreferences so the login survives app restarts.
 */
class PersistentCookieJar(context: Context) : CookieJar {
    private val prefs = context.getSharedPreferences("tb_cookies", Context.MODE_PRIVATE)
    private val cookies = mutableMapOf<String, Cookie>()

    init {
        prefs.all.forEach { (_, v) ->
            val raw = v as? String ?: return@forEach
            ApiClient.base.toHttpUrlOrNull()?.let { url ->
                Cookie.parse(url, raw)?.let { cookies[it.name] = it }
            }
        }
    }

    override fun saveFromResponse(url: HttpUrl, list: List<Cookie>) {
        for (c in list) {
            cookies[c.name] = c
            prefs.edit().putString(c.name, c.toString()).apply()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.values.toList()

    fun clear() {
        cookies.clear()
        prefs.edit().clear().apply()
    }
}
