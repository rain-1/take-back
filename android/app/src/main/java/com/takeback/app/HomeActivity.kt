package com.takeback.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.takeback.app.databinding.ActivityHomeBinding
import com.takeback.app.net.ApiClient
import com.takeback.app.net.Mentions
import com.takeback.app.net.Events
import com.takeback.app.net.EventsListener
import com.takeback.app.net.Friend
import com.takeback.app.net.Group
import com.takeback.app.net.GroupInvite
import com.takeback.app.net.Server
import kotlinx.coroutines.launch

/**
 * HomeActivity shows the signed-in user's friends with live presence, incoming
 * friend requests, and an add-friend box. Tapping a friend opens their chat.
 */
class HomeActivity : AppCompatActivity(), EventsListener {

    private lateinit var binding: ActivityHomeBinding
    private var friends: List<Friend> = emptyList()
    private var groups: List<Group> = emptyList()
    private var invites: List<GroupInvite> = emptyList()
    private var servers: List<Server> = emptyList()

    private var onImagePicked: ((android.net.Uri) -> Unit)? = null
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onImagePicked?.invoke(it) }
    }
    private val picker = ServerDialogs.ImagePicker { cb -> onImagePicked = cb; pickImage.launch("image/*") }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort; ignored if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotifPermission()

        binding.addFriendBtn.setOnClickListener { addFriend() }
        binding.newGroupBtn.setOnClickListener { createGroup() }
        binding.settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.newServerBtn.setOnClickListener {
            ServerDialogs.create(this, picker) { sv -> refresh(); ServerDialogs.openServer(this, sv) }
        }
        binding.joinServerBtn.setOnClickListener { ServerDialogs.join(this) { sv -> refresh(); ServerDialogs.openServer(this, sv) } }
        setupCollapsible()

        Mentions.init(this)
        BackgroundNotifications.configure(this)
        Events.addListener(this)
        // Come back to where you were, like the web client does on reload.
        LastChat.reopen(this)
    }

    override fun onResume() {
        super.onResume()
        IncomingCalls.attach(this) // someone calling shows up over whatever you're doing
        Events.openFriendId = null
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        Events.removeListener(this)
    }

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refresh() {
        lifecycleScope.launch {
            try {
                val me = ApiClient.me()
                binding.meNick.text = me.nick
                binding.meAvatar.removeAllViews()
                binding.meAvatar.addView(Avatars.view(this@HomeActivity, me.nick, me.avatarUrl, 30))
                friends = ApiClient.friends()
                groups = ApiClient.groups()
                invites = runCatching { ApiClient.groupInvites() }.getOrDefault(emptyList())
                servers = runCatching { ApiClient.servers() }.getOrDefault(servers)
                servers.forEach { Events.serverNames[it.id] = it.name }
                friends.forEach { Events.knownNicks[it.user.id] = it.user.nick }
                groups.forEach { Events.groupNames[it.id] = it.name }
                render()
                renderGroups()
                renderServers()
            } catch (_: Exception) { /* transient */ }
        }
    }

    /** New group: the same dialog shape as creating a server. */
    private fun createGroup() {
        val input = tbInput(this).apply { hint = "e.g. weekend plans" }
        AlertDialog.Builder(this)
            .setTitle("New group")
            .setView(pad(input))
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    runCatching { ApiClient.createGroup(name) }
                        .onSuccess { g -> refresh(); openGroup(g) }
                        .onFailure { toast(it.message ?: "Couldn't create the group") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A dialog's field wants breathing room. */
    private fun pad(view: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val d = resources.displayMetrics.density
        setPadding((22 * d).toInt(), (8 * d).toInt(), (22 * d).toInt(), 0)
        addView(view)
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    private fun renderGroups() {
        binding.groups.removeAllViews()
        if (groups.isEmpty()) {
            binding.groups.addView(TextView(this).apply {
                text = "No groups yet — start one with ＋."
                setTextColor(tbColor(R.color.tb_dim))
                setPadding(16, 12, 16, 12)
            })
            return
        }
        val ordered = groups.sortedWith(
            compareByDescending<Group> { it.lastActivity }.thenBy { it.name.lowercase() }
        )
        for (g in ordered) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 22, 16, 22)
                isClickable = true
                setOnClickListener { openGroup(g) }
            }
            row.addView(Icons.view(this, R.drawable.ic_text_channel, 18, R.color.tb_muted))
            row.addView(TextView(this).apply {
                text = g.name
                setTextColor(tbColor(R.color.tb_text))
                textSize = 16f
                if (g.unread > 0) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = 20 }
            })
            if (g.unread > 0) row.addView(pip(g.unread, Mentions.has(Mentions.groupKey(g.id)))) else row.addView(TextView(this).apply {
                text = g.memberCount.toString()
                setTextColor(tbColor(R.color.tb_dim)); textSize = 13f
            })
            binding.groups.addView(row)
        }
    }

    // ---- servers ----

    private fun renderServers() {
        binding.servers.removeAllViews()
        if (servers.isEmpty()) {
            binding.servers.addView(TextView(this).apply {
                text = "No servers yet — create one, or join with an invite."
                setTextColor(tbColor(R.color.tb_dim))
                setPadding(16, 12, 16, 12)
            })
            return
        }
        for (sv in servers) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 16, 16, 16)
                isClickable = true
                setOnClickListener { ServerDialogs.openServer(this@HomeActivity, sv) }
            }
            row.addView(ServerDialogs.iconView(this, sv, 36))
            row.addView(TextView(this).apply {
                text = sv.name
                setTextColor(tbColor(R.color.tb_text))
                textSize = 16f
                if (sv.unread > 0) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = 24 }
            })
            // Someone is in one of its voice channels: say so without opening it.
            if (sv.voiceCount > 0) row.addView(Icons.view(this, R.drawable.ic_voice_channel, 16, R.color.tb_online, endMarginDp = 8).apply {
                contentDescription = if (sv.voiceCount == 1) "1 person in voice" else "${sv.voiceCount} people in voice"
            })
            if (sv.unread > 0) row.addView(pip(sv.unread, Mentions.serverMentioned(sv.id)))
            binding.servers.addView(row)
        }
    }

    // ---- collapsible sections ----

    private fun setupCollapsible() {
        val prefs = getSharedPreferences("tb_home", MODE_PRIVATE)
        fun wire(key: String, title: TextView, label: String, vararg bodies: View) {
            fun apply(collapsed: Boolean) {
                title.text = (if (collapsed) "▸ " else "▾ ") + label
                bodies.forEach { it.visibility = if (collapsed) View.GONE else View.VISIBLE }
            }
            apply(prefs.getBoolean(key, false))
            title.setOnClickListener {
                val collapsed = !prefs.getBoolean(key, false)
                prefs.edit().putBoolean(key, collapsed).apply()
                apply(collapsed)
            }
        }
        wire("collapsed.servers", binding.serversTitle, "SERVERS", binding.servers)
        wire("collapsed.groups", binding.groupsTitle, "GROUPS", binding.groups)
        wire("collapsed.friends", binding.friendsTitle, "FRIENDS", binding.friends)
    }

    private fun openGroup(g: Group) {
        startActivity(Intent(this, GroupChatActivity::class.java).apply {
            putExtra(GroupChatActivity.EXTRA_GROUP_ID, g.id)
            putExtra(GroupChatActivity.EXTRA_GROUP_NAME, g.name)
            putExtra(GroupChatActivity.EXTRA_CALL_CODE, g.callCode)
        })
    }

    private fun render() {
        val requests = friends.filter { it.status == "pending" && it.direction == "incoming" }
        // Newest conversation first; never-messaged friends fall to the bottom
        // alphabetically. Not sorted by unread/presence — those shift as you tap
        // or as people come online, making rows jump.
        val accepted = friends.filter { it.status == "accepted" }
            .sortedWith(
                compareByDescending<Friend> { it.lastActivity }.thenBy { it.user.nick.lowercase() }
            )

        binding.requestsHeader.visibility =
            if (requests.isEmpty() && invites.isEmpty()) View.GONE else View.VISIBLE
        binding.requests.removeAllViews()
        // Group invites first — being pulled into a group is the more surprising ask.
        for (i in invites) binding.requests.addView(inviteRow(i))
        for (f in requests) binding.requests.addView(requestRow(f))

        binding.friends.removeAllViews()
        if (accepted.isEmpty()) {
            binding.friends.addView(TextView(this).apply {
                text = "No friends yet — add someone with ＋."
                setTextColor(tbColor(R.color.tb_dim))
                setPadding(16, 24, 16, 16)
            })
        } else {
            for (f in accepted) binding.friends.addView(friendRow(f))
        }
    }

    private fun friendRow(f: Friend): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 24, 16, 24)
            isClickable = true
            setOnClickListener { openChat(f) }
        }
        row.addView(Avatars.view(this, f.user.nick, f.user.avatarUrl, 36, endMarginDp = 6))
        row.addView(dot(f.online))
        row.addView(TextView(this).apply {
            text = f.user.nick
            setTextColor(tbColor(R.color.tb_text))
            textSize = 16f
            if (f.unread > 0) setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = 16 }
        })
        if (f.unread > 0) row.addView(pip(f.unread, Mentions.has(Mentions.dmKey(f.user.id)))) else row.addView(android.widget.ImageButton(this, null, 0, R.style.HeaderIcon).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = "Remove ${f.user.nick}"
            setOnClickListener { remove(f) }
        })
        return row
    }

    /** A pending group invite: join or decline, like a friend request. */
    private fun inviteRow(i: GroupInvite): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
        }
        row.addView(TextView(this).apply {
            text = "${i.groupName}\ninvited by ${i.invitedBy}"
            setTextColor(tbColor(R.color.tb_text)); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        row.addView(Button(this).apply {
            text = "Join"
            setOnClickListener { respondInvite(i, true) }
        })
        row.addView(Button(this).apply {
            text = getString(R.string.decline)
            setOnClickListener { respondInvite(i, false) }
        })
        return row
    }

    private fun respondInvite(i: GroupInvite, accept: Boolean) = lifecycleScope.launch {
        runCatching { ApiClient.respondGroupInvite(i.groupId, accept) }
        refresh()
    }

    private fun requestRow(f: Friend): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
        }
        row.addView(TextView(this).apply {
            text = f.user.nick
            setTextColor(tbColor(R.color.tb_text))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        row.addView(Button(this).apply {
            text = getString(R.string.accept)
            setOnClickListener { respond(f, true) }
        })
        row.addView(Button(this).apply {
            text = getString(R.string.decline)
            setOnClickListener { respond(f, false) }
        })
        return row
    }

    /**
     * pip is the unread badge: an accent pill with the count (99+ capped). It's
     * RED when someone mentioned you in that conversation — "you're being asked
     * for" is a different signal from "there's something new". Same as web.
     */
    private fun pip(n: Int, mentioned: Boolean = false): View = TextView(this).apply {
        text = if (n > 99) "99+" else n.toString()
        setTextColor(Color.WHITE)
        textSize = 11f
        setPadding(14, 4, 14, 4)
        if (mentioned) contentDescription = "$n unread, you were mentioned"
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 999f
            setColor(tbColor(if (mentioned) R.color.tb_danger else R.color.tb_accent))
        }
    }

    private fun dot(online: Boolean): View = View(this).apply {
        val size = (10 * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
        setBackgroundColor(if (online) tbColor(R.color.tb_online) else tbColor(R.color.tb_border))
    }

    /** Add a friend: a dialog now, rather than a box wedged above the list. */
    private fun addFriend() {
        val input = tbInput(this).apply { hint = "their nickname" }
        AlertDialog.Builder(this)
            .setTitle("Add a friend")
            .setMessage("They'll get a request to accept before you can message each other.")
            .setView(pad(input))
            .setPositiveButton("Send request") { _, _ ->
                val nick = input.text.toString().trim()
                if (nick.isEmpty()) return@setPositiveButton
                lifecycleScope.launch {
                    runCatching { ApiClient.sendFriendRequest(nick) }
                        .onSuccess { toast("Request sent to $nick"); refresh() }
                        .onFailure { toast(it.message ?: "Couldn't send that request") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun respond(f: Friend, accept: Boolean) = lifecycleScope.launch {
        runCatching { ApiClient.respondFriend(f.user.id, accept) }
        refresh()
    }

    /**
     * Unfriending is destructive and mutual, so confirm first — the ✕ sits where
     * a "dismiss" control would, and must not silently delete a friendship.
     */
    private fun remove(f: Friend) {
        AlertDialog.Builder(this)
            .setTitle("Remove ${f.user.nick}?")
            .setMessage(
                "You'll each disappear from the other's friends list and won't be " +
                    "able to message until you're friends again. Your history is kept."
            )
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    runCatching { ApiClient.removeFriend(f.user.id) }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openChat(f: Friend) {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_FRIEND_ID, f.user.id)
            putExtra(ChatActivity.EXTRA_FRIEND_NICK, f.user.nick)
        })
    }



    // ---- live events ----

    override fun onPresence(userId: Long, online: Boolean) = runOnUiThread {
        friends = friends.map { if (it.user.id == userId) it.copy(online = online) else it }
        render()
    }

    override fun onHello(onlineFriendIds: List<Long>) = runOnUiThread {
        friends = friends.map { it.copy(online = onlineFriendIds.contains(it.user.id)) }
        render()
    }

    override fun onFriendRequest(fromId: Long, fromNick: String) = runOnUiThread { refresh() }

    override fun onFriendUpdate() = runOnUiThread { refresh() }

    // A mention just arrived: re-fetch so the count AND the red pip update now,
    // rather than on the next resume.
    override fun onMentionsChanged() = runOnUiThread { refresh() }

    override fun onGroupUpdate(groupId: Long) = runOnUiThread { refresh() }

    override fun onGroupInvite(groupId: Long, groupName: String, invitedBy: String) =
        runOnUiThread { refresh() }

    override fun onChannelMessage(message: com.takeback.app.net.ChannelMessage) = runOnUiThread {
        if (message.senderId == ApiClient.myId) return@runOnUiThread
        servers = servers.map { if (it.id == message.serverId) it.copy(unread = it.unread + 1) else it }
        renderServers()
    }

    override fun onServerUpdate(serverId: Long, deleted: Boolean) = runOnUiThread { refresh() }

    /** Someone joined or left a voice channel: update that server's speaker. */
    override fun onServerActivity(activity: com.takeback.app.net.ServerActivity) = runOnUiThread {
        val inVoice = activity.voice.values.sumOf { it.size }
        servers = servers.map { if (it.id == activity.serverId) it.copy(voiceCount = inVoice) else it }
        renderServers()
    }

    override fun onPause() {
        super.onPause()
        IncomingCalls.detach(this)
    }
}
