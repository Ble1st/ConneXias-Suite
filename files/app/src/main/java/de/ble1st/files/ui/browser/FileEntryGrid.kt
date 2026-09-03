package de.ble1st.files.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.ble1st.files.data.fs.FileEntry
import de.ble1st.files.util.FileCategory

/**
 * Grid-Variante von [FileEntryRow] für dieselbe Ordnerliste — bewusst schlanke MVP-Fassung:
 * Öffnen/Auswählen wie in der Listenansicht (Tap/Long-Press), aber ohne eigenes Pro-Zelle-Menü
 * (die Selektions-Toolbar oben deckt Kopieren/Verschieben/Löschen/Teilen bereits ab, ein
 * zusätzliches Kontextmenü pro Kachel wäre für den Start reiner Mehraufwand ohne neuen Nutzen).
 * Miniaturbilder nur für [FileCategory.IMAGE] über Coil — Video-Thumbnails bräuchten einen
 * eigenen Frame-Decoder (Coils `coil-video`-Artefakt), das ist ein Ausbauschritt, kein Tag-1-Bedarf;
 * Videos zeigen bis dahin nur ihr generisches Icon, wie jeder andere nicht-Bild-Typ auch.
 */
@Composable
fun FileEntryGrid(
    entries: List<FileEntry>,
    selectedPaths: Set<String>,
    isSelectionMode: Boolean,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 96.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
    ) {
        items(entries, key = { it.file.path }) { entry ->
            FileGridCell(
                entry = entry,
                isSelected = entry.file.path in selectedPaths,
                isSelectionMode = isSelectionMode,
                onClick = { onClick(entry) },
                onLongClick = { onLongClick(entry) },
            )
        }
    }
}

@Composable
private fun FileGridCell(
    entry: FileEntry,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .padding(4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                if (entry.category == FileCategory.IMAGE) {
                    AsyncImage(
                        model = entry.file,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = iconFor(entry),
                            contentDescription = null,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                if (isSelectionMode && isSelected) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(4.dp).align(Alignment.TopEnd),
                    )
                }
            }
            Text(
                text = entry.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun iconFor(entry: FileEntry): ImageVector = when (entry.category) {
    FileCategory.FOLDER -> Icons.Filled.Folder
    FileCategory.IMAGE -> Icons.Filled.Description // wird nie erreicht, s. FileEntryGrid-Aufrufer
    FileCategory.VIDEO -> Icons.Filled.VideoFile
    FileCategory.AUDIO -> Icons.Filled.AudioFile
    FileCategory.TEXT -> Icons.Filled.Description
    FileCategory.ARCHIVE -> Icons.Filled.FolderZip
    FileCategory.APK, FileCategory.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}
