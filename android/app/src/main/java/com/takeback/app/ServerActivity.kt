package com.takeback.app

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.takeback.app.net.ApiClient
import com.takeback.app.net.Channel
import com.takeback.app.net.ChannelMessage
import com.takeback.app.net.Events
import com.takeback.app.net.EventsListener
import com.takeback.app.net.Mentions
import com.takeback.app.net.Server
import com.takeback.app.net.ServerActivity as Activity
import com.takeback.app.net.ServerMember
import kotlinx.coroutines.launch

/**
 * ServerActivity is one server: its text channels, its voice channels with
 * who's in each, and who's active (viewing the server or in its voice) versus
 * away. The phone's version of the web client's channel and member columns,
 * stacked into one scrolling screen.
 */
class ServerActivity : AppCompatActivity(), EventsListener {

    companion object {
        const val EXTRA_SERVER_ID = "serverId"
        const val EXTRA_SERVER_NAME = "serverName"
        private const val DIM = "#5A6273"
        private const val TEXT = "#E8EAF0"
        private const val MUTED = "#8A93A6"
    }

    private var serverId = 0L
    private var server: Server? = null
    private var channels: List<Channel> = emptyList()
    private var members: Map<Long, ServerMember> = emptyMap()
    private var activity: Activity? = null

    private lateinit var iconBox: LinearLayout
    private lateinit var title: TextView
    private lateinit var content: LinearLayout

