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
    private lateinit var markwon: Markwon
    private var groupId: Long = 0
    private lateinit var groupName: String
    private lateinit var callCode: String
    private var myId: Long = 0
    private var members: List<GroupMember> = emptyList()

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { uploadImage(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupChatBinding.inflate(layoutInflater)
        setContentView(binding.root)
        markwon = Markwon.create(this)

        groupId = intent.getLongExtra(EXTRA_GROUP_ID, 0)
        groupName = intent.getStringExtra(EXTRA_GROUP_NAME) ?: "group"
        callCode = intent.getStringExtra(EXTRA_CALL_CODE) ?: ""
        binding.groupName.text = "# $groupName"

        binding.sendBtn.setOnClickListener { sendText() }
        binding.imgBtn.setOnClickListener { pickImage.launch("image/*") }
        binding.callBtn.setOnClickListener { startCall() }
        binding.addMemberBtn.setOnClickListener { promptAddMember() }

        Events.addListener(this)
        load()
    }

    override fun onResume() {
        super.onResume()
        Events.openGroupId = groupId
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
                binding.messages.removeAllViews()
                msgs.forEach { addMessage(it) }
                scrollToBottom()
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
                    cornerRadius = 999f; setColor(Color.parseColor("#1C2029"))
                }
                val lp = LinearLayout.LayoutParams(-2, -2); lp.marginEnd = 12; layoutParams = lp
            }
            val size = (9 * resources.displayMetrics.density).toInt()
            chip.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginEnd = 10 }
                setBackgroundColor(if (m.online) Color.parseColor("#22C55E") else Color.parseColor("#3F4553"))
            })
            chip.addView(TextView(this).apply {
                text = m.nick + if (m.owner) " ★" else ""
                setTextColor(Color.parseColor("#E7E9EE")); textSize = 13f
            })
            binding.members.addView(chip)
        }
    }

    private fun nickOf(userId: Long): String? = members.firstOrNull { it.id == userId }?.nick

    private fun promptAddMember() {
        val input = EditText(this).apply { hint = "nickname" }
        AlertDialog.Builder(this)
            .setTitle("Add member")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val nick = input.text.toString().trim()
                if (nick.isNotEmpty()) lifecycleScope.launch {
                    runCatching { ApiClient.addGroupMember(groupId, nick) }
                    members = runCatching { ApiClient.groupMembers(groupId) }.getOrDefault(members)
                    renderMembers()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendText() {
        val body = binding.input.text.toString().trim()
        if (body.isEmpty()) return
        binding.input.setText("")
        lifecycleScope.launch {
            runCatching { ApiClient.sendGroupText(groupId, body) }
                .onSuccess { addMessage(it); scrollToBottom() }
        }
    }

    private fun uploadImage(uri: Uri) {
        lifecycleScope.launch {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                val name = (uri.lastPathSegment ?: "image").substringAfterLast('/')
                addMessage(ApiClient.sendGroupImage(groupId, name, bytes, "")); scrollToBottom()
            } catch (_: Exception) { /* ignore */ }
        }
    }

    private fun startCall() {
        val code = callCode.ifEmpty { return }
        lifecycleScope.launch {
            runCatching { ApiClient.sendGroupText(groupId, "📞 call:$code") }
                .onSuccess { addMessage(it); scrollToBottom() }
        }
        joinCall(code)
    }

    private fun joinCall(code: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROOM, code))
    }

    // ---- rendering ----

    private fun addMessage(m: GroupMessage) {
        val mine = m.senderId == myId
        val bubble = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
            background = GradientDrawable().apply {
                cornerRadius = 28f
                setColor(Color.parseColor(if (mine) "#274690" else "#1C2029"))
            }
        }
        // Label messages from others with the sender's nick.
        if (!mine) nickOf(m.senderId)?.let {
            bubble.addView(TextView(this).apply {
                text = it; setTextColor(Color.parseColor("#8B93A7")); textSize = 11f
            })
        }

        val call = CALL_RE.find(m.body)
        if (call != null) {
            val code = call.groupValues[1]
            bubble.addView(TextView(this).apply { text = "📞 Video call"; setTextColor(Color.WHITE) })
            bubble.addView(Button(this).apply {
                text = "Join call $code"; setOnClickListener { joinCall(code) }
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
                    setOnClickListener { m.imageUrl?.let { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } }
                    val lp = LinearLayout.LayoutParams(-2, -2); lp.topMargin = 8; layoutParams = lp
                })
            }
        }

        binding.messages.addView(LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.topMargin = 8 }
            gravity = if (mine) Gravity.END else Gravity.START
            addView(bubble)
        })
    }

    private fun scrollToBottom() = binding.scroll.post { binding.scroll.fullScroll(View.FOCUS_DOWN) }

    // ---- live events ----

    override fun onGroupMessage(message: GroupMessage) = runOnUiThread {
        if (message.groupId == groupId) { addMessage(message); scrollToBottom() }
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
