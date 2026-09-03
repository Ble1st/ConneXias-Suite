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
import coil3.compose.AsyncImage
import de.ble1st.gallery.R
import de.ble1st.gallery.data.media.ALL_BUCKET_ID
import de.ble1st.gallery.data.media.MediaType
import de.ble1st.gallery.ui.GalleryViewModel
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
    val allItems by viewModel.allItems.collectAsState()
    val customAlbums by viewModel.customAlbums.collectAsState()
    val siblings = remember(allItems, bucketId, customAlbumId, customAlbums) {
        val scoped = when {
            customAlbumId != null -> viewModel.itemsForCustomAlbum(customAlbumId)
            bucketId == ALL_BUCKET_ID -> allItems
            else -> allItems.filter { it.bucketId == bucketId }
        }
        scoped.filter { it.type == MediaType.IMAGE }.sortedByDescending { it.dateSortMillis }
    }

    if (siblings.isEmpty()) {
        // Letztes Bild wurde gelöscht/verschwand, während der Betrachter offen war.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val initialPage = remember(siblings, startItemId) {
        siblings.indexOfFirst { it.id == startItemId }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialPage) { siblings.size }
    val currentItem = siblings[pagerState.currentPage.coerceIn(siblings.indices)]

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
                title = { Text(currentItem.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                    }
                },
                actions = {
                    IconButton(onClick = { onEdit(currentItem.uri) }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.editor_title))
                    }
                    IconButton(onClick = { MediaActions.share(context, listOf(currentItem.uri)) }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
                    }
                    IconButton(onClick = { MediaActions.openWith(context, currentItem.uri) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.action_open_with))
                    }
                    IconButton(onClick = { showInfo = true }) {
                        Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.action_info))
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
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
            ZoomableImage(
                uri = siblings[page].uri,
                onZoomChanged = { zoomed -> if (page == pagerState.currentPage) isZoomed = zoomed },
            )
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
                    when (val outcome = MediaActions.requestRemove(context, listOf(currentItem.uri))) {
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
        MediaInfoDialog(item = currentItem, onDismiss = { showInfo = false })
    }
}

@Composable
private fun ZoomableImage(uri: Uri, onZoomChanged: (Boolean) -> Unit) {
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
            contentDescription = null,
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
