package de.ble1st.camera.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Anders als ConneXias Files' [de.ble1st.files]-Sonderberechtigung (MANAGE_EXTERNAL_STORAGE, nur
 * über einen Settings-Bildschirm zu gewähren) sind CAMERA und RECORD_AUDIO ganz normale
 * gefährliche Laufzeit-Berechtigungen — ein einziger
 * `ActivityResultContracts.RequestMultiplePermissions()`-Dialog genügt, kein Sonderfall pro
 * API-Level nötig.
 */
object CameraPermission {

    /** RECORD_AUDIO ist Teil der Kern-Anfrage (nicht erst beim ersten Videostart nachgefordert):
     * ein Video ohne Ton nach bereits erteiltem Kamerazugriff wäre eine stille, verwirrende
     * Funktionslücke statt eines klaren Berechtigungs-Onboardings.
     *
     * WRITE_EXTERNAL_STORAGE ist im Manifest mit maxSdkVersion=28 deklariert (s. dortiger
     * Kommentar), wurde aber nie angefragt — auf API 26–28 (vor Scoped Storage) scheiterte
     * MediaStore.insert auf die DCIM-Sammlung dadurch typischerweise mit einer
     * SecurityException. Auf API 29+ ist die Berechtigung ohnehin wirkungslos (`checkSelfPermission`
     * für eine per maxSdkVersion begrenzte Berechtigung liefert dort systemseitig immer GRANTED),
     * daher hier bewusst API-Level-gated statt unbedingt mit angefragt. */
    val required: Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    fun hasAccess(context: Context): Boolean = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
