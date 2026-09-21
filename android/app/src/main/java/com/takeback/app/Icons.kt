package com.takeback.app

import android.content.Context
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

/**
 * The app's icons, at the size a row wants them.
 *
 * The screens used emoji for this — 🔊, #, ⚙, ＋ — which render as a different
 * typeface (and a different colour) on every phone, and sit oddly beside text.
 * These are the game-icons.net set, tinted from the palette, so a row of
 * controls reads as one thing. See ATTRIBUTION.md.
 */
object Icons {
    fun view(ctx: Context, drawable: Int, sizeDp: Int, colour: Int, endMarginDp: Int = 0): ImageView {
        val d = ctx.resources.displayMetrics.density
        return ImageView(ctx).apply {
            setImageResource(drawable)
            setColorFilter(tbColor(colour))
            layoutParams = LinearLayout.LayoutParams((sizeDp * d).toInt(), (sizeDp * d).toInt()).also {
                it.marginEnd = (endMarginDp * d).toInt()
            }
        }
    }
}
