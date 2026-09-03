package de.ble1st.gallery.ui.grid

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyColumnItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.ble1st.gallery.R
import de.ble1st.gallery.data.album.CustomAlbum
import de.ble1st.gallery.data.media.MediaItem
import de.ble1st.gallery.data.media.MediaType
import de.ble1st.gallery.data.media.SortOrder
import de.ble1st.gallery.data.media.groupByTime
import de.ble1st.gallery.ui.GalleryViewModel
import de.ble1st.gallery.ui.viewer.MediaInfoDialog
import de.ble1st.gallery.util.DeleteOutcome
import de.ble1st.gallery.util.MediaActions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Thumbnail-Grid für "Alle" oder ein einzelnes Album — Mehrfachauswahl per Long-Press
 * (`combinedClickable`), Top-Bar wechselt dabei von Sortier-Menü auf Auswahl-Aktionen
 * (Alle auswählen/Teilen/Löschen/Info bei genau einem ausgewählten Element).
 */
@Composable
fun MediaGridScreen(
    bucketId: Long,
    bucketName: String,
    viewModel: GalleryViewModel,
    onNavigateUp: () -> Unit,
    onOpenViewer: (MediaItem) -> Unit,
    onStartSlideshow: (Long) -> Unit,
) {
    val context = LocalContext.current
    val allItems by viewModel.allItems.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val customAlbums by viewModel.customAlbums.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    // favorites gehört mit in die Schlüssel: das Favoriten-Album (FAVORITES_BUCKET_ID) ändert
    // seinen Inhalt, ohne dass sich allItems oder sortOrder ändern.
    val bucketItems = remember(allItems, sortOrder, bucketId, favorites) { viewModel.itemsForBucket(bucketId) }

    // analyse.md ("weiterhin gültig" — "Selection leckt über Alben"): [GalleryViewModel.selection]
    // ist ein einziges, geteiltes StateFlow über alle Grid-/Album-Ansichten hinweg (bewusst, s.
    // Klassendoc dort) — ohne diesen Reset zeigte ein frisch geöffnetes Album sofort den
    // Auswahlmodus (Top-Bar mit Löschen/Teilen/Info) mit der Anzahl aus dem zuletzt verlassenen
    // Album, obwohl in diesem Album selbst noch nichts angetippt wurde. Nur an [bucketId] gebunden,
    // nicht an jede Rekomposition — Zurückkommen von einem Betrachter zum selben Album (bucketId
    // unverändert) verwirft die Auswahl absichtlich nicht.
    LaunchedEffect(bucketId) {
        viewModel.clearSelection()
    }

    var sortMenuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var infoItem by remember { mutableStateOf<MediaItem?>(null) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showAddToAlbum by remember { mutableStateOf(false) }
    val selectionActive = selection.isNotEmpty()
    // Nur clientseitiges Filtern der bereits geladenen Bucket-Liste nach Dateiname — kein
    // eigener MediaStore-Volltextindex, genügt für die typische Gerätemedienmenge, ohne eine
    // zusätzliche Abfrage-/Indexierungsschicht für v1 einzuführen.
    val items = remember(bucketItems, searchQuery) {
        if (searchQuery.isBlank()) bucketItems else bucketItems.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
    }
    // null = keine Zeitleiste, flaches Raster (s. Kommentar an der Grid-Verwendung unten).
    val sections = remember(items, sortOrder) {
        if (sortOrder == SortOrder.DATE) groupByTime(items) else null
    }

    // Für API 30+ (createDeleteRequest) führt das System das Löschen bei Bestätigung selbst aus;
    // für die API-29-RecoverableSecurityException-Variante gewährt die Bestätigung nur die
    // Berechtigung (s. MediaActions.requestDelete-Klassendoc "Bekannte Einschränkung") — in
    // beiden Fällen reicht hier ein reines "Auswahl zurücksetzen", der Grid-Inhalt selbst
    // aktualisiert sich automatisch über den ContentObserver in GalleryViewModel.
    // s. ImageViewerScreen-Kommentar: Abbrechen im System-Dialog darf nicht wie ein Löscherfolg
    // behandelt werden.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> if (result.resultCode == Activity.RESULT_OK) viewModel.onItemsDeleted() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            if (selectionActive) {
                                pluralStringResource(R.plurals.selection_count, selection.size, selection.size)
                            } else {
                                bucketName
                            },
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (selectionActive) viewModel.clearSelection() else onNavigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                        }
                    },
                    actions = {
                        if (selectionActive) {
                            IconButton(onClick = { viewModel.selectAll(items.map { it.id }) }) {
                                Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.action_select_all))
                            }
                            IconButton(onClick = { showAddToAlbum = true }) {
                                Icon(Icons.Filled.PhotoAlbum, contentDescription = stringResource(R.string.album_add_to))
                            }
                            // Eine gemischte Auswahl (teils markiert, teils nicht) wird als
                            // "noch nicht markiert" behandelt und der Tipp markiert alles —
                            // umschalten je Element wäre für den Nutzer nicht vorhersehbar
                            // (s. FavoritesStore.setAll).
                            val allFavorite = selection.all { it in favorites }
                            IconButton(onClick = { viewModel.setFavorites(selection, !allFavorite) }) {
                                Icon(
                                    if (allFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                                    contentDescription = stringResource(
                                        if (allFavorite) R.string.favorite_remove else R.string.favorite_add,
                                    ),
                                )
                            }
                            IconButton(onClick = { MediaActions.share(context, selectedUris(items, selection)) }) {
                                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
                            }
                            if (selection.size == 1) {
                                IconButton(onClick = { infoItem = items.find { it.id == selection.first() } }) {
                                    Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.action_info))
                                }
                            }
                            IconButton(onClick = { showDeleteConfirm = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                            }
                        } else {
                            IconButton(onClick = { showSearch = !showSearch; if (!showSearch) searchQuery = "" }) {
                                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
                            }
                            IconButton(onClick = { onStartSlideshow(bucketId) }, enabled = items.any { it.type == MediaType.IMAGE }) {
                                Icon(Icons.Filled.PlayCircle, contentDescription = stringResource(R.string.slideshow_start))
                            }
                            Box {
                                IconButton(onClick = { sortMenuExpanded = true }) {
                                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.grid_action_sort))
                                }
                                DropdownMenu(expanded = sortMenuExpanded, onDismissRequest = { sortMenuExpanded = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sort_by_date)) },
                                        onClick = { viewModel.setSortOrder(SortOrder.DATE); sortMenuExpanded = false },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sort_by_name)) },
                                        onClick = { viewModel.setSortOrder(SortOrder.NAME); sortMenuExpanded = false },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.sort_by_size)) },
                                        onClick = { viewModel.setSortOrder(SortOrder.SIZE); sortMenuExpanded = false },
                                    )
                                }
                            }
                        }
                    },
                )
                if (showSearch && !selectionActive) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.action_search)) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
        },
    ) { padding ->
        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.grid_empty))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = padding,
                modifier = Modifier.fillMaxSize(),
            ) {
                // Datums-Überschriften nur bei Sortierung nach Datum: bei Name/Größe stünde über
                // fast jeder Kachel eine eigene Überschrift, weil aufeinanderfolgende Einträge
                // dann nichts mehr miteinander zu tun haben.
                if (sections != null) {
                    sections.forEach { section ->
                        item(
                            key = "section_${section.startMillis}_${section.monthOnly}",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            SectionHeader(startMillis = section.startMillis, monthOnly = section.monthOnly)
                        }
                        items(section.items, key = { it.id }) { item ->
                            MediaThumbnail(
                                item = item,
                                selected = item.id in selection,
                                selectionActive = selectionActive,
                                favorite = item.id in favorites,
                                onClick = { if (selectionActive) viewModel.toggleSelection(item.id) else onOpenViewer(item) },
                                onLongClick = { viewModel.toggleSelection(item.id) },
                            )
                        }
                    }
                } else {
                    items(items, key = { it.id }) { item ->
                        MediaThumbnail(
                            item = item,
                            selected = item.id in selection,
                            selectionActive = selectionActive,
                            favorite = item.id in favorites,
                            onClick = { if (selectionActive) viewModel.toggleSelection(item.id) else onOpenViewer(item) },
                            onLongClick = { viewModel.toggleSelection(item.id) },
                        )
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(pluralStringResource(R.plurals.delete_confirm_body, selection.size, selection.size)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    when (val outcome = MediaActions.requestRemove(context, selectedUris(items, selection))) {
                        is DeleteOutcome.Deleted -> viewModel.onItemsDeleted()
                        is DeleteOutcome.NeedsConfirmation ->
                            deleteLauncher.launch(IntentSenderRequest.Builder(outcome.intentSender).build())
                    }
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    infoItem?.let { item -> MediaInfoDialog(item = item, onDismiss = { infoItem = null }) }

    if (showAddToAlbum) {
        AddToAlbumDialog(
            albums = customAlbums,
            onDismiss = { showAddToAlbum = false },
            onCreateAndAdd = { name ->
                val album = viewModel.createCustomAlbum(name)
                viewModel.addToCustomAlbum(album.id, selection)
                showAddToAlbum = false
            },
            onAddToExisting = { albumId ->
                viewModel.addToCustomAlbum(albumId, selection)
                showAddToAlbum = false
            },
        )
    }
}

@Composable
private fun AddToAlbumDialog(
    albums: List<CustomAlbum>,
    onDismiss: () -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onAddToExisting: (String) -> Unit,
) {
    var newAlbumName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.album_add_to)) },
        text = {
            Column {
                if (albums.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.padding(bottom = 8.dp)) {
                        lazyColumnItems(albums, key = { it.id }) { album ->
                            Text(
                                text = album.name,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onAddToExisting(album.id) }
                                    .padding(vertical = 12.dp),
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newAlbumName,
                        onValueChange = { newAlbumName = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.album_create_title)) },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(enabled = newAlbumName.isNotBlank(), onClick = { onCreateAndAdd(newAlbumName.trim()) }) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.album_create_title))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun selectedUris(items: List<MediaItem>, selection: Set<Long>): List<Uri> =
    items.filter { it.id in selection }.map { it.uri }

/**
 * Überschrift eines Zeitabschnitts. Die Formatierung sitzt hier und nicht in [groupByTime], weil
 * sie `Locale`-abhängig ist — die Gruppierung selbst bleibt dadurch framework-frei und testbar.
 *
 * [FormatStyle.LONG] für Tage ("3. September 2026") statt eines Kurzformats: die Überschrift ist
 * der einzige Ort, an dem das Datum überhaupt ausgeschrieben steht.
 */
@Composable
private fun SectionHeader(startMillis: Long, monthOnly: Boolean) {
    val date = remember(startMillis) {
        Instant.ofEpochMilli(startMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val label = remember(date, monthOnly) {
        val pattern = if (monthOnly) {
            DateTimeFormatter.ofPattern("LLLL yyyy")
        } else {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)
        }
        date.format(pattern)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun MediaThumbnail(
    item: MediaItem,
    selected: Boolean,
    selectionActive: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
        // Markierung auch außerhalb des Favoriten-Albums sichtbar — sonst wäre nach dem Setzen
        // nirgends zu erkennen, welche Aufnahme markiert ist, ohne das Album zu wechseln.
        if (favorite && !selectionActive) {
            Icon(
                Icons.Filled.Star,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
        if (selected) {
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
