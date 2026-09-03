package de.ble1st.camera.ui.capture

import android.net.Uri
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.HdrOff
import androidx.compose.material.icons.filled.HdrOn
import androidx.compose.material.icons.filled.Timer10
import androidx.compose.material.icons.filled.Timer3
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import de.ble1st.camera.R
import de.ble1st.camera.data.camera.CaptureMode
import de.ble1st.camera.nav.CaptureRequestInfo
import de.ble1st.camera.permission.CameraPermission
import de.ble1st.camera.util.SecureScreenEffect
import kotlinx.coroutines.delay

/**
 * Vorschau-Bildschirm — alles hier liegt als halbtransparente Overlays über einer vollflächigen
 * [PreviewView] (AndroidView, s. Klassendoc [de.ble1st.camera.data.camera.CameraController]), nicht
 * in einem Scaffold mit eigenem Hintergrund, damit der Sucher wirklich randlos bleibt.
 */
@Composable
fun CaptureScreen(
    onOpenReview: (Uri, Boolean) -> Unit,
    captureRequestInfo: CaptureRequestInfo? = null,
    viewModel: CaptureViewModel = viewModel(),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsState()
    val previewView = remember { PreviewView(context) }

    SecureScreenEffect()

    // analyse.md ("RECORD_AUDIO Pflicht für Foto"): RECORD_AUDIO ist beim Onboarding jetzt
    // optional (s. CameraPermission.kt) — wer sie dort abgelehnt hat, aber später doch den
    // Videomodus wählt, bekommt hier eine zweite Chance, statt für immer auf stumme Aufnahmen
    // festzusitzen. Kein Blockieren des Moduswechsels selbst: ohne Mikrofon nimmt
    // CameraController stumm auf (s. CaptureViewModel.onAudioUnavailable), der Wechsel gelingt so
    // oder so.
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    val onSelectModeWithAudioPrompt: (CaptureMode) -> Unit = { mode ->
        if (mode == CaptureMode.VIDEO && !CameraPermission.hasAudioAccess(context)) {
            audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
        viewModel.setMode(mode)
    }

    // Sperrt den Foto-/Video-Umschalter auf den vom Aufrufer angeforderten Modus (System-Kamera-
    // Contract, s. CaptureRequestInfo-Klassendoc) — nur einmalig beim Eintritt, nicht bei jedem
    // Rebind, sonst würde ein späterer manueller Versuch, den Modus zu wechseln, sofort wieder
    // zurückgesetzt.
    LaunchedEffect(captureRequestInfo) {
        captureRequestInfo?.let { viewModel.lockMode(it.forcedMode) }
    }

    LaunchedEffect(state.mode, state.lensFacing, state.hdrEnabled) {
        viewModel.bindPreview(context, lifecycleOwner, previewView)
    }

    // Kamera-Hardware freigeben, sobald die App in den Hintergrund geht (ON_PAUSE) und beim
    // Wiedereintritt (ON_RESUME) neu binden — s. CaptureViewModel.releaseCamera()-Klassendoc.
    LifecycleEventEffect(lifecycleOwner) { event ->
        when (event) {
            Lifecycle.Event.ON_PAUSE -> viewModel.releaseCamera()
            Lifecycle.Event.ON_RESUME -> viewModel.bindPreview(context, lifecycleOwner, previewView)
            else -> Unit
        }
    }

    // Navigation Capture→Review verlässt diesen Composable, ohne dass ON_PAUSE feuert (dieselbe
    // Activity bleibt RESUMED) — ohne dieses onDispose blieb die Kamera-Hardware während der
    // gesamten Review-Ansicht gebunden (LED an, Session offen, unnötiger Akkuverbrauch/Privacy-
    // Eindruck). Der Rebind beim Zurückkommen passiert automatisch, weil LaunchedEffect oben bei
    // Wiedereintritt in die Komposition erneut feuert.
    DisposableEffect(Unit) {
        onDispose { viewModel.releaseCamera() }
    }

    // Bildschirm bleibt während jeder "beschäftigten" Phase an (Countdown, Foto wird geschrieben,
    // laufende Videoaufnahme) — vorher nur an `state.isRecording` geknüpft: ein Selbstauslöser-
    // Countdown oder ein spürbar langsames Foto-Schreiben (analyse.md, Mittel) konnten durch den
    // normalen Auto-Lock unterbrochen werden, mitten im Vorgang.
    SideEffect { previewView.keepScreenOn = state.isBusy }

    // Die Compose-UI bleibt bewusst im festen Hochformat-Layout (s. AndroidManifest.xml-Kommentar
    // zu `configChanges`) — trotzdem sollen Fotos/Videos unabhängig von der tatsächlichen
    // Gerätehaltung korrekt ausgerichtet gespeichert werden. Ein `OrientationEventListener` bildet
    // den rohen Sensorwinkel auf die nächstliegende 90°-`Surface.ROTATION_*`-Stufe ab und reicht
    // sie an CameraController weiter, die Vorschau selbst dreht sich nicht mit.
    val updateTargetRotation = rememberUpdatedState(viewModel::updateTargetRotation)
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when {
                    orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
                    orientation < 135 -> Surface.ROTATION_270
                    orientation < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
                updateTargetRotation.value(rotation)
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        if (state.gridEnabled) {
            GridOverlay(modifier = Modifier.fillMaxSize())
        }

        state.countdownSecondsLeft?.let { remaining ->
            Text(
                text = remaining.toString(),
                style = MaterialTheme.typography.displayLarge,
                color = Color.White,
                // Tippen bricht den Countdown ab, statt ihn abwarten zu müssen — vorher gab es
                // keine Möglichkeit, einen versehentlich gestarteten Selbstauslöser zu stoppen.
                modifier = Modifier
                    .align(Alignment.Center)
                    .clickable { viewModel.cancelCountdown() },
            )
        }

        if (state.isRecording) {
            RecordingIndicator(
                elapsedSeconds = state.recordingElapsedSeconds,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(top = 16.dp),
            )
        }

        TopControls(
            state = state,
            onCycleFlash = viewModel::cycleFlash,
            onToggleTorch = viewModel::toggleTorch,
            onToggleGrid = viewModel::toggleGrid,
            onCycleTimer = viewModel::cycleTimer,
            onToggleHdr = viewModel::toggleHdr,
            onToggleManual = viewModel::toggleManualControls,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(8.dp),
        )

        if (state.manualControlsEnabled && state.manualSensorRanges != null) {
            ManualControlsPanel(
                state = state,
                onIsoChanged = viewModel::setManualIso,
                onShutterChanged = viewModel::setManualShutterNanos,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(bottom = 220.dp, start = 24.dp, end = 24.dp),
            )
        } else if (state.exposureCompensationRange.last > state.exposureCompensationRange.first) {
            ExposureCompensationSlider(
                state = state,
                onChanged = viewModel::setExposureCompensationIndex,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(bottom = 220.dp, start = 24.dp, end = 24.dp),
            )
        }

        BottomControls(
            state = state,
            modeSwitchVisible = captureRequestInfo == null,
            onZoomChanged = viewModel::onZoomSliderChanged,
            onSelectMode = onSelectModeWithAudioPrompt,
            onSwitchLens = viewModel::switchLens,
            onShutter = { viewModel.onShutterPressed(context) },
            onOpenReview = onOpenReview,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars),
        )

        state.errorMessage?.let { message ->
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(16.dp)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.85f), MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            LaunchedEffect(message) {
                delay(3000)
                viewModel.clearError()
            }
        }
    }
}

