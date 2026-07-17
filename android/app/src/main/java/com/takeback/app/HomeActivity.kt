package com.takeback.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
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
import com.takeback.app.net.Events
import com.takeback.app.net.EventsListener
import com.takeback.app.net.Friend
import com.takeback.app.net.Group
import com.takeback.app.net.GroupInvite
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

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best-effort; ignored if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotifPermission()

        binding.addBtn.setOnClickListener { addFriend() }
        binding.newGroupBtn.setOnClickListener { createGroup() }
        binding.logout.setOnClickListener { logout() }

        Events.addListener(this)
    }

    override fun onResume() {
        super.onResume()
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
                binding.meNick.text = "signed in as ${me.nick}"
                friends = ApiClient.friends()
                groups = ApiClient.groups()
                invites = runCatching { ApiClient.groupInvites() }.getOrDefault(emptyList())
                render()
                renderGroups()
            } catch (_: Exception) { /* transient */ }
        }
    }

    private fun createGroup() {
        val name = binding.newGroupName.text.toString().trim()
        if (name.isEmpty()) return
        lifecycleScope.launch {
            runCatching { ApiClient.createGroup(name) }.onSuccess { g ->
                binding.newGroupName.setText("")
                refresh()
                openGroup(g)
            }
        }
    }

    private fun renderGroups() {
        binding.groups.removeAllViews()
        if (groups.isEmpty()) {
            binding.groups.addView(TextView(this).apply {
                text = "No groups yet."
                setTextColor(Color.parseColor("#5A6273"))
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
            row.addView(TextView(this).apply {
                text = "#"; setTextColor(Color.parseColor("#8A93A6")); textSize = 16f
            })
            row.addView(TextView(this).apply {
                text = g.name
                setTextColor(Color.parseColor("#E8EAF0"))
                textSize = 16f
                if (g.unread > 0) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = 20 }
            })
            if (g.unread > 0) row.addView(pip(g.unread)) else row.addView(TextView(this).apply {
                text = g.memberCount.toString()
                setTextColor(Color.parseColor("#5A6273")); textSize = 13f
            })
            binding.groups.addView(row)
        }
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
                text = getString(R.string.no_friends)
                setTextColor(Color.parseColor("#5A6273"))
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
            setTextColor(Color.parseColor("#E8EAF0"))
            textSize = 16f
            if (f.unread > 0) setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = 16 }
        })
        if (f.unread > 0) row.addView(pip(f.unread)) else row.addView(Button(this).apply {
            text = "✕"
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
            text = "# ${i.groupName}\ninvited by ${i.invitedBy}"
            setTextColor(Color.parseColor("#E8EAF0")); textSize = 13f
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
            setTextColor(Color.parseColor("#E8EAF0"))
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

    /** pip is the unread badge: an accent pill with the count (99+ capped). */
    private fun pip(n: Int): View = TextView(this).apply {
        text = if (n > 99) "99+" else n.toString()
        setTextColor(Color.WHITE)
        textSize = 11f
        setPadding(14, 4, 14, 4)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 999f
            setColor(Color.parseColor("#5B8CFF"))
        }
    }

    private fun dot(online: Boolean): View = View(this).apply {
        val size = (10 * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
        setBackgroundColor(if (online) Color.parseColor("#34D399") else Color.parseColor("#39404F"))
    }

    private fun addFriend() {
        val nick = binding.addNick.text.toString().trim()
        if (nick.isEmpty()) return
        binding.addError.text = ""
        lifecycleScope.launch {
            try {
                ApiClient.sendFriendRequest(nick)
                binding.addNick.setText("")
                refresh()
            } catch (e: Exception) {
                binding.addError.text = e.message
            }
        }
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

    private fun logout() = lifecycleScope.launch {
        runCatching { ApiClient.logout() }
        Events.stop()
        startActivity(Intent(this@HomeActivity, LoginActivity::class.java))
        finish()
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

    override fun onGroupUpdate(groupId: Long) = runOnUiThread { refresh() }

    override fun onGroupInvite(groupId: Long, groupName: String, invitedBy: String) =
        runOnUiThread { refresh() }
}
