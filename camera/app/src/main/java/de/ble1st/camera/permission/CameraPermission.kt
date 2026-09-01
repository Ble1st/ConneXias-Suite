package de.ble1st.camera.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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
     * Funktionslücke statt eines klaren Berechtigungs-Onboardings. */
    val required: Array<String> = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

    fun hasAccess(context: Context): Boolean = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