@Composable
private fun LifecycleEventEffect(lifecycleOwner: LifecycleOwner, onEvent: (Lifecycle.Event) -> Unit) {
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> onEvent(event) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun GridOverlay(modifier: Modifier = Modifier) {
    // Klassisches Drittel-Raster (Fotografie-Kompositionshilfe) — zwei horizontale, zwei
    // vertikale Linien statt eines vollen 3x3-Netzes an den Rändern.
    Canvas(modifier = modifier) {
        val lineColor = Color.White.copy(alpha = 0.5f)
        val thirdWidth = size.width / 3f
        val thirdHeight = size.height / 3f
        for (i in 1..2) {
            drawLine(lineColor, Offset(thirdWidth * i, 0f), Offset(thirdWidth * i, size.height), strokeWidth = 1.dp.toPx())
            drawLine(lineColor, Offset(0f, thirdHeight * i), Offset(size.width, thirdHeight * i), strokeWidth = 1.dp.toPx())
        }
    }
}

@Composable
private fun RecordingIndicator(elapsedSeconds: Int, modifier: Modifier = Modifier) {
    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(modifier = Modifier.size(10.dp).background(Color.Red, CircleShape))
        Text(
            text = "%d:%02d".format(minutes, seconds),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun TopControls(
    state: CaptureUiState,
    onCycleFlash: () -> Unit,
    onToggleTorch: () -> Unit,
    onToggleGrid: () -> Unit,
    onCycleTimer: () -> Unit,
    onToggleHdr: () -> Unit,
    onToggleManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.35f)),
    ) {
        if (state.mode == CaptureMode.PHOTO) {
            IconButton(onClick = onCycleFlash) {
                val (icon, description) = when (state.flashMode) {
                    ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn to stringResource(R.string.capture_action_flash_on)
                    ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto to stringResource(R.string.capture_action_flash_auto)
                    else -> Icons.Filled.FlashOff to stringResource(R.string.capture_action_flash_off)
                }
                Icon(icon, contentDescription = description, tint = Color.White)
            }
        } else {
            IconButton(onClick = onToggleTorch) {
                val icon = if (state.torchOn) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff
                val description = stringResource(
                    if (state.torchOn) R.string.capture_action_torch_on else R.string.capture_action_torch_off,
                )
                Icon(icon, contentDescription = description, tint = Color.White)
            }
        }
        IconButton(onClick = onToggleGrid) {
            Icon(Icons.Filled.GridOn, contentDescription = stringResource(R.string.capture_action_grid), tint = Color.White)
        }
        IconButton(onClick = onCycleTimer) {
            val (icon, description) = when (state.timerOption) {
                TimerOption.OFF -> Icons.Filled.TimerOff to stringResource(R.string.capture_timer_off)
                TimerOption.THREE -> Icons.Filled.Timer3 to stringResource(R.string.capture_timer_3s)
                TimerOption.TEN -> Icons.Filled.Timer10 to stringResource(R.string.capture_timer_10s)
            }
            Icon(icon, contentDescription = description, tint = Color.White)
        }
        // Nur im Foto-Modus sichtbar: Extension-Selektoren unterstützen keine gleichzeitige
        // VideoCapture-Bindung (s. CameraController-Klassendoc).
        if (state.mode == CaptureMode.PHOTO && state.hdrAvailable) {
            IconButton(onClick = onToggleHdr) {
                val icon = if (state.hdrEnabled) Icons.Filled.HdrOn else Icons.Filled.HdrOff
                val description = stringResource(
                    if (state.hdrEnabled) R.string.capture_action_hdr_on else R.string.capture_action_hdr_off,
                )
                Icon(icon, contentDescription = description, tint = Color.White)
            }
        }
        if (state.manualSensorRanges != null) {
            IconButton(onClick = onToggleManual) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = stringResource(R.string.capture_action_manual),
                    tint = if (state.manualControlsEnabled) MaterialTheme.colorScheme.primary else Color.White,
                )
            }
        }
    }
}

