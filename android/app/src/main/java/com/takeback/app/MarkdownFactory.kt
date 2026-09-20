package com.takeback.app

import android.content.Context
import android.content.ContextWrapper
import android.text.util.Linkify
import androidx.appcompat.app.AppCompatActivity
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolverDef
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.linkify.LinkifyPlugin

/**
 * The Markwon instance both chat screens render message bodies with.
 *
 * LinkifyPlugin is what makes bare URLs tappable: people type "www.example.com"
 * far more often than they type a full Markdown link, and without this those
 * arrived as inert grey text. WEB_URLS covers schemeless hosts too, matching the
 * web client's autoLink; EMAIL_ADDRESSES makes an address dialable into a mail
 * app. Deliberately NOT Linkify.ALL — that includes PHONE_NUMBERS, which turns
 * any longish run of digits (a call code, a version, an ID) into a phone link.
 */
fun Context.markwon(): Markwon =
    Markwon.builder(this)
        .usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES))
        // A take-back invite link opens the join dialog right here, rather than
        // the website in a browser.
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver { view, link ->
                    val code = ServerDialogs.inviteCodeOf(link)?.takeIf { link.contains("invite=") }
                    val activity = view.context.findActivity()
                    when {
                        code != null && activity != null -> ServerDialogs.join(activity, code)
                        // Resolving a link ends in ACTION_VIEW at whatever app
                        // claims its scheme, and in Markdown the SENDER picks the
                        // scheme: [look](something://…). The web client builds
                        // http/https links and nothing else (see renderMarkdown),
                        // so hold to the same rule here — plus the mailto: that
                        // LinkifyPlugin makes out of an address above.
                        isWebLink(link) -> LinkResolverDef().resolve(view, link)
                        else -> android.widget.Toast.makeText(
                            view.context, "That link isn't a web address.",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
        .build()

/** A link a message is allowed to send you to, by its scheme. */
private fun isWebLink(link: String): Boolean =
    when (link.substringBefore(':', "").lowercase()) {
        "http", "https", "mailto" -> true
        else -> false
    }

private fun android.content.Context.findActivity(): AppCompatActivity? {
    var c: android.content.Context? = this
    while (c is ContextWrapper) {
        if (c is AppCompatActivity) return c
        c = c.baseContext
    }
    return null
}
