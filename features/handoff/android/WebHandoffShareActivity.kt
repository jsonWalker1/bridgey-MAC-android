package dev.bridgey.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * WEB + BOOKS HANDOFF ALPHA - Share → "Continue on Mac". No UI. A shared URL or browser selection
 * goes to [WebHandoff]; a quote from a reader goes to [BooksHandoff] (see [shareRoute]).
 * Web: takes the shared URL (or selected text),
 * enriches it with browser context when the Web Handoff accessibility service is enabled and shows
 * the same page, and hands it to [WebHandoff.perform]. Basic URL sharing needs no accessibility.
 */
class WebHandoffShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            val url = sharedUrl(text)
            val service = WebHandoffPocService.current?.get()
            val sender = referrer?.takeIf { it.scheme == "android-app" }?.host
            val route = shareRoute(url != null, sender, runCatching { service?.browserOnScreen() }.getOrNull() == true)
            android.util.Log.i("BridgeyWebHandoff", "SHARE route=$route sender=${sender ?: "?"} service=${service != null} url=${url != null}")
            if (route == ShareRoute.BOOK) {
                // A quote from a reader: add title/position when that reader is open and readable.
                val reader = runCatching { service?.readerSource() }.getOrNull()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                BooksHandoff.perform(this, BookSource(
                    title = reader?.title ?: subject, chapter = reader?.chapter, page = reader?.page,
                    pages = reader?.pages, quote = text, app = reader?.app ?: sender,
                ), origin = "share")
            } else {
                val enriched = runCatching { service?.shareSource(url, text) }
                    .onFailure { android.util.Log.w("BridgeyWebHandoff", "SHARE context failed: ${it.javaClass.simpleName}") }
                    .getOrNull()
                WebHandoff.perform(this, enriched ?: url?.let { WebHandoffSource(it) }, origin = "share")
            }
        }
        finish()
    }
}
