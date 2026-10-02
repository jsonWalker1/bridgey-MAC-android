package dev.bridgey.android

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * BOOKS HANDOFF ALPHA - Quick Settings tile "Continue reading on Mac". Tapping it reads the reader
 * app behind the shade (Web Handoff accessibility service, Play Books only) and hands the book
 * position to [BooksHandoff.perform]. Active only while a Mac with Web links is connected.
 */
class BooksHandoffTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val app = application as BridgeyApplication
        if (!app.isPrimaryUser) { qsTile?.apply { state = Tile.STATE_UNAVAILABLE; updateTile() }; return }
        listening?.cancel()
        listening = scope.launch {
            combine(app.pairing.state, app.pairing.remoteFeatures) { _, _ -> Unit }.collect {
                qsTile?.apply {
                    val ready = WebHandoff.macConnected(app)
                    state = if (ready) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
                    label = "Continue reading"
                    if (Build.VERSION.SDK_INT >= 29) subtitle = if (ready) "on Mac" else "Mac not connected"
                    updateTile()
                }
            }
        }
    }

    override fun onStopListening() { listening?.cancel(); listening = null; super.onStopListening() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    override fun onClick() {
        super.onClick()
        val service = WebHandoffPocService.current?.get()
        val source = runCatching { service?.readerSource() }.getOrNull()
        if (service == null) {
            BooksHandoff.toast(applicationContext, "Turn on Bridgey Web Handoff in Accessibility settings to continue reading")
            return
        }
        BooksHandoff.perform(this, source, origin = "tile")
    }
}
