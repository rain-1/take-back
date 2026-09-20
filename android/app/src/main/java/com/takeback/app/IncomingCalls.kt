package com.takeback.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import com.takeback.app.net.ApiClient
import com.takeback.app.net.CallState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Someone calling you, while you're looking at the app: a banner across the top
 * with Join and Decline. No ringing — that's what River asked for, and the
 * notification covers the case where the app isn't open.
 *
 * The banner attaches itself to whatever screen is in front, so it doesn't have
 * to be built into every layout.
 */
object IncomingCalls {
    private var call: CallState? = null
    private var host: Activity? = null
    private var bar: View? = null

    /** A call arrived or changed. Shows the banner, or takes it away. */
    fun update(state: CallState) {
        val mine = state.callerId == ApiClient.myId
        call = if (state.live && !mine) state else if (call?.code == state.code) null else call
        host?.runOnUiThread { render() }
    }

    /** Called by a screen as it comes to the front. */
    fun attach(activity: Activity) {
        host = activity
        render()
    }

    fun detach(activity: Activity) {
        if (host === activity) {
            remove()
            host = null
        }
    }

    /** Stop showing a call we've joined or turned down. */
    fun dismiss(code: String) {
        if (call?.code == code) { call = null; host?.runOnUiThread { render() } }
    }

    private fun remove() {
        (bar?.parent as? ViewGroup)?.removeView(bar)
        bar = null
    }

    private fun render() {
        val activity = host ?: return
        val c = call
        remove()
        if (c == null || activity.isFinishing) return

        val d = activity.resources.displayMetrics.density
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((14 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(Color.parseColor("#171B24"))
                setStroke((1 * d).toInt(), Color.parseColor("#232936"))
            }
        }
        row.addView(TextView(activity).apply {
            text = "📞 ${c.callerNick} is calling"
            setTextColor(Color.parseColor("#E8EAF0"))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        row.addView(action(activity, "Join") {
            dismiss(c.code)
            Calls.join(activity, c.code)
        })
        row.addView(action(activity, "Decline") {
            dismiss(c.code)
            CoroutineScope(Dispatchers.Main).launch {
                runCatching { ApiClient.declineCall(c.code) }
            }
        })

        val lp = FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            val m = (10 * d).toInt()
            setMargins(m, m, m, m)
        }
        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        content.addView(row, lp)
        bar = row
    }

    private fun action(activity: Activity, label: String, onClick: () -> Unit): Button =
        Button(ContextThemeWrapper(activity, R.style.HeaderAction), null, 0).apply {
            text = label
            setTextColor(Color.parseColor(if (label == "Join") "#5B8CFF" else "#8A93A6"))
            setOnClickListener { onClick() }
        }
}
