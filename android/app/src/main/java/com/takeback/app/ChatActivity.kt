package com.takeback.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.takeback.app.databinding.ActivityChatBinding
import com.takeback.app.net.ApiClient
import com.takeback.app.net.Events
import com.takeback.app.net.EventsListener
import com.takeback.app.net.Message
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * ChatActivity is a 1:1 direct-message conversation: Markdown text, image
 * sharing with thumbnails, live incoming messages, and a button to start a
 * video call with this friend.
 */
class ChatActivity : AppCompatActivity(), EventsListener {

    companion object {
        const val EXTRA_FRIEND_ID = "friendId"
        const val EXTRA_FRIEND_NICK = "friendNick"
        private val CALL_RE = Regex("^📞 call:([A-Z0-9]{4,8})$")
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var markwon: Markwon
    private var friendId: Long = 0
    private lateinit var friendNick: String
    private var myId: Long = 0

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { uploadImage(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        markwon = Markwon.create(this)

        friendId = intent.getLongExtra(EXTRA_FRIEND_ID, 0)
        friendNick = intent.getStringExtra(EXTRA_FRIEND_NICK) ?: "friend"
        binding.friendNick.text = friendNick

        binding.sendBtn.setOnClickListener { sendText() }
        binding.imgBtn.setOnClickListener { pickImage.launch("image/*") }
        binding.callBtn.setOnClickListener { startCall() }

        Events.addListener(this)
        load()
    }

    override fun onResume() {
        super.onResume()
        Events.openFriendId = friendId // suppress notifications for this chat
    }

    override fun onPause() {
        super.onPause()
        Events.openFriendId = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Events.removeListener(this)
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                myId = ApiClient.me().id
                val msgs = ApiClient.conversation(friendId)
                binding.messages.removeAllViews()
                msgs.forEach { addMessage(it) }
                scrollToBottom()
            } catch (_: Exception) { /* transient */ }
        }
    }

    private fun sendText() {
        val body = binding.input.text.toString().trim()
        if (body.isEmpty()) return
        binding.input.setText("")
        lifecycleScope.launch {
            runCatching { ApiClient.sendText(friendId, body) }
                .onSuccess { addMessage(it); scrollToBottom() }
        }
    }

    private fun uploadImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                val name = (uri.lastPathSegment ?: "image").substringAfterLast('/')
                val msg = ApiClient.sendImage(friendId, name, bytes, "")
                addMessage(msg); scrollToBottom()
            } catch (_: Exception) { /* ignore */ }
        }
    }

    private fun startCall() {
        val code = randomCode()
        lifecycleScope.launch {
            // Send a joinable call message so the friend can tap to join.
            runCatching { ApiClient.sendText(friendId, "📞 call:$code") }
                .onSuccess { addMessage(it); scrollToBottom() }
        }
        joinCall(code)
    }

    private fun joinCall(code: String) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_ROOM, code)
        })
    }

    // ---- rendering ----

    private fun addMessage(m: Message) {
        val mine = m.senderId == myId

        val call = CALL_RE.find(m.body)
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            background = bubbleBg(mine)
        }

        if (call != null) {
            val code = call.groupValues[1]
            bubble.addView(TextView(this).apply {
                text = "📞 Video call"; setTextColor(Color.WHITE)
            })
            bubble.addView(Button(this).apply {
                text = "Join call $code"
                setOnClickListener { joinCall(code) }
            })
        } else {
            if (m.body.isNotEmpty()) {
                val tv = TextView(this).apply { setTextColor(Color.parseColor("#E7E9EE")) }
                markwon.setMarkdown(tv, m.body)
                bubble.addView(tv)
            }
            if (m.thumbUrl != null) {
                bubble.addView(ImageView(this).apply {
                    adjustViewBounds = true
                    maxWidth = (240 * resources.displayMetrics.density).toInt()
                    load(m.thumbUrl)
                    setOnClickListener {
                        m.imageUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                    }
                    val lp = LinearLayout.LayoutParams(-2, -2); lp.topMargin = 8; layoutParams = lp
                })
            }
        }

        val row = LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 8 }
            gravity = if (mine) Gravity.END else Gravity.START
            addView(bubble)
        }
        binding.messages.addView(row)
    }

    private fun bubbleBg(mine: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 28f
        setColor(Color.parseColor(if (mine) "#274690" else "#1C2029"))
    }

    private fun scrollToBottom() = binding.scroll.post {
        binding.scroll.fullScroll(View.FOCUS_DOWN)
    }

    private fun randomCode(): String {
        val a = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return (1..6).map { a[Random.nextInt(a.length)] }.joinToString("")
    }

    // ---- live events ----

    override fun onMessage(message: Message) = runOnUiThread {
        if (message.senderId == friendId) { addMessage(message); scrollToBottom() }
    }
}
