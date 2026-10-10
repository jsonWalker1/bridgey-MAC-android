package dev.bridgey.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * MD-7: "Continue on which Mac?" - shown only when several Macs can take a handoff (browser chip,
 * Share, Play Books tile). The handoff is sent to the Mac the user picks, never to another one;
 * dismissing sends nothing. Transparent, no history, not exported.
 */
class HandoffTargetActivity : Activity() {
    private var dialog: AlertDialog? = null

    override fun onDestroy() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pairing = (application as BridgeyApplication).pairing
        val isBook = intent.getStringExtra(EXTRA_TYPE) == TYPE_BOOK
        val feature = if (isBook) FeatureApplicability.Feature.BOOKS_HANDOFF else FeatureApplicability.Feature.WEB_HANDOFF
        val macs = pairing.targets(feature)
        if (macs.isEmpty()) {
            Toast.makeText(this, "No Mac available — nothing sent", Toast.LENGTH_SHORT).show()
            return finish()
        }
        val origin = intent.getStringExtra(EXTRA_ORIGIN).orEmpty()
        dialog = AlertDialog.Builder(this)
            .setTitle(if (isBook) "Continue reading on" else "Continue on")
            .setItems(macs.map { it.name }.toTypedArray()) { _, index ->
                val deviceId = macs[index].deviceId
                if (isBook) {
                    val payload = intent.getStringExtra(EXTRA_VALUE) ?: return@setItems
                    BooksHandoff.send(this, deviceId, BookPlan(payload, intent.getBooleanExtra(EXTRA_HAS_POSITION, false),
                        intent.getBooleanExtra(EXTRA_HAS_QUOTE, false)), origin)
                } else {
                    val url = intent.getStringExtra(EXTRA_VALUE) ?: return@setItems
                    val kind = runCatching { WebHandoffKind.valueOf(intent.getStringExtra(EXTRA_KIND).orEmpty()) }
                        .getOrDefault(WebHandoffKind.URL_ONLY)
                    WebHandoff.send(this, deviceId, WebHandoffPlan(url, kind), origin)
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { finish() }
            .show()
    }

    companion object {
        private const val EXTRA_TYPE = "dev.bridgey.android.extra.HANDOFF_TYPE"
        private const val EXTRA_VALUE = "dev.bridgey.android.extra.HANDOFF_VALUE"
        private const val EXTRA_KIND = "dev.bridgey.android.extra.HANDOFF_KIND"
        private const val EXTRA_HAS_POSITION = "dev.bridgey.android.extra.HANDOFF_HAS_POSITION"
        private const val EXTRA_HAS_QUOTE = "dev.bridgey.android.extra.HANDOFF_HAS_QUOTE"
        private const val EXTRA_ORIGIN = "dev.bridgey.android.extra.HANDOFF_ORIGIN"
        private const val TYPE_WEB = "web"
        private const val TYPE_BOOK = "book"

        internal fun chooseForWeb(context: Context, plan: WebHandoffPlan, origin: String) =
            context.startActivity(intent(context) {
                putExtra(EXTRA_TYPE, TYPE_WEB).putExtra(EXTRA_VALUE, plan.url).putExtra(EXTRA_KIND, plan.kind.name)
                    .putExtra(EXTRA_ORIGIN, origin)
            })

        /** [launch] lets the Quick Settings tile collapse the shade so the chooser is not hidden behind it. */
        internal fun chooseForBook(context: Context, plan: BookPlan, origin: String, launch: (Intent) -> Unit = context::startActivity) =
            launch(intent(context) {
                putExtra(EXTRA_TYPE, TYPE_BOOK).putExtra(EXTRA_VALUE, plan.payload)
                    .putExtra(EXTRA_HAS_POSITION, plan.hasPosition).putExtra(EXTRA_HAS_QUOTE, plan.hasQuote)
                    .putExtra(EXTRA_ORIGIN, origin)
            })

        private fun intent(context: Context, extras: Intent.() -> Unit): Intent =
            Intent(context, HandoffTargetActivity::class.java).apply(extras)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
