package de.ble1st.camera.ui.capture

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.camera.core.Camera
import androidx.camera.core.ImageCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import de.ble1st.camera.data.camera.CameraController
import de.ble1st.camera.data.camera.CameraExtension
import de.ble1st.camera.data.camera.CaptureMode
import de.ble1st.camera.data.camera.LensFacing
import de.ble1st.camera.data.camera.ManualSensorRanges
import de.ble1st.camera.data.camera.VideoQuality
import de.ble1st.camera.data.settings.CameraSettingsStore
import de.ble1st.camera.data.storage.MediaStoreSaver
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class TimerOption(val seconds: Int) { OFF(0), THREE(3), TEN(10) }

data class CaptureUiState(
    val mode: CaptureMode = CaptureMode.PHOTO,
    val lensFacing: LensFacing = LensFacing.BACK,
    val flashMode: Int = ImageCapture.FLASH_MODE_OFF,
    val torchOn: Boolean = false,
    val gridEnabled: Boolean = false,
    val timerOption: TimerOption = TimerOption.OFF,
    val countdownSecondsLeft: Int? = null,
    val isRecording: Boolean = false,
    val recordingElapsedSeconds: Int = 0,
    val zoomRatio: Float = 1f,
    // Vorher fest 1f..8f im UI verdrahtet, unabhängig von der tatsächlichen Hardware — auf
    // Geräten mit kleinerem/größerem Zoombereich (z. B. reine 1x-Frontkamera oder ein 20x-Tele)
    // konnte der Regler entweder nie das Maximum erreichen oder einen ungültigen Bereich anbieten.
    // Wird bei jedem Bind aus `Camera.cameraInfo.zoomState` neu gelesen (s. `bindPreview`).
    val zoomRatioRange: ClosedFloatingPointRange<Float> = 1f..1f,
    // Camera2-Extensions (HDR/Nacht/Bokeh/Automatik, s. CameraExtension) — nur im Foto-Modus
    // verfügbar, die Verfügbarkeit wird erst nach dem Binden vom Gerät gemeldet (Extension-Support
    // ist geräte- *und* objektivabhängig, nicht vorab bekannt).
    val availableExtensions: Set<CameraExtension> = emptySet(),
    val extension: CameraExtension = CameraExtension.NONE,
    val videoQuality: VideoQuality = VideoQuality.DEFAULT,
    val exposureCompensationRange: IntRange = 0..0,
    val exposureCompensationIndex: Int = 0,
    val manualSensorRanges: ManualSensorRanges? = null,
    val manualControlsEnabled: Boolean = false,
    val manualIso: Int = 0,
    val manualShutterNanos: Long = 0L,
    val lastCaptureUri: Uri? = null,
    val lastCaptureIsVideo: Boolean = false,
    // Deckt das Fenster zwischen "Auslöser gedrückt" und "Foto fertig geschrieben" ab — ohne
    // dieses Flag zählte isBusy nur Countdown/Aufnahme, ein zweiter Tap auf den Auslöser während
    // eines noch laufenden takePhoto()-Aufrufs (I/O kann je nach Gerät spürbar dauern) konnte zwei
    // sich überlappende Aufnahmen auslösen.
    val isCapturingPhoto: Boolean = false,
    val errorMessage: String? = null,
) {
    val isBusy: Boolean get() = countdownSecondsLeft != null || isRecording || isCapturingPhoto
}

/**
 * Hält nur den [CameraController] (an den Application-Context gebunden, s. [bindPreview]) —
 * bewusst keine `PreviewView`/`LifecycleOwner`-Referenz im ViewModel selbst, um keine
 * Activity-Referenz über eine Konfigurationsänderung hinaus zu halten. `CaptureScreen` ruft
 * [bindPreview] stattdessen aus einem `LaunchedEffect(mode, lensFacing, extension)` und beim
 * Wiedereintritt aus dem Hintergrund erneut auf (s. dortige Kommentare).
 *
 * [AndroidViewModel] statt [androidx.lifecycle.ViewModel] seit 2026-09-03, ausschließlich wegen
 * der gespeicherten Sucher-Einstellungen: die müssen bereits im Anfangszustand stehen, bevor der
 * erste `LaunchedEffect`-Bind läuft — sonst bindet die Kamera einmal mit den Defaults und sofort
 * danach ein zweites Mal mit den geladenen Werten. Ein Laden aus der Komposition heraus
 * (`remember { }`) wäre der offensichtliche Weg gewesen, ist aber genau das, was Compose-Lint mit
 * `RememberReturnType` zu Recht verbietet. Der `Application`-Context ist prozesslanglebig, hier
 * also unbedenklich — im Unterschied zu einer Activity-Referenz.
 */
class CaptureViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    private var controller: CameraController? = null
    private var countdownJob: Job? = null
    private var recordingTimerJob: Job? = null

    // Nur dafür da, dass die Umschalter unten ihren neuen Wert persistieren können, ohne dass
    // jeder von ihnen einen Context als Parameter durchreichen müsste (die UI ruft sie als
    // Methodenreferenzen auf).
    private val settingsContext: Context = application

    init {
        // Zuletzt benutzte Sucher-Einstellungen sofort in den Anfangszustand ziehen, noch bevor
        // die erste Komposition ihn liest — s. Klassendoc.
        val saved = CameraSettingsStore.load(settingsContext)
        _uiState.update {
            it.copy(
                mode = saved.mode,
                lensFacing = saved.lensFacing,
                flashMode = saved.flashMode,
                gridEnabled = saved.gridEnabled,
                timerOption = saved.timerOption,
                extension = saved.extension,
                videoQuality = saved.videoQuality,
            )
        }
    }

    /** Die Videoqualität ist die einzige gespeicherte Einstellung, die außerhalb dieses
     * ViewModels geändert werden kann (Einstellungs-Bildschirm) — beim Wiedereintritt in den
     * Sucher neu einlesen, sonst bindet die nächste Videoaufnahme noch mit dem alten Wert. */
    fun refreshVideoQuality(context: Context) {
        val quality = CameraSettingsStore.loadVideoQuality(context.applicationContext)
        if (_uiState.value.videoQuality != quality) {
            _uiState.update { it.copy(videoQuality = quality) }
        }
    }

    fun bindPreview(context: Context, lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val appContext = context.applicationContext
        val cameraController = controller ?: CameraController(appContext).also { controller = it }
        val state = _uiState.value
        cameraController.bind(
            lifecycleOwner = lifecycleOwner,
            previewView = previewView,
            lensFacing = state.lensFacing,
            mode = state.mode,
            extensionRequested = state.extension,
            videoQuality = state.videoQuality,
            onZoomChanged = { ratio -> _uiState.update { it.copy(zoomRatio = ratio) } },
            onBound = { boundCamera ->
                cameraController.setFlashMode(state.flashMode)
                // Torch war vorher nach jedem Rebind (Modus-/Kamerawechsel, ON_RESUME) stillschweigend
                // aus, obwohl der UI-Schalter noch "an" zeigte — Hardware-Zustand und UI-Zustand
                // liefen auseinander. Zustand hier explizit erneut auf den Controller angewendet.
                cameraController.setTorch(state.torchOn)
                val zoomState = boundCamera.cameraInfo.zoomState.value
                val zoomRange = if (zoomState != null) {
                    zoomState.minZoomRatio..zoomState.maxZoomRatio
                } else {
                    1f..1f
                }
                val evRange = cameraController.exposureCompensationRange()
                // Vorher wurde die EV-Korrektur bei jedem Rebind stillschweigend auf 0
                // zurückgesetzt (nur der UI-Wert, nicht einmal auf den Controller angewendet) — ein
                // z. B. beim Kamerawechsel oder Return-aus-dem-Hintergrund verlorener manueller
                // EV-Wert war für Nutzende überraschend. Jetzt wird der vorherige Index beibehalten,
                // sofern er im (ggf. neuen) Bereich des frisch gebundenen Geräts noch gültig ist.
                val restoredEvIndex = state.exposureCompensationIndex.coerceIn(evRange.lower, evRange.upper)
                cameraController.setExposureCompensationIndex(restoredEvIndex)
                _uiState.update {
                    it.copy(
                        zoomRatioRange = zoomRange,
                        exposureCompensationRange = evRange.lower..evRange.upper,
                        exposureCompensationIndex = restoredEvIndex,
                    )
                }
                if (state.manualControlsEnabled) {
                    cameraController.setManualSensorControls(state.manualIso, state.manualShutterNanos)
                }
            },
            // Ein gespeicherter Extension-Modus, den das gerade gebundene Objektiv nicht anbietet
            // (Frontkamera, anderes Gerät), fällt still auf NONE zurück statt eine Auswahl
            // anzuzeigen, die beim Auslösen wirkungslos wäre — CameraController bindet in diesem
            // Fall ohnehin ohne Extension-Selektor (s. dortiges `useExtension`).
            onAvailableExtensions = { available ->
                _uiState.update {
                    it.copy(
                        availableExtensions = available,
                        extension = if (it.extension in available) it.extension else CameraExtension.NONE,
                    )
                }
            },
            onManualSensorSupport = { ranges ->
                _uiState.update {
                    if (ranges == null) {
                        it.copy(manualSensorRanges = null, manualControlsEnabled = false)
                    } else {
                        it.copy(
                            manualSensorRanges = ranges,
                            manualIso = if (it.manualIso in ranges.isoRange.lower..ranges.isoRange.upper) it.manualIso else ranges.isoRange.lower,
                            manualShutterNanos = if (it.manualShutterNanos in ranges.shutterNanosRange.lower..ranges.shutterNanosRange.upper) {
                                it.manualShutterNanos
                            } else {
                                ranges.shutterNanosRange.lower
                            },
                        )
                    }
                }
            },
            onError = { throwable -> _uiState.update { it.copy(errorMessage = describeBindError(context, throwable)) } },
        )
    }

    /** CameraX' rohe Bind-Fehlermeldung (z. B. "Camera2 CameraDevice.StateCallback onError X") ist
     * für Nutzende nicht verständlich — insbesondere wenn Warden die Kamera per
     * `DevicePolicyManager.setCameraDisabled` gesperrt hat, landete bisher genau diese kryptische
     * Rohmeldung im UI statt eines erklärenden Hinweises. `getCameraDisabled` direkt abgefragt
     * statt die Exception-Nachricht nach einem Textmuster zu durchsuchen (fragil/lokalisierungs-
     * abhängig) — der einzige zuverlässige Weg, den Warden-Sperrzustand zu erkennen. */
    private fun describeBindError(context: Context, throwable: Throwable): String? {
        val devicePolicyManager = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        val cameraDisabled = runCatching { devicePolicyManager?.getCameraDisabled(null) == true }.getOrDefault(false)
        return if (cameraDisabled) {
            context.getString(de.ble1st.camera.R.string.error_camera_disabled_by_policy)
        } else {
            throwable.message
        }
    }

    /** Läuft die Kamera-Hardware frei, sobald die App in den Hintergrund geht (ON_PAUSE) — kein
     * Foreground-Service, der eine unbeaufsichtigt weiterlaufende Aufnahme rechtfertigen würde
     * (bewusster v1-Scope, s. README "Noch nicht enthalten"), also auch keine Aufnahme, die im
     * Hintergrund weiterläuft. Eine aktive Videoaufnahme wird dabei sauber finalisiert statt
     * abgebrochen. */
    fun releaseCamera() {
        controller?.shutdown()
        recordingTimerJob?.cancel()
        countdownJob?.cancel()
        _uiState.update {
            it.copy(isRecording = false, recordingElapsedSeconds = 0, countdownSecondsLeft = null, isCapturingPhoto = false)
        }
    }

    /** Bricht einen laufenden Selbstauslöser-Countdown ab, ohne die Kamera zu lösen — vorher gab
     * es keine Möglichkeit, einen versehentlich gestarteten Timer zu stoppen, außer die Aufnahme
     * abzuwarten oder den Screen zu verlassen. */
    fun cancelCountdown() {
        countdownJob?.cancel()
        _uiState.update { it.copy(countdownSecondsLeft = null) }
    }

    fun setMode(mode: CaptureMode) {
        if (_uiState.value.isBusy || _uiState.value.mode == mode) return
        _uiState.update { it.copy(mode = mode) }
        CameraSettingsStore.saveMode(settingsContext, mode)
    }

    /** Sperrt den Aufnahmemodus auf `mode`, für den System-Kamera-Contract (ACTION_IMAGE_CAPTURE/
     * ACTION_VIDEO_CAPTURE, s. [de.ble1st.camera.nav.CaptureRequestInfo]) — anders als [setMode]
     * ignoriert diese Funktion `isBusy` nicht als Sperre, sondern läuft unbedingt einmalig beim
     * Bildschirmeintritt, damit ein Messenger, der ein Foto angefragt hat, nicht versehentlich ein
     * Video zurückbekommt.
     *
     * Speichert den Modus bewusst **nicht** (anders als [setMode]): dieser Wert kommt vom
     * aufrufenden Fremd-Programm, nicht vom Nutzer dieser App — ein Messenger, der einmalig ein
     * Video anfordert, soll nicht dafür sorgen, dass der Sucher beim nächsten regulären Start im
     * Videomodus aufgeht. */
    fun lockMode(mode: CaptureMode) {
        if (_uiState.value.mode != mode) _uiState.update { it.copy(mode = mode) }
    }

    fun switchLens() {
        if (_uiState.value.isBusy) return
        val next = if (_uiState.value.lensFacing == LensFacing.BACK) LensFacing.FRONT else LensFacing.BACK
        _uiState.update { it.copy(lensFacing = next) }
        CameraSettingsStore.saveLensFacing(settingsContext, next)
    }

    fun cycleFlash() {
        val next = when (_uiState.value.flashMode) {
            ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
        controller?.setFlashMode(next)
        _uiState.update { it.copy(flashMode = next) }
        CameraSettingsStore.saveFlashMode(settingsContext, next)
    }

    /** Dauerlicht wird als einzige Sucher-Einstellung bewusst nicht gespeichert — s.
     * [CameraSettingsStore]-Klassendoc (sichtbarer, aktiv Akku verbrauchender Hardware-Zustand). */
    fun toggleTorch() {
        val next = !_uiState.value.torchOn
        controller?.setTorch(next)
        _uiState.update { it.copy(torchOn = next) }
    }

    fun toggleGrid() {
        val next = !_uiState.value.gridEnabled
        _uiState.update { it.copy(gridEnabled = next) }
        CameraSettingsStore.saveGridEnabled(settingsContext, next)
    }

    fun cycleTimer() {
        val next = when (_uiState.value.timerOption) {
            TimerOption.OFF -> TimerOption.THREE
            TimerOption.THREE -> TimerOption.TEN
            TimerOption.TEN -> TimerOption.OFF
        }
        _uiState.update { it.copy(timerOption = next) }
        CameraSettingsStore.saveTimerOption(settingsContext, next)
    }

    /** Löst einen Rebind aus (s. `LaunchedEffect(mode, lensFacing, extension)` in
     * [de.ble1st.camera.ui.capture.CaptureScreen]) — Extension-Selektoren werden beim Binden
     * gewählt, nicht nachträglich auf eine laufende Session angewendet. [CameraExtension.NONE] ist
     * immer wählbar (kein Selektor nötig), jeder andere Modus nur, wenn das gebundene Objektiv ihn
     * meldet. */
    fun selectExtension(extension: CameraExtension) {
        val state = _uiState.value
        if (state.mode != CaptureMode.PHOTO) return
        if (extension != CameraExtension.NONE && extension !in state.availableExtensions) return
        if (state.extension == extension) return
        _uiState.update { it.copy(extension = extension) }
        CameraSettingsStore.saveExtension(settingsContext, extension)
    }

    fun toggleManualControls() {
        val next = !_uiState.value.manualControlsEnabled
        if (_uiState.value.manualSensorRanges == null) return
        _uiState.update { it.copy(manualControlsEnabled = next) }
        if (next) {
            controller?.setManualSensorControls(_uiState.value.manualIso, _uiState.value.manualShutterNanos)
        } else {
            controller?.setManualSensorControls(null, null)
        }
    }

    fun setManualIso(iso: Int) {
        _uiState.update { it.copy(manualIso = iso) }
        if (_uiState.value.manualControlsEnabled) {
            controller?.setManualSensorControls(iso, _uiState.value.manualShutterNanos)
        }
    }

    fun setManualShutterNanos(nanos: Long) {
        _uiState.update { it.copy(manualShutterNanos = nanos) }
        if (_uiState.value.manualControlsEnabled) {
            controller?.setManualSensorControls(_uiState.value.manualIso, nanos)
        }
    }

    fun setExposureCompensationIndex(index: Int) {
        controller?.setExposureCompensationIndex(index)
        _uiState.update { it.copy(exposureCompensationIndex = index) }
    }

    /** Von `CaptureScreen`s `OrientationEventListener` bei jeder Geräte-Drehung aufgerufen — hält
     * das Foto-/Video-Ausgabe-Rotationsziel unabhängig von der (bewusst fixen, s.
     * `AndroidManifest.xml`-Kommentar) Compose-UI-Ausrichtung nach. */
    fun updateTargetRotation(surfaceRotation: Int) {
        controller?.updateTargetRotation(surfaceRotation)
    }

    fun onZoomSliderChanged(ratio: Float) {
        controller?.setZoomRatio(ratio)
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /** Zentraler Auslöser: Foto sofort/nach Countdown; Video startet nach Countdown und stoppt bei
     * erneutem Antippen während laufender Aufnahme (kein zweiter Countdown fürs Stoppen). */
    fun onShutterPressed(context: Context) {
        val state = _uiState.value
        if (state.mode == CaptureMode.VIDEO && state.isRecording) {
            stopVideoRecording()
            return
        }
        if (state.isBusy) return

        val timerSeconds = state.timerOption.seconds
        if (timerSeconds == 0) {
            performCapture(context)
        } else {
            countdownJob = viewModelScope.launch {
                for (remaining in timerSeconds downTo 1) {
                    _uiState.update { it.copy(countdownSecondsLeft = remaining) }
                    delay(1000)
                }
                _uiState.update { it.copy(countdownSecondsLeft = null) }
                performCapture(context)
            }
        }
    }

    private fun performCapture(context: Context) {
        when (_uiState.value.mode) {
            CaptureMode.PHOTO -> takePhoto(context)
            CaptureMode.VIDEO -> startVideoRecording(context)
        }
    }

    private fun takePhoto(context: Context) {
        val cameraController = controller ?: return
        _uiState.update { it.copy(isCapturingPhoto = true) }
        cameraController.takePhoto(
            outputOptions = MediaStoreSaver.imageOutputOptions(context),
            onSaved = { uri -> _uiState.update { it.copy(lastCaptureUri = uri, lastCaptureIsVideo = false, isCapturingPhoto = false) } },
            onError = { exception -> _uiState.update { it.copy(errorMessage = exception.message, isCapturingPhoto = false) } },
        )
    }

    private fun startVideoRecording(context: Context) {
        controller?.startVideoRecording(
            outputOptions = MediaStoreSaver.videoOutputOptions(context),
            // s. CameraController.startVideoRecording-Kommentar: videoCapture kann kurz nach
            // einem Moduswechsel/ON_RESUME noch ungebunden sein — vorher gab es hier gar keine
            // Rückmeldung, ein Tap auf den Auslöser blieb kommentarlos wirkungslos.
            onError = { _uiState.update { it.copy(errorMessage = "Kamera ist gerade nicht aufnahmebereit (Rebind läuft noch)") } },
            // analyse.md ("RECORD_AUDIO Pflicht für Foto"): Mikrofonzugriff ist jetzt optional
            // (s. CameraPermission.kt) — fehlt er, nimmt CameraController stumm auf statt
            // abzustürzen oder den Aufnahmestart zu verweigern. Kurzer Hinweis statt einer
            // stillen, verwirrenden Funktionslücke (dieselbe Begründung, die vorher für die
            // Pflicht-Anfrage stand — hier jetzt als sichtbarer Hinweis statt als Blockade).
            onAudioUnavailable = { _uiState.update { it.copy(errorMessage = "Kein Mikrofonzugriff — Video wird ohne Ton aufgenommen") } },
        ) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    _uiState.update { it.copy(isRecording = true, recordingElapsedSeconds = 0) }
                    recordingTimerJob = viewModelScope.launch {
                        var elapsed = 0
                        while (isActive) {
                            delay(1000)
                            elapsed += 1
                            _uiState.update { it.copy(recordingElapsedSeconds = elapsed) }
                        }
                    }
                }
                is VideoRecordEvent.Finalize -> {
                    recordingTimerJob?.cancel()
                    _uiState.update {
                        if (event.hasError()) {
                            it.copy(isRecording = false, errorMessage = event.cause?.message)
                        } else {
                            it.copy(
                                isRecording = false,
                                lastCaptureUri = event.outputResults.outputUri,
                                lastCaptureIsVideo = true,
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun stopVideoRecording() {
        controller?.stopVideoRecording()
    }

    override fun onCleared() {
        controller?.shutdown()
    }
}
