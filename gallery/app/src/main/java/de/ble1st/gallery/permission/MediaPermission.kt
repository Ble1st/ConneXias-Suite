package de.ble1st.gallery.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Welche Laufzeit-Berechtigung(en) zum Lesen von Bildern/Videos nötig sind, hängt vom API-Level
 * ab — anders als ConneXias Files' Sonderberechtigung (MANAGE_EXTERNAL_STORAGE) sind das überall
 * ganz normale gefährliche Berechtigungen, ein einziger
 * `ActivityResultContracts.RequestMultiplePermissions()`-Dialog genügt:
 *
 * - API 33+ (Photo Picker Permissions): getrennte READ_MEDIA_IMAGES/READ_MEDIA_VIDEO statt der
 *   pauschalen READ_EXTERNAL_STORAGE.
 * - API 29–32: READ_EXTERNAL_STORAGE genügt zum Lesen; Löschen fremder Einträge läuft dort über
 *   `MediaStore.createDeleteRequest`/`RecoverableSecurityException` (s. MediaActions.kt), ohne
 *   zusätzliche Schreibberechtigung.
 * - API 26–28 (vor Scoped Storage): zusätzlich WRITE_EXTERNAL_STORAGE, weil Löschen dort nur über
 *   direkten `ContentResolver.delete` mit Schreibrecht funktioniert (kein
 *   `createDeleteRequest`/`RecoverableSecurityException` vor API 29).
 */
object MediaPermission {

    val required: Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else ->
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    fun hasAccess(context: Context): Boolean = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
