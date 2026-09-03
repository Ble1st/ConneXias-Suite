package de.ble1st.files.ui.localshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ble1st.files.R
import de.ble1st.files.data.localshare.LocalShareStatus
import de.ble1st.files.util.QrCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Start/Stop-Umschalter für die WLAN/Hotspot-Freigabe des übergebenen Ordners. Bewusst schlanke
 * Fassung (s. README): nur Herunterladen, kein Upload-Empfang.
 *
 * Der Link steht als Text (Kopieren/Teilen an ein zweites eigenes Gerät) **und** seit 2026-09-03
 * als QR-Code da. Der Text allein reichte nur für den Fall "zweites eigenes Gerät, auf dem ich
 * ohnehin einen Messenger offen habe" — der eigentliche Anwendungsfall der Freigabe ist aber das
 * fremde Gerät im selben WLAN, auf das sich der Link gerade *nicht* per Chat schicken lässt und
 * dessen Nutzer sonst eine IP samt Port abtippen müsste.
 */
@Composable
fun LocalShareScreen(directory: File, onNavigateUp: () -> Unit) {
    val context = LocalContext.current
    val viewModel: LocalShareViewModel = viewModel()
    val status by viewModel.status.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(id = R.string.local_share_copied)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.local_share_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                text = stringResource(id = R.string.local_share_hint, directory.name.ifEmpty { directory.path }),
                style = MaterialTheme.typography.bodyMedium,
            )
            Column(modifier = Modifier.padding(top = 24.dp)) {
                when (val current = status) {
                    is LocalShareStatus.Running -> {
                        Text(current.url, style = MaterialTheme.typography.titleMedium)
                        QrCodeCard(content = current.url)
                        Row(modifier = Modifier.padding(top = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                copyToClipboard(context, current.url)
                                scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            }) { Text(stringResource(id = R.string.local_share_action_copy)) }
                            OutlinedButton(onClick = { shareText(context, current.url) }) {
                                Text(stringResource(id = R.string.local_share_action_share))
                            }
                        }
                        Button(onClick = viewModel::stop, modifier = Modifier.padding(top = 16.dp)) {
                            Text(stringResource(id = R.string.local_share_action_stop))
                        }
                    }
                    is LocalShareStatus.Failed -> {
                        Text(current.message, color = MaterialTheme.colorScheme.error)
                        Button(onClick = { viewModel.start(directory) }, modifier = Modifier.padding(top = 16.dp)) {
                            Text(stringResource(id = R.string.local_share_action_start))
                        }
                    }
                    LocalShareStatus.Stopped -> {
                        Button(onClick = { viewModel.start(directory) }) {
                            Text(stringResource(id = R.string.local_share_action_start))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Der QR-Code auf einer eigenen weißen Fläche — auch im Dunkelmodus, s. [QrCode]-Klassendoc. Die
 * weiße Fläche ist zugleich die "Quiet Zone", die der Code zum Erkanntwerden braucht.
 *
 * Erzeugt wird außerhalb der Composition ([produceState] auf [Dispatchers.Default]), abhängig von
 * Inhalt und Kantenlänge: die Bitmap-Allokation ist der teure Teil, und der Link ändert sich beim
 * Neustart der Freigabe (anderer Port).
 */
@Composable
private fun QrCodeCard(content: String) {
    val sizeDp = 220.dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizePx = with(density) { sizeDp.roundToPx() }
    val bitmap by produceState<android.graphics.Bitmap?>(null, content, sizePx) {
        value = withContext(Dispatchers.Default) { QrCode.encode(content, sizePx) }
    }
    val image = bitmap ?: return
    Surface(
        modifier = Modifier.padding(top = 16.dp),
        color = Color.White,
        shape = MaterialTheme.shapes.medium,
    ) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = stringResource(id = R.string.local_share_qr_description),
            modifier = Modifier.padding(12.dp).size(sizeDp),
            alignment = Alignment.Center,
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java)
    manager.setPrimaryClip(ClipData.newPlainText("Freigabe-Link", text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
