package com.takeback.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import coil.load
import coil.transform.CircleCropTransformation

/**
 * Avatars builds the same circular profile picture / initials fallback the web
 * client uses: an uploaded image when available, otherwise a colour-from-nick
 * initials circle.
 */
object Avatars {

    /** A circular avatar [sizeDp] across. Margin-capable params so it drops into
     *  a LinearLayout with an end margin if the caller wants one. */
    fun view(ctx: Context, nick: String, url: String, sizeDp: Int, endMarginDp: Int = 0): View {
        val d = ctx.resources.displayMetrics.density
        val px = (sizeDp * d).toInt()
        val lp = android.view.ViewGroup.MarginLayoutParams(px, px).apply {
            marginEnd = (endMarginDp * d).toInt()
        }
        return if (url.isNotEmpty()) {
            ImageView(ctx).apply {
                layoutParams = lp
                load(url) { transformations(CircleCropTransformation()) }
            }
        } else {
            TextView(ctx).apply {
                layoutParams = lp
                text = initials(nick)
                setTextColor(Color.WHITE)
                textSize = sizeDp * 0.32f
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colorFor(nick))
                }
            }
        }
    }

    fun initials(nick: String) = nick.ifEmpty { "?" }.take(2).uppercase()

    /** Same hue-from-nick hash as the web client, so avatars match across clients. */
    fun colorFor(nick: String): Int {
        var h = 0L
        for (c in nick) h = (h * 31 + c.code) and 0xFFFFFFFFL
        return Color.HSVToColor(floatArrayOf((h % 360).toFloat(), 0.55f, 0.42f))
    }
}
