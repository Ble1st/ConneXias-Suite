package de.ble1st.gallery.ui.editor

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import coil3.compose.AsyncImage
import de.ble1st.gallery.R
import de.ble1st.gallery.util.CropAspect
import de.ble1st.gallery.util.PhotoEditSaver
import de.ble1st.gallery.util.PhotoFilter
import de.ble1st.gallery.util.composeColorMatrix
import kotlinx.coroutines.launch

@Composable
fun PhotoEditorScreen(uri: Uri, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf(PhotoFilter.NONE) }
    var aspect by remember { mutableStateOf(CropAspect.ORIGINAL) }
    var isSaving by remember { mutableStateOf(false) }
    val savedMessage = stringResource(R.string.editor_saved)
    val saveFailedMessage = stringResource(R.string.editor_save_failed)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_desc_back))
                    }
                },
                actions = {
                    IconButton(
                        enabled = !isSaving,
                        onClick = {
                            isSaving = true
                            scope.launch {
                                val saved = PhotoEditSaver.saveEdited(context, uri, filter, aspect)
                                isSaving = false
                                Toast.makeText(context, if (saved != null) savedMessage else saveFailedMessage, Toast.LENGTH_SHORT).show()
                                if (saved != null) onSaved()
                            }
                        },
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.editor_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            AsyncImage(
                model = uri,
                contentDescription = null,
                contentScale = if (aspect == CropAspect.ORIGINAL) ContentScale.Fit else ContentScale.Crop,
                colorFilter = if (filter == PhotoFilter.NONE) null else ColorFilter.colorMatrix(filter.composeColorMatrix()),
                // Live-Vorschau nähert den Zuschnitt nur an (ContentScale.Crop füllt den
                // verfügbaren Bereich statt exakt das Zielseitenverhältnis als Rahmen zu zeigen) —
                // der tatsächlich gespeicherte Zuschnitt folgt exakt `centerCrop()`
                // (util/PhotoEditor.kt), unabhängig von dieser Vorschau-Vereinfachung.
                modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black),
            )

            Text(
                text = stringResource(R.string.editor_crop_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CropAspect.entries.forEach { option ->
                    AspectChip(option = option, selected = option == aspect, onClick = { aspect = option })
                }
            }

            Text(
                text = stringResource(R.string.editor_filter_label),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(PhotoFilter.entries) { option ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (option == filter) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { filter = option }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(text = filterLabel(option), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun AspectChip(option: CropAspect, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(text = aspectLabel(option), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun aspectLabel(option: CropAspect): String = when (option) {
    CropAspect.ORIGINAL -> stringResource(R.string.editor_crop_original)
    CropAspect.SQUARE -> "1:1"
    CropAspect.FOUR_THREE -> "4:3"
    CropAspect.SIXTEEN_NINE -> "16:9"
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
