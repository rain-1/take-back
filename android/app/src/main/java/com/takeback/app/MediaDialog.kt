package com.takeback.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog

/**
 * Plays a video or audio attachment inside the app, like the web client's
 * inline players, instead of handing it to another app. "Open in…" is still
 * there for a player you prefer.
 */
object MediaDialog {
    fun show(ctx: Context, url: String, name: String, kind: String) {
        val d = ctx.resources.displayMetrics.density
        val video = VideoView(ctx)
        val status = TextView(ctx).apply {
            text = if (kind == "audio") "🎵 Loading…" else "Loading…"
            setTextColor(Color.parseColor("#8A93A6")); textSize = 14f
            gravity = Gravity.CENTER
        }
        val frame = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            // Audio has no picture: keep the player short, just room for its controls.
            val h = if (kind == "audio") (96 * d).toInt() else (ctx.resources.displayMetrics.heightPixels * 0.45).toInt()
            layoutParams = LinearLayout.LayoutParams(-1, h)
            addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
            addView(status, FrameLayout.LayoutParams(-1, -1))
        }
        val controls = MediaController(ctx)
        controls.setAnchorView(frame)
        video.setMediaController(controls)
        video.setVideoURI(Uri.parse(url))
        video.setOnPreparedListener {
            status.text = if (kind == "audio") "🎵 $name" else ""
            if (kind != "audio") status.visibility = android.view.View.GONE
            video.start()
            controls.show(0)
        }
        video.setOnErrorListener { _, _, _ ->
            status.text = "This can't be played here. Try Open in…"
            status.visibility = android.view.View.VISIBLE
            true
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle(name)
            .setView(LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; addView(frame) })
            .setPositiveButton("Close", null)
            .setNeutralButton("Open in…") { _, _ ->
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "$kind/*")) }
                    .onFailure { Toast.makeText(ctx, "No other app can open that.", Toast.LENGTH_SHORT).show() }
            }
            .create()
        dialog.setOnDismissListener { video.stopPlayback(); controls.hide() }
        dialog.show()
    }
}
