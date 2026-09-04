package de.ble1st.files.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import de.ble1st.files.R
import de.ble1st.files.data.fileops.ConflictPolicy
import de.ble1st.files.data.fs.FileEntry
import de.ble1st.files.data.fs.LocalFileSystem
import de.ble1st.files.util.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ein einziger Text-Eingabe-Dialog für Neuer-Ordner/Neue-Datei/Umbenennen/Komprimieren — alle vier
 * sind strukturell "ein Name rein, ein Ergebnis raus", nur mit unterschiedlichem Titel/Vorbelegung.
 */
@Composable
fun NameInputDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.action_cancel)) }
        },
    )
}

/** Seit Einführung des Papierkorbs (s. `data/trash/TrashEntry.kt`) verschiebt "Löschen" im
 * normalen Datei-Browser nur noch — der Text macht das explizit, sonst wäre die Bestätigung
 * irreführend ("endgültig", obwohl es das nicht mehr ist). Endgültiges Löschen gibt es weiterhin,
 * aber nur noch im Papierkorb-Bildschirm selbst (`ui/trash/TrashScreen.kt`), auf bereits dorthin
 * verschobenen Einträgen. */
@Composable
fun ConfirmDeleteDialog(entries: List<FileEntry>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val message = if (entries.size == 1) {
        stringResource(id = R.string.dialog_trash_message_one, entries.first().name)
    } else {
        pluralStringResource(R.plurals.dialog_trash_message_many, entries.size, entries.size)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.dialog_trash_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(id = R.string.action_move)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.action_cancel)) }
        },
    )
}

/**
 * Zeigt die Namen an, die im Zielordner schon existieren, und lässt den Nutzer einmalig für den
 * ganzen Einfüge-Vorgang entscheiden — kein Pro-Datei-Wizard, das wäre bei vielen Konflikten mehr
 * Klick-Overhead als Nutzen. "Beide behalten" entspricht dem bisherigen Auto-Umbenennen-Verhalten.
 */
@Composable
fun ConflictResolutionDialog(
    conflictingNames: List<String>,
    onResolve: (ConflictPolicy) -> Unit,
    onDismiss: () -> Unit,
) {
    val message = if (conflictingNames.size == 1) {
        stringResource(id = R.string.dialog_conflict_message_one, conflictingNames.first())
    } else {
        pluralStringResource(R.plurals.dialog_conflict_message_many, conflictingNames.size, conflictingNames.size) +
            "\n" + conflictingNames.joinToString("\n") { "• $it" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.dialog_conflict_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onResolve(ConflictPolicy.OVERWRITE) }) {
                Text(stringResource(id = R.string.action_overwrite))
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { onResolve(ConflictPolicy.SKIP) }) {
                    Text(stringResource(id = R.string.action_skip))
                }
                TextButton(onClick = { onResolve(ConflictPolicy.KEEP_BOTH) }) {
                    Text(stringResource(id = R.string.action_keep_both))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.action_cancel)) }
            }
        },
    )
}

@Composable
fun PropertiesDialog(entry: FileEntry, onDismiss: () -> Unit) {
    var totalSize by remember(entry) { mutableLongStateOf(entry.sizeBytes) }
    var itemCount by remember(entry) { mutableStateOf<Int?>(null) }
    if (entry.isDirectory) {
        LaunchedEffect(entry) {
            totalSize = withContext(Dispatchers.IO) { LocalFileSystem.sizeOf(entry.file) }
            itemCount = withContext(Dispatchers.IO) { LocalFileSystem.countFiles(entry.file) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.name) },
        text = {
            Column {
                Text(stringResource(id = R.string.properties_path, entry.file.path))
                Text(stringResource(id = R.string.properties_size, formatFileSize(totalSize)))
                itemCount?.let { Text(stringResource(id = R.string.properties_item_count, it)) }
                Text(
                    stringResource(
                        id = R.string.properties_hidden,
                        stringResource(id = if (entry.isHidden) R.string.value_yes else R.string.value_no),
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.action_close)) }
        },
    )
}
