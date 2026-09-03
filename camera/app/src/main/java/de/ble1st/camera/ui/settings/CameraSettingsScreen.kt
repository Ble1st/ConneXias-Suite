package de.ble1st.camera.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import de.ble1st.camera.R
import de.ble1st.camera.data.camera.VideoQuality
import de.ble1st.camera.data.settings.CameraSettingsStore
import de.ble1st.camera.data.storage.MediaStoreSaver
import kotlinx.coroutines.launch

/**
 * Der erste eigene Einstellungs-Bildschirm dieser App (bis 2026-09-03 gab es keinen, s. README
 * "Noch nicht enthalten"). Bewusst klein gehalten: die allermeisten Aufnahmeeinstellungen gehören
 * in den Sucher, wo man sie beim Fotografieren braucht, und werden dort seit demselben Datum auch
 * gespeichert (s. [CameraSettingsStore]). Hier steht nur, was *nicht* in den Sucher gehört —
 * eine selten geänderte Grundeinstellung, eine reine Information und ein Zurücksetzen.
 *
 * Kein ViewModel: drei Werte, von denen einer gelesen und geschrieben wird, rechtfertigen keine
 * eigene Zustandsschicht. Das Lesen läuft synchron über SharedPreferences (s. dortiges Klassendoc
 * zur Begründung, warum das hier kein Problem ist).
 */
@Composable
fun CameraSettingsScreen(onBack: () -> Unit, onOpenAbout: () -> Unit) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var videoQuality by remember { mutableStateOf(CameraSettingsStore.loadVideoQuality(context)) }
    val resetDoneMessage = stringResource(R.string.settings_viewfinder_reset_done)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.settings_section_video),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Text(
                text = stringResource(R.string.settings_video_quality_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
            Column(modifier = Modifier.selectableGroup()) {
                VideoQuality.entries.forEach { quality ->
                    ListItem(
                        headlineContent = { Text(stringResource(videoQualityLabel(quality))) },
                        leadingContent = {
                            RadioButton(
                                selected = quality == videoQuality,
                                onClick = null,
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            // Rolle bewusst hier statt am RadioButton (der bekommt
                            // `onClick = null`): so ist die ganze Zeile ein einziges
                            // Bedienelement für die Bedienungshilfen statt zweier
                            // ineinanderliegender.
                            .selectable(
                                selected = quality == videoQuality,
                                role = Role.RadioButton,
                                onClick = {
                                    videoQuality = quality
                                    CameraSettingsStore.saveVideoQuality(context, quality)
                                },
                            ),
                    )
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.settings_section_storage),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            ListItem(
                headlineContent = { Text(MediaStoreSaver.RELATIVE_PATH) },
                supportingContent = { Text(stringResource(R.string.settings_storage_hint)) },
            )

            HorizontalDivider()

            Text(
                text = stringResource(R.string.settings_section_viewfinder),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            Text(
                text = stringResource(R.string.settings_viewfinder_reset_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Button(
                onClick = {
                    CameraSettingsStore.resetViewfinderSettings(context)
                    scope.launch { snackbarHostState.showSnackbar(resetDoneMessage) }
                },
                modifier = Modifier.padding(16.dp),
            ) {
                Text(stringResource(R.string.settings_viewfinder_reset))
            }

            HorizontalDivider()

            ListItem(
                headlineContent = { Text(stringResource(R.string.about_title)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAbout),
            )
        }
    }
}

private fun videoQualityLabel(quality: VideoQuality): Int = when (quality) {
    VideoQuality.SD -> R.string.settings_video_quality_sd
    VideoQuality.HD -> R.string.settings_video_quality_hd
    VideoQuality.FHD -> R.string.settings_video_quality_fhd
    VideoQuality.UHD -> R.string.settings_video_quality_uhd
}
