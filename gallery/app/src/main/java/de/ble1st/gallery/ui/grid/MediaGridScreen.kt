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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import de.ble1st.gallery.R
import de.ble1st.gallery.data.album.CustomAlbum
import de.ble1st.gallery.data.media.MediaItem
import de.ble1st.gallery.data.media.MediaListItem
import de.ble1st.gallery.data.media.MediaType
import de.ble1st.gallery.data.media.SortOrder
import de.ble1st.gallery.data.media.sectionKeyOf
import de.ble1st.gallery.ui.GalleryViewModel
import de.ble1st.gallery.ui.MediaScope
import de.ble1st.gallery.ui.viewer.MediaInfoDialog
import de.ble1st.gallery.util.DeleteOutcome
import de.ble1st.gallery.util.MediaActions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val selection by viewModel.selection.collectAsState()
    val customAlbums by viewModel.customAlbums.collectAsState()
    val favorites by viewModel.favorites.collectAsState()

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

    // Der Bildschirm sagt dem ViewModel, welchen Ausschnitt er zeigt; der Inhalt selbst kommt
    // seitenweise zurück (analyse.md 6.2). Die Suche läuft dadurch als DISPLAY_NAME-LIKE in der
    // Abfrage statt als Kotlin-Filter über eine vollständig geladene Liste — sonst würde sie nur
    // die schon gescrollten Seiten durchsuchen.
    val mediaScope = remember(bucketId) { MediaScope.of(bucketId) }
    LaunchedEffect(mediaScope, searchQuery) {
        viewModel.setGridRequest(mediaScope, searchQuery)
    }
    val gridItems = viewModel.gridItems.collectAsLazyPagingItems()
    val isEmpty = gridItems.loadState.refresh is LoadState.NotLoading && gridItems.itemCount == 0

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
                            IconButton(onClick = { scope.launch { viewModel.selectAllInScope() } }) {
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
                            IconButton(onClick = { scope.launch { MediaActions.share(context, viewModel.selectedUris()) } }) {
                                Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
                            }
                            if (selection.size == 1) {
                                IconButton(onClick = { scope.launch { infoItem = viewModel.itemById(selection.first()) } }) {
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
                            // Ohne "enabled"-Prüfung auf vorhandene Bilder: die verlangte einen
                            // vollständig geladenen Bestand. Die Diashow selbst kehrt sofort
                            // zurück, wenn der Ausschnitt kein einziges Bild enthält.
                            IconButton(onClick = { onStartSlideshow(bucketId) }) {
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
        if (isEmpty) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.grid_empty))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = padding,
                modifier = Modifier.fillMaxSize(),
            ) {
                // Überschriften und Kacheln kommen als ein gemeinsamer Strom (MediaListItem), weil
                // sich ein seitenweise geladener Bestand nicht vorab in Abschnitte zerlegen lässt
                // — die Überschriften entstehen zwischen zwei benachbarten Einträgen des Stroms
                // (PagingData.insertSeparators, s. GalleryViewModel). Bei Sortierung nach Name
                // oder Größe fügt das ViewModel gar keine ein.
                items(
                    count = gridItems.itemCount,
                    key = { index ->
                        when (val entry = gridItems.peek(index)) {
                            is MediaListItem.Entry -> "item_${entry.item.id}"
                            is MediaListItem.Header -> "header_${entry.key.startMillis}_${entry.key.monthOnly}"
                            null -> "placeholder_$index"
                        }
                    },
                    span = { index ->
                        // peek() statt get(): der Span wird beim Layout abgefragt, ein get() würde
                        // dabei als Zugriff gewertet und das Nachladen anstoßen.
                        if (gridItems.peek(index) is MediaListItem.Header) {
                            GridItemSpan(maxLineSpan)
                        } else {
                            GridItemSpan(1)
                        }
                    },
                ) { index ->
                    when (val entry = gridItems[index]) {
                        is MediaListItem.Header ->
                            SectionHeader(startMillis = entry.key.startMillis, monthOnly = entry.key.monthOnly)
                        is MediaListItem.Entry -> {
                            val item = entry.item
                            MediaThumbnail(
                                item = item,
                                selected = item.id in selection,
                                selectionActive = selectionActive,
                                favorite = item.id in favorites,
                                onClick = { if (selectionActive) viewModel.toggleSelection(item.id) else onOpenViewer(item) },
                                onLongClick = { viewModel.toggleSelection(item.id) },
                            )
                        }
                        null -> Box(modifier = Modifier.aspectRatio(1f))
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
                    scope.launch {
                        when (val outcome = MediaActions.requestRemove(context, viewModel.selectedUris())) {
                            is DeleteOutcome.Deleted -> viewModel.onItemsDeleted()
                            is DeleteOutcome.NeedsConfirmation ->
                                deleteLauncher.launch(IntentSenderRequest.Builder(outcome.intentSender).build())
                        }
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

/**
 * Überschrift eines Zeitabschnitts. Die Formatierung sitzt hier und nicht in [sectionKeyOf], weil
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
    // Der Auswahlzustand hängt am Zellen-Knoten selbst statt als contentDescription am
    // Häkchen-Symbol: `selected` ist eine Standard-Semantik, die eine Vorlesehilfe in ihrer
    // eigenen Sprache ansagt ("ausgewählt"/"nicht ausgewählt") — und sie fehlt sonst auch beim
    // leeren Kreis, der ja gerade "nicht ausgewählt" bedeutet. Nur im Auswahlmodus gesetzt,
    // sonst wäre jede Kachel im Normalbetrieb fälschlich "nicht ausgewählt".
    val itemSelected = selected
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (selectionActive) Modifier.semantics { this.selected = itemSelected } else Modifier,
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
                contentDescription = stringResource(R.string.content_desc_video),
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            )
        }
        // Markierung auch außerhalb des Favoriten-Albums sichtbar — sonst wäre nach dem Setzen
        // nirgends zu erkennen, welche Aufnahme markiert ist, ohne das Album zu wechseln.
        if (favorite && !selectionActive) {
            Icon(
                Icons.Filled.Star,
                contentDescription = stringResource(R.string.content_desc_favorite),
                tint = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        }
        if (selected) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
            Icon(
                Icons.Filled.CheckCircle,
                // Absichtlich ohne Beschreibung — der Zustand steht als `selected` am Zellen-Knoten.
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
