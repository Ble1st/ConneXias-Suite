package de.ble1st.files.ui.viewer

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
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.ble1st.files.data.fs.LocalFileSystem
import de.ble1st.files.R
import de.ble1st.files.util.FileActions
import de.ble1st.files.util.FileCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Eigener Bildbetrachter (Nutzeranforderung, löst [PlaceholderViewerScreen] für IMAGE ab): Wischen
 * zwischen allen Bildern desselben Ordners (HorizontalPager), Pinch-Zoom + Doppeltipp-Zoom,
 * Drehen der Ansicht. Coil (AsyncImage) statt manuellem BitmapFactory — dekodiert lokale Dateien
 * ohne Netzwerk-Fetcher und skaliert automatisch auf die Zielgröße statt das volle Bild in den
 * Speicher zu laden.
 */
@Composable
fun ImageViewerScreen(file: File, onBack: () -> Unit) {
    val context = LocalContext.current
    // Geschwister-Bilder im selben Ordner für den Wisch-durch-die-Galerie-Effekt — ohne sie wäre
    // jedes Bild eine isolierte Sackgasse, wie es MaterialFiles & Co. nicht machen.
    //
    // analyse.md (2. Durchgang, Mittel — "Bildbetrachter listet Geschwister auf dem Main-Thread"):
    // `remember(file) { ... }` lief bisher synchron im Composition-Body — LocalFileSystem.list
    // ruft `listFiles()` auf und kategorisiert/sortiert jeden Treffer, bei einem großen DCIM-Ordner
    // (tausende Dateien) ein potenziell spürbarer Main-Thread-Block bis hin zum ANR. Jetzt: sofort
    // mit nur [file] selbst starten (das angeforderte Bild ist damit ab dem ersten Frame sichtbar),
    // die echte Geschwisterliste per LaunchedEffect auf Dispatchers.IO nachladen und den Pager erst
    // dann — falls nötig — auf den tatsächlichen Index von [file] springen lassen.
    var siblings by remember(file) { mutableStateOf(listOf(file)) }
    val pagerState = rememberPagerState(initialPage = 0) { siblings.size }
    LaunchedEffect(file) {
        val loaded = withContext(Dispatchers.IO) {
            val parent = file.parentFile
            val images = parent?.let { LocalFileSystem.list(it) }
                ?.filter { it.category == FileCategory.IMAGE }
                ?.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                ?.map { it.file }
                .orEmpty()
            images.ifEmpty { listOf(file) }
        }
        siblings = loaded
        val targetPage = loaded.indexOf(file).coerceAtLeast(0)
        if (targetPage != pagerState.currentPage) pagerState.scrollToPage(targetPage)
    }
    val currentFile = siblings.getOrElse(pagerState.currentPage) { file }

    // Solange ein Bild gezoomt ist, muss der Pager selbst nicht mehr auf horizontale Wischgesten
    // reagieren — sonst konkurrieren Bild-Pan und Seitenwechsel um dieselbe Geste.
    var isZoomed by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState.currentPage) { isZoomed = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentFile.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(id = R.string.content_desc_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { FileActions.share(context, listOf(currentFile)) }) {
                        Icon(
                            Icons.Filled.Share,
                            contentDescription = stringResource(id = R.string.action_share),
                        )
                    }
                    IconButton(onClick = { FileActions.openWithOtherApp(context, currentFile) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource(id = R.string.action_open_with),
                        )
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
                file = siblings[page],
                onZoomChanged = { zoomed -> if (page == pagerState.currentPage) isZoomed = zoomed },
            )
        }
    }
}

@Composable
private fun ZoomableImage(file: File, onZoomChanged: (Boolean) -> Unit) {
    var scale by remember(file) { mutableFloatStateOf(1f) }
    var offset by remember(file) { mutableStateOf(Offset.Zero) }
    var rotation by remember(file) { mutableFloatStateOf(0f) }

    fun setScale(next: Float) {
        scale = next.coerceIn(1f, 6f)
        if (scale <= 1f) offset = Offset.Zero
        onZoomChanged(scale > 1f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Eigene Geste statt detectTransformGestures: die würde auch ein Ein-Finger-Wischen bei
            // scale == 1 als (wirkungslosen) Pan konsumieren und ihn damit dem umgebenden
            // HorizontalPager wegnehmen — live am Gerät als "Wischen zum nächsten Bild reagiert
            // nicht" aufgefallen. Ein-Finger-Bewegungen werden deshalb nur konsumiert, sobald
            // bereits gezoomt ist (dann ist Pager-Scroll ohnehin per userScrollEnabled deaktiviert);
            // Zwei-Finger-Pinch wird immer verarbeitet, unabhängig vom Zoom-Stand.
            .pointerInput(file) {
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
            .pointerInput(file) {
                detectTapGestures(onDoubleTap = { setScale(if (scale > 1f) 1f else 3f) })
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = file,
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                    rotationZ = rotation
                },
        )
        // Dreht nur die Ansicht (nicht die EXIF-Orientierung auf der Festplatte) — persistentes
        // Drehen bräuchte verlustfreies JPEG-Transrotieren, ein eigener Ausbauschritt.
        IconButton(
            onClick = { rotation = (rotation + 90f) % 360f },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f), MaterialTheme.shapes.small),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.RotateRight,
                contentDescription = stringResource(id = R.string.content_desc_rotate),
                tint = Color.White,
            )
        }
    }
}
