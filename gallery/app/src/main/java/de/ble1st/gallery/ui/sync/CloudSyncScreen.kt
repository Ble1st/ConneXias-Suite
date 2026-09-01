package de.ble1st.gallery.ui.sync

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
import de.ble1st.gallery.data.webdav.WebDavClient
import de.ble1st.gallery.ui.GalleryViewModel
import kotlinx.coroutines.launch

/**
 * Ein-Wege-Cloud-Sicherung auf einen selbst gehosteten WebDAV-Server — Kontoformular + ein
 * "Jetzt sichern"-Knopf statt eines vollen Datei-Browsers (den hat ConneXias Files bereits, s.
 * [de.ble1st.gallery.data.sync.CloudSyncManager]-Klassendoc zur bewussten Ein-Wege-Beschränkung).
 */
@Composable
fun CloudSyncScreen(viewModel: GalleryViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val allItems by viewModel.allItems.collectAsState()
    val progress by CloudSyncManager.progress.collectAsState()
    val storedAccount = remember { WebDavAccountStore.get(context) }

    var baseUrl by remember { mutableStateOf(storedAccount?.baseUrl.orEmpty()) }
    var username by remember { mutableStateOf(storedAccount?.username.orEmpty()) }
    var password by remember { mutableStateOf(storedAccount?.password.orEmpty()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    val connectionOkMessage = stringResource(R.string.cloud_sync_test_ok)
    val connectionFailedMessage = stringResource(R.string.cloud_sync_test_failed)

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

            Column(modifier = Modifier.padding(top = 16.dp)) {
                OutlinedButton(
                    enabled = baseUrl.isNotBlank() && username.isNotBlank() && !testing,
                    onClick = {
                        WebDavAccountStore.save(context, WebDavAccount(baseUrl.trim(), username.trim(), password))
                        testing = true
                        statusMessage = null
                        scope.launch {
                            val result = WebDavClient.testConnection(WebDavAccount(baseUrl.trim(), username.trim(), password))
                            testing = false
                            statusMessage = if (result.isSuccess) connectionOkMessage else connectionFailedMessage
                        }
                    },
                ) { Text(stringResource(R.string.cloud_sync_save_and_test)) }

                if (testing) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }
                statusMessage?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }

                Button(
                    enabled = baseUrl.isNotBlank() && username.isNotBlank() && !progress.running,
                    onClick = {
                        val account = WebDavAccount(baseUrl.trim(), username.trim(), password)
                        WebDavAccountStore.save(context, account)
                        scope.launch { CloudSyncManager.sync(context, account, allItems) }
                    },
                    modifier = Modifier.padding(top = 16.dp),
                ) { Text(stringResource(R.string.cloud_sync_start)) }

                if (progress.running || progress.done) {
                    val fraction = if (progress.total == 0) 1f else (progress.uploaded + progress.failed) / progress.total.toFloat()
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                    Text(
                        stringResource(R.string.cloud_sync_progress, progress.uploaded, progress.total, progress.failed),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    progress.currentName?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
                }

                OutlinedButton(
                    onClick = {
                        WebDavAccountStore.clear(context)
                        CloudSyncState.reset(context)
                        CloudSyncManager.resetProgress()
                        baseUrl = ""; username = ""; password = ""; statusMessage = null
                    },
                    modifier = Modifier.padding(top = 24.dp),
                ) { Text(stringResource(R.string.cloud_sync_remove_account)) }
            }
        }
    }
}
