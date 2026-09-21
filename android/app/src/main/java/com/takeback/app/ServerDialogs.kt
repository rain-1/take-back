package com.takeback.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import coil.transform.RoundedCornersTransformation
import com.takeback.app.net.ApiClient
import com.takeback.app.net.Channel
import com.takeback.app.net.Server
import kotlinx.coroutines.launch

/**
 * The server dialogs shared by the home screen, a server's screen and chat
 * links: create, join (with a preview), invite, the server menu, and the
 * admin's channel controls. Same flows as the web client's.
 */
object ServerDialogs {

    /** Something that can pick an image and hand back its Uri (an ActivityResult launcher). */
    fun interface ImagePicker { fun pick(onPicked: (Uri) -> Unit) }

    // ---- icons ----

    /** A server's icon: its picture, or initials on a colour from its name. */
    fun iconView(ctx: Context, server: Server, sizeDp: Int): View {
        val d = ctx.resources.displayMetrics.density
        val px = (sizeDp * d).toInt()
        val lp = LinearLayout.LayoutParams(px, px)
        if (server.iconUrl.isNotEmpty()) {
            return ImageView(ctx).apply {
                layoutParams = lp
                scaleType = ImageView.ScaleType.CENTER_CROP
                load(server.iconUrl) { transformations(RoundedCornersTransformation(sizeDp * d * 0.3f)) }
            }
        }
        return TextView(ctx).apply {
            layoutParams = lp
            text = server.name.split(Regex("\\s+")).mapNotNull { it.firstOrNull() }.joinToString("").take(2).uppercase().ifEmpty { "?" }
            setTextColor(Color.WHITE)
            textSize = sizeDp * 0.3f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = sizeDp * d * 0.3f
                setColor(iconColor(server.name))
            }
        }
    }

    /** Same hash as the web client's server icons: hsl(h, 45%, 38%). */
    private fun iconColor(name: String): Int {
        var h = 0L
        for (c in name) h = (h * 31 + c.code) and 0xFFFFFFFFL
        return Color.HSVToColor(floatArrayOf((h % 360).toFloat(), 0.62f, 0.55f))
    }

    // ---- invite links ----

    /**
     * The invite code in a take-back invite link (…/?invite=CODE on this app's
     * server), or a bare code as typed. Null if it's neither.
     */
    fun inviteCodeOf(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val uri = runCatching { Uri.parse(t) }.getOrNull()
        if (uri != null && uri.scheme != null && uri.host != null) {
            val ours = runCatching { Uri.parse(ApiClient.base).host }.getOrNull()
            if (!uri.host.equals(ours, ignoreCase = true)) return null
            return uri.getQueryParameter("invite")?.takeIf { it.isNotBlank() }
        }
        return t.takeIf { Regex("^[A-Za-z0-9]{4,32}$").matches(it) }
    }

    // ---- create / join ----

    fun create(activity: AppCompatActivity, picker: ImagePicker, onCreated: (Server) -> Unit) {
        val name = input(activity, "e.g. Movie night crew")
        var icon: Uri? = null
        val iconLabel = TextView(activity).apply {
            text = "No icon (optional)"; setTextColor(tbColor(R.color.tb_muted)); textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val iconRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(iconLabel)
            addView(Button(activity).apply {
                text = "Pick icon"
                setOnClickListener { picker.pick { uri -> icon = uri; iconLabel.text = "Icon: " + Attachments.displayName(activity, uri) } }
            })
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Create a server")
            .setView(column(activity, label(activity, "Server name"), name, iconRow))
            .setPositiveButton("Create", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { btn ->
                val n = name.text.toString().trim()
                if (n.isEmpty()) { name.error = "Give it a name"; return@setOnClickListener }
                btn.isEnabled = false
                activity.lifecycleScope.launch {
                    try {
                        var sv = ApiClient.createServer(n)
                        icon?.let { uri ->
                            runCatching {
                                val bytes = activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                                ApiClient.setServerIcon(sv.id, Attachments.displayName(activity, uri), bytes)
                            }.onFailure { toast(activity, "Server created, but the icon failed: ${it.message}") }
                            sv = ApiClient.servers().firstOrNull { it.id == sv.id } ?: sv
                        }
                        dialog.dismiss()
                        onCreated(sv)
                    } catch (e: Exception) {
                        btn.isEnabled = true
                        toast(activity, e.message ?: "Couldn't create the server")
                    }
                }
            }
        }
        dialog.show()
    }

    /** Join by link or code: look the server up first, then join. */
    fun join(activity: AppCompatActivity, prefill: String = "", onJoined: (Server) -> Unit = { openServer(activity, it) }) {
        val code = input(activity, "Invite link or code").apply { setText(prefill) }
        val preview = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 10), 0, 0)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Join a server")
            .setView(column(activity, label(activity, "Invite"), code, preview))
            .setPositiveButton("Join", null)
            .setNeutralButton("Look up", null)
            .setNegativeButton("Cancel", null)
            .create()

        fun lookUp() {
            preview.removeAllViews()
            val c = inviteCodeOf(code.text.toString()) ?: run {
                if (code.text.isNotBlank()) preview.addView(note(activity, "That isn't a take-back invite.", error = true))
                return
            }
            activity.lifecycleScope.launch {
                runCatching { ApiClient.previewInvite(c) }
                    .onSuccess { p ->
                        preview.addView(iconView(activity, p.server, 44))
                        preview.addView(TextView(activity).apply {
                            val count = "${p.server.memberCount} member" + if (p.server.memberCount == 1) "" else "s"
                            text = p.server.name + "\n" + count + if (p.alreadyMember) " · you're already in it" else ""
                            setTextColor(tbColor(R.color.tb_text)); textSize = 14f
                            setPadding(dp(activity, 12), 0, 0, 0)
                        })
                    }
                    .onFailure { preview.addView(note(activity, it.message ?: "Couldn't find that invite", error = true)) }
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { lookUp() }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { btn ->
                val c = inviteCodeOf(code.text.toString()) ?: run { code.error = "Paste an invite link or code"; return@setOnClickListener }
                btn.isEnabled = false
                activity.lifecycleScope.launch {
                    try {
                        val (sv, joined) = ApiClient.joinInvite(c)
                        dialog.dismiss()
                        toast(activity, if (joined) "Joined ${sv.name}!" else "You're already in ${sv.name}.")
                        onJoined(ApiClient.servers().firstOrNull { it.id == sv.id } ?: sv)
                    } catch (e: Exception) {
                        btn.isEnabled = true
                        toast(activity, e.message ?: "Couldn't join")
                    }
                }
            }
            if (prefill.isNotEmpty()) lookUp()
        }
        dialog.show()
    }

    fun openServer(ctx: Context, sv: Server) {
        ctx.startActivity(Intent(ctx, ServerActivity::class.java)
            .putExtra(ServerActivity.EXTRA_SERVER_ID, sv.id)
            .putExtra(ServerActivity.EXTRA_SERVER_NAME, sv.name))
    }

    // ---- invite ----

    fun invite(activity: AppCompatActivity, sv: Server) {
        activity.lifecycleScope.launch {
            val code = try { ApiClient.createInvite(sv.id) } catch (e: Exception) {
                toast(activity, e.message ?: "Couldn't make an invite"); return@launch
            }
            val link = ApiClient.inviteLink(code)
            AlertDialog.Builder(activity)
                .setTitle("Invite people to ${sv.name}")
                .setMessage("Anyone with this link can join (they'll need a take-back account). The code works too, typed into Join.\n\n$link\n\nCode: $code")
                .setPositiveButton("Share") { _, _ ->
                    activity.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_TEXT, "Join ${sv.name} on take-back: $link"), "Share invite"))
                }
                .setNeutralButton("Copy link") { _, _ ->
                    (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("invite", link))
                    toast(activity, "Invite link copied")
                }
                .setNegativeButton("Done", null)
                .show()
        }
    }

    // ---- server menu ----

    fun menu(activity: AppCompatActivity, sv: Server, picker: ImagePicker, onChanged: () -> Unit, onGone: () -> Unit) {
        val owner = sv.ownerId == ApiClient.myId
        val items = mutableListOf<Pair<String, () -> Unit>>()
        items += "Invite people" to { invite(activity, sv) }
        if (sv.isAdmin) {
            items += "Rename server" to {
                prompt(activity, "Rename server", sv.name, "Save") { n ->
                    ApiClient.renameServer(sv.id, n); onChanged()
                }
            }
            items += "Change icon" to {
                picker.pick { uri ->
                    activity.lifecycleScope.launch {
                        runCatching {
                            val bytes = activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                            ApiClient.setServerIcon(sv.id, Attachments.displayName(activity, uri), bytes)
                        }.onSuccess { onChanged() }.onFailure { toast(activity, it.message ?: "Icon upload failed") }
                    }
                }
            }
            items += "New text channel" to { createChannel(activity, sv, "text", onChanged) }
            items += "New voice channel" to { createChannel(activity, sv, "voice", onChanged) }
        }
        if (owner) {
            items += "Delete server" to {
                confirm(activity, "Delete ${sv.name}?", "Every channel and message goes, for everyone. This can't be undone.", "Delete") {
                    ApiClient.deleteServer(sv.id); onGone()
                }
            }
        } else {
            items += "Leave server" to {
                confirm(activity, "Leave ${sv.name}?", "You can rejoin with an invite.", "Leave") {
                    ApiClient.leaveServer(sv.id); onGone()
                }
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(sv.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .show()
    }

    // ---- channels ----

    fun createChannel(activity: AppCompatActivity, sv: Server, kind: String, onCreated: () -> Unit) {
        val name = input(activity, if (kind == "voice") "e.g. Hangout" else "e.g. announcements")
        val group = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        val text = RadioButton(activity).apply { this.text = "Text"; id = View.generateViewId(); setTextColor(tbColor(R.color.tb_text)) }
        val voice = RadioButton(activity).apply { this.text = "Voice"; id = View.generateViewId(); setTextColor(tbColor(R.color.tb_text)) }
        group.addView(text); group.addView(voice)
        group.check(if (kind == "voice") voice.id else text.id)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("New channel")
            .setView(column(activity, label(activity, "Type"), group, label(activity, "Channel name"), name))
            .setPositiveButton("Create", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = name.text.toString().trim()
                if (n.isEmpty()) { name.error = "Give it a name"; return@setOnClickListener }
                val k = if (group.checkedRadioButtonId == voice.id) "voice" else "text"
                activity.lifecycleScope.launch {
                    runCatching { ApiClient.createChannel(sv.id, n, k) }
                        .onSuccess { dialog.dismiss(); onCreated() }
                        .onFailure { e -> toast(activity, e.message ?: "Couldn't create the channel") }
                }
            }
        }
        dialog.show()
    }

    /** Long-press menu on a channel, for admins. */
    fun channelMenu(activity: AppCompatActivity, ch: Channel, onChanged: () -> Unit) {
        AlertDialog.Builder(activity)
            .setIcon(if (ch.kind == "voice") R.drawable.ic_voice_channel else R.drawable.ic_text_channel)
            .setTitle(ch.name)
            .setItems(arrayOf("Rename", "Delete")) { _, i ->
                if (i == 0) prompt(activity, "Rename channel", ch.name, "Save") { n ->
                    ApiClient.renameChannel(ch.id, n); onChanged()
                } else confirm(activity, "Delete ${ch.name}?",
                    if (ch.kind == "voice") "Anyone in it is disconnected." else "Its messages go with it, for everyone.", "Delete") {
                    ApiClient.deleteChannel(ch.id); onChanged()
                }
            }
            .show()
    }

    // ---- small helpers ----

    private fun prompt(activity: AppCompatActivity, title: String, value: String, action: String, onSave: suspend (String) -> Unit) {
        val field = input(activity, "").apply { setText(value); setSelection(value.length) }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(column(activity, field))
            .setPositiveButton(action) { _, _ ->
                val v = field.text.toString().trim()
                if (v.isEmpty() || v == value) return@setPositiveButton
                activity.lifecycleScope.launch {
                    runCatching { onSave(v) }.onFailure { toast(activity, it.message ?: "That didn't work") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(activity: AppCompatActivity, title: String, message: String, action: String, onYes: suspend () -> Unit) {
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(action) { _, _ ->
                activity.lifecycleScope.launch {
                    runCatching { onYes() }.onFailure { toast(activity, it.message ?: "That didn't work") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun input(ctx: Context, hint: String) = tbInput(ctx).apply {
        this.hint = hint
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        isSingleLine = true
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    private fun label(ctx: Context, text: String) = TextView(ctx).apply {
        this.text = text; setTextColor(tbColor(R.color.tb_muted)); textSize = 12f
        setPadding(0, dp(ctx, 8), 0, 0)
    }

    private fun note(ctx: Context, text: String, error: Boolean = false) = TextView(ctx).apply {
        this.text = text; textSize = 13f
        setTextColor(tbColor(if (error) R.color.tb_danger else R.color.tb_muted))
    }

    private fun column(ctx: Context, vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(ctx, 22), dp(ctx, 6), dp(ctx, 22), 0)
        views.forEach { addView(it) }
    }

    private fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}
