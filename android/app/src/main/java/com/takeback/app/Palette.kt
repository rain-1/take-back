package com.takeback.app

import android.content.Context
import androidx.core.content.ContextCompat

/**
 * The app's colours, read from res/values/colors.xml.
 *
 * Screens used to write hex strings inline — 145 of them, for thirteen colours
 * — so the same surface came out three different greys and secondary buttons
 * wore the accent. Going through here means one place to change a colour, and
 * the phone matches the web and desktop clients, which share this palette.
 *
 * It takes no Context on purpose: most of these are set inside `apply {}` on a
 * view, where `this` is the view and reaching for the activity is noise.
 */
object Palette {
    private lateinit var app: Context

    fun init(context: Context) { app = context.applicationContext }

    fun color(id: Int): Int = ContextCompat.getColor(app, id)
}

/** Shorthand: `tbColor(R.color.tb_text)`. */
fun tbColor(id: Int): Int = Palette.color(id)
