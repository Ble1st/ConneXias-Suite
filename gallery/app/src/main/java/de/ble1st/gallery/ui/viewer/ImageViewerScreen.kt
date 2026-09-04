package de.ble1st.gallery.ui.viewer

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import de.ble1st.gallery.R
import de.ble1st.gallery.data.media.MediaType
import de.ble1st.gallery.ui.GalleryViewModel
import de.ble1st.gallery.ui.MediaScope
import de.ble1st.gallery.util.DeleteOutcome
import de.ble1st.gallery.util.MediaActions

/**
 * 1:1-Muster von ConneXias Files' `ImageViewerScreen` (Wischen zwischen allen Bildern desselben
 * Albums via HorizontalPager, Pinch-/Doppeltipp-Zoom) — Datenquelle ist hier ein
 * `content://`-MediaStore-Uri statt eines `java.io.File`, Geschwister sind alle IMAGE-Einträge
 * desselben Buckets statt aller Bilddateien desselben Ordners. Videos haben einen eigenen,
 * pagerlosen Screen ([VideoPlayerScreen]) statt hier mit eingemischt zu werden.
 */
@Composable
fun ImageViewerScreen(
    bucketId: Long,
    startItemId: Long,
    viewModel: GalleryViewModel,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onEdit: (Uri) -> Unit,
    /** Wenn gesetzt, sind die Wisch-Geschwister auf dieses benutzerdefinierte Album beschränkt
     * statt auf [bucketId] — [de.ble1st.gallery.ui.albums.CustomAlbumScreen] öffnete diesen
     * Betrachter zuvor immer mit `ALL_BUCKET_ID`, ein Wischen führte dadurch durch die gesamte
     * Galerie statt durch das Album, in dem der Nutzer eigentlich war (bewusste v1-Vereinfachung,
     * s. dortiger Klassendoc — jetzt nachgezogen). [bucketId] bleibt in diesem Fall unbenutzt. */
    customAlbumId: String? = null,
) {
    val context = LocalContext.current
    val favorites by viewModel.favorites.collectAsState()
    val customAlbums by viewModel.customAlbums.collectAsState()

    // Der Scope kennt die virtuellen Alben (ALL/FAVORITES) und die eigenen Alben an einer einzigen
    // Stelle — vorher lief die Auflösung über itemsForBucket, weil sonst ein reines
    // `bucketId ==`-Filter beim Favoriten-Album eine leere Geschwisterliste geliefert hätte und der
    // Betrachter sofort wieder zugegangen wäre.
    val mediaScope = remember(bucketId, customAlbumId) { MediaScope.of(bucketId, customAlbumId) }
    // Für ID-Mengen-Scopes (Favoriten, eigenes Album) die aktuelle Menge; für Ordner null, dann
    // läuft die Abfrage seitenweise. favorites/customAlbums gehören mit in den Schlüssel: beide
    // ändern den Inhalt, ohne dass sich der Scope ändert.
    val scopeIds = remember(mediaScope, favorites, customAlbums) { viewModel.idsForScope(mediaScope) }
    val siblings = remember(mediaScope, scopeIds) { viewModel.pagedImages(mediaScope, scopeIds) }
        .collectAsLazyPagingItems()

    // Die Startposition kann nicht mehr aus einer geladenen Liste abgelesen werden — sie wird
    // einmalig abgefragt (analyse.md 6.2). Bis sie da ist, steht hier bewusst noch kein Pager:
    // mit initialPage = 0 gebaut, würde er auf dem ersten Bild stehen bleiben.
    val startIndex by produceState<Int?>(null, mediaScope, scopeIds, startItemId) {
        value = viewModel.imageIndexOf(mediaScope, scopeIds, startItemId).coerceAtLeast(0)
    }

    val refreshDone = siblings.loadState.refresh is LoadState.NotLoading
    if (refreshDone && siblings.itemCount == 0) {
        // Letztes Bild wurde gelöscht/verschwand, während der Betrachter offen war.
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val resolvedStart = startIndex
    if (resolvedStart == null || siblings.itemCount == 0) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val pagerState = rememberPagerState(initialPage = resolvedStart.coerceIn(0, siblings.itemCount - 1)) {
        siblings.itemCount
    }
    // get() statt itemSnapshotList: der indizierte Zugriff meldet Paging, dass diese Position
    // gebraucht wird, und stößt das Nachladen an. Bis dahin ist der Wert null (Platzhalter) — die
    // Kopfzeile zeigt dann nichts an, statt dass der ganze Bildschirm wartet.
    val currentItem = siblings[pagerState.currentPage.coerceIn(0, siblings.itemCount - 1)]

    var isZoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage) { isZoomed = false }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    // analyse.md (2. Durchgang, Mittel): dieser Callback feuert bei JEDEM Rückkehren aus dem
    // System-Löschdialog, auch wenn der Nutzer dort abgebrochen/zurückgewischt hat
    // (RESULT_CANCELED) — vorher wurde das identisch zu einem Erfolg behandelt: der Betrachter
    // schloss sich, die Auswahl wurde geleert, das Medium war aber unverändert noch da.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> if (result.resultCode == Activity.RESULT_OK) { viewModel.onItemsDeleted(); onDeleted() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentItem?.displayName.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                    }
                },
                actions = {
                    // Solange die aktuelle Seite noch lädt, bleiben die Aktionen abgeschaltet
                    // statt auf ein anderes als das gezeigte Bild zu wirken.
                    val isFavorite = currentItem != null && currentItem.id in favorites
                    IconButton(enabled = currentItem != null, onClick = { currentItem?.let { viewModel.toggleFavorite(it.id) } }) {
                        Icon(
                            if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = stringResource(
                                if (isFavorite) R.string.favorite_remove else R.string.favorite_add,
                            ),
                        )
                    }
                    IconButton(enabled = currentItem != null, onClick = { currentItem?.let { onEdit(it.uri) } }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.editor_title))
                    }
                    IconButton(enabled = currentItem != null, onClick = { currentItem?.let { MediaActions.share(context, listOf(it.uri)) } }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
                    }
                    IconButton(enabled = currentItem != null, onClick = { currentItem?.let { MediaActions.openWith(context, it.uri) } }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.action_open_with))
                    }
                    IconButton(enabled = currentItem != null, onClick = { showInfo = true }) {
                        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.action_info))
                    }
                    IconButton(enabled = currentItem != null, onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete))
                    }
                },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = !isZoomed,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) { page ->
            val item = siblings[page]
            if (item == null) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black))
            } else {
                ZoomableImage(
                    uri = item.uri,
                    contentDescription = item.displayName,
                    onZoomChanged = { zoomed -> if (page == pagerState.currentPage) isZoomed = zoomed },
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(pluralStringResource(R.plurals.delete_confirm_body, 1, 1)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    val target = currentItem ?: return@TextButton
                    when (val outcome = MediaActions.requestRemove(context, listOf(target.uri))) {
                        is DeleteOutcome.Deleted -> { viewModel.onItemsDeleted(); onDeleted() }
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

    if (showInfo) {
        currentItem?.let { MediaInfoDialog(item = it, onDismiss = { showInfo = false }) }
    }
}

@Composable
private fun ZoomableImage(uri: Uri, contentDescription: String, onZoomChanged: (Boolean) -> Unit) {
    var scale by remember(uri) { mutableFloatStateOf(1f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }

    fun setScale(next: Float) {
        scale = next.coerceIn(1f, 6f)
        if (scale <= 1f) offset = Offset.Zero
        onZoomChanged(scale > 1f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Dieselbe Geste wie ConneXias Files' ZoomableImage (s. dortiger Kommentar): Ein-
            // Finger-Bewegungen werden nur bei bereits aktivem Zoom konsumiert, sonst würde ein
            // Wischen zum nächsten Bild vom Pager nie ankommen.
            .pointerInput(uri) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val isMultiTouch = event.changes.size >= 2
                        if ((isMultiTouch || scale > 1f) && (zoomChange != 1f || panChange != Offset.Zero)) {
                            event.changes.forEach { it.consume() }
                            setScale(scale * zoomChange)
                            if (scale > 1f) offset += panChange
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(uri) {
                detectTapGestures(onDoubleTap = { setScale(if (scale > 1f) 1f else 3f) })
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = uri,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
