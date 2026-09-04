package de.ble1st.camera.permission

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Anders als ConneXias Files' [de.ble1st.files]-Sonderberechtigung (MANAGE_EXTERNAL_STORAGE, nur
 * über einen Settings-Bildschirm zu gewähren) sind CAMERA und RECORD_AUDIO ganz normale
 * gefährliche Laufzeit-Berechtigungen — ein einziger
 * `ActivityResultContracts.RequestMultiplePermissions()`-Dialog genügt, kein Sonderfall pro
 * API-Level nötig.
 */
object CameraPermission {

    /** WRITE_EXTERNAL_STORAGE ist im Manifest mit maxSdkVersion=28 deklariert (s. dortiger
     * Kommentar), wurde aber nie angefragt — auf API 26–28 (vor Scoped Storage) scheiterte
     * MediaStore.insert auf die DCIM-Sammlung dadurch typischerweise mit einer
     * SecurityException. Auf API 29+ ist die Berechtigung ohnehin wirkungslos (`checkSelfPermission`
     * für eine per maxSdkVersion begrenzte Berechtigung liefert dort systemseitig immer GRANTED),
     * daher hier bewusst API-Level-gated statt unbedingt mit angefragt. */
    private val storageIfNeeded: List<String> = buildList {
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /**
     * analyse.md ("weiterhin gültig" — "RECORD_AUDIO Pflicht für Foto"): [required] enthielt bisher
     * auch RECORD_AUDIO, und [hasAccess] (das Onboarding-Gate in `CameraNavHost`) verlangte damit
     * implizit Mikrofonzugriff, um überhaupt ein einziges Foto aufnehmen zu können — eine Ablehnung
     * der Mikrofon-Berechtigung sperrte den kompletten Sucher, nicht nur den Ton in Videos. CAMERA
     * ist die einzige Berechtigung, ohne die die App grundsätzlich nichts anzeigen kann; RECORD_AUDIO
     * betrifft ausschließlich Ton *innerhalb* von Videos, ein rein optionales Feature verglichen mit
     * dem Sucher selbst.
     */
    val required: Array<String> = (listOf(Manifest.permission.CAMERA) + storageIfNeeded).toTypedArray()

    /** RECORD_AUDIO wird weiterhin im selben Dialog wie [required] mit angefragt (s.
     * [initialRequestSet]) — ein Video ohne Ton nach bereits erteiltem Kamerazugriff wäre sonst eine
     * stille, verwirrende Funktionslücke statt eines klaren Berechtigungs-Onboardings. Der
     * Unterschied zu vorher: eine Ablehnung blockiert nur noch den Ton in Videos
     * ([de.ble1st.camera.data.camera.CameraController.startVideoRecording] fragt [hasAudioAccess]
     * selbst ab und nimmt sonst stumm auf), nicht mehr den gesamten Sucher. Wird beim Umschalten in
     * den Videomodus erneut angefragt, falls noch nicht erteilt (s. `CaptureScreen.kt`) — für den
     * Fall, dass die erste Anfrage (Fotomodus) abgelehnt wurde, der Nutzer es aber für Video doch
     * will. */
    val initialRequestSet: Array<String> = (required.toList() + Manifest.permission.RECORD_AUDIO).toTypedArray()

    fun hasAccess(context: Context): Boolean = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun hasAudioAccess(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Liefert die einschließende Activity aus einem Context (Compose `LocalContext` ist eine
     *  Activity-Wrapper). Liefert null, falls die Context-Hierarchie unerwartet keine Activity
     *  enthält (z. B. Service-Context). */
    fun findActivity(context: Context): Activity? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return ctx as? Activity
    }

    /** Intent in die Detail-Einstellungen dieser App, wo der Nutzer nach einer dauerhaften
     *  Ablehnung ("Nicht mehr fragen") die Berechtigung manuell erteilen kann. */
    fun appDetailsSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

    /** Liefert true, wenn für mindestens eine der [required]-Berechtigungen noch eine erneute
     *  System-Anfrage möglich ist (rationale zeigbar). False bedeutet "dauerhaft abgelehnt" —
     *  dann bleibt nur [appDetailsSettingsIntent]. */
    fun canStillRequestRationale(activity: Activity): Boolean = required.any {
        activity.shouldShowRequestPermissionRationale(it)
    }
}
