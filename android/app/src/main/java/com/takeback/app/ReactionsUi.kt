package com.takeback.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.takeback.app.net.Reaction

/**
 * Shared reaction UI for the DM and group chat screens. Mobile has no hover, so
 * "who reacted" is a tap on the pill (shows a dialog) rather than a tooltip.
 */
object ReactionsUi {
    val emojis = listOf("👍", "❤️", "😂", "🎉", "🙏", "👀", "🔥", "😮", "😢", "💯", "✅", "🚀")

    /**
     * Fill [row] with a pill per reaction. Tapping a pill toggles your own;
     * long-pressing it shows who reacted.
     */
    fun render(
        ctx: Context,
        row: LinearLayout,
        reactions: List<Reaction>,
        onToggle: (emoji: String, add: Boolean) -> Unit,
    ) {
        row.removeAllViews()
        val density = ctx.resources.displayMetrics.density
        for (r in reactions) {
            val pill = TextView(ctx).apply {
                text = "${r.emoji} ${r.count}"
                textSize = 12f
                setTextColor(Color.parseColor("#E7E9EE"))
                setPadding((10 * density).toInt(), (3 * density).toInt(), (10 * density).toInt(), (3 * density).toInt())
                background = GradientDrawable().apply {
                    cornerRadius = 999f
                    setColor(Color.parseColor(if (r.mine) "#274690" else "#1C2029"))
                    setStroke((1 * density).toInt(), Color.parseColor(if (r.mine) "#3B60B0" else "#2A303C"))
                }
                setOnClickListener { onToggle(r.emoji, !r.mine) }
                setOnLongClickListener { showWho(ctx, r); true }
            }
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.marginEnd = (6 * density).toInt()
            pill.layoutParams = lp
            row.addView(pill)
        }
    }

    /** A grid of emoji to pick from. */
    fun showPicker(ctx: Context, onPick: (String) -> Unit) {
        val cols = 6
        val density = ctx.resources.displayMetrics.density
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt()) }
        val dialog = AlertDialog.Builder(ctx).setView(grid).create()
        var rowView: LinearLayout? = null
        emojis.forEachIndexed { i, e ->
            if (i % cols == 0) {
                rowView = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                grid.addView(rowView)
            }
            rowView!!.addView(TextView(ctx).apply {
                text = e
                textSize = 24f
                setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
                setOnClickListener { onPick(e); dialog.dismiss() }
            })
        }
        dialog.show()
    }

    private fun showWho(ctx: Context, r: Reaction) {
        AlertDialog.Builder(ctx)
            .setTitle("${r.emoji}  ${r.count}")
            .setMessage(r.nicks.joinToString("\n"))
            .setPositiveButton("OK", null)
            .show()
    }
}