    private var onImagePicked: ((Uri) -> Unit)? = null
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onImagePicked?.invoke(it) }
    }
    private val picker = ServerDialogs.ImagePicker { cb -> onImagePicked = cb; pickImage.launch("image/*") }

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverId = intent.getLongExtra(EXTRA_SERVER_ID, 0)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0D11"))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(8), dp(10))
            setBackgroundColor(Color.parseColor("#11141B"))
        }
        iconBox = LinearLayout(this)
        title = TextView(this).apply {
            text = intent.getStringExtra(EXTRA_SERVER_NAME) ?: "server"
            setTextColor(Color.parseColor(TEXT)); textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(10) }
        }
        header.addView(iconBox)
        header.addView(title)
        header.addView(headerAction("Invite", "Invite people") {
            server?.let { ServerDialogs.invite(this@ServerActivity, it) }
        })
        header.addView(headerAction("⚙", "Server menu") { openMenu() })
        root.addView(header)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(24))
        }
        root.addView(ScrollView(this).apply {
            addView(content)
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        })
        setContentView(root)

        Events.addListener(this)
    }

    override fun onStart() {
        super.onStart()
        Events.viewStart(this, serverId) // looking at this server makes me active in it
    }

    override fun onStop() {
        super.onStop()
        Events.viewStop(this)
    }

    override fun onResume() {
        super.onResume()
        IncomingCalls.attach(this) // someone calling shows up over whatever you're doing
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        Events.removeListener(this)
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                if (ApiClient.myId == 0L) ApiClient.me()
                val sv = ApiClient.servers().firstOrNull { it.id == serverId }
                if (sv == null) { gone("You're no longer in this server."); return@launch }
                server = sv
                Events.serverNames[sv.id] = sv.name
                channels = ApiClient.channels(serverId)
                members = ApiClient.serverMembers(serverId).associateBy { it.user.id }
                val a = ApiClient.serverActivity(serverId)
                val known = activity
                if (known == null || known.epoch != a.epoch || a.seq >= known.seq) activity = a
                render()
            } catch (_: Exception) { /* transient; the next event or resume retries */ }
        }
    }

    private fun gone(message: String) {
        ServerDialogs.toast(this, message)
        finish()
    }

    private fun openMenu() {
        val sv = server ?: return
        ServerDialogs.menu(this, sv, picker, onChanged = { load() }, onGone = {
            if (Calls.voice?.serverId == sv.id) Calls.voice = null
            finish()
        })
    }

    // ---- rendering ----

    private fun render() {
        val sv = server ?: return
        title.text = sv.name
        iconBox.removeAllViews()
        iconBox.addView(ServerDialogs.iconView(this, sv, 34))
        content.removeAllViews()

        sectionHeader("Text channels", if (sv.isAdmin) ({ ServerDialogs.createChannel(this, sv, "text") { load() } }) else null)
        for (ch in channels.filter { it.kind == "text" }) content.addView(textChannelRow(sv, ch))

        sectionHeader("Voice channels", if (sv.isAdmin) ({ ServerDialogs.createChannel(this, sv, "voice") { load() } }) else null)
        for (ch in channels.filter { it.kind == "voice" }) {
            content.addView(voiceChannelRow(sv, ch))
            for (id in activity?.voice?.get(ch.id).orEmpty()) content.addView(occupantRow(id))
        }

        val active = activity?.active.orEmpty()
        val inVoice = activity?.voice?.values?.flatten()?.toSet().orEmpty()
        val sorted = members.values.sortedBy { it.user.nick.lowercase() }
        val here = sorted.filter { it.user.id in active }
        val away = sorted.filter { it.user.id !in active }
        sectionHeader("Active — ${here.size}", null)
        for (m in here) content.addView(memberRow(m, true, m.user.id in inVoice))
        sectionHeader("Away — ${away.size}", null)
        for (m in away) content.addView(memberRow(m, false, false))
    }

    private fun sectionHeader(label: String, onAdd: (() -> Unit)?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(14), 0, dp(2))
        }
        row.addView(TextView(this).apply {
            text = label.uppercase()
            setTextColor(Color.parseColor(DIM)); textSize = 11f
            letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        if (onAdd != null) row.addView(headerAction("＋", "New channel") { onAdd() })
        content.addView(row)
    }

    /**
     * A muted, borderless action beside a heading — the same as the home
     * screen's, rather than a bright accent button.
     */
    private fun headerAction(label: String, describe: String, onClick: () -> Unit): Button =
        Button(androidx.appcompat.view.ContextThemeWrapper(this, R.style.HeaderAction), null, 0).apply {
            text = label
            contentDescription = describe
            setOnClickListener { onClick() }
        }

    private fun baseRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(12), dp(10), dp(12))
        isClickable = true
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(Color.parseColor("#232936")), null,
            GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.WHITE) })
    }

    private fun textChannelRow(sv: Server, ch: Channel): View {
        val row = baseRow()
        val unread = ch.unread > 0
        row.addView(TextView(this).apply { text = "#"; setTextColor(Color.parseColor(MUTED)); textSize = 17f })
        row.addView(TextView(this).apply {
            text = ch.name
            setTextColor(Color.parseColor(if (unread) TEXT else MUTED)); textSize = 16f
            if (unread) setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(12) }
        })
        if (unread) row.addView(pip(ch.unread, Mentions.has(Mentions.channelKey(ch.id))))
        row.setOnClickListener { openChannel(sv, ch) }
        if (sv.isAdmin) row.setOnLongClickListener { ServerDialogs.channelMenu(this, ch) { load() }; true }
        return row
    }

    private fun voiceChannelRow(sv: Server, ch: Channel): View {
        val row = baseRow()
        val connected = Calls.voice?.channelId == ch.id
        val color = if (connected) "#34D399" else MUTED
        row.addView(TextView(this).apply { text = "🔊"; textSize = 15f })
        row.addView(TextView(this).apply {
            text = ch.name
            setTextColor(Color.parseColor(color)); textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(10) }
        })
        if (connected) row.addView(TextView(this).apply {
            text = "connected"; setTextColor(Color.parseColor("#34D399")); textSize = 12f
        })
        row.contentDescription = if (connected) "${ch.name}, voice, connected" else "Join voice ${ch.name}"
        row.setOnClickListener { joinVoice(ch) }
        if (sv.isAdmin) row.setOnLongClickListener { ServerDialogs.channelMenu(this, ch) { load() }; true }
        return row
    }

    private fun occupantRow(userId: Long): View {
        val m = members[userId]
        val nick = m?.user?.nick ?: "someone"
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(42), dp(3), dp(10), dp(3))
            addView(Avatars.view(this@ServerActivity, nick, m?.user?.avatarUrl ?: "", 22, endMarginDp = 8))
            addView(TextView(this@ServerActivity).apply {
                text = nick
                setTextColor(Color.parseColor(if (userId == ApiClient.myId) TEXT else MUTED)); textSize = 14f
            })
        }
    }

    private fun memberRow(m: ServerMember, here: Boolean, inVoice: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
            alpha = if (here) 1f else 0.5f
        }
        row.addView(Avatars.view(this, m.user.nick, m.user.avatarUrl, 32, endMarginDp = 10))
        row.addView(TextView(this).apply {
            text = m.user.nick + if (m.user.id == ApiClient.myId) " (you)" else ""
            setTextColor(Color.parseColor(TEXT)); textSize = 15f
        })
        if (inVoice) row.addView(TextView(this).apply {
            text = " 🔊"; textSize = 13f; contentDescription = "in a voice channel"
        })
        row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        if (m.role == "admin") row.addView(TextView(this).apply {
            text = "ADMIN"; setTextColor(Color.parseColor(DIM)); textSize = 10f; letterSpacing = 0.06f
        })
        return row
    }

    private fun pip(n: Int, mentioned: Boolean): View = TextView(this).apply {
        text = if (n > 99) "99+" else n.toString()
        setTextColor(Color.WHITE); textSize = 11f
        setPadding(dp(7), dp(2), dp(7), dp(2))
        if (mentioned) contentDescription = "$n unread, you were mentioned"
        background = GradientDrawable().apply {
            cornerRadius = 999f
            setColor(Color.parseColor(if (mentioned) "#F87171" else "#5B8CFF"))
        }
    }

    // ---- actions ----

    private fun openChannel(sv: Server, ch: Channel) {
        startActivity(Intent(this, ChannelChatActivity::class.java)
            .putExtra(ChannelChatActivity.EXTRA_CHANNEL_ID, ch.id)
            .putExtra(ChannelChatActivity.EXTRA_CHANNEL_NAME, ch.name)
            .putExtra(ChannelChatActivity.EXTRA_SERVER_ID, sv.id)
            .putExtra(ChannelChatActivity.EXTRA_SERVER_NAME, sv.name))
    }

    private fun joinVoice(ch: Channel) {
        if (ch.callCode.isEmpty()) { ServerDialogs.toast(this, "That voice channel isn't available."); return }
        if (Calls.voice?.channelId != ch.id) ServerDialogs.toast(this, "Joining 🔊 ${ch.name}")
        Calls.join(this, ch.callCode, Calls.Voice(serverId, ch.id, ch.name))
    }

    // ---- live events (OkHttp thread) ----

    override fun onServerActivity(activity: Activity) = runOnUiThread {
        if (activity.serverId != serverId) return@runOnUiThread
        val prev = this.activity
        // Overtaken by a newer snapshot — but only within one run of the server,
        // whose counter starts again from zero when it restarts.
        if (prev != null && prev.epoch == activity.epoch && activity.seq < prev.seq) return@runOnUiThread
        this.activity = activity
        render()
    }

    override fun onServerUpdate(serverId: Long, deleted: Boolean) = runOnUiThread {
        if (serverId != this.serverId) return@runOnUiThread
        if (deleted) gone("This server was deleted.") else load()
    }

    override fun onChannelMessage(message: ChannelMessage) = runOnUiThread {
        if (message.serverId != serverId || message.senderId == ApiClient.myId) return@runOnUiThread
        channels = channels.map { if (it.id == message.channelId) it.copy(unread = it.unread + 1) else it }
        render()
    }

    override fun onMentionsChanged() = runOnUiThread { render() }

    override fun onPresence(userId: Long, online: Boolean) {}

    override fun onPause() {
        super.onPause()
        IncomingCalls.detach(this)
    }
}
