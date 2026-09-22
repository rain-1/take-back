package com.takeback.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * A signaling peer known within a room. Mirrors the server's `Peer`.
 */
data class RemotePeer(val id: String, val nick: String)

/**
 * Callbacks delivered on the OkHttp WebSocket thread. The activity is
 * responsible for hopping to the main thread where it touches UI.
 */
interface SignalingListener {
    fun onWelcome(selfId: String, peers: List<RemotePeer>)
    fun onHello(fromId: String, nick: String)
    /**
     * A peer announced its mic/camera state (or its initial state on join),
     * along with their profile picture URL ("" if they have none).
     * [screenId] names the stream carrying their screen share, or "" if they
     * aren't sharing — a screen track is otherwise indistinguishable from a
     * camera track.
     */
    fun onState(fromId: String, video: Boolean, audio: Boolean, screenId: String, avatarUrl: String) {}
    fun onOffer(fromId: String, nick: String, sdpJson: JSONObject)
    fun onAnswer(fromId: String, sdpJson: JSONObject)
    fun onCandidate(fromId: String, candidateJson: JSONObject)
    fun onLeave(fromId: String)
    fun onClosed(reason: String)
}

/**
 * SignalingClient speaks the same tiny JSON protocol as the Go server and the
 * web client: messages carry a `type`, optional `to`/`from`/`nick`, a `peers`
 * list on welcome, and a `payload` that is itself a JSON *string* (so it can be
 * forwarded verbatim). We match that shape precisely for interop.
 */
class SignalingClient(
    private val baseUrl: String,
    private val room: String,
    private val nick: String,
    private val listener: SignalingListener,
    base: OkHttpClient = OkHttpClient(),
) {
    // Built from the app's client when given one, so the handshake carries the
    // session cookie: a server's voice channel only admits its members.
    private val http = base.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var stopped = false
    private var reconnectDelayMs = 1_000L
    private var reconnectPending = false

    @Synchronized fun connect() {
        stopped = false
        open(++generation)
    }

    private fun open(gen: Int) {
        val url = "$baseUrl?room=${enc(room)}&nick=${enc(nick)}"
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!markOpen(gen)) webSocket.close(1000, "superseded")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation || stopped) return
                runCatching { dispatch(text) }.onFailure {
                    listener.onClosed("invalid signaling message")
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                reconnectLater(gen, reason.ifEmpty { "closed" })
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                reconnectLater(gen, t.message ?: "connection failed")
        })
    }

    @Synchronized private fun markOpen(gen: Int): Boolean {
        if (gen != generation || stopped) return false
        reconnectDelayMs = 1_000L
        reconnectPending = false
        return true
    }

    @Synchronized private fun reconnectLater(gen: Int, reason: String) {
        if (stopped || gen != generation || reconnectPending) return
        listener.onClosed(reason)
        reconnectPending = true
        val delay = reconnectDelayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(15_000L)
        handler.postDelayed({
            retry(gen)
        }, delay)
    }

    @Synchronized private fun retry(gen: Int) {
        if (stopped || gen != generation) return
        reconnectPending = false
        open(gen)
    }

    /** Drop a socket tied to the old default network and reconnect immediately. */
    @Synchronized fun networkChanged() {
        if (stopped) return
        generation++
        reconnectPending = false
        handler.removeCallbacksAndMessages(null)
        socket?.cancel()
        socket = null
        reconnectDelayMs = 1_000L
        open(generation)
    }

    private fun dispatch(text: String) {
        val msg = JSONObject(text)
        val from = msg.optString("from")
        val nickField = msg.optString("nick")
        when (msg.optString("type")) {
            "welcome" -> {
                val peers = mutableListOf<RemotePeer>()
                val arr: JSONArray = msg.optJSONArray("peers") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    peers.add(RemotePeer(p.getString("id"), p.optString("nick", "peer")))
                }
                listener.onWelcome(msg.optString("to"), peers)
            }
            "hello" -> listener.onHello(from, nickField)
            "state" -> {
                val p = payloadOf(msg)
                listener.onState(
                    from,
                    p.optBoolean("video", true),
                    p.optBoolean("audio", true),
                    p.optString("screenId"),
                    p.optString("avatarUrl"),
                )
            }
            "offer" -> listener.onOffer(from, nickField, payloadOf(msg))
            "answer" -> listener.onAnswer(from, payloadOf(msg))
            "candidate" -> listener.onCandidate(from, payloadOf(msg))
            "leave" -> listener.onLeave(from)
        }
    }

    /** The `payload` field is a JSON-encoded string; decode it back to an object. */
    private fun payloadOf(msg: JSONObject): JSONObject =
        JSONObject(msg.optString("payload", "{}"))

    private fun sendRaw(type: String, to: String?, payload: JSONObject?) {
        val obj = JSONObject().put("type", type).put("nick", nick)
        if (to != null) obj.put("to", to)
        // Match the web client: payload is a string containing serialized JSON.
        if (payload != null) obj.put("payload", payload.toString())
        socket?.send(obj.toString())
    }

    /** Broadcast our mic/camera/screen state to everyone in the room (no `to` = all). */
    fun sendState(video: Boolean, audio: Boolean, screenId: String, avatarUrl: String) =
        sendRaw("state", null, JSONObject()
            .put("video", video)
            .put("audio", audio)
            .put("screenId", screenId)
            // Travels with the call so everyone sees everyone's picture, the
            // same field the web client sends.
            .put("avatarUrl", avatarUrl))

    fun sendOffer(to: String, sdp: JSONObject) = sendRaw("offer", to, sdp)
    fun sendAnswer(to: String, sdp: JSONObject) = sendRaw("answer", to, sdp)
    fun sendCandidate(to: String, candidate: JSONObject) = sendRaw("candidate", to, candidate)

    @Synchronized fun close() {
        stopped = true
        generation++
        reconnectPending = false
        handler.removeCallbacksAndMessages(null)
        socket?.close(1000, "bye")
        socket = null
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
