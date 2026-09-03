package de.ble1st.files.ui.localshare

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ble1st.files.R
import de.ble1st.files.data.localshare.LocalShareStatus
import kotlinx.coroutines.launch
import java.io.File

/**
 * Start/Stop-Umschalter für die WLAN/Hotspot-Freigabe des übergebenen Ordners. Bewusst schlanke
 * MVP-Fassung (s. README): nur Herunterladen (kein Upload-Empfang), nur ein Text-Link zum
 * Kopieren/Teilen statt eines QR-Codes — bei einer im selben WLAN erreichbaren URL ist Copy/Share
 * (z. B. per Chat an ein zweites eigenes Gerät) meist ohnehin praktischer als ein zusätzlich zu
 * scannender Code.
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
