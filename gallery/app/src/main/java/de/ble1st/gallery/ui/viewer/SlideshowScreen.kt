package de.ble1st.gallery.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import coil3.compose.AsyncImage
import de.ble1st.gallery.R
import de.ble1st.gallery.data.media.MediaType
import de.ble1st.gallery.ui.GalleryViewModel
import de.ble1st.gallery.ui.MediaScope
import kotlinx.coroutines.delay

private const val SLIDE_DURATION_MS = 4000L

/** Automatisch weiterlaufender Bild-Pager — nur Fotos (Videos würden eine eigene Wiedergabelogik
 * pro Folie brauchen, bewusst außen vor gelassen, s. README). Tippen pausiert/setzt fort statt
 * eines eigenen Einstellungsdialogs für die Anzeigedauer — v1-Vereinfachung mit fester
 * [SLIDE_DURATION_MS]. */
@Composable
fun SlideshowScreen(bucketId: Long, viewModel: GalleryViewModel, onBack: () -> Unit) {
    // favorites/customAlbums gehören mit in den Schlüssel — beide ändern den Inhalt eines
    // ID-Mengen-Albums, ohne dass sich der Scope ändert.
    val favorites by viewModel.favorites.collectAsState()
    val customAlbums by viewModel.customAlbums.collectAsState()
    val mediaScope = remember(bucketId) { MediaScope.of(bucketId) }
    val scopeIds = remember(mediaScope, favorites, customAlbums) { viewModel.idsForScope(mediaScope) }
    // Dieselbe seitenweise Bilderquelle wie im Betrachter (analyse.md 6.2) — eine Diashow über
    // eine große Mediathek hätte sonst deren gesamten Bestand geladen, um das erste Bild zu zeigen.
    val images = remember(mediaScope, scopeIds) { viewModel.pagedImages(mediaScope, scopeIds) }
        .collectAsLazyPagingItems()
    var playing by remember { mutableStateOf(true) }

    val refreshDone = images.loadState.refresh is LoadState.NotLoading
    if (refreshDone && images.itemCount == 0) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    if (images.itemCount == 0) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

    val pagerState = rememberPagerState(initialPage = 0) { images.itemCount }

    LaunchedEffect(playing, pagerState.currentPage, images.itemCount) {
        if (!playing) return@LaunchedEffect
        delay(SLIDE_DURATION_MS)
        val next = (pagerState.currentPage + 1) % images.itemCount
        pagerState.animateScrollToPage(next)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.slideshow_start)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                    }
                },
                actions = {
                    IconButton(onClick = { playing = !playing }) {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(if (playing) R.string.slideshow_pause else R.string.slideshow_resume),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).background(Color.Black).clickable { playing = !playing },
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                // null = Platzhalter, die Seite lädt noch. Schwarz statt eines Spinners: die
                // Diashow läuft im Vollbild auf schwarzem Grund, ein Ladeindikator wäre der einzige
                // helle Punkt darin.
                val item = images[page]
                if (item != null) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
