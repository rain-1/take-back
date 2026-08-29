package com.takeback.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.takeback.app.databinding.ActivityChatBinding
import com.takeback.app.net.ApiClient
import com.takeback.app.net.Events
import com.takeback.app.net.EventsListener
import com.takeback.app.net.Message
import com.takeback.app.net.Reaction
import com.takeback.app.net.User
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * ChatActivity is a 1:1 direct-message conversation, drawn in the same
 * Slack-style grouped layout as the web client (see [MessageRenderer]).
 */
class ChatActivity : AppCompatActivity(), EventsListener {

    companion object {
        const val EXTRA_FRIEND_ID = "friendId"
        const val EXTRA_FRIEND_NICK = "friendNick"
        private val CALL_RE = Regex("^📞 call:([A-Z0-9]{4,8})$")
    }

    private lateinit var binding: ActivityChatBinding
    private lateinit var renderer: MessageRenderer
    private var friendId: Long = 0
    private lateinit var friendNick: String
    private var me: User? = null
    private var friend: User? = null
    private var replyingTo: Message? = null

    private val pickAttachment = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { uploadAttachment(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        friendId = intent.getLongExtra(EXTRA_FRIEND_ID, 0)
        friendNick = intent.getStringExtra(EXTRA_FRIEND_NICK) ?: "friend"
        binding.friendNick.text = friendNick

        renderer = MessageRenderer(
            this, binding.messages, binding.scroll, Markwon.create(this),
            onReply = { startReply(it.id, it.senderNick, it.body) },
            onReact = { id, emoji, add -> react(id, emoji, add) },
            onJoinCall = { joinCall(it) },
            onOpenAttachment = { openAttachment(it) },
            onEdit = { editMessage(it) },
        )

        binding.sendBtn.setOnClickListener { sendText() }
        // "*/*" so it isn't just photos any more — video, audio, documents, anything.
        binding.imgBtn.setOnClickListener { pickAttachment.launch("*/*") }
        binding.callBtn.setOnClickListener { startCall() }
        binding.replyCancel.setOnClickListener { cancelReply() }

        Events.addListener(this)
        load()
    }

    override fun onResume() {
        super.onResume()
        Events.openFriendId = friendId
        Events.clearMessageNotification(friendId) // viewing it dismisses its notification
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
                me = ApiClient.me()
                // The friend's avatar comes from the friends list.
                friend = runCatching { ApiClient.friends().firstOrNull { it.user.id == friendId }?.user }.getOrNull()
                val msgs = ApiClient.conversation(friendId)
                renderer.clear()
                msgs.forEach { render(it) }
                renderer.scrollToBottom()
                // Viewing the conversation clears its unread count on the server.
                msgs.lastOrNull()?.let { runCatching { ApiClient.markRead("dm", friendId, it.id) } }
            } catch (_: Exception) { /* transient */ }
        }
    }

    /** Convert a DM message into the renderer's normalized form. */
    private fun render(m: Message) {
        val mine = m.senderId == me?.id
        val sender = if (mine) me else friend
        val call = CALL_RE.find(m.body)?.groupValues?.get(1)
        renderer.add(
            RMsg(
                id = m.id, senderId = m.senderId,
                senderNick = sender?.nick ?: (if (mine) "you" else friendNick),
                senderAvatar = sender?.avatarUrl ?: "",
                body = m.body, imageUrl = m.imageUrl, thumbUrl = m.thumbUrl,
                attachment = m.attachment, created = m.created,
                reactions = m.reactions,
                replyTo = m.replyTo,
                replyNick = if (m.replySender == me?.id) (me?.nick ?: "you") else friendNick,
                replyBody = m.replyBody,
                mine = mine, callCode = call,
                editedAt = m.editedAt,
            )
        )
    }

    /** Prompt to edit one of my own messages, then push the change. */
    private fun editMessage(m: RMsg) {
        val input = android.widget.EditText(this).apply { setText(m.body); setSelection(m.body.length) }
        AlertDialog.Builder(this)
            .setTitle("Edit message")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val body = input.text.toString().trim()
                if (body.isEmpty() || body == m.body) return@setPositiveButton
                lifecycleScope.launch {
                    runCatching { ApiClient.editMessage(m.id, body) }
                        .onSuccess { renderer.updateMessage(it.id, it.body, it.editedAt) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendText() {
        val body = binding.input.text.toString().trim()
        if (body.isEmpty()) return
        binding.input.setText("")
        val replyTo = replyingTo?.id ?: 0
        cancelReply()
        lifecycleScope.launch {
            runCatching { ApiClient.sendText(friendId, body, replyTo) }
                .onSuccess { render(it); renderer.scrollToBottom() }
        }
    }

    /**
     * Post a picked file — image, video, audio or anything else — to this chat.
     * The size is checked against the server's cap before reading the bytes, so
     * a 2 GB video fails immediately instead of after filling memory with it.
     */
    private fun uploadAttachment(uri: Uri) {
        val declared = Attachments.size(this, uri)
        if (declared > Attachments.MAX_BYTES) {
            toast("That file is ${Attachments.formatSize(declared)} — the limit is " +
                  Attachments.formatSize(Attachments.MAX_BYTES) + ".")
            return
        }
        lifecycleScope.launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                if (bytes.size > Attachments.MAX_BYTES) { // provider didn't declare a size
                    toast("That file is too big — the limit is " +
                          Attachments.formatSize(Attachments.MAX_BYTES) + ".")
                    return@launch
                }
                val name = Attachments.displayName(this@ChatActivity, uri)
                render(ApiClient.sendAttachment(friendId, name, bytes, "")); renderer.scrollToBottom()
            } catch (e: Exception) {
                toast("Couldn't send that file: " + (e.message ?: "upload failed"))
            }
        }
    }

    /** Hand an attachment's URL to whatever app handles that type. */
    private fun openAttachment(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("No app on this device can open that.") }
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()

    private fun startCall() {
        val code = randomCode()
        lifecycleScope.launch {
            runCatching { ApiClient.sendText(friendId, "📞 call:$code") }
                .onSuccess { render(it); renderer.scrollToBottom() }
        }
        joinCall(code)
    }

    private fun joinCall(code: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROOM, code))
    }

    // ---- replies ----

    private fun startReply(id: Long, nick: String, body: String) {
        replyingTo = Message(id, 0, 0, body, null, null, created = 0) // only id is used on send
        binding.replyBarText.text = "Replying to $nick: ${body.take(50).ifEmpty { "image" }}"
        binding.replyBar.visibility = View.VISIBLE
    }

    private fun cancelReply() {
        replyingTo = null
        binding.replyBar.visibility = View.GONE
    }

    private fun react(messageId: Long, emoji: String, add: Boolean) {
        lifecycleScope.launch { runCatching { ApiClient.react("dm", messageId, emoji, add) } }
    }

    override fun onReaction(scope: String, messageId: Long, reactions: List<Reaction>) = runOnUiThread {
        if (scope == "dm") renderer.updateReactions(messageId, reactions)
    }

    private fun randomCode(): String {
        val a = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return (1..6).map { a[Random.nextInt(a.length)] }.joinToString("")
    }

    override fun onMessage(message: Message) = runOnUiThread {
        if (message.senderId == friendId) {
            render(message); renderer.scrollToBottom()
            // Keep it read while we're looking at it.
            lifecycleScope.launch { runCatching { ApiClient.markRead("dm", friendId, message.id) } }
        }
    }

    override fun onMessageEdited(message: Message) = runOnUiThread {
        if (renderer.has(message.id)) renderer.updateMessage(message.id, message.body, message.editedAt)
    }
}
