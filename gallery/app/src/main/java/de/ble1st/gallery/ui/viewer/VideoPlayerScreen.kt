package de.ble1st.gallery.ui.viewer

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import de.ble1st.gallery.R
import de.ble1st.gallery.data.media.MediaItem
import de.ble1st.gallery.ui.GalleryViewModel
import de.ble1st.gallery.util.DeleteOutcome
import de.ble1st.gallery.util.MediaActions

/**
 * 1:1-Muster von ConneXias Files' `VideoPlayerScreen` (Media3 ExoPlayer, Pause-bei-ON_PAUSE/
 * Release-beim-Verlassen) — kein Pager (anders als [ImageViewerScreen]): Durchblättern mehrerer
 * Videos hintereinander ist kein v1-Ziel, jedes gleichzeitig vorbereitete ExoPlayer-Pageset wäre
 * unnötig teuer für einen seltenen Anwendungsfall.
 */
@Composable
fun VideoPlayerScreen(item: MediaItem, viewModel: GalleryViewModel, onBack: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }

    val exoPlayer = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(ExoMediaItem.fromUri(item.uri))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) exoPlayer.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(exoPlayer) { onDispose { exoPlayer.release() } }

    // s. ImageViewerScreen-Kommentar: Abbrechen im System-Dialog darf nicht wie ein Löscherfolg
    // behandelt werden.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> if (result.resultCode == Activity.RESULT_OK) { viewModel.onItemsDeleted(); onDeleted() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                    }
                },
                actions = {
                    IconButton(onClick = { MediaActions.share(context, listOf(item.uri)) }) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.action_share))
                    }
                    IconButton(onClick = { MediaActions.openWith(context, item.uri) }) {
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
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { ctx -> PlayerView(ctx).apply { player = exoPlayer; useController = true } },
            onRelease = { it.player = null },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(pluralStringResource(R.plurals.delete_confirm_body, 1, 1)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    when (val outcome = MediaActions.requestRemove(context, listOf(item.uri))) {
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
        MediaInfoDialog(item = item, onDismiss = { showInfo = false })
    }
}
