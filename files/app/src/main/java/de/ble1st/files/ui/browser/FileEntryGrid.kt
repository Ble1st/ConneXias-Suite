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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.ble1st.files.data.fs.FileEntry
import de.ble1st.files.util.FileCategory
import de.ble1st.files.util.VideoThumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Grid-Variante von [FileEntryRow] für dieselbe Ordnerliste — bewusst schlanke MVP-Fassung:
 * Öffnen/Auswählen wie in der Listenansicht (Tap/Long-Press), aber ohne eigenes Pro-Zelle-Menü
 * (die Selektions-Toolbar oben deckt Kopieren/Verschieben/Löschen/Teilen bereits ab, ein
 * zusätzliches Kontextmenü pro Kachel wäre für den Start reiner Mehraufwand ohne neuen Nutzen).
 * Miniaturbilder für [FileCategory.IMAGE] über Coil und — seit 2026-09-03 — für
 * [FileCategory.VIDEO] über [VideoThumbnails] (Plattform-`MediaMetadataRetriever` statt eines
 * zusätzlichen `coil-video`-Artefakts, s. dortiges Klassendoc). Jeder andere Typ zeigt weiterhin
 * sein generisches Icon.
 *
 * Wird die Liste als Suchergebnis angezeigt (rekursive Suche, s.
 * [de.ble1st.files.data.search.RecursiveSearch]), liefert [pathLabelFor] je Treffer den Ordner, in
 * dem er liegt — ohne diese Zeile stünden gleichnamige Treffer aus verschiedenen Ordnern
 * ununterscheidbar nebeneinander.
 */
@Composable
fun FileEntryGrid(
    entries: List<FileEntry>,
    selectedPaths: Set<String>,
    isSelectionMode: Boolean,
    onClick: (FileEntry) -> Unit,
    onLongClick: (FileEntry) -> Unit,
    pathLabelFor: (FileEntry) -> String? = { null },
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
                pathLabel = pathLabelFor(entry),
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
    pathLabel: String?,
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
                } else if (entry.category == FileCategory.VIDEO) {
                    // produceState statt eines LaunchedEffect + eigener State-Variable: der
                    // Schlüssel ist der Dateipfad, ein Recycling der Kachel auf eine andere Datei
                    // verwirft das alte Bild damit automatisch. Solange nichts geladen ist (oder
                    // sich kein Frame gewinnen ließ), bleibt es beim generischen Video-Icon —
                    // dieselbe Darstellung wie vor diesem Feature, kein leerer Platzhalter.
                    val thumbnail by produceState<android.graphics.Bitmap?>(null, entry.file.path) {
                        value = withContext(Dispatchers.IO) { VideoThumbnails.get(entry.file) }
                    }
                    val bitmap = thumbnail
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        PlaceholderIcon(entry)
                    }
                } else {
                    PlaceholderIcon(entry)
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
            if (pathLabel != null) {
                Text(
                    text = pathLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlaceholderIcon(entry: FileEntry) {
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

private fun iconFor(entry: FileEntry): ImageVector = when (entry.category) {
    FileCategory.FOLDER -> Icons.Filled.Folder
    FileCategory.IMAGE -> Icons.Filled.Description // nur erreichbar, wenn Coil nichts liefert
    FileCategory.VIDEO -> Icons.Filled.VideoFile
    FileCategory.AUDIO -> Icons.Filled.AudioFile
    FileCategory.TEXT -> Icons.Filled.Description
    FileCategory.ARCHIVE -> Icons.Filled.FolderZip
    FileCategory.APK, FileCategory.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}
