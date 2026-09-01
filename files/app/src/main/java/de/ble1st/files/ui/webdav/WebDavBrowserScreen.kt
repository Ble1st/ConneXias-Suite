package de.ble1st.files.ui.webdav

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.ble1st.files.data.webdav.WebDavAccount
import de.ble1st.files.data.webdav.WebDavEntry
import de.ble1st.files.ui.browser.NameInputDialog
import de.ble1st.files.ui.viewer.ViewerCategory
import de.ble1st.files.util.FileCategory
import java.io.File

/**
 * Durchsuchen eines konfigurierten WebDAV-Servers — Aufbau bewusst schlanker als
 * [de.ble1st.files.ui.browser.FileBrowserScreen] (keine Zwischenablage, keine Mehrfachauswahl):
 * das v1-Scope für Netzwerkspeicher deckt Durchsuchen/Herunterladen/Hochladen/Umbenennen/Löschen/
 * Neuer-Ordner ab.
 */
@Composable
fun WebDavBrowserScreen(
    account: WebDavAccount,
    path: String,
    onNavigateUp: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenViewer: (File, ViewerCategory) -> Unit,
) {
    val context = LocalContext.current
    val application = context.applicationContext as android.app.Application
    val viewModel: WebDavBrowserViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "${account.id}:$path",
        factory = WebDavBrowserViewModel.Factory(application, account, path),
    )
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.uploadFrom(uri)
    }

    LaunchedEffect(state.statusMessage) {
        state.statusMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeStatusMessage()
        }
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (path == "/") account.label else path.substringAfterLast('/')) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { uploadLauncher.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.UploadFile, contentDescription = "Hochladen")
                    }
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Aktualisieren")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.showDialog(WebDavDialog.NewFolder) }) {
                Icon(Icons.Filled.CreateNewFolder, contentDescription = "Neuer Ordner")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                state.entries.isEmpty() && state.errorMessage == null ->
                    Text("Leer", modifier = Modifier.align(Alignment.Center))
                else -> LazyColumn {
                    items(state.entries) { entry ->
                        WebDavEntryRow(
                            entry = entry,
                            onClick = { handleOpen(entry, state.path, viewModel, onOpenFolder, onOpenViewer) },
                            onRename = { viewModel.showDialog(WebDavDialog.Rename(entry)) },
                            onDelete = { viewModel.showDialog(WebDavDialog.ConfirmDelete(entry)) },
                            onDownload = { viewModel.downloadToDownloads(entry) },
                        )
                    }
                }
            }
        }
    }

    when (val dialog = state.pendingDialog) {
        WebDavDialog.NewFolder -> NameInputDialog(
            title = "Neuer Ordner",
            initialValue = "",
            confirmLabel = "Erstellen",
            onConfirm = viewModel::createFolder,
            onDismiss = { viewModel.showDialog(null) },
        )
        is WebDavDialog.Rename -> NameInputDialog(
            title = "Umbenennen",
            initialValue = dialog.entry.name,
            confirmLabel = "Umbenennen",
            onConfirm = { newName -> viewModel.rename(dialog.entry, newName) },
            onDismiss = { viewModel.showDialog(null) },
        )
        is WebDavDialog.ConfirmDelete -> WebDavConfirmDeleteDialog(
            entry = dialog.entry,
            onConfirm = { viewModel.delete(dialog.entry) },
            onDismiss = { viewModel.showDialog(null) },
        )
        null -> Unit
    }
}

private fun handleOpen(
    entry: WebDavEntry,
    currentPath: String,
    viewModel: WebDavBrowserViewModel,
    onOpenFolder: (String) -> Unit,
    onOpenViewer: (File, ViewerCategory) -> Unit,
) {
    if (entry.isDirectory) {
        onOpenFolder(entry.path)
        return
    }
    when (entry.category) {
        FileCategory.IMAGE -> viewModel.downloadToCache(entry) { file -> onOpenViewer(file, ViewerCategory.IMAGE) }
        FileCategory.VIDEO -> viewModel.downloadToCache(entry) { file -> onOpenViewer(file, ViewerCategory.VIDEO) }
        // TEXT/OTHER: kein Inline-Editor für WebDAV-Quellen — ein "Speichern" dort würde nur die
        // lokale Kopie überschreiben und fälschlich Synchronität mit dem Server vortäuschen.
        else -> viewModel.downloadToDownloads(entry)
    }
}
