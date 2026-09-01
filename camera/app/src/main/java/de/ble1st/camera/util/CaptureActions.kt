package de.ble1st.camera.util

import android.content.ActivityNotFoundException
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
        }
        launchOrToast(context, Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openInGallery(context: Context, uri: Uri) {
        val mimeType = context.contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchOrToast(context, intent)
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
