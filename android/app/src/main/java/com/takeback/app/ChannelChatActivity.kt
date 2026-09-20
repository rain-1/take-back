package com.takeback.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.takeback.app.databinding.ActivityGroupChatBinding
import com.takeback.app.net.ApiClient
import com.takeback.app.net.ChannelMessage
import com.takeback.app.net.Events
import com.takeback.app.net.EventsListener
import com.takeback.app.net.Mentions
import com.takeback.app.net.ServerMember
import kotlinx.coroutines.launch

/**
 * ChannelChatActivity is a server's text channel. It works like a group chat —
 * replies, reactions, edits, deletes, attachments, mentions — with the server's
 * members as the people in it, and admins able to delete anyone's message.
 * Viewing it keeps me active in the server.
 */
class ChannelChatActivity : AppCompatActivity(), EventsListener {

    companion object {
        const val EXTRA_CHANNEL_ID = "channelId"
        const val EXTRA_CHANNEL_NAME = "channelName"
        const val EXTRA_SERVER_ID = "serverId"
        const val EXTRA_SERVER_NAME = "serverName"
    }

    private lateinit var binding: ActivityGroupChatBinding
    private lateinit var renderer: MessageRenderer
    private var channelId = 0L
    private var serverId = 0L
    private var myId = 0L
    private var isAdmin = false
    private var members: Map<Long, ServerMember> = emptyMap()
    private var replyingTo: Long = 0

