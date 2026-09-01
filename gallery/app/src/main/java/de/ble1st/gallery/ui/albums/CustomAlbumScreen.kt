package de.ble1st.gallery.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.ble1st.gallery.R
import de.ble1st.gallery.data.media.MediaItem
import de.ble1st.gallery.data.media.MediaType
import de.ble1st.gallery.ui.GalleryViewModel
import de.ble1st.gallery.util.MediaActions

/**
 * Grid eines benutzerdefinierten Albums — anders als [de.ble1st.gallery.ui.grid.MediaGridScreen]
 * KEINE "Von Gerät löschen"-Aktion, nur "Aus Album entfernen" (entfernt lediglich die Referenz in
 * [de.ble1st.gallery.data.album.CustomAlbumStore], die Aufnahme selbst bleibt unangetastet) —
 * andernfalls würde ein Nutzer, der ein Foto aus einem selbst erstellten Album entfernen will,
 * versehentlich die tatsächliche Aufnahme verlieren. Tippen öffnet den regulären Bild-/
 * Videobetrachter mit `ALL_BUCKET_ID`-Geschwister-Scoping statt eines albumspezifischen Pagers —
 * bewusste v1-Vereinfachung (s. README), ein eigener Album-Pager wäre ein separater Ausbauschritt.
 */
@Composable
fun CustomAlbumScreen(
    albumId: String,
    albumName: String,
    viewModel: GalleryViewModel,
    onNavigateUp: () -> Unit,
    onOpenViewer: (MediaItem) -> Unit,
    onAlbumDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val allItems by viewModel.allItems.collectAsState()
    val customAlbums by viewModel.customAlbums.collectAsState()
    val album = customAlbums.find { it.id == albumId }
    val items = remember(allItems, album) { viewModel.itemsForCustomAlbum(albumId) }

    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showDeleteAlbumConfirm by remember { mutableStateOf(false) }
    val selectionActive = selection.isNotEmpty()

    if (album == null) {
        // Album wurde zwischenzeitlich gelöscht (z. B. über einen zweiten offenen Tab dieses
        // Screens) — kein Absturz auf eine jetzt ungültige Referenz.
        onNavigateUp()
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionActive) {
                            pluralStringResource(R.plurals.selection_count, selection.size, selection.size)
                        } else {
                            albumName
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selectionActive) selection = emptySet() else onNavigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                    }
                },
                actions = {
                    if (selectionActive) {
                        IconButton(onClick = { MediaActions.share(context, items.filter { it.id in selection }.map { it.uri }) }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
                        }
                        IconButton(
                            onClick = {
                                viewModel.removeFromCustomAlbum(albumId, selection)
                                selection = emptySet()
                            },
                        ) {
                            Icon(Icons.Filled.PlaylistRemove, contentDescription = stringResource(R.string.album_remove_item))
                        }
                    } else {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.album_delete_title))
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.album_delete_title)) },
                                onClick = { menuExpanded = false; showDeleteAlbumConfirm = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.album_empty))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = padding,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.id }) { item ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .padding(1.dp)
                            .combinedClickable(
                                onClick = {
                                    if (selectionActive) {
                                        selection = if (item.id in selection) selection - item.id else selection + item.id
                                    } else {
                                        onOpenViewer(item)
                                    }
                                },
                                onLongClick = { selection = if (item.id in selection) selection - item.id else selection + item.id },
                            ),
                    ) {
                        AsyncImage(
                            model = item.uri,
                            contentDescription = item.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (item.type == MediaType.VIDEO) {
                            Icon(
                                Icons.Filled.Videocam,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                            )
                        }
                        if (item.id in selection) {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                            )
                        } else if (selectionActive) {
                            Icon(
                                Icons.Filled.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteAlbumConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAlbumConfirm = false },
            title = { Text(stringResource(R.string.album_delete_title)) },
            text = { Text(stringResource(R.string.album_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAlbumConfirm = false
                    viewModel.deleteCustomAlbum(albumId)
                    onAlbumDeleted()
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteAlbumConfirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
