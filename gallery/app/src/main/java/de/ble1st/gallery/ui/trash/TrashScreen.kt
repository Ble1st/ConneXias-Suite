package de.ble1st.gallery.ui.trash

import android.app.Activity
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.ble1st.gallery.R
import de.ble1st.gallery.data.media.MediaItem
import de.ble1st.gallery.data.media.MediaStoreRepository
import de.ble1st.gallery.util.DeleteOutcome
import de.ble1st.gallery.util.MediaActions

/**
 * Papierkorb — nur ab API 30 erreichbar (`MediaStore.createTrashRequest`/`QUERY_ARG_MATCH_TRASHED`
 * existieren nicht davor, s. [MediaStoreRepository]-Klassendoc); der Einstiegspunkt in
 * [de.ble1st.gallery.ui.albums.AlbumsScreen] ist auf älteren Versionen bereits ausgeblendet.
 * Eigenständiger `produceState`-Datenfluss statt eines Anschlusses an [de.ble1st.gallery.ui.GalleryViewModel]
 * — Papierkorb-Inhalte sind normalerweise leer/selten genutzt, ein eigener `ContentObserver` nur
 * für diesen Screen ist günstiger als ihn dauerhaft im Haupt-ViewModel mitzuführen.
 */
@RequiresApi(Build.VERSION_CODES.R)
@Composable
fun TrashScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val trashedItems by produceState(initialValue = emptyList<MediaItem>()) {
        MediaStoreRepository.observeTrashedMedia(context).collect { value = it }
    }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val selectionActive = selection.isNotEmpty()

    // s. ImageViewerScreen-Kommentar (ConneXias Galerie, gleiche Ursache): Abbrechen im
    // System-Dialog darf die Auswahl nicht kommentarlos leeren, als wäre die Aktion durchgelaufen
    // — sonst müsste der Nutzer nach einem Abbruch die Auswahl erneut zusammenklicken.
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> if (result.resultCode == Activity.RESULT_OK) selection = emptySet() }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> if (result.resultCode == Activity.RESULT_OK) selection = emptySet() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectionActive) {
                            pluralStringResource(R.plurals.selection_count, selection.size, selection.size)
                        } else {
                            stringResource(R.string.trash_title)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (selectionActive) selection = emptySet() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                    }
                },
                actions = {
                    if (selectionActive) {
                        IconButton(onClick = { selection = trashedItems.map { it.id }.toSet() }) {
                            Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.action_select_all))
                        }
                        IconButton(
                            onClick = {
                                val uris = trashedItems.filter { it.id in selection }.map { it.uri }
                                val intentSender = MediaActions.requestTrash(context, uris, trashed = false)
                                restoreLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                            },
                        ) {
                            Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.trash_restore))
                        }
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.DeleteForever, contentDescription = stringResource(R.string.trash_delete_forever))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (trashedItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.trash_empty))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 100.dp),
                contentPadding = padding,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(trashedItems, key = { it.id }) { item ->
                    Box(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .padding(1.dp)
                            .combinedClickable(
                                onClick = { selection = if (item.id in selection) selection - item.id else selection + item.id },
                                onLongClick = { selection = if (item.id in selection) selection - item.id else selection + item.id },
                            )
                            // s. MediaGridScreen. Anders als dort ohne Bedingung: im Papierkorb
                            // ist Antippen immer Auswählen, einen Nicht-Auswahlmodus gibt es nicht.
                            .semantics { selected = item.id in selection },
                    ) {
                        AsyncImage(
                            model = item.uri,
                            contentDescription = item.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (item.id in selection) {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)))
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                            )
                        } else {
                            Icon(
                                Icons.Filled.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.trash_delete_forever)) },
            text = { Text(pluralStringResource(R.plurals.trash_delete_forever_body, selection.size, selection.size)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    val uris = trashedItems.filter { it.id in selection }.map { it.uri }
                    when (val outcome = MediaActions.requestDelete(context, uris)) {
                        is DeleteOutcome.Deleted -> selection = emptySet()
                        is DeleteOutcome.NeedsConfirmation ->
                            deleteLauncher.launch(IntentSenderRequest.Builder(outcome.intentSender).build())
                    }
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