    private val pickAttachment = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { uploadAttachment(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        channelId = intent.getLongExtra(EXTRA_CHANNEL_ID, 0)
        serverId = intent.getLongExtra(EXTRA_SERVER_ID, 0)
        val serverName = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "server"
        binding.groupName.text = "# ${intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "channel"}  ·  $serverName"
        // A channel has no call button (voice channels are the calls) and no
        // member strip or add-member (that's the server's business).
        binding.callBtn.visibility = View.GONE
        binding.addMemberBtn.visibility = View.GONE
        (binding.members.parent as? View)?.visibility = View.GONE

        renderer = MessageRenderer(
            this, binding.messages, binding.scroll, markwon(),
            onReply = { startReply(it.id, it.senderNick, it.body) },
            onReact = { id, emoji, add -> react(id, emoji, add) },
            onJoinCall = { Calls.join(this, it) },
            onOpenAttachment = { openAttachment(it) },
            onDelete = { deleteMessage(it) },
            onEdit = { editMessage(it) },
            knownNick = { n -> members.values.firstOrNull { it.user.nick.equals(n, ignoreCase = true) }?.user?.nick },
            onMentionTap = { nick -> showProfile(nick) },
            canModerate = { isAdmin },
        )

        binding.sendBtn.setOnClickListener { sendText() }
        binding.imgBtn.setOnClickListener { pickAttachment.launch("*/*") }
        binding.replyCancel.setOnClickListener { cancelReply() }

        LastChat.remember(this, "channel", channelId,
            intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "channel", serverId, serverName)
        Events.addListener(this)
        load()
    }

    override fun onStart() {
        super.onStart()
        Events.viewStart(this, serverId)
    }

    override fun onStop() {
        super.onStop()
        Events.viewStop(this)
    }

    override fun onResume() {
        super.onResume()
        IncomingCalls.attach(this) // someone calling shows up over whatever you're doing
        Events.openChannelId = channelId
        Events.clearChannelMessageNotification(channelId)
        Mentions.clear(Mentions.channelKey(channelId))
    }

    override fun onPause() {
        super.onPause()
        IncomingCalls.detach(this)
        Events.openChannelId = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Events.removeListener(this)
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                myId = ApiClient.me().id
                refreshMembership()
                val msgs = ApiClient.channelConversation(channelId)
                renderer.clear()
                msgs.forEach { render(it) }
                renderer.scrollToBottom()
                msgs.lastOrNull()?.let { runCatching { ApiClient.markRead("channel", channelId, it.id) } }
            } catch (e: Exception) {
                ServerDialogs.toast(this@ChannelChatActivity, e.message ?: "Couldn't load the channel")
            }
        }
    }

    private suspend fun refreshMembership() {
        members = ApiClient.serverMembers(serverId).associateBy { it.user.id }
        isAdmin = members[myId]?.role == "admin"
    }

    // ---- sending ----

    private fun sendText() {
        // Keep the first line's indentation (code, lists); drop only blank lines
        // before it and whitespace at the end. Same as the web composer.
        val body = binding.input.text.toString().replace(Regex("^\\s*\\n"), "").trimEnd()
        if (body.isBlank()) return
        binding.input.setText("")
        val replyTo = replyingTo
        cancelReply()
        lifecycleScope.launch {
            runCatching { ApiClient.sendChannelText(channelId, body, replyTo) }
                .onSuccess { render(it); renderer.scrollToBottom() }
                .onFailure { toast("Couldn't send: " + (it.message ?: "failed")) }
        }
    }

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
                if (bytes.size > Attachments.MAX_BYTES) {
                    toast("That file is too big — the limit is " + Attachments.formatSize(Attachments.MAX_BYTES) + ".")
                    return@launch
                }
                val name = Attachments.displayName(this@ChannelChatActivity, uri)
                render(ApiClient.sendChannelAttachment(channelId, name, bytes, "")); renderer.scrollToBottom()
            } catch (e: Exception) {
                toast("Couldn't send that file: " + (e.message ?: "upload failed"))
            }
        }
    }

    private fun deleteMessage(m: RMsg) {
        val removingOthers = !m.mine
        val go = {
            lifecycleScope.launch {
                runCatching { ApiClient.deleteMessage(m.id, "channel") }
                    .onSuccess { renderer.markDeleted(m.id) }
                    .onFailure { toast("Couldn't delete: " + (it.message ?: "failed")) }
            }
        }
        if (!removingOthers) { go(); return }
        AlertDialog.Builder(this)
            .setTitle("Delete ${m.senderNick}'s message?")
            .setMessage("You're an admin here, so you can remove it for everyone.")
            .setPositiveButton("Delete") { _, _ -> go() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editMessage(m: RMsg) {
        val input = android.widget.EditText(this).apply { setText(m.body); setSelection(m.body.length) }
        AlertDialog.Builder(this)
            .setTitle("Edit message")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val body = input.text.toString().trim()
                if (body.isEmpty() || body == m.body) return@setPositiveButton
                lifecycleScope.launch {
                    runCatching { ApiClient.editChannelMessage(m.id, body) }
                        .onSuccess { renderer.updateMessage(it.id, it.body, it.editedAt) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun react(messageId: Long, emoji: String, add: Boolean) = lifecycleScope.launch {
        runCatching { ApiClient.react("channel", messageId, emoji, add) }
    }

    private fun startReply(id: Long, nick: String, body: String) {
        replyingTo = id
        binding.replyBarText.text = "Replying to $nick: ${body.take(50).ifEmpty { "attachment" }}"
        binding.replyBar.visibility = View.VISIBLE
    }

    private fun cancelReply() {
        replyingTo = 0
        binding.replyBar.visibility = View.GONE
    }

    private fun openAttachment(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { toast("No app on this device can open that.") }
    }

    private fun showProfile(nick: String) {
        if (nick.equals(ApiClient.myNick, ignoreCase = true)) {
            ProfileCard.show(this, nick, "This is you.")
            return
        }
        val m = members.values.firstOrNull { it.user.nick.equals(nick, ignoreCase = true) }
        val status = "In this server" + if (m?.role == "admin") " · admin" else ""
        lifecycleScope.launch {
            val friend = runCatching { ApiClient.friends() }.getOrDefault(emptyList())
                .firstOrNull { it.status == "accepted" && it.user.nick.equals(nick, ignoreCase = true) }
            ProfileCard.show(this@ChannelChatActivity, nick, status,
                onMessage = friend?.let { f -> {
                    startActivity(Intent(this@ChannelChatActivity, ChatActivity::class.java)
                        .putExtra(ChatActivity.EXTRA_FRIEND_ID, f.user.id)
                        .putExtra(ChatActivity.EXTRA_FRIEND_NICK, f.user.nick))
                } })
        }
    }

    private fun toast(msg: String) = ServerDialogs.toast(this, msg)

    // ---- rendering ----

    private fun nickOf(id: Long) = members[id]?.user?.nick

    private fun render(m: ChannelMessage) {
        renderer.add(
            RMsg(
                id = m.id, senderId = m.senderId,
                senderNick = nickOf(m.senderId) ?: "someone",
                senderAvatar = members[m.senderId]?.user?.avatarUrl ?: "",
                body = m.body, imageUrl = m.imageUrl, thumbUrl = m.thumbUrl,
                attachment = m.attachment, created = m.created, deletedAt = m.deletedAt,
                reactions = m.reactions,
                replyTo = m.replyTo,
                replyNick = if (m.replySender == myId) "you" else (nickOf(m.replySender) ?: "someone"),
                replyBody = m.replyBody,
                mine = m.senderId == myId, callCode = null,
                editedAt = m.editedAt,
            )
        )
    }

    // ---- live events (OkHttp thread) ----

    override fun onChannelMessage(message: ChannelMessage) = runOnUiThread {
        if (message.channelId != channelId || renderer.has(message.id)) return@runOnUiThread
        lifecycleScope.launch {
            // Someone who joined after we loaded the member list needs a name.
            if (message.senderId !in members) runCatching { refreshMembership() }
            render(message); renderer.scrollToBottom()
            runCatching { ApiClient.markRead("channel", channelId, message.id) }
        }
    }

    override fun onChannelMessageEdited(message: ChannelMessage) = runOnUiThread {
        if (message.channelId == channelId && renderer.has(message.id)) {
            renderer.updateMessage(message.id, message.body, message.editedAt)
        }
    }

    override fun onMessageDeleted(scope: String, messageId: Long, containerId: Long) = runOnUiThread {
        if (scope == "channel" && containerId == channelId) renderer.markDeleted(messageId)
    }

    override fun onReaction(scope: String, messageId: Long, reactions: List<com.takeback.app.net.Reaction>) =
        runOnUiThread { if (scope == "channel" && renderer.has(messageId)) renderer.updateReactions(messageId, reactions) }

    override fun onServerUpdate(serverId: Long, deleted: Boolean) = runOnUiThread {
        if (serverId != this.serverId) return@runOnUiThread
        if (deleted) { toast("This server was deleted."); finish(); return@runOnUiThread }
        lifecycleScope.launch {
            val stillHere = runCatching { ApiClient.channels(serverId).any { it.id == channelId } }
                .getOrElse { false }
            if (!stillHere) { toast("That channel is gone, or you're no longer in the server."); finish() }
            else runCatching { refreshMembership() }
        }
    }
}
