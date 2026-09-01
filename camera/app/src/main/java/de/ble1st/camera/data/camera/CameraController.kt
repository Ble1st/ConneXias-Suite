// androidx.annotation.OptIn statt Kotlin-eigenem kotlin.OptIn: ExperimentalCamera2Interop trägt
// die Java-Annotation androidx.annotation.RequiresOptIn (nicht Kotlins kotlin.RequiresOptIn) —
// nur die AndroidX-eigene OptIn-Variante wird von Lints UnsafeOptInUsageError-Check erkannt.
@file:androidx.annotation.OptIn(markerClass = [androidx.camera.camera2.interop.ExperimentalCamera2Interop::class])

package de.ble1st.camera.data.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.util.Range
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

enum class LensFacing { BACK, FRONT }
enum class CaptureMode { PHOTO, VIDEO }

/** Sensorbereiche für manuelle ISO-/Verschlusszeit-Regler — `null`, wenn das gebundene Gerät
 * keine `MANUAL_SENSOR`-Fähigkeit meldet (z. B. `LEGACY`-Hardwarelevel), dann bleibt der Regler
 * in der UI ausgeblendet statt Werte anzubieten, die die Hardware ohnehin ignoriert. */
data class ManualSensorRanges(val isoRange: Range<Int>, val shutterNanosRange: Range<Long>)

/**
 * Kapselt sämtliches CameraX-Use-Case-Wiring hinter einer schlanken API für [de.ble1st.camera.ui.capture.CaptureViewModel]
 * — Preview/ImageCapture/VideoCapture werden bewusst nicht alle drei gleichzeitig gebunden: einige
 * Geräte mit `LEGACY`-Camera2-Hardwarelevel unterstützen diese Kombination nicht gleichzeitig
 * (CameraX wirft dort eine `IllegalArgumentException` beim Binden). Stattdessen wird je nach
 * [CaptureMode] neu gebunden (Preview+ImageCapture ODER Preview+VideoCapture) — funktioniert
 * dadurch auf alle Geräteklassen, kostet nur einen kurzen Preview-Reset beim Moduswechsel.
 *
 * HDR läuft über [ExtensionsManager] (Camera2-Extensions, vom Gerätehersteller im HAL
 * bereitgestellt) statt über eine eigene Mehrfachbelichtungs-Fusion — nur für den Foto-Modus
 * verfügbar (die Extension-Selektoren unterstützen keine gleichzeitige `VideoCapture`-Bindung).
 * Manuelle ISO-/Verschlusszeit-Kontrolle läuft über [Camera2CameraControl]/[CaptureRequestOptions]
 * (Camera2-Interop) statt einer eigenen Camera2-Session — bleibt dadurch innerhalb von CameraX'
 * Lifecycle-Bindung statt eine parallele Camera2-Pipeline aufzubauen.
 */
