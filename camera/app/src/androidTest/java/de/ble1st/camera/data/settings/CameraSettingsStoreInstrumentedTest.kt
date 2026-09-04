package de.ble1st.camera.data.settings

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.ble1st.camera.data.camera.CameraExtension
import de.ble1st.camera.data.camera.CaptureMode
import de.ble1st.camera.data.camera.LensFacing
import de.ble1st.camera.data.camera.VideoQuality
import de.ble1st.camera.ui.capture.TimerOption
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Die Einstellungen aus dem Sucher müssen einen App-Neustart überleben — die Funktion, die laut
 * README-Punkt „Einstellungen werden nicht dauerhaft gespeichert" im Alltag am meisten gestört
 * hat. [CameraSettingsStore] liegt auf `SharedPreferences` und ist deshalb nur unter
 * Instrumentation prüfbar.
 *
 * Der zweite, wichtigere Teil: **kein gespeicherter Wert darf die App zum Absturz bringen.** Eine
 * heruntergestufte App oder eine manipulierte Prefs-Datei kann Enum-Namen enthalten, die es nicht
 * mehr gibt; `enumValueOf` würde dann mit `IllegalArgumentException` bis in die Komposition
 * durchschlagen.
 */
@RunWith(AndroidJUnit4::class)
class CameraSettingsStoreInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun rawPrefs() =
        context.getSharedPreferences("camera_settings", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        rawPrefs().edit { clear() }
    }

    @Test
    fun freshInstallLoadsTheDocumentedDefaults() {
        val snapshot = CameraSettingsStore.load(context)
        assertEquals(CaptureMode.PHOTO, snapshot.mode)
        assertEquals(LensFacing.BACK, snapshot.lensFacing)
        assertEquals(ImageCapture.FLASH_MODE_OFF, snapshot.flashMode)
        assertEquals(false, snapshot.gridEnabled)
        assertEquals(TimerOption.OFF, snapshot.timerOption)
        assertEquals(CameraExtension.NONE, snapshot.extension)
        assertEquals(VideoQuality.DEFAULT, snapshot.videoQuality)
    }

    @Test
    fun everySavedViewfinderSettingComesBack() {
        CameraSettingsStore.saveMode(context, CaptureMode.VIDEO)
        CameraSettingsStore.saveLensFacing(context, LensFacing.FRONT)
        CameraSettingsStore.saveFlashMode(context, ImageCapture.FLASH_MODE_AUTO)
        CameraSettingsStore.saveGridEnabled(context, true)
        CameraSettingsStore.saveTimerOption(context, TimerOption.TEN)
        CameraSettingsStore.saveExtension(context, CameraExtension.NIGHT)
        CameraSettingsStore.saveVideoQuality(context, VideoQuality.UHD)

        val snapshot = CameraSettingsStore.load(context)
        assertEquals(CaptureMode.VIDEO, snapshot.mode)
        assertEquals(LensFacing.FRONT, snapshot.lensFacing)
        assertEquals(ImageCapture.FLASH_MODE_AUTO, snapshot.flashMode)
        assertEquals(true, snapshot.gridEnabled)
        assertEquals(TimerOption.TEN, snapshot.timerOption)
        assertEquals(CameraExtension.NIGHT, snapshot.extension)
        assertEquals(VideoQuality.UHD, snapshot.videoQuality)
    }

    @Test
    fun unknownEnumNamesFallBackToDefaultsInsteadOfCrashing() {
        rawPrefs().edit {
            putString("capture_mode", "PANORAMA")
            putString("lens_facing", "SEITLICH")
            putString("timer_option", "FUENF")
            putString("camera_extension", "MAKRO")
            putString("video_quality", "8K")
        }
        val snapshot = CameraSettingsStore.load(context)
        assertEquals(CaptureMode.PHOTO, snapshot.mode)
        assertEquals(LensFacing.BACK, snapshot.lensFacing)
        assertEquals(TimerOption.OFF, snapshot.timerOption)
        assertEquals(CameraExtension.NONE, snapshot.extension)
        assertEquals(VideoQuality.DEFAULT, snapshot.videoQuality)
    }

    @Test
    fun unknownFlashModeFallsBackToOff() {
        // Blitz ist ein roher Int ohne Namensabgleich — ein Wert außerhalb der drei bekannten
        // Konstanten würde von CameraX beim Setzen abgelehnt.
        rawPrefs().edit { putInt("flash_mode", 4711) }
        assertEquals(ImageCapture.FLASH_MODE_OFF, CameraSettingsStore.load(context).flashMode)
    }

    @Test
    fun resetClearsViewfinderSettingsButKeepsVideoQuality() {
        // Die Videoqualität wurde bewusst im Einstellungs-Bildschirm gesetzt und ist nicht
        // beiläufig im Sucher entstanden — sie darf beim Zurücksetzen nicht mit verschwinden.
        CameraSettingsStore.saveMode(context, CaptureMode.VIDEO)
        CameraSettingsStore.saveGridEnabled(context, true)
        CameraSettingsStore.saveExtension(context, CameraExtension.BOKEH)
        CameraSettingsStore.saveVideoQuality(context, VideoQuality.SD)

        CameraSettingsStore.resetViewfinderSettings(context)

        val snapshot = CameraSettingsStore.load(context)
        assertEquals(CaptureMode.PHOTO, snapshot.mode)
        assertEquals(false, snapshot.gridEnabled)
        assertEquals(CameraExtension.NONE, snapshot.extension)
        assertEquals(VideoQuality.SD, snapshot.videoQuality)
        assertEquals(VideoQuality.SD, CameraSettingsStore.loadVideoQuality(context))
    }

    @Test
    fun loadVideoQualityAgreesWithTheFullSnapshot() {
        CameraSettingsStore.saveVideoQuality(context, VideoQuality.HD)
        assertEquals(
            CameraSettingsStore.load(context).videoQuality,
            CameraSettingsStore.loadVideoQuality(context),
        )
    }
}
