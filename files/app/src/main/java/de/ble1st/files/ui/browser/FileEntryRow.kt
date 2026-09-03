package de.ble1st.files.ui.browser

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import de.ble1st.files.data.fs.FileEntry
import de.ble1st.files.util.FileCategory
import de.ble1st.files.util.formatFileSize
import java.text.DateFormat
import java.util.Date

@Composable
fun FileEntryRow(
    entry: FileEntry,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    trailingContent: @Composable (() -> Unit)? = null,
    /** Ordner, in dem der Eintrag liegt — gesetzt, wenn diese Zeile ein Treffer der rekursiven
     * Suche ist (s. [de.ble1st.files.data.search.RecursiveSearch]). Ohne diese Angabe stünden
     * gleichnamige Treffer aus verschiedenen Ordnern ununterscheidbar untereinander. */
    pathLabel: String? = null,
) {
    ListItem(
        headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                text = if (pathLabel != null) "$pathLabel · ${subtitleFor(entry)}" else subtitleFor(entry),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingContent = {
            if (isSelectionMode) {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
            } else {
                Icon(iconFor(entry), contentDescription = null)
            }
        },
        trailingContent = trailingContent,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

private fun subtitleFor(entry: FileEntry): String {
    val date = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.lastModifiedMillis))
    return if (entry.isDirectory) date else "${formatFileSize(entry.sizeBytes)} · $date"
}

private fun iconFor(entry: FileEntry): ImageVector = when (entry.category) {
    FileCategory.FOLDER -> Icons.Filled.Folder
    FileCategory.IMAGE -> Icons.Filled.Image
    FileCategory.VIDEO -> Icons.Filled.VideoFile
    FileCategory.AUDIO -> Icons.Filled.AudioFile
    FileCategory.TEXT -> Icons.Filled.Description
    FileCategory.ARCHIVE -> Icons.Filled.FolderZip
    FileCategory.APK, FileCategory.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}
