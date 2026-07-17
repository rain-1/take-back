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
data class ServerVersion(val name: String, val version: String, val protocol: Int) {
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

data class Message(
    val id: Long,
    val senderId: Long,
    val recipientId: Long,
    val body: String,
    val imageUrl: String?,   // absolute URL, or null
    val thumbUrl: String?,
    val created: Long,
    val reactions: List<Reaction> = emptyList(),
    val replyTo: Long = 0,
    val replySender: Long = 0,
    val replyBody: String = "",
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
    val created: Long,
    val reactions: List<Reaction> = emptyList(),
    val replyTo: Long = 0,
    val replySender: Long = 0,
    val replyBody: String = "",
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

    /** Absolute URL for a server-relative media path (e.g. "/media/x.jpg"). */
    fun mediaUrl(path: String): String = if (path.startsWith("http")) path else base + path

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun jsonBody(obj: JSONObject): RequestBody = obj.toString().toRequestBody(jsonType)

    // ---- version ----

    /** Ask the server what it is and whether we speak its protocol. */
    suspend fun serverVersion(): ServerVersion {
        val o = JSONObject(get("/api/version"))
        return ServerVersion(o.optString("name"), o.optString("version"), o.optInt("protocol"))
    }

    // ---- auth ----

    suspend fun register(nick: String, password: String): User =
        userCall("/api/register", nick, password)

    suspend fun login(nick: String, password: String): User =
        userCall("/api/login", nick, password)

    private suspend fun userCall(path: String, nick: String, password: String): User {
        val body = JSONObject().put("nick", nick).put("password", password)
        return parseUser(post(path, jsonBody(body)))
    }

    suspend fun logout() {
        post("/api/logout", FormBody.Builder().build())
        cookieJar.clear()
    }

    /** Our own user id, cached so reaction events can compute the `mine` flag. */
    @Volatile var myId: Long = 0
        private set

    suspend fun me(): User = parseUser(get("/api/me")).also { myId = it.id }

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

    suspend fun sendImage(withUser: Long, filename: String, bytes: ByteArray, caption: String): Message {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("with", withUser.toString())
            .addFormDataPart("body", caption)
            .addFormDataPart("image", filename, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        return parseMessage(post("/api/messages/image", body))
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

    suspend fun sendGroupImage(groupId: Long, filename: String, bytes: ByteArray, caption: String): GroupMessage {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("group", groupId.toString())
            .addFormDataPart("body", caption)
            .addFormDataPart("image", filename, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        return parseGroupMessage(JSONObject(post("/api/groups/messages/image", body)))
    }

    /** Add or remove your emoji on a message. scope is "dm" or "group". */
    suspend fun react(scope: String, messageId: Long, emoji: String, add: Boolean) =
        post("/api/reactions", jsonBody(JSONObject()
            .put("scope", scope).put("messageId", messageId)
            .put("emoji", emoji).put("add", add))).let {}

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
        created = o.getLong("created"),
        reactions = parseReactions(o.optJSONArray("reactions")),
        replyTo = o.optLong("replyTo"),
        replySender = o.optLong("replySender"),
        replyBody = o.optString("replyBody"),
    )

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
        created = o.getLong("created"),
        reactions = parseReactions(o.optJSONArray("reactions")),
        replyTo = o.optLong("replyTo"),
        replySender = o.optLong("replySender"),
        replyBody = o.optString("replyBody"),
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
