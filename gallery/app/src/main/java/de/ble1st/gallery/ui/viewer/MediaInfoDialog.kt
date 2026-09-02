package de.ble1st.gallery.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.ble1st.gallery.R
import de.ble1st.gallery.data.media.MediaItem
import de.ble1st.gallery.util.MediaFormatters

/** Analog zu ConneXias Files' Eigenschaften-Dialog — Abmessungen/Größe/Datum/Pfad statt
 * java.io.File-Metadaten direkt aus dem bereits geladenen [MediaItem]. */
@Composable
fun MediaInfoDialog(item: MediaItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.info_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoRow(stringResource(R.string.info_dimensions), "${item.width} × ${item.height}")
                InfoRow(stringResource(R.string.info_size), MediaFormatters.formatSize(item.sizeBytes))
                InfoRow(stringResource(R.string.info_date), MediaFormatters.formatDate(item.dateSortMillis))
                InfoRow(stringResource(R.string.info_path), item.path)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.info_close)) }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
