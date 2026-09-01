package de.ble1st.files.ui.webdav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import de.ble1st.files.data.webdav.WebDavEntry
import de.ble1st.files.util.FileCategory
import de.ble1st.files.util.formatFileSize
import java.text.DateFormat
import java.util.Date

/** Parallel zu [de.ble1st.files.ui.browser.FileEntryRow], aber für [WebDavEntry] statt einem
 * lokalen `java.io.File`-basierten Eintrag — WebDAV liefert nur Name/Größe/Zeitstempel, kein
 * `File`-Objekt, deshalb keine gemeinsame Basis. */
@Composable
fun WebDavEntryRow(
    entry: WebDavEntry,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(subtitleFor(entry)) },
        leadingContent = { Icon(iconFor(entry), contentDescription = null) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Mehr")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    if (!entry.isDirectory) {
                        DropdownMenuItem(text = { Text("Herunterladen") }, onClick = { menuExpanded = false; onDownload() })
                    }
                    DropdownMenuItem(text = { Text("Umbenennen") }, onClick = { menuExpanded = false; onRename() })
                    DropdownMenuItem(text = { Text("Löschen") }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

private fun subtitleFor(entry: WebDavEntry): String {
    val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.lastModifiedMillis))
    return if (entry.isDirectory) date else "${formatFileSize(entry.sizeBytes)} · $date"
}

private fun iconFor(entry: WebDavEntry): ImageVector = when (entry.category) {
    FileCategory.FOLDER -> Icons.Filled.Folder
    FileCategory.IMAGE -> Icons.Filled.Image
    FileCategory.VIDEO -> Icons.Filled.VideoFile
    FileCategory.TEXT -> Icons.Filled.Description
    FileCategory.ARCHIVE -> Icons.Filled.FolderZip
    FileCategory.AUDIO -> Icons.Filled.AudioFile
    FileCategory.APK, FileCategory.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}
