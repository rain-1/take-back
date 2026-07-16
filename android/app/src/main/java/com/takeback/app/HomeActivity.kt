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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.takeback.app.databinding.ActivityHomeBinding
import com.takeback.app.net.ApiClient
import com.takeback.app.net.Events
import com.takeback.app.net.EventsListener
import com.takeback.app.net.Friend
import com.takeback.app.net.Group
import kotlinx.coroutines.launch

/**
 * HomeActivity shows the signed-in user's friends with live presence, incoming
 * friend requests, and an add-friend box. Tapping a friend opens their chat.
 */
class HomeActivity : AppCompatActivity(), EventsListener {

    private lateinit var binding: ActivityHomeBinding
    private var friends: List<Friend> = emptyList()
    private var groups: List<Group> = emptyList()

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
                setTextColor(Color.parseColor("#5B6273"))
                setPadding(16, 12, 16, 12)
            })
            return
        }
        for (g in groups) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 22, 16, 22)
                isClickable = true
                setOnClickListener { openGroup(g) }
            }
            row.addView(TextView(this).apply {
                text = "#"; setTextColor(Color.parseColor("#8B93A7")); textSize = 16f
            })
            row.addView(TextView(this).apply {
                text = g.name
                setTextColor(Color.parseColor("#E7E9EE"))
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = 20 }
            })
            row.addView(TextView(this).apply {
                text = g.memberCount.toString()
                setTextColor(Color.parseColor("#5B6273")); textSize = 13f
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
        val accepted = friends.filter { it.status == "accepted" }
            .sortedWith(compareByDescending<Friend> { it.online }.thenBy { it.user.nick.lowercase() })

        binding.requestsHeader.visibility = if (requests.isEmpty()) View.GONE else View.VISIBLE
        binding.requests.removeAllViews()
        for (f in requests) binding.requests.addView(requestRow(f))

        binding.friends.removeAllViews()
        if (accepted.isEmpty()) {
            binding.friends.addView(TextView(this).apply {
                text = getString(R.string.no_friends)
                setTextColor(Color.parseColor("#5B6273"))
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
        row.addView(dot(f.online))
        row.addView(TextView(this).apply {
            text = f.user.nick
            setTextColor(Color.parseColor("#E7E9EE"))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = 24 }
        })
        row.addView(Button(this).apply {
            text = "✕"
            setOnClickListener { remove(f) }
        })
        return row
    }

    private fun requestRow(f: Friend): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 12)
        }
        row.addView(TextView(this).apply {
            text = f.user.nick
            setTextColor(Color.parseColor("#E7E9EE"))
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

    private fun dot(online: Boolean): View = View(this).apply {
        val size = (10 * resources.displayMetrics.density).toInt()
        layoutParams = LinearLayout.LayoutParams(size, size)
        setBackgroundColor(if (online) Color.parseColor("#22C55E") else Color.parseColor("#3F4553"))
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

    private fun remove(f: Friend) = lifecycleScope.launch {
        runCatching { ApiClient.removeFriend(f.user.id) }
        refresh()
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
}
