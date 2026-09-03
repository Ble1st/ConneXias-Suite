package de.ble1st.camera.ui.review

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import de.ble1st.camera.R
import de.ble1st.camera.nav.CaptureRequestInfo
import de.ble1st.camera.util.CaptureActions
import de.ble1st.camera.util.PhotoFilter
import de.ble1st.camera.util.PhotoFilterSaver
import de.ble1st.camera.util.SecureScreenEffect
import de.ble1st.camera.util.composeColorMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kurz-Ansicht der letzten Aufnahme — anders als ein vollwertiger Bild-/Videobetrachter (den hat
 * ConneXias Galerie) bewusst nur ein einzelnes Element ohne Wisch-durch-alle-Aufnahmen-Pager: das
 * Durchblättern der gesamten Bildergalerie ist nicht Aufgabe der Kamera-App (s. README "Noch
 * nicht enthalten").
 */
@Composable
fun CaptureReviewScreen(
    uri: Uri,
    isVideo: Boolean,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    captureRequestInfo: CaptureRequestInfo? = null,
    onUseCapture: (Uri?) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SecureScreenEffect()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDelivering by remember { mutableStateOf(false) }
    val deliverFailedMessage = stringResource(R.string.review_deliver_failed)
    // Nur für Fotos relevant — Videos haben keine Filteranwendung (s. README "Noch nicht
    // enthalten"). Zurückgesetzt auf NONE bei jedem neuen `uri`, damit ein bereits gespeicherter
    // Filter nicht versehentlich auf die nächste Aufnahme "durchrutscht".
    var selectedFilter by remember(uri) { mutableStateOf(PhotoFilter.NONE) }
    var showFilterStrip by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    // Vorab via stringResource() (konfigurationsbewusst) statt context.getString() im
    // Coroutine-Callback aufgelöst — Letzteres würde bei einer zwischenzeitlichen
    // Konfigurationsänderung (z. B. Sprachwechsel) einen veralteten String liefern.
    val savedMessage = stringResource(R.string.review_filtered_saved)
    val saveFailedMessage = stringResource(R.string.review_filtered_save_failed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                    }
                },
                actions = {
                    if (!isVideo) {
                        IconButton(onClick = { showFilterStrip = !showFilterStrip }) {
                            Icon(Icons.Filled.FilterVintage, contentDescription = stringResource(R.string.review_action_filter))
                        }
                        if (selectedFilter != PhotoFilter.NONE) {
                            IconButton(
                                enabled = !isSaving,
                                onClick = {
                                    isSaving = true
                                    scope.launch {
                                        val saved = PhotoFilterSaver.saveFiltered(context, uri, selectedFilter)
                                        isSaving = false
                                        val message = if (saved != null) savedMessage else saveFailedMessage
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                },
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.review_action_save_filtered))
                            }
                        }
                    }
                    // Nur sichtbar, wenn diese Aufnahme über ACTION_IMAGE_CAPTURE/ACTION_VIDEO_CAPTURE
                    // angefordert wurde (s. CaptureRequestInfo-Klassendoc) — übergibt die Aufnahme an
                    // den aufrufenden Prozess (EXTRA_OUTPUT-Ziel oder als MediaStore-Uri im
                    // Ergebnis-Intent) und schließt die App danach.
                    if (captureRequestInfo != null) {
                        IconButton(
                            enabled = !isDelivering,
                            onClick = {
                                isDelivering = true
                                scope.launch {
                                    val result = deliverCapture(context, uri, captureRequestInfo)
                                    isDelivering = false
                                    result.onSuccess { delivered -> onUseCapture(delivered) }
                                        .onFailure { Toast.makeText(context, deliverFailedMessage, Toast.LENGTH_SHORT).show() }
                                }
                            },
                        ) {
                            Icon(Icons.Filled.TaskAlt, contentDescription = stringResource(R.string.review_action_use_capture))
                        }
                    }
                    IconButton(onClick = { CaptureActions.share(context, uri) }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.review_action_share))
                    }
                    IconButton(onClick = { CaptureActions.openInGallery(context, uri) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = stringResource(R.string.review_action_open_gallery))
                    }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.review_action_delete))
                    }
                },
            )
        },
    ) { padding ->
        if (isVideo) {
            ReviewVideoPlayer(uri = uri, modifier = Modifier.fillMaxSize().padding(padding))
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    colorFilter = if (selectedFilter == PhotoFilter.NONE) {
                        null
                    } else {
                        ColorFilter.colorMatrix(selectedFilter.composeColorMatrix())
                    },
                    modifier = Modifier.fillMaxSize().weight(1f).background(Color.Black),
                )
                if (showFilterStrip) {
                    FilterFilmstrip(
                        uri = uri,
                        selected = selectedFilter,
                        onSelect = { selectedFilter = it },
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.review_delete_confirm_title)) },
            text = { Text(stringResource(R.string.review_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    if (CaptureActions.delete(context, uri)) onDeleted()
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun FilterFilmstrip(uri: Uri, selected: PhotoFilter, onSelect: (PhotoFilter) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(Color.Black).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        items(PhotoFilter.entries) { filter ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (filter == selected) {
                                Modifier.background(MaterialTheme.colorScheme.primary)
                            } else {
                                Modifier
                            },
                        )
                        .padding(2.dp)
                        .clickable { onSelect(filter) },
                ) {
                    AsyncImage(
                        model = uri,
                        contentDescription = filterLabel(filter),
                        contentScale = ContentScale.Crop,
                        colorFilter = if (filter == PhotoFilter.NONE) null else ColorFilter.colorMatrix(filter.composeColorMatrix()),
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)),
                    )
                }
                Text(
                    text = filterLabel(filter),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun filterLabel(filter: PhotoFilter): String = stringResource(
    when (filter) {
        PhotoFilter.NONE -> R.string.filter_none
        PhotoFilter.BW -> R.string.filter_bw
        PhotoFilter.SEPIA -> R.string.filter_sepia
        PhotoFilter.VINTAGE -> R.string.filter_vintage
        PhotoFilter.COOL -> R.string.filter_cool
        PhotoFilter.WARM -> R.string.filter_warm
    },
)

/** Übergibt die Aufnahme an den Aufrufer eines System-Kamera-Contracts (s. `CaptureRequestInfo`):
 * war `EXTRA_OUTPUT` gesetzt, werden die Bytes dorthin kopiert und `null` zurückgegeben (der
 * Aufrufer kennt die Ziel-Uri bereits selbst); ohne `EXTRA_OUTPUT` wird stattdessen die eigene
 * MediaStore-Uri der Aufnahme als Ergebnis geliefert (s. `CaptureRequestInfo`-Klassendoc). */
private suspend fun deliverCapture(context: Context, uri: Uri, captureRequestInfo: CaptureRequestInfo): Result<Uri?> =
    withContext(Dispatchers.IO) {
        val outputUri = captureRequestInfo.outputUri ?: return@withContext Result.success(uri)
        runCatching {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                context.contentResolver.openOutputStream(outputUri)?.use { output ->
                    input.copyTo(output)
                    true
                }
            } ?: false
            check(copied) { "Konnte Aufnahme nicht in EXTRA_OUTPUT-Ziel kopieren" }
            // analyse.md (2. Durchgang, Mittel): die Aufnahme lag vorher immer zuerst dauerhaft in
            // MediaStore (DCIM/ConneXias Kamera, s. CaptureViewModel.takePhoto) — der Aufrufer
            // bekam über `outputUri` seine eigene Kopie, die interne blieb aber zusätzlich für
            // immer als unerwünschtes Duplikat in der Galerie des Nutzers stehen. Der Aufrufer hat
            // seine Kopie an dieser Stelle bereits sicher (check() oben wäre sonst schon
            // fehlgeschlagen), das interne Duplikat wird jetzt aufgeräumt. Ein Löschfehler hier
            // (z. B. Berechtigungsrand fall) ist kein Abbruchgrund für die Übergabe selbst.
            runCatching { context.contentResolver.delete(uri, null, null) }
            null
        }
    }

@Composable
private fun ReviewVideoPlayer(uri: Uri, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }

    // Dieselbe Pause-bei-ON_PAUSE/Release-bei-Verlassen-Logik wie ConneXias Files'
    // VideoPlayerScreen — kein MediaSession/Foreground-Service, Wiedergabe darf daher nicht
    // unbeaufsichtigt im Hintergrund weiterlaufen.
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true } },
        onRelease = { it.player = null },
    )
}
