package de.ble1st.gallery.ui.sync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.ble1st.gallery.R
import de.ble1st.gallery.data.sync.CloudSyncManager
import de.ble1st.gallery.data.sync.CloudSyncState
import de.ble1st.gallery.data.webdav.WebDavAccount
import de.ble1st.gallery.data.webdav.WebDavAccountStore
import de.ble1st.gallery.data.sync.CloudSyncWorker
import de.ble1st.gallery.data.sync.SyncProgress
import de.ble1st.gallery.data.webdav.WebDavClient
import kotlinx.coroutines.launch

/**
 * Ein-Wege-Cloud-Sicherung auf einen selbst gehosteten WebDAV-Server — Kontoformular + ein
 * "Jetzt sichern"-Knopf statt eines vollen Datei-Browsers (den hat ConneXias Files bereits, s.
 * [de.ble1st.gallery.data.sync.CloudSyncManager]-Klassendoc zur bewussten Ein-Wege-Beschränkung).
 */
@Composable
fun CloudSyncScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by CloudSyncManager.progress(context).collectAsState(SyncProgress())
    // Als Zustand und nicht als einmalig gelesener Wert: nach einem erfolgreichen
    // "Verbindung testen" muss der Sicherungs-Knopf sofort freigegeben werden (er hängt jetzt am
    // gespeicherten Konto, s. Kommentar dort).
    var storedAccount by remember { mutableStateOf(WebDavAccountStore.get(context)) }

    var baseUrl by remember { mutableStateOf(storedAccount?.baseUrl.orEmpty()) }
    var username by remember { mutableStateOf(storedAccount?.username.orEmpty()) }
    var password by remember { mutableStateOf(storedAccount?.password.orEmpty()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    // Leeres Passwort war zuvor akzeptiert (isValid prüfte es nicht) — ein WebDAV-Server, der
    // wirklich anonymen Zugriff erlaubt, ist der seltene Rand-Fall, ein versehentlich leer
    // gelassenes Feld der häufige.
    val isValid = baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    val connectionOkMessage = stringResource(R.string.cloud_sync_test_ok)
    val connectionFailedMessage = stringResource(R.string.cloud_sync_test_failed)

    // Der Sicherungs-Auftrag läuft als Foreground-Worker mit Fortschritts-Benachrichtigung
    // (s. CloudSyncWorker). Ohne POST_NOTIFICATIONS (ab API 33) liefe er zwar weiter, aber
    // unsichtbar — bei einem Vorgang, der Minuten bis Stunden dauern kann, ist das genau die
    // Anzeige, die der Nutzer braucht. Einmalige, nicht blockierende Anfrage beim Öffnen dieses
    // Bildschirms; ein Ablehnen verhindert die Sicherung nicht.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cloud_sync_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.cloud_sync_explanation))
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                singleLine = true,
                label = { Text(stringResource(R.string.cloud_sync_server_url)) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                singleLine = true,
                label = { Text(stringResource(R.string.cloud_sync_username)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                label = { Text(stringResource(R.string.cloud_sync_password)) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            // Cleartext bleibt app-weit erlaubt (s. network_security_config.xml-Kommentar — viele
            // selbst gehostete Server im Heimnetz haben kein gültiges TLS-Zertifikat), aber ein
            // "http://"-Server wird nicht mehr stillschweigend akzeptiert.
            if (baseUrl.isNotBlank() && !baseUrl.startsWith("https://", ignoreCase = true)) {
                Text(
                    "Achtung: kein HTTPS — Zugangsdaten werden unverschlüsselt übertragen.",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Column(modifier = Modifier.padding(top = 16.dp)) {
                OutlinedButton(
                    enabled = isValid && !testing,
                    onClick = {
                        testing = true
                        statusMessage = null
                        val account = WebDavAccount(baseUrl.trim(), username.trim(), password)
                        scope.launch {
                            val result = WebDavClient.testConnection(account)
                            testing = false
                            // Erst nach erfolgreichem Test persistieren (vorher wurden die
                            // Zugangsdaten sofort beim Tippen auf den Button gespeichert, unabhängig
                            // vom Testergebnis — ein Tippfehler im Passwort landete so unbemerkt
                            // dauerhaft im verschlüsselten Storage).
                            if (result.isSuccess) {
                                WebDavAccountStore.save(context, account)
                                storedAccount = account
                            }
                            statusMessage = if (result.isSuccess) connectionOkMessage else connectionFailedMessage
                        }
                    },
                ) { Text(stringResource(R.string.cloud_sync_save_and_test)) }

                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }
                statusMessage?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }

                // analyse.md (2. Durchgang, Mittel): "Jetzt sichern" persistierte die aktuellen
                // Feldwerte früher immer, ungeachtet eines Tests — ein Tippfehler im Passwort
                // landete so unbemerkt dauerhaft im verschlüsselten Storage. Persistiert wird
                // ausschließlich über einen erfolgreichen "Verbindung testen"-Lauf.
                //
                // Seit die Sicherung als WorkManager-Auftrag läuft (2026-09-03), ist ein
                // gespeichertes Konto zusätzlich Voraussetzung: Zugangsdaten dürfen nicht als
                // Worker-Eingabedaten in die WorkManager-Datenbank wandern, der Worker liest sie
                // deshalb selbst aus dem verschlüsselten Speicher (s. CloudSyncWorker-Klassendoc).
                Button(
                    enabled = storedAccount != null && !progress.running,
                    onClick = { CloudSyncManager.startSync(context) },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text(stringResource(R.string.cloud_sync_start)) }
                if (storedAccount == null) {
                    Text(
                        stringResource(R.string.cloud_sync_requires_saved_account),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (progress.running || progress.done) {
                    val fraction = if (progress.total == 0) 1f else (progress.uploaded + progress.failed) / progress.total.toFloat()
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                    Text(
                        stringResource(R.string.cloud_sync_progress, progress.uploaded, progress.total, progress.failed),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    progress.currentName?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
                }
                if (progress.running) {
                    OutlinedButton(
                        onClick = { CloudSyncManager.cancel(context) },
                        modifier = Modifier.padding(top = 8.dp),
                    ) { Text(stringResource(R.string.action_cancel)) }
                }
                // Der Auftrag lief, hat aber nichts hochgeladen — der Nutzer muss erfahren,
                // warum, sonst wirkt "Jetzt sichern" schlicht wirkungslos.
                when (progress.failure) {
                    CloudSyncWorker.SyncFailure.NO_ACCOUNT ->
                        Text(
                            stringResource(R.string.cloud_sync_requires_saved_account),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    CloudSyncWorker.SyncFailure.CANCELLED ->
                        Text(
                            stringResource(R.string.cloud_sync_cancelled),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    null -> Unit
                }

                OutlinedButton(
                    onClick = {
                        CloudSyncManager.cancel(context)
                        WebDavAccountStore.clear(context)
                        CloudSyncState.reset(context)
                        CloudSyncManager.resetProgress(context)
                        storedAccount = null
                        baseUrl = ""; username = ""; password = ""; statusMessage = null
                    },
                    modifier = Modifier.padding(top = 24.dp),
                ) { Text(stringResource(R.string.cloud_sync_remove_account)) }
            }
        }
    }
}
