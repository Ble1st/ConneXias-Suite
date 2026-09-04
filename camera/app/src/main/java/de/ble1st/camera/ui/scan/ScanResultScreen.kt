package de.ble1st.camera.ui.scan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ble1st.camera.R
import de.ble1st.camera.data.scan.ScanResultHolder
import kotlinx.coroutines.launch

/**
 * Ergebnis-Bildschirm nach einem erfolgreichen Scan (s. `CaptureScreen`s ScanContract-Launcher) —
 * zeigt nur den rohen dekodierten Text plus Öffnen/Kopieren/Teilen, kein automatisches Handeln
 * (z. B. sofortiges Öffnen einer erkannten URL): derselbe "erst sichtbar machen, dann bewusst
 * bestätigen"-Schritt wie bei Wardens ChildVPN-QR-Scan, hier ohne Schlüsselmaterial aber mit
 * demselben Grundgedanken — ein Scan kann alles Mögliche enthalten (Phishing-Link, Wallet-Adresse,
 * beliebiger Text), automatisches Aufrufen wäre riskant.
 */
@Composable
fun ScanResultScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var scannedText by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val copiedMessage = stringResource(id = R.string.scan_result_copied)

    // Einmaliges Konsumieren statt Beobachten des StateFlows (s. ScanResultHolder-Klassendoc) —
    // dieser Bildschirm existiert nur für genau einen Scan, ein späteres erneutes Sammeln desselben
    // Werts (z. B. nach Konfigurationsänderung) darf den bereits gezeigten Text nicht verlieren,
    // deshalb lokaler Compose-State statt direktem `collectAsState()`.
    LaunchedEffect(Unit) { scannedText = ScanResultHolder.consume() }

    val text = scannedText
    val url = text?.let { parseAsUrl(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.scan_result_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.content_desc_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        if (text == null) return@Scaffold
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth().weight(1f),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    text = text,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (url != null) {
                    Button(onClick = { openUrl(context, url) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(id = R.string.scan_result_action_open))
                    }
                }
                OutlinedButton(
                    onClick = {
                        copyToClipboard(context, text)
                        scope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(id = R.string.scan_result_action_copy)) }
                OutlinedButton(onClick = { shareText(context, text) }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(id = R.string.scan_result_action_share))
                }
            }
        }
    }
}

/** Nur `http(s)://`-Links bekommen den "Öffnen"-Knopf — ein beliebiges Scan-Ergebnis (reiner Text,
 * eine `WIFI:`/`MECARD:`-Payload, o. Ä.) hat keinen sinnvollen `ACTION_VIEW`-Handler und würde sonst
 * nur zu "Keine App gefunden" führen. */
private fun parseAsUrl(text: String): Uri? {
    val uri = runCatching { Uri.parse(text) }.getOrNull() ?: return null
    return if (uri.scheme == "http" || uri.scheme == "https") uri else null
}

private fun openUrl(context: Context, uri: Uri) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java)
    manager.setPrimaryClip(ClipData.newPlainText("Scan-Ergebnis", text))
}

private fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
