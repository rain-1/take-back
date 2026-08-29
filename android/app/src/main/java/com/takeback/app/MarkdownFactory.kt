package com.takeback.app

import android.content.Context
import android.text.util.Linkify
import io.noties.markwon.Markwon
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
        .build()
