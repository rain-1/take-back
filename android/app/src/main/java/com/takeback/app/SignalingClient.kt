package com.takeback.app

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
    /** A peer announced its mic/camera state (or its initial state on join). */
    fun onState(fromId: String, video: Boolean, audio: Boolean) {}
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
) {
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null

    fun connect() {
        val url = "$baseUrl?room=${enc(room)}&nick=${enc(nick)}"
        val request = Request.Builder().url(url).build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = dispatch(text)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                listener.onClosed(reason.ifEmpty { "closed" })
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onClosed(t.message ?: "connection failed")
        })
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
                listener.onState(from, p.optBoolean("video", true), p.optBoolean("audio", true))
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

    /** Broadcast our mic/camera state to everyone in the room (no `to` = all). */
    fun sendState(video: Boolean, audio: Boolean) =
        sendRaw("state", null, JSONObject().put("video", video).put("audio", audio))

    fun sendOffer(to: String, sdp: JSONObject) = sendRaw("offer", to, sdp)
    fun sendAnswer(to: String, sdp: JSONObject) = sendRaw("answer", to, sdp)
    fun sendCandidate(to: String, candidate: JSONObject) = sendRaw("candidate", to, candidate)

    fun close() {
        socket?.close(1000, "bye")
        socket = null
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}
