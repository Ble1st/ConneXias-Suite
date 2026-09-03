package de.ble1st.files.ui.trash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ble1st.files.R
import de.ble1st.files.data.trash.TrashEntry
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Papierkorb-Bildschirm — Liste aller über [de.ble1st.files.ui.browser.FileBrowserViewModel
 * .deleteEntries] verschobenen Einträge, mit Wiederherstellen/Endgültig-löschen pro Eintrag und
 * einer Leeren-Aktion für den gesamten Papierkorb. Kein eigener Auswahlmodus wie im normalen
 * Datei-Browser (Mehrfachauswahl) — bei einer Papierkorb-typischen Handvoll Einträgen reicht die
 * Pro-Zeile-Aktion, s. `TrashViewModel`-Klassendoc zur bewusst schlanken MVP-Fassung.
 */
@Composable
fun TrashScreen(onNavigateUp: () -> Unit) {
    val viewModel: TrashViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(id = R.string.trash_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (uiState.entries.isNotEmpty()) {
                        IconButton(onClick = { viewModel.showDialog(TrashDialog.ConfirmEmpty) }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(id = R.string.trash_action_empty))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                uiState.entries.isEmpty() -> Text(
                    text = stringResource(id = R.string.trash_empty),
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyColumn {
                    items(uiState.entries, key = { it.id }) { entry ->
                        TrashRow(
                            entry = entry,
                            onRestore = { viewModel.restore(entry) },
                            onDeleteForever = { viewModel.showDialog(TrashDialog.ConfirmDeleteForever(entry)) },
                        )
                    }
                }
            }
        }
    }

    when (val dialog = uiState.pendingDialog) {
        is TrashDialog.ConfirmDeleteForever -> ConfirmDeleteForeverDialog(
            entry = dialog.entry,
            onConfirm = { viewModel.deleteForever(dialog.entry) },
            onDismiss = { viewModel.showDialog(null) },
        )
        TrashDialog.ConfirmEmpty -> ConfirmEmptyTrashDialog(
            count = uiState.entries.size,
            onConfirm = viewModel::emptyTrash,
            onDismiss = { viewModel.showDialog(null) },
        )
        null -> Unit
    }
}

@Composable
private fun TrashRow(entry: TrashEntry, onRestore: () -> Unit, onDeleteForever: () -> Unit) {
    val dateLabel = remember(entry.deletedAtMillis) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.deletedAtMillis))
    }
    ListItem(
        headlineContent = { Text(entry.originalName) },
        supportingContent = {
            Column {
                Text(entry.originalParentPath, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = stringResource(id = R.string.trash_deleted_on, dateLabel),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        leadingContent = {
            Icon(
                if (entry.isDirectory) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
            )
        },
        trailingContent = {
            Column {
                IconButton(onClick = onRestore) {
                    Icon(Icons.Filled.Restore, contentDescription = stringResource(id = R.string.trash_action_restore))
                }
                IconButton(onClick = onDeleteForever) {
                    Icon(Icons.Filled.DeleteForever, contentDescription = stringResource(id = R.string.trash_action_delete_forever))
                }
            }
        },
    )
}

@Composable
private fun ConfirmDeleteForeverDialog(entry: TrashEntry, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.trash_confirm_delete_forever_title)) },
        text = { Text(stringResource(id = R.string.trash_confirm_delete_forever_message, entry.originalName)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(id = R.string.trash_action_delete_forever)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.action_cancel)) } },
    )
}

@Composable
private fun ConfirmEmptyTrashDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.trash_confirm_empty_title)) },
        text = { Text(stringResource(id = R.string.trash_confirm_empty_message, count)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(id = R.string.trash_action_empty)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.action_cancel)) } },
    )
}
