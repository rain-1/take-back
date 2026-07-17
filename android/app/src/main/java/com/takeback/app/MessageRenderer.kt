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
    val created: Long,
    val reactions: List<Reaction>,
    val replyTo: Long,
    val replyNick: String,
    val replyBody: String,
    val mine: Boolean,
    val callCode: String?,
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
    private val onOpenImage: (url: String) -> Unit,
) {
    private val d = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()
    private val groupWindow = 5 * 60 // seconds

    private var lastSender = -1L
    private var lastTime = 0L
    private var currentMain: LinearLayout? = null

    private val messageViews = HashMap<Long, View>()          // for jump-to
    private val reactionRows = HashMap<Long, LinearLayout>()
    private val reactionState = HashMap<Long, List<Reaction>>()

    fun clear() {
        container.removeAllViews()
        messageViews.clear(); reactionRows.clear(); reactionState.clear()
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
            setTextColor(Color.parseColor("#E8EAF0")); textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        h.addView(TextView(ctx).apply {
            text = timeOf(m.created)
            setTextColor(Color.parseColor("#5A6273")); textSize = 11f
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
                    })
                true
            }
        }

        if (m.replyTo != 0L) {
            col.addView(ReactionsUi.quoteBlock(ctx, m.replyNick, m.replyBody) {
                messageViews[m.replyTo]?.let { flashTo(it) }
            })
        }

        if (m.callCode != null) {
            col.addView(TextView(ctx).apply { text = "📞 Video call"; setTextColor(Color.parseColor("#E8EAF0")) })
            col.addView(Button(ctx).apply { text = "Join call ${m.callCode}"; setOnClickListener { onJoinCall(m.callCode) } })
        } else {
            if (m.body.isNotEmpty()) {
                val tv = TextView(ctx).apply { setTextColor(Color.parseColor("#E8EAF0")); textSize = 15f }
                markwon.setMarkdown(tv, m.body)
                col.addView(tv)
            }
            if (m.thumbUrl != null) {
                col.addView(ImageView(ctx).apply {
                    adjustViewBounds = true
                    maxWidth = dp(240)
                    load(m.thumbUrl)
                    setOnClickListener { m.imageUrl?.let { onOpenImage(it) } }
                    val lp = LinearLayout.LayoutParams(-2, -2); lp.topMargin = dp(6); layoutParams = lp
                })
            }
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

    /** Update one message's reactions from a live event. */
    fun updateReactions(id: Long, reactions: List<Reaction>) {
        reactionState[id] = reactions
        reactionRows[id]?.let { ReactionsUi.render(ctx, it, reactions) { emoji, add -> onReact(id, emoji, add) } }
    }

    private fun flashTo(v: View) {
        scroll.post {
            scroll.smoothScrollTo(0, v.top)
            val orig = v.background
            v.setBackgroundColor(Color.parseColor("#2F4A8F"))
            v.postDelayed({ v.background = orig }, 900)
        }
    }

    fun scrollToBottom() = scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }

    private fun timeOf(created: Long): String =
        android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(created * 1000))
}
