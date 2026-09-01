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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
fun ConfirmDeleteDialog(entries: List<FileEntry>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val message = if (entries.size == 1) {
        "„${entries.first().name}“ endgültig löschen?"
    } else {
        "${entries.size} Einträge endgültig löschen?"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Löschen") },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Löschen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
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
        "„${conflictingNames.first()}“ existiert im Zielordner bereits."
    } else {
        "${conflictingNames.size} Namen existieren im Zielordner bereits:\n" +
            conflictingNames.joinToString("\n") { "• $it" }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name bereits vorhanden") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onResolve(ConflictPolicy.OVERWRITE) }) { Text("Überschreiben") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { onResolve(ConflictPolicy.SKIP) }) { Text("Überspringen") }
                TextButton(onClick = { onResolve(ConflictPolicy.KEEP_BOTH) }) { Text("Beide behalten") }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
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
                Text("Pfad: ${entry.file.path}")
                Text("Größe: ${formatFileSize(totalSize)}")
                itemCount?.let { Text("Enthaltene Dateien: $it") }
                Text("Versteckt: ${if (entry.isHidden) "Ja" else "Nein"}")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}
