package dev.bridgey.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * BRIDGEY SMB SERVER FEASIBILITY POC - isolated, throwaway. See SmbPocServer.kt's doc comment.
 * Launch directly for testing (never linked from any production UI):
 *   adb shell am start -n dev.bridgey.android/.SmbPocActivity
 * Delete this file + the <activity> entry in AndroidManifest.xml to remove the UI half of the POC.
 */
internal class SmbPocActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SmbPocScreen(
                        shareDirPath = SmbPocServer.shareDirectory(applicationContext).absolutePath,
                        onStart = { startService(Intent(this, SmbPocService::class.java)) },
                        onStop = { startService(Intent(this, SmbPocService::class.java).setAction(SmbPocService.ACTION_STOP)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SmbPocScreen(shareDirPath: String, onStart: () -> Unit, onStop: () -> Unit) {
    var running by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Bridgey SMB POC", style = MaterialTheme.typography.headlineSmall)
        Text("Feasibility spike only - not a shipping feature.")
        Text("Share: \\\\${SmbPocServer.SERVER_NAME}:${SmbPocServer.SMB_PORT}\\${SmbPocServer.SHARE_NAME}")
        Text("User: ${SmbPocServer.USERNAME}  Pass: ${SmbPocServer.PASSWORD}")
        Text("Directory: $shareDirPath")
        Button(onClick = { onStart(); running = true }) { Text("Start SMB server") }
        Button(onClick = { onStop(); running = false }) { Text("Stop SMB server") }
        Text(if (running) "Requested: running" else "Requested: stopped")
    }
}
