package com.takeback.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import com.takeback.app.databinding.ActivityGroupChatBinding
import com.takeback.app.net.ApiClient
import com.takeback.app.net.Events
import com.takeback.app.net.EventsListener
import com.takeback.app.net.GroupMember
import com.takeback.app.net.GroupMessage
import io.noties.markwon.Markwon
import kotlinx.coroutines.launch

/**
 * GroupChatActivity is a multi-user group conversation: member list with live
 * presence, Markdown + image messages labelled by sender, add-member, and a
 * group video call that joins the group's shared call code.
 */
class GroupChatActivity : AppCompatActivity(), EventsListener {

    companion object {
        const val EXTRA_GROUP_ID = "groupId"
        const val EXTRA_GROUP_NAME = "groupName"
        const val EXTRA_CALL_CODE = "callCode"
        private val CALL_RE = Regex("^📞 call:([A-Z0-9]{4,8})$")
    }

    private lateinit var binding: ActivityGroupChatBinding
    private lateinit var renderer: MessageRenderer
    private var groupId: Long = 0
    private lateinit var groupName: String
    private lateinit var callCode: String
    private var myId: Long = 0
    private var members: List<GroupMember> = emptyList()
    private var replyingTo: GroupMessage? = null

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { uploadImage(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        groupId = intent.getLongExtra(EXTRA_GROUP_ID, 0)
        groupName = intent.getStringExtra(EXTRA_GROUP_NAME) ?: "group"
        callCode = intent.getStringExtra(EXTRA_CALL_CODE) ?: ""
        binding.groupName.text = "# $groupName"

        renderer = MessageRenderer(
            this, binding.messages, binding.scroll, Markwon.create(this),
            onReply = { startReply(it.id, it.senderNick, it.body) },
            onReact = { id, emoji, add -> react(id, emoji, add) },
            onJoinCall = { joinCall(it) },
            onOpenImage = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) },
            onEdit = { editMessage(it) },
        )

        binding.sendBtn.setOnClickListener { sendText() }
        binding.imgBtn.setOnClickListener { pickImage.launch("image/*") }
        binding.callBtn.setOnClickListener { startCall() }
        binding.replyCancel.setOnClickListener { cancelReply() }
        binding.addMemberBtn.setOnClickListener { promptAddMember() }