/** Belichtungskorrektur (EV) — nur sichtbar, solange keine manuelle ISO-/Verschlusszeit-Steuerung
 * aktiv ist (Camera2 erlaubt AE-Korrektur nur bei eingeschalteter Auto-Belichtung, s.
 * `CameraController.setManualSensorControls`-Kommentar). */
@Composable
private fun ExposureCompensationSlider(state: CaptureUiState, onChanged: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.capture_label_ev, state.exposureCompensationIndex),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(64.dp),
        )
        Slider(
            value = state.exposureCompensationIndex.toFloat(),
            onValueChange = { onChanged(it.toInt()) },
            valueRange = state.exposureCompensationRange.first.toFloat()..state.exposureCompensationRange.last.toFloat(),
            steps = (state.exposureCompensationRange.last - state.exposureCompensationRange.first - 1)
                .coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Manuelle ISO-/Verschlusszeit-Regler — beide gemeinsam sichtbar, sobald der Manuell-Umschalter
 * in [TopControls] aktiviert ist (s. `CaptureViewModel.toggleManualControls`). Verschlusszeit wird
 * in ganzen Millisekunden statt Nanosekunden angezeigt (nutzerverständlicher als der
 * Camera2-Rohwert). */
@Composable
private fun ManualControlsPanel(
    state: CaptureUiState,
    onIsoChanged: (Int) -> Unit,
    onShutterChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ranges = state.manualSensorRanges ?: return
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.capture_label_iso, state.manualIso),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(64.dp),
            )
            Slider(
                value = state.manualIso.toFloat(),
                onValueChange = { onIsoChanged(it.toInt()) },
                valueRange = ranges.isoRange.lower.toFloat()..ranges.isoRange.upper.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val shutterMillis = state.manualShutterNanos / 1_000_000f
            Text(
                text = stringResource(R.string.capture_label_shutter, shutterMillis),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.width(64.dp),
            )
            Slider(
                value = state.manualShutterNanos.toFloat(),
                onValueChange = { onShutterChanged(it.toLong()) },
                valueRange = ranges.shutterNanosRange.lower.toFloat()..ranges.shutterNanosRange.upper.toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BottomControls(
    state: CaptureUiState,
    onZoomChanged: (Float) -> Unit,
    onSelectMode: (CaptureMode) -> Unit,
    onSwitchLens: () -> Unit,
    onShutter: () -> Unit,
    onOpenReview: (Uri, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    modeSwitchVisible: Boolean = true,
) {
    Column(modifier = modifier.padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Slider(
            value = state.zoomRatio,
            onValueChange = onZoomChanged,
            // Gerätespezifischer Bereich statt vorher fest 1f..8f — s. Kommentar an
            // CaptureUiState.zoomRatioRange.
            valueRange = state.zoomRatioRange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp),
        )
        // Ausgeblendet, solange ein Aufrufer per System-Kamera-Contract einen festen Modus
        // angefordert hat (s. CaptureScreen: viewModel.lockMode) — ein Umschalter, der sofort
        // wieder auf den angeforderten Modus zurückspringt, wäre nur verwirrend.
        if (modeSwitchVisible) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                ModeLabel(
                    text = stringResource(R.string.capture_mode_photo),
                    selected = state.mode == CaptureMode.PHOTO,
                    enabled = !state.isBusy,
                    onClick = { onSelectMode(CaptureMode.PHOTO) },
                )
                Spacer(modifier = Modifier.width(16.dp))
                ModeLabel(
                    text = stringResource(R.string.capture_mode_video),
                    selected = state.mode == CaptureMode.VIDEO,
                    enabled = !state.isBusy,
                    onClick = { onSelectMode(CaptureMode.VIDEO) },
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LastCaptureThumbnail(state = state, onOpenReview = onOpenReview)
            ShutterButton(mode = state.mode, isRecording = state.isRecording, isBusy = state.isBusy, onClick = onShutter)
            IconButton(onClick = onSwitchLens, enabled = !state.isBusy) {
                Icon(
                    Icons.Filled.Cameraswitch,
                    contentDescription = stringResource(R.string.capture_action_switch_camera),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun ModeLabel(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        color = if (selected) MaterialTheme.colorScheme.primary else Color.White,
        style = if (selected) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(8.dp),
    )
}

@Composable
private fun LastCaptureThumbnail(state: CaptureUiState, onOpenReview: (Uri, Boolean) -> Unit) {
    val uri = state.lastCaptureUri
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.DarkGray)
            .then(if (uri != null) Modifier.clickable { onOpenReview(uri, state.lastCaptureIsVideo) } else Modifier),
    ) {
        if (uri != null) {
            AsyncImage(
                model = uri,
                contentDescription = stringResource(R.string.capture_last_capture),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun ShutterButton(mode: CaptureMode, isRecording: Boolean, isBusy: Boolean, onClick: () -> Unit) {
    // Während einer laufenden Videoaufnahme bleibt der Auslöser aktiv (zum Stoppen) — sonst
    // (Countdown läuft, oder Foto wird gerade geschrieben) ist er gesperrt, damit kein zweiter
    // Auslöse-/Countdown-Vorgang parallel startet.
    val clickable = !isBusy || isRecording
    val description = stringResource(R.string.capture_action_shutter)
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.25f))
            .semantics { contentDescription = description }
            .clickable(enabled = clickable, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val fillColor = if (mode == CaptureMode.VIDEO && isRecording) Color.Red else Color.White
        Box(
            modifier = Modifier
                .size(if (isRecording) 28.dp else 56.dp)
                .clip(if (isRecording) RoundedCornerShape(6.dp) else CircleShape)
                .background(fillColor),
        )
    }
}
