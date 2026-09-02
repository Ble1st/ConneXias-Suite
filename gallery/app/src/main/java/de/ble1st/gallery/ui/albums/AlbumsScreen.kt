package de.ble1st.gallery.ui.albums

import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.ble1st.gallery.R
import de.ble1st.gallery.data.album.CustomAlbum
import de.ble1st.gallery.data.media.ALL_BUCKET_ID
import de.ble1st.gallery.data.media.Bucket
import de.ble1st.gallery.ui.GalleryViewModel

/** Einstiegsbildschirm: virtuelles "Alle"-Album, MediaStore-Buckets, dann eigene (virtuelle)
 * Alben — reine Einstiegspunkte in [de.ble1st.gallery.ui.grid.MediaGridScreen]/
 * [de.ble1st.gallery.ui.albums.CustomAlbumScreen], kein eigener Sortier-/Auswahlzustand hier. */
@Composable
fun AlbumsScreen(
    viewModel: GalleryViewModel,
    onOpenBucket: (Long, String) -> Unit,
    onOpenCustomAlbum: (String, String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenCloudSync: () -> Unit,
) {
    val buckets by viewModel.buckets.collectAsState()
    val allItems by viewModel.allItems.collectAsState()
    val customAlbums by viewModel.customAlbums.collectAsState()
    val allLabel = stringResource(R.string.album_all)
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.albums_title)) },
                actions = {
                    // Papierkorb existiert als MediaStore-Konzept erst ab API 30
                    // (MediaStore.createTrashRequest) — auf älteren Versionen kein Einstiegspunkt
                    // statt eines Screens, der dort dauerhaft leer bliebe.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        IconButton(onClick = onOpenTrash) {
                            Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.trash_title))
                        }
                    }
                    IconButton(onClick = onOpenCloudSync) {
                        Icon(Icons.Filled.CloudSync, contentDescription = stringResource(R.string.cloud_sync_title))
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            contentPadding = padding,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (allItems.isNotEmpty()) {
                item {
                    AlbumTile(
                        name = allLabel,
                        coverUri = allItems.first().uri,
                        itemCount = allItems.size,
                        onClick = { onOpenBucket(ALL_BUCKET_ID, allLabel) },
                    )
                }
            }
            items(buckets, key = { "bucket_${it.id}" }) { bucket ->
                AlbumTile(
                    name = bucket.name,
                    coverUri = bucket.coverUri,
                    itemCount = bucket.itemCount,
                    onClick = { onOpenBucket(bucket.id, bucket.name) },
                )
            }
            items(customAlbums, key = { "custom_${it.id}" }) { album ->
                CustomAlbumTile(
                    album = album,
                    coverUri = allItems.find { it.id in album.itemIds }?.uri,
                    itemCount = viewModel.liveItemCount(album),
                    onClick = { onOpenCustomAlbum(album.id, album.name) },
                )
            }
            item(key = "create_album") {
                CreateAlbumTile(onClick = { showCreateDialog = true })
            }
        }
    }

    if (showCreateDialog) {
        CreateAlbumDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name ->
                showCreateDialog = false
                val album = viewModel.createCustomAlbum(name)
                onOpenCustomAlbum(album.id, album.name)
            },
        )
    }
}

@Composable
private fun CreateAlbumDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.album_create_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.album_name_label)) },
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()) }) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun AlbumTile(name: String, coverUri: Uri, itemCount: Int, onClick: () -> Unit) {
    Column(modifier = Modifier.padding(8.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = coverUri,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = pluralStringResource(R.plurals.album_item_count, itemCount, itemCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CustomAlbumTile(album: CustomAlbum, coverUri: Uri?, itemCount: Int, onClick: () -> Unit) {
    Column(modifier = Modifier.padding(8.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUri != null) {
                AsyncImage(
                    model = coverUri,
                    contentDescription = album.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Filled.PhotoAlbum, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            text = album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = pluralStringResource(R.plurals.album_item_count, itemCount, itemCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CreateAlbumTile(onClick: () -> Unit) {
    Column(modifier = Modifier.padding(8.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.album_create_title))
        }
        Text(
            text = stringResource(R.string.album_create_title),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
