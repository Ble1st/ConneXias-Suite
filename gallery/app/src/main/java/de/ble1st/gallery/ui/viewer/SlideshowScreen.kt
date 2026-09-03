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
import coil3.compose.AsyncImage
import de.ble1st.gallery.R
import de.ble1st.gallery.data.media.MediaType
import de.ble1st.gallery.ui.GalleryViewModel
import kotlinx.coroutines.delay

private const val SLIDE_DURATION_MS = 4000L

/** Automatisch weiterlaufender Bild-Pager — nur Fotos (Videos würden eine eigene Wiedergabelogik
 * pro Folie brauchen, bewusst außen vor gelassen, s. README). Tippen pausiert/setzt fort statt
 * eines eigenen Einstellungsdialogs für die Anzeigedauer — v1-Vereinfachung mit fester
 * [SLIDE_DURATION_MS]. */
@Composable
fun SlideshowScreen(bucketId: Long, viewModel: GalleryViewModel, onBack: () -> Unit) {
    val allItems by viewModel.allItems.collectAsState()
    // favorites mit als Schlüssel — das Favoriten-Album ändert seinen Inhalt, ohne dass sich
    // allItems ändert (s. GalleryViewModel.itemsForBucket).
    val favorites by viewModel.favorites.collectAsState()
    val images = remember(allItems, bucketId, favorites) { viewModel.itemsForBucket(bucketId).filter { it.type == MediaType.IMAGE } }
    var playing by remember { mutableStateOf(true) }

    if (images.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val pagerState = rememberPagerState(initialPage = 0) { images.size }

    LaunchedEffect(playing, pagerState.currentPage, images.size) {
        if (!playing) return@LaunchedEffect
        delay(SLIDE_DURATION_MS)
        val next = (pagerState.currentPage + 1) % images.size
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
                AsyncImage(
                    model = images[page].uri,
                    contentDescription = images[page].displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