class CameraController(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    // Hält den Zoom-Regler in der UI (CaptureViewModel-State) mit der tatsächlichen
    // Kamera-Zoomstufe synchron, egal ob die Änderung vom Regler selbst oder von der
    // Pinch-Geste in attachGestures() kommt — eine einzelne Quelle der Wahrheit statt zweier
    // unabhängig driftender Zoom-Werte.
    private var onZoomChanged: ((Float) -> Unit)? = null

    val hasActiveRecording: Boolean get() = activeRecording != null

    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lensFacing: LensFacing,
        mode: CaptureMode,
        hdrRequested: Boolean,
        onZoomChanged: (Float) -> Unit,
        onBound: (Camera) -> Unit,
        onHdrAvailability: (Boolean) -> Unit,
        onManualSensorSupport: (ManualSensorRanges?) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        this.onZoomChanged = onZoomChanged
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                try {
                    val provider = providerFuture.get().also { cameraProvider = it }
                    val baseSelector = CameraSelector.Builder()
                        .requireLensFacing(
                            if (lensFacing == LensFacing.FRONT) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK,
                        )
                        .build()

                    // ExtensionsManager wird bei jedem Bind neu abgefragt (statt einmalig
                    // gecacht): Verfügbarkeit hängt vom `baseSelector` (Front/Rückkamera) ab, ein
                    // Kamerawechsel kann die HDR-Verfügbarkeit also ändern.
                    val extFuture = ExtensionsManager.getInstanceAsync(context, provider)
                    extFuture.addListener(
                        {
                            try {
                                val extManager = extFuture.get().also { extensionsManager = it }
                                val hdrAvailable = extManager.isExtensionAvailable(baseSelector, ExtensionMode.HDR)
                                onHdrAvailability(hdrAvailable)
                                val selector = if (mode == CaptureMode.PHOTO && hdrRequested && hdrAvailable) {
                                    extManager.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.HDR)
                                } else {
                                    baseSelector
                                }
                                continueBind(
                                    lifecycleOwner,
                                    previewView,
                                    provider,
                                    selector,
                                    mode,
                                    onBound,
                                    onManualSensorSupport,
                                    onError,
                                )
                            } catch (t: Throwable) {
                                onError(t)
                            }
                        },
                        ContextCompat.getMainExecutor(context),
                    )
                } catch (t: Throwable) {
                    onError(t)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun continueBind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        mode: CaptureMode,
        onBound: (Camera) -> Unit,
        onManualSensorSupport: (ManualSensorRanges?) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        try {
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val useCases = mutableListOf<UseCase>(preview)
            when (mode) {
                CaptureMode.PHOTO -> {
                    val capture = ImageCapture.Builder().build()
                    imageCapture = capture
                    videoCapture = null
                    useCases += capture
                }
                CaptureMode.VIDEO -> {
                    // FHD statt HIGHEST: hält Dateigröße/Encoder-Last moderat, mit
                    // automatischem Downgrade auf ein von der Hardware unterstütztes
                    // niedrigeres Profil statt eines harten Fehlers auf schwacher Hardware.
                    val recorder = Recorder.Builder()
                        .setQualitySelector(
                            QualitySelector.from(Quality.FHD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)),
                        )
                        .build()
                    val capture = VideoCapture.withOutput(recorder)
                    videoCapture = capture
                    imageCapture = null
                    useCases += capture
                }
            }

            provider.unbindAll()
            val boundCamera = provider.bindToLifecycle(lifecycleOwner, selector, *useCases.toTypedArray())
            camera = boundCamera
            attachGestures(previewView)
            // Jeder Rebind (Modus-/Kamerawechsel) startet wieder bei 1x statt der alten
            // Zoomstufe der vorherigen Kamera — Front-/Rückkamera haben oft
            // unterschiedliche min/max-Zoombereiche, ein übernommener Wert könnte
            // außerhalb des neuen Bereichs liegen.
            onZoomChanged?.invoke(1f)
            onManualSensorSupport(manualSensorRanges(boundCamera))
            onBound(boundCamera)
        } catch (t: Throwable) {
            onError(t)
        }
    }

    fun setFlashMode(@ImageCapture.FlashMode mode: Int) {
        imageCapture?.flashMode = mode
    }

    fun setTorch(enabled: Boolean) {
        camera?.cameraControl?.enableTorch(enabled)
    }

    fun currentZoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    fun setZoomRatio(ratio: Float) {
        val zoomState = camera?.cameraInfo?.zoomState?.value ?: return
        val clamped = ratio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        camera?.cameraControl?.setZoomRatio(clamped)
        onZoomChanged?.invoke(clamped)
    }

    /** Aktueller Belichtungskorrektur-Bereich (in EV-Schritten, geräteabhängig) — `0..0`, wenn das
     * Gerät keine Korrektur unterstützt; die UI blendet den Regler dann aus. */
    fun exposureCompensationRange(): Range<Int> =
        camera?.cameraInfo?.exposureState?.exposureCompensationRange ?: Range(0, 0)

    fun setExposureCompensationIndex(index: Int) {
        val range = exposureCompensationRange()
        // setExposureCompensationIndex wirft IllegalArgumentException außerhalb des Bereichs —
        // ein Regler, der kurz einen ungültigen Zwischenwert meldet (Compose-Recomposition-Timing),
        // soll die Kamera-Session nicht zum Absturz bringen.
        runCatching { camera?.cameraControl?.setExposureCompensationIndex(index.coerceIn(range.lower, range.upper)) }
    }

    /** Liest ISO-/Verschlusszeit-Bereiche direkt aus den Camera2-Characteristics des gebundenen
     * Geräts — `null`, wenn `REQUEST_AVAILABLE_CAPABILITIES` kein `MANUAL_SENSOR` enthält (z. B.
     * `LEGACY`-Hardwarelevel), dann würden gesetzte Werte ohnehin von der Hardware ignoriert. */
    private fun manualSensorRanges(boundCamera: Camera): ManualSensorRanges? {
        val characteristics = Camera2CameraInfo.from(boundCamera.cameraInfo)
        val capabilities = characteristics.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val supportsManualSensor = capabilities
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        if (!supportsManualSensor) return null
        val isoRange = characteristics.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val shutterRange = characteristics.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (isoRange == null || shutterRange == null) return null
        return ManualSensorRanges(isoRange, shutterRange)
    }

    /** `iso`/`shutterNanos` beide `null` schaltet zurück auf automatische Belichtung
     * (`CONTROL_AE_MODE_ON`) — ein einzelner `null`-Wert bei aktivem manuellem Modus wird nicht
     * unterstützt (Camera2 verlangt AE entweder ganz an oder ganz aus), die UI bietet daher beide
     * Regler immer gemeinsam an. */
    fun setManualSensorControls(iso: Int?, shutterNanos: Long?) {
        val cameraControl = camera?.cameraControl ?: return
        val camera2Control = Camera2CameraControl.from(cameraControl)
        val options = CaptureRequestOptions.Builder().apply {
            if (iso != null && shutterNanos != null) {
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterNanos)
            } else {
                setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }
        }.build()
        camera2Control.captureRequestOptions = options
    }

    fun updateTargetRotation(rotation: Int) {
        imageCapture?.targetRotation = rotation
        videoCapture?.targetRotation = rotation
    }

    fun takePhoto(
        outputOptions: ImageCapture.OutputFileOptions,
        onSaved: (Uri?) -> Unit,
        onError: (ImageCaptureException) -> Unit,
    ) {
        val capture = imageCapture ?: return
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) = onSaved(output.savedUri)
                override fun onError(exception: ImageCaptureException) = onError(exception)
            },
        )
    }

    // Lint kann nicht sehen, dass CameraPermission.hasAccess() (CAMERA + RECORD_AUDIO) bereits
    // Voraussetzung für das Erreichen des Capture-Screens ist (s. CameraNavHost' Onboarding-Route)
    // — withAudioEnabled() kann diesen Screen nie ohne bereits erteilte Berechtigung erreichen,
    // eine erneute Laufzeitprüfung hier wäre totes Code-Duplikat.
    @SuppressLint("MissingPermission")
    fun startVideoRecording(outputOptions: MediaStoreOutputOptions, onEvent: (VideoRecordEvent) -> Unit) {
        val capture = videoCapture ?: return
        activeRecording = capture.output
            .prepareRecording(context, outputOptions)
            .withAudioEnabled()
            .start(ContextCompat.getMainExecutor(context), onEvent)
    }

    fun stopVideoRecording() {
        activeRecording?.stop()
        activeRecording = null
    }

    /** Pinch-Zoom (ScaleGestureDetector auf `cameraControl.setZoomRatio`) + Tap-to-Focus
     * (GestureDetector auf `cameraControl.startFocusAndMetering`) — beide über denselben
     * `setOnTouchListener`, weil PreviewView nur einen einzigen Touch-Listener gleichzeitig
     * erlaubt. */
    private fun attachGestures(previewView: PreviewView) {
        val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    setZoomRatio(currentZoomRatio() * detector.scaleFactor)
                    return true
                }
            },
        )
        val tapDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                    camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                    return true
                }
            },
        )
        previewView.setOnTouchListener { view, event ->
            scaleDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            view.performClick()
            true
        }
    }

    fun shutdown() {
        stopVideoRecording()
        cameraProvider?.unbindAll()
    }
}
