package de.ble1st.files.ui.viewer

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import de.ble1st.files.util.FileActions
import java.io.File

/**
 * Eigener Videoplayer (Nutzeranforderung, löst [PlaceholderViewerScreen] für VIDEO ab) — Media3
 * ExoPlayer, kein Play-Services-Dienst (erfüllt die "kein Google-Play-Services"-Vorgabe, s.
 * README). `file://`-Uri statt FileProvider-`content://`: ExoPlayer liest hier prozessintern über
 * MANAGE_EXTERNAL_STORAGE, FileProvider ist nur für Zugriff durch fremde Apps nötig (s.
 * FileActions.kt-Klassendoc), nicht für den eigenen ExoPlayer.
 */
@Composable
fun VideoPlayerScreen(file: File, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }

    // Ohne MediaSession/Foreground-Service (bewusst nicht gebaut, s. Klassendoc) darf die
    // Wiedergabe nicht unbeaufsichtigt im Hintergrund weiterlaufen — deshalb Pause bei ON_PAUSE,
    // vollständiges Release erst wenn der Screen ganz aus der Komposition fällt (s. unten).
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = { FileActions.share(context, listOf(file)) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Teilen")
                    }
                },
            )
        },
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            onRelease = { it.player = null },
        )
    }
}
