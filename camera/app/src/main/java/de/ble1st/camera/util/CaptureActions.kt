package de.ble1st.camera.util

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import de.ble1st.camera.R

/**
 * MediaStore liefert für Aufnahmen bereits `content://`-Uris — anders als ConneXias Files'
 * `FileActions` (das erst über `FileProvider` aus einem `java.io.File` eine solche Uri bauen muss)
 * braucht diese App dafür keinen eigenen Provider (s. AndroidManifest.xml).
 */
object CaptureActions {

    fun share(context: Context, uri: Uri) {
        val mimeType = context.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_STREAM, uri)
            type = mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // analyse.md (2. Durchgang, Mittel): EXTRA_STREAM + FLAG_GRANT_READ_URI_PERMISSION
            // allein reicht bei manchen Ziel-Apps nicht — einige lesen die zu gewährende Uri aus
            // dem ClipData statt aus dem Extra (üblicher Weg für Mehrfach-Uris, von manchen Apps
            // aber auch für Einzel-Uris erwartet). Ohne ClipData bekam so eine Ziel-App gelegentlich
            // keinen Lesezugriff, obwohl der Chooser die App anbot.
            clipData = ClipData.newUri(context.contentResolver, "capture", uri)
        }
        launchOrToast(context, Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Zielt zuerst explizit auf ConneXias Galerie (dieselbe Suite, deklariert einen passenden
     * ACTION_VIEW-Intent-Filter für Bild- und Video-MIME-Typen) statt einer generischen
     * ACTION_VIEW-Auswahl — vorher landete "In Galerie öffnen" immer in der System-Default-Galerie
     * des Geräts, nie in der zur Suite gehörenden App (analyse.md Abschnitt 3/5/6). Ist ConneXias
     * Galerie nicht installiert, fällt der Aufruf auf die generische Variante zurück statt nur
     * einen "keine App gefunden"-Toast zu zeigen. */
    fun openInGallery(context: Context, uri: Uri) {
        val mimeType = context.contentResolver.getType(uri) ?: "*/*"
        val suitePackage = "de.ble1st.gallery"
        val baseIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val targeted = Intent(baseIntent).setPackage(suitePackage)
        try {
            context.startActivity(targeted)
        } catch (_: ActivityNotFoundException) {
            launchOrToast(context, baseIntent)
        }
    }

    fun delete(context: Context, uri: Uri): Boolean =
        context.contentResolver.delete(uri, null, null) > 0

    private fun launchOrToast(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.error_no_app_found), Toast.LENGTH_SHORT).show()
        }
    }
}