        Events.addListener(this)
        load()
    }

    override fun onResume() {
        super.onResume()
        Events.openGroupId = groupId
        Events.clearGroupMessageNotification(groupId) // viewing it dismisses its notification
    }

    override fun onPause() {
        super.onPause()
        Events.openGroupId = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Events.removeListener(this)
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                myId = ApiClient.me().id
                members = ApiClient.groupMembers(groupId)
                renderMembers()
                val msgs = ApiClient.groupConversation(groupId)
                renderer.clear()
                msgs.forEach { render(it) }
                renderer.scrollToBottom()
                // Viewing the conversation clears its unread count on the server.
                msgs.lastOrNull()?.let { runCatching { ApiClient.markRead("group", groupId, it.id) } }
            } catch (_: Exception) { /* transient */ }
        }
    }

    private fun renderMembers() {
        binding.members.removeAllViews()
        for (m in members) {
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 8, 16, 8)
                background = GradientDrawable().apply {
                    cornerRadius = 999f; setColor(Color.parseColor("#171B24"))
                }
                val lp = LinearLayout.LayoutParams(-2, -2); lp.marginEnd = 12; layoutParams = lp
            }
            chip.addView(Avatars.view(this, m.nick, m.avatarUrl, 20, endMarginDp = 6))
            val size = (8 * resources.displayMetrics.density).toInt()
            chip.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginStart = 6; it.marginEnd = 8 }
                setBackgroundColor(if (m.online) Color.parseColor("#34D399") else Color.parseColor("#39404F"))
            })
            chip.addView(TextView(this).apply {
                text = m.nick + if (m.owner) " ★" else ""
                setTextColor(Color.parseColor("#E8EAF0")); textSize = 13f
            })
            binding.members.addView(chip)
        }
    }

    private fun nickOf(userId: Long): String? = members.firstOrNull { it.id == userId }?.nick

    private fun promptAddMember() {
        val input = EditText(this).apply { hint = "nickname" }
        AlertDialog.Builder(this)
            .setTitle("Invite to group")
            .setView(input)
            .setPositiveButton("Invite") { _, _ ->
                val nick = input.text.toString().trim()
                if (nick.isNotEmpty()) lifecycleScope.launch {
                    runCatching { ApiClient.inviteGroupMember(groupId, nick) }
                        .onSuccess {
                            // They won't appear until they accept — say so, or it
                            // looks like nothing happened.
                            toast("Invited $nick — they'll join once they accept")
                        }
                        .onFailure { toast(it.message ?: "Couldn't invite") }
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
            runCatching { ApiClient.sendGroupText(groupId, body, replyTo) }
                .onSuccess { render(it); renderer.scrollToBottom() }
        }
    }

    private fun uploadImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                val name = (uri.lastPathSegment ?: "image").substringAfterLast('/')
                render(ApiClient.sendGroupImage(groupId, name, bytes, "")); renderer.scrollToBottom()
            } catch (_: Exception) { /* ignore */ }
        }
    }

    private fun startCall() {
        val code = callCode.ifEmpty { return }
        lifecycleScope.launch {
            runCatching { ApiClient.sendGroupText(groupId, "📞 call:$code") }
                .onSuccess { render(it); renderer.scrollToBottom() }
        }
        joinCall(code)
    }

    private fun joinCall(code: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROOM, code))
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    // ---- rendering ----

    private fun avatarOf(userId: Long) = members.firstOrNull { it.id == userId }?.avatarUrl ?: ""

    /** Convert a group message into the renderer's normalized form. */
    private fun render(m: GroupMessage) {
        val call = CALL_RE.find(m.body)?.groupValues?.get(1)
        renderer.add(
            RMsg(
                id = m.id, senderId = m.senderId,
                senderNick = if (m.senderId == myId) (nickOf(myId) ?: "you") else (nickOf(m.senderId) ?: "someone"),
                senderAvatar = avatarOf(m.senderId),
                body = m.body, imageUrl = m.imageUrl, thumbUrl = m.thumbUrl, created = m.created,
                reactions = m.reactions,
                replyTo = m.replyTo,
                replyNick = if (m.replySender == myId) "you" else (nickOf(m.replySender) ?: "someone"),
                replyBody = m.replyBody,
                mine = m.senderId == myId, callCode = call,
                editedAt = m.editedAt,
            )
        )
    }

    /** Prompt to edit one of my own group messages, then push the change. */
    private fun editMessage(m: RMsg) {
        val input = android.widget.EditText(this).apply { setText(m.body); setSelection(m.body.length) }
        AlertDialog.Builder(this)
            .setTitle("Edit message")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val body = input.text.toString().trim()
                if (body.isEmpty() || body == m.body) return@setPositiveButton
                lifecycleScope.launch {
                    runCatching { ApiClient.editGroupMessage(m.id, body) }
                        .onSuccess { renderer.updateMessage(it.id, it.body, it.editedAt) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun react(messageId: Long, emoji: String, add: Boolean) = lifecycleScope.launch {
        runCatching { ApiClient.react("group", messageId, emoji, add) }
    }

    private fun startReply(id: Long, nick: String, body: String) {
        replyingTo = GroupMessage(id, groupId, 0, body, null, null, 0)
        binding.replyBarText.text = "Replying to $nick: ${body.take(50).ifEmpty { "image" }}"
        binding.replyBar.visibility = View.VISIBLE
    }

    private fun cancelReply() {
        replyingTo = null
        binding.replyBar.visibility = View.GONE
    }


    override fun onReaction(scope: String, messageId: Long, reactions: List<com.takeback.app.net.Reaction>) =
        runOnUiThread { if (scope == "group") renderer.updateReactions(messageId, reactions) }

    // ---- live events ----

    override fun onGroupMessage(message: GroupMessage) = runOnUiThread {
        if (message.groupId == groupId) {
            render(message); renderer.scrollToBottom()
            lifecycleScope.launch { runCatching { ApiClient.markRead("group", groupId, message.id) } }
        }
    }

    override fun onGroupMessageEdited(message: GroupMessage) = runOnUiThread {
        if (message.groupId == groupId && renderer.has(message.id)) {
            renderer.updateMessage(message.id, message.body, message.editedAt)
        }
    }

    override fun onGroupUpdate(gid: Long) = runOnUiThread {
        if (gid == groupId) lifecycleScope.launch {
            members = runCatching { ApiClient.groupMembers(groupId) }.getOrDefault(members)
            renderMembers()
        }
    }

    override fun onPresence(userId: Long, online: Boolean) = runOnUiThread {
        members = members.map { if (it.id == userId) it.copy(online = online) else it }
        renderMembers()
    }
}
