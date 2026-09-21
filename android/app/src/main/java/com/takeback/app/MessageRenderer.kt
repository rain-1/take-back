package com.takeback.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import coil.load
import com.takeback.app.net.Attachment
import com.takeback.app.net.Reaction
import io.noties.markwon.Markwon

/**
 * A normalized message the renderer can draw, from either a DM or a group.
 */
data class RMsg(
    val id: Long,
    val senderId: Long,
    val senderNick: String,
    val senderAvatar: String,
    val body: String,
    val imageUrl: String?,
    val thumbUrl: String?,
    val attachment: Attachment? = null,
    val created: Long,
    val reactions: List<Reaction>,
    val replyTo: Long,
    val replyNick: String,
    val replyBody: String,
    val mine: Boolean,
    val callCode: String?,
    val editedAt: Long = 0,
    val deletedAt: Long = 0,
)

/**
 * MessageRenderer draws the Slack/Discord-style message list the web client uses:
 * one left-aligned column, consecutive messages from the same sender grouped
 * under a single avatar + name + timestamp. Shared by the DM and group screens.
 */
class MessageRenderer(
    private val ctx: Context,
    private val container: LinearLayout,
    private val scroll: ScrollView,
    private val markwon: Markwon,
    private val onReply: (RMsg) -> Unit,
    private val onReact: (id: Long, emoji: String, add: Boolean) -> Unit,
    private val onJoinCall: (code: String) -> Unit,
    private val onOpenAttachment: (url: String) -> Unit,
    private val onEdit: (RMsg) -> Unit = {},
    private val onDelete: (RMsg) -> Unit = {},
    /** Canonical nick for a name someone @-mentioned, or null if they aren't here. */
    private val knownNick: (String) -> String? = { null },
    private val onMentionTap: (nick: String) -> Unit = {},
    /** Whether I may delete other people's messages here (a server admin). */
    private val canModerate: () -> Boolean = { false },
    /** What became of a call code, if the screen knows. */
    private val callStateFor: (String) -> com.takeback.app.net.CallState? = { null },
    /** My own id, and names for the people in a call. */
    private val myId: () -> Long = { 0 },
    private val nickOf: (Long) -> String? = { null },
    private val onDeclineCall: (String) -> Unit = {},
) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val groupWindow = 5 * 60 // seconds

    private companion object {
        val MENTION = Regex("(^|[^\\w@/])@([A-Za-z0-9_-]{1,32})")
    }

    private var lastSender = -1L
    private var lastTime = 0L
    private var currentMain: LinearLayout? = null

    private val messageViews = HashMap<Long, View>()          // for jump-to
    private val reactionRows = HashMap<Long, LinearLayout>()
    private val reactionState = HashMap<Long, List<Reaction>>()
    private val bodyViews = HashMap<Long, TextView>()         // for in-place edit
    private val callMessages = HashMap<String, MutableList<RMsg>>() // code -> its messages
    private val editedMarks = HashMap<Long, TextView>()       // "· edited" markers

    fun clear() {
        container.removeAllViews()
        messageViews.clear(); reactionRows.clear(); reactionState.clear()
        bodyViews.clear(); editedMarks.clear(); callMessages.clear()
        lastSender = -1L; lastTime = 0L; currentMain = null
    }

    fun add(m: RMsg) {
        val newGroup = m.senderId != lastSender || (m.created - lastTime) > groupWindow || currentMain == null
        if (newGroup) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                val lp = LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(10); layoutParams = lp
                setPadding(dp(8), 0, dp(8), 0)
            }
            row.addView(Avatars.view(ctx, m.senderNick, m.senderAvatar, 36, endMarginDp = 10))
            val main = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            main.addView(header(m))
            row.addView(main)
            container.addView(row)
            currentMain = main
        }
        currentMain!!.addView(messageView(m))
        lastSender = m.senderId
        lastTime = m.created
    }

    private fun header(m: RMsg): View {
        val h = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        h.addView(TextView(ctx).apply {
            text = m.senderNick
            setTextColor(tbColor(R.color.tb_text)); textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        h.addView(TextView(ctx).apply {
            text = timeOf(m.created)
            setTextColor(tbColor(R.color.tb_dim)); textSize = 11f
            val lp = LinearLayout.LayoutParams(-2, -2); lp.marginStart = dp(6); layoutParams = lp
        })
        return h
    }

    private fun messageView(m: RMsg): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(1), dp(2), dp(1))
            setOnLongClickListener {
                ReactionsUi.showActions(ctx,
                    onReply = { onReply(m) },
                    onReact = {
                        ReactionsUi.showPicker(ctx) { emoji ->
                            val mine = reactionState[m.id]?.firstOrNull { it.emoji == emoji }?.mine ?: false
                            onReact(m.id, emoji, !mine)
                        }
                    },
                    // Edit is offered only for your own text messages (not images/calls).
                    onEdit = if (m.mine && m.callCode == null && m.body.isNotEmpty() && m.deletedAt == 0L)
                        ({ onEdit(m) }) else null,
                    // Delete covers your own messages of any kind — an attachment
                    // is the thing you most often want to take back.
                    // Admins may also remove anyone's message in their server.
                    onDelete = if ((m.mine || canModerate()) && m.deletedAt == 0L) ({ onDelete(m) }) else null)
                true
            }
        }

        if (m.replyTo != 0L) {
            col.addView(ReactionsUi.quoteBlock(ctx, m.replyNick, m.replyBody) {
                messageViews[m.replyTo]?.let { flashTo(it) }
            })
        }

        if (m.deletedAt != 0L) {
            // A withdrawn message leaves a marker rather than a hole, so replies
            // quoting it still point at something and the list doesn't reflow.
            col.addView(TextView(ctx).apply {
                text = "message deleted"
                setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_delete, 0, 0, 0)
                compoundDrawableTintList = android.content.res.ColorStateList.valueOf(tbColor(R.color.tb_dim))
                compoundDrawablePadding = dp(6)
                setTextColor(tbColor(R.color.tb_dim))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.ITALIC)
            })
        } else if (m.callCode != null) {
            callMessages.getOrPut(m.callCode) { mutableListOf() }.add(m)
            col.addView(callView(m, m.callCode))
        } else {
            if (m.body.isNotEmpty()) {
                val tv = TextView(ctx).apply { setTextColor(tbColor(R.color.tb_text)); textSize = 15f }
                renderBody(tv, m.body)
                // Tappable mentions/links give the text view its own touch
                // handling, which would otherwise swallow the long-press that
                // opens this message's Reply/React/Edit/Delete menu.
                tv.setOnLongClickListener { col.performLongClick() }
                bodyViews[m.id] = tv
                col.addView(tv)
                // Muted "· edited" marker, shown once a message has been edited.
                val mark = TextView(ctx).apply {
                    text = "· edited"; setTextColor(tbColor(R.color.tb_dim)); textSize = 11f
                    visibility = if (m.editedAt > 0) View.VISIBLE else View.GONE
                }
                editedMarks[m.id] = mark
                col.addView(mark)
            }
            attachmentView(m)?.let { col.addView(it) }
        }

        val rx = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(-2, -2); lp.topMargin = dp(3); layoutParams = lp
        }
        reactionRows[m.id] = rx
        reactionState[m.id] = m.reactions
        ReactionsUi.render(ctx, rx, m.reactions) { emoji, add -> onReact(m.id, emoji, add) }
        col.addView(rx)

        messageViews[m.id] = col
        return col
    }

    /**
     * The view for a message's attachment, or null when it has none.
     *
     * An image shows its server-made thumbnail and opens full-size in the app
     * when tapped. Everything else becomes a tappable chip naming the file:
     * video and audio open an in-app player ([MediaDialog]), other documents go
     * to whatever app handles that type.
     */
    private fun attachmentView(m: RMsg): View? {
        // Fall back to the image fields for messages that predate `attachment`.
        val att = m.attachment ?: m.thumbUrl?.let {
            Attachment(m.imageUrl ?: it, "image", "image", 0, it)
        } ?: return null

        if (att.kind == "image" && att.thumbUrl != null) {
            return ImageView(ctx).apply {
                adjustViewBounds = true
                maxWidth = dp(240)
                load(att.thumbUrl)
                // Opens over the conversation rather than in a browser.
                setOnClickListener { MediaDialog.showImage(ctx, att.url, att.name) }
                val lp = LinearLayout.LayoutParams(-2, -2); lp.topMargin = dp(6); layoutParams = lp
            }
        }

        val icon = when (att.kind) {
            "video" -> R.drawable.ic_video
            "audio" -> R.drawable.ic_audio
            else -> R.drawable.ic_attach
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(tbColor(R.color.tb_surface))
                setStroke(dp(1), tbColor(R.color.tb_border))
            }
            addView(Icons.view(ctx, icon, 18, R.color.tb_muted))
            addView(TextView(ctx).apply {
                text = att.name
                setTextColor(tbColor(R.color.tb_text))
                textSize = 13f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE // keep the extension visible
                val lp = LinearLayout.LayoutParams(0, -2, 1f); lp.leftMargin = dp(8); layoutParams = lp
            })
            if (att.size > 0) {
                addView(TextView(ctx).apply {
                    text = formatSize(att.size)
                    setTextColor(tbColor(R.color.tb_dim))
                    textSize = 11f
                    val lp = LinearLayout.LayoutParams(-2, -2); lp.leftMargin = dp(8); layoutParams = lp
                })
            }
            // Video and audio play right here; other files go to an app that opens them.
            setOnClickListener {
                if (att.kind == "video" || att.kind == "audio") MediaDialog.show(ctx, att.url, att.name, att.kind)
                else onOpenAttachment(att.url)
            }
            val lp = LinearLayout.LayoutParams(dp(260), -2); lp.topMargin = dp(6); layoutParams = lp
        }
    }

    /**
     * A call in the conversation, drawn as what it became: someone waiting (with
     * Join), or a line of history — "river started a call that lasted 4 minutes."
     * A call nobody recorded is from before calls were tracked, so it is history
     * too rather than a button into an empty room.
     */
    private fun callView(m: RMsg, code: String): View {
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val c = callStateFor(code)
        val caller = if (m.mine) "You" else m.senderNick
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val glyph = Icons.view(ctx, R.drawable.ic_call, 16, R.color.tb_online, endMarginDp = 8)
        val line = TextView(ctx).apply { textSize = 14f }
        val history = { text: String ->
            line.text = text
            line.setTextColor(tbColor(R.color.tb_muted))
            glyph.setColorFilter(tbColor(R.color.tb_muted))
        }
        when {
            c == null -> history("$caller started a call.")
            c.outcome == "ended" ->
                history("$caller started a call" +
                    (if (c.seconds > 0) " that lasted ${humanDuration(c.seconds)}." else "."))
            c.outcome == "declined" -> {
                val by = if (c.declinedBy == myId()) "You" else (nickOf(c.declinedBy) ?: "They")
                history("$caller started a call. $by declined it.")
            }
            c.outcome == "missed" ->
                history(if (m.mine) "$caller started a call. Nobody joined."
                        else "You missed a call from $caller.")
            else -> {
                val others = c.participants.filter { it != myId() }.mapNotNull { nickOf(it) }
                line.text = "$caller started a call." +
                    when {
                        others.isEmpty() -> ""
                        others.size == 1 -> " ${others[0]} is waiting."
                        else -> " ${others.joinToString(", ")} are in it."
                    }
                line.setTextColor(tbColor(R.color.tb_text))
            }
        }
        row.addView(glyph); row.addView(line)
        box.addView(row)
        if (c != null && c.live) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(Button(ctx).apply { text = "Join"; setOnClickListener { onJoinCall(code) } })
            if (c.callerId != myId()) row.addView(Button(ctx).apply {
                text = "Decline"; setOnClickListener { onDeclineCall(code) }
            })
            box.addView(row)
        }
        return box
    }

    /** Repaint the messages announcing [code], after its state changed. */
    fun updateCall(code: String) {
        for (m in callMessages[code].orEmpty()) {
            val col = messageViews[m.id] as? LinearLayout ?: continue
            col.removeAllViews()
            col.addView(callView(m, code))
        }
    }

    private fun humanDuration(seconds: Long): String = when {
        seconds < 60 -> "less than a minute"
        seconds < 120 -> "a minute"
        seconds < 3600 -> "${(seconds + 30) / 60} minutes"
        seconds < 7200 -> "an hour"
        else -> "${seconds / 3600} hours"
    }

    /** A byte count the way a file manager would show it. */
    private fun formatSize(n: Long): String {
        val units = listOf("B", "KB", "MB", "GB")
        var v = n.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (v < 10 && i > 0) String.format("%.1f %s", v, units[i])
               else String.format("%.0f %s", v, units[i])
    }

    /** Apply an edit to a message already on screen (mine or a peer's). */
    fun updateMessage(id: Long, body: String, editedAt: Long) {
        bodyViews[id]?.let { renderBody(it, body) }
        editedMarks[id]?.visibility = if (editedAt > 0) View.VISIBLE else View.GONE
    }

    /**
     * Turn a message already on screen into the "deleted" marker, for my own
     * delete and for the live event when someone else deletes theirs.
     *
     * The row itself stays put so the list doesn't jump and replies quoting it
     * still have somewhere to scroll to; only its content is replaced.
     */
    fun markDeleted(id: Long) {
        val col = messageViews[id] as? LinearLayout ?: return
        col.removeAllViews()
        col.setOnLongClickListener(null) // nothing left to reply to, react to or edit
        col.addView(TextView(ctx).apply {
            text = "message deleted"
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_delete, 0, 0, 0)
            compoundDrawableTintList = android.content.res.ColorStateList.valueOf(tbColor(R.color.tb_dim))
            compoundDrawablePadding = dp(6)
            setTextColor(tbColor(R.color.tb_dim))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.ITALIC)
        })
        bodyViews.remove(id)
        editedMarks.remove(id)
        reactionRows.remove(id)
        reactionState.remove(id)
    }

    /** Markdown, then @mentions on top of it. */
    private fun renderBody(tv: TextView, body: String) {
        markwon.setMarkdown(tv, body)
        applyMentions(tv)
    }

    /**
     * Highlight @mentions of people in this conversation and make them tappable.
     *
     * Runs over the text Markwon produced, so it never has to understand Markdown.
     * Same rule as the web client: the @ can't follow a word character, another @
     * or a slash (so an email address isn't a mention), and only names that
     * [knownNick] recognises count — a stray "@" in prose is left alone. Your own
     * name is coloured differently, because that's the one you scan for.
     */
    private fun applyMentions(tv: TextView) {
        val text = tv.text ?: return
        val ssb = android.text.SpannableStringBuilder(text)
        var any = false
        for (m in MENTION.findAll(ssb)) {
            val name = m.groups[2] ?: continue
            val nick = knownNick(name.value) ?: continue
            val start = name.range.first - 1 // include the @
            val end = name.range.last + 1
            val isMe = nick.equals(com.takeback.app.net.ApiClient.myNick, ignoreCase = true)
            val fg = tbColor(if (isMe) R.color.tb_mention_me else R.color.tb_mention)
            val bg = tbColor(if (isMe) R.color.tb_mention_me_bg else R.color.tb_mention_bg)
            ssb.setSpan(object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) = onMentionTap(nick)
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.color = fg
                    ds.isUnderlineText = false
                    ds.isFakeBoldText = true
                }
            }, start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            ssb.setSpan(android.text.style.BackgroundColorSpan(bg), start, end,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            any = true
        }
        if (any) {
            tv.text = ssb
            tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        }
    }

    /** Whether a message with this id is currently on screen. */
    fun has(id: Long): Boolean = messageViews.containsKey(id)

    /** Update one message's reactions from a live event. */
    fun updateReactions(id: Long, reactions: List<Reaction>) {
        reactionState[id] = reactions
        reactionRows[id]?.let { ReactionsUi.render(ctx, it, reactions) { emoji, add -> onReact(id, emoji, add) } }
    }

    private fun flashTo(v: View) {
        scroll.post {
            scroll.smoothScrollTo(0, v.top)
            val orig = v.background
            v.setBackgroundColor(tbColor(R.color.tb_accent_dim))
            v.postDelayed({ v.background = orig }, 900)
        }
    }

    fun scrollToBottom() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun timeOf(created: Long): String =
        android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(created * 1000))
}
