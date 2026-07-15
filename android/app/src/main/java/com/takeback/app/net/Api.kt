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

data class User(val id: Long, val nick: String)

data class Friend(
    val user: User,
    val status: String,     // pending | accepted
    val direction: String,  // incoming | outgoing
    val online: Boolean,
)

data class Message(
    val id: Long,
    val senderId: Long,
    val recipientId: Long,
    val body: String,
    val imageUrl: String?,   // absolute URL, or null
    val thumbUrl: String?,
    val created: Long,
)

/**
 * ApiClient is the app-wide HTTP client for the take-back REST API. It persists
 * the session cookie so logins survive process restarts, and exposes suspend
 * functions that run on Dispatchers.IO.
 */
object ApiClient {
    val base: String get() = BuildConfig.BASE_URL

    lateinit var http: OkHttpClient
        private set

    private lateinit var cookieJar: PersistentCookieJar

    fun init(context: Context) {
        if (::http.isInitialized) return
        cookieJar = PersistentCookieJar(context.applicationContext)
        http = OkHttpClient.Builder().cookieJar(cookieJar).build()
    }

    /** Absolute URL for a server-relative media path (e.g. "/media/x.jpg"). */
    fun mediaUrl(path: String): String = if (path.startsWith("http")) path else base + path

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun jsonBody(obj: JSONObject): RequestBody = obj.toString().toRequestBody(jsonType)

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

    suspend fun me(): User = parseUser(get("/api/me"))

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

    suspend fun sendText(withUser: Long, body: String): Message =
        parseMessage(post("/api/messages", jsonBody(JSONObject().put("with", withUser).put("body", body))))

    suspend fun sendImage(withUser: Long, filename: String, bytes: ByteArray, caption: String): Message {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("with", withUser.toString())
            .addFormDataPart("body", caption)
            .addFormDataPart("image", filename, bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        return parseMessage(post("/api/messages/image", body))
    }

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

    private fun parseUser(json: String) = JSONObject(json).let { User(it.getLong("id"), it.getString("nick")) }

    private fun parseFriend(o: JSONObject): Friend {
        val u = o.getJSONObject("user")
        return Friend(
            user = User(u.getLong("id"), u.getString("nick")),
            status = o.getString("status"),
            direction = o.optString("direction"),
            online = o.optBoolean("online"),
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
