package de.ble1st.camera.data.settings

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.core.content.edit
import de.ble1st.camera.data.camera.CameraExtension
import de.ble1st.camera.data.camera.CaptureMode
import de.ble1st.camera.data.camera.LensFacing
import de.ble1st.camera.data.camera.VideoQuality
import de.ble1st.camera.ui.capture.TimerOption

/**
 * Merkt sich die Sucher-Einstellungen über App-Neustarts hinweg. Bis 2026-09-03 fielen Blitz,
 * Raster, Selbstauslöser, Modus und Objektiv bei jedem Start auf die Defaults zurück — der
 * README-Punkt "Einstellungen werden nicht dauerhaft gespeichert", der nach zwanzig anderen
 * Kamera-Funktionen im Alltag am meisten gestört hat.
 *
 * Bewusst SharedPreferences statt DataStore, wie ConneXias Files' `ViewModePreference`: es geht um
 * eine Handvoll skalarer UI-Präferenzen, nicht um ein Datenmodell mit Beobachtern. Der einzige
 * Grund für DataStore wäre asynchrones Lesen — und genau das ist hier unerwünscht, weil die Werte
 * *vor* dem ersten Kamera-Bind feststehen müssen (s. `CaptureScreen`: `remember { restore() }`
 * läuft während der Komposition, nicht in einem `LaunchedEffect`). SharedPreferences hält die
 * Datei nach dem ersten Zugriff im Speicher, ein Lesen kostet danach keine I/O mehr.
 *
 * **Dauerlicht (Torch) wird bewusst nicht gespeichert.** Ein Gerät, das beim Öffnen der App
 * sofort die LED einschaltet, weil das vor drei Tagen einmal so war, ist überraschend und kostet
 * spürbar Akku — anders als bei Blitz/Raster/Timer ist der Zustand hier physisch sichtbar und
 * verbraucht aktiv Energie. Ebenfalls nicht gespeichert: EV-Korrektur und manuelle
 * ISO-/Verschlusszeitwerte, weil ihre gültigen Bereiche geräte- und objektivabhängig sind und
 * beim Rebind ohnehin neu ermittelt werden.
 *
 * Jeder Lesevorgang ist gegen einen unbekannten gespeicherten Wert abgesichert (App-Downgrade,
 * manipulierte Prefs-Datei): ein nicht auflösbarer Enum-Name fällt auf den Default zurück, statt
 * eine `IllegalArgumentException` bis in die Komposition durchzureichen.
 */
object CameraSettingsStore {
    private const val PREFS_FILE = "camera_settings"

    private const val KEY_MODE = "capture_mode"
    private const val KEY_LENS = "lens_facing"
    private const val KEY_FLASH = "flash_mode"
    private const val KEY_GRID = "grid_enabled"
    private const val KEY_TIMER = "timer_option"
    private const val KEY_EXTENSION = "camera_extension"
    private const val KEY_VIDEO_QUALITY = "video_quality"

    /** Was beim App-Start wiederhergestellt wird — bewusst ohne Torch/EV/Manuellwerte, s.
     * Klassendoc. [videoQuality] ist keine Sucher-Einstellung, sondern eine echte Einstellung aus
     * dem Einstellungs-Bildschirm, wird hier aber mitgeführt, weil sie aus derselben Datei kommt. */
    data class Snapshot(
        val mode: CaptureMode,
        val lensFacing: LensFacing,
        val flashMode: Int,
        val gridEnabled: Boolean,
        val timerOption: TimerOption,
        val extension: CameraExtension,
        val videoQuality: VideoQuality,
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private inline fun <reified T : Enum<T>> readEnum(raw: String?, fallback: T): T =
        raw?.let { name -> runCatching { enumValueOf<T>(name) }.getOrNull() } ?: fallback

    fun load(context: Context): Snapshot {
        val p = prefs(context)
        val flash = p.getInt(KEY_FLASH, ImageCapture.FLASH_MODE_OFF)
        return Snapshot(
            mode = readEnum(p.getString(KEY_MODE, null), CaptureMode.PHOTO),
            lensFacing = readEnum(p.getString(KEY_LENS, null), LensFacing.BACK),
            // Anders als bei den Enums gibt es hier keinen Namensabgleich, der einen unbekannten
            // Wert auffangen würde — ImageCapture.FLASH_MODE_* sind rohe Ints. Ein Wert außerhalb
            // der drei bekannten Konstanten würde von CameraX beim Setzen abgelehnt, deshalb hier
            // explizit auf "aus" zurückfallen.
            flashMode = if (flash in KNOWN_FLASH_MODES) flash else ImageCapture.FLASH_MODE_OFF,
            gridEnabled = p.getBoolean(KEY_GRID, false),
            timerOption = readEnum(p.getString(KEY_TIMER, null), TimerOption.OFF),
            extension = readEnum(p.getString(KEY_EXTENSION, null), CameraExtension.NONE),
            videoQuality = readEnum(p.getString(KEY_VIDEO_QUALITY, null), VideoQuality.DEFAULT),
        )
    }

    private val KNOWN_FLASH_MODES = setOf(
        ImageCapture.FLASH_MODE_OFF,
        ImageCapture.FLASH_MODE_AUTO,
        ImageCapture.FLASH_MODE_ON,
    )

    fun saveMode(context: Context, mode: CaptureMode) =
        prefs(context).edit { putString(KEY_MODE, mode.name) }

    fun saveLensFacing(context: Context, lensFacing: LensFacing) =
        prefs(context).edit { putString(KEY_LENS, lensFacing.name) }

    fun saveFlashMode(context: Context, flashMode: Int) =
        prefs(context).edit { putInt(KEY_FLASH, flashMode) }

    fun saveGridEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit { putBoolean(KEY_GRID, enabled) }

    fun saveTimerOption(context: Context, option: TimerOption) =
        prefs(context).edit { putString(KEY_TIMER, option.name) }

    fun saveExtension(context: Context, extension: CameraExtension) =
        prefs(context).edit { putString(KEY_EXTENSION, extension.name) }

    fun saveVideoQuality(context: Context, quality: VideoQuality) =
        prefs(context).edit { putString(KEY_VIDEO_QUALITY, quality.name) }

    fun loadVideoQuality(context: Context): VideoQuality =
        readEnum(prefs(context).getString(KEY_VIDEO_QUALITY, null), VideoQuality.DEFAULT)

    /** Setzt ausschließlich die *Sucher*-Einstellungen zurück (Modus, Objektiv, Blitz, Raster,
     * Timer, Extension) — die Videoqualität bleibt stehen, weil sie im Einstellungs-Bildschirm
     * bewusst gesetzt wurde und nicht beiläufig im Sucher entstanden ist. */
    fun resetViewfinderSettings(context: Context) {
        prefs(context).edit {
            remove(KEY_MODE)
            remove(KEY_LENS)
            remove(KEY_FLASH)
            remove(KEY_GRID)
            remove(KEY_TIMER)
            remove(KEY_EXTENSION)
        }
    }
}
