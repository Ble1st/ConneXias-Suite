package de.ble1st.files.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * ACTION_VIEW/ACTION_SEND über [FileProvider] statt nacktem `file://`-Uri (seit Android N von
 * Fremd-Apps abgelehnt, s. AndroidManifest.xml-Provider-Eintrag). Zentral hier statt einzeln in
 * FileBrowserScreen/PlaceholderViewerScreen dupliziert, weil beide denselben Uri-Aufbau brauchen.
 */
object FileActions {

    private fun uriFor(context: Context, file: File) =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun openWithOtherApp(context: Context, file: File) {
        val uri = uriFor(context, file)
        val mimeType = resolveMimeType(file) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchOrToast(context, intent)
    }

    fun share(context: Context, files: List<File>) {
        val uris = ArrayList(files.map { uriFor(context, it) })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.first())
                type = resolveMimeType(files.first()) ?: "*/*"
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                type = "*/*"
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchOrToast(context, Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun launchOrToast(context: Context, intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "Keine App gefunden, die das öffnen kann", Toast.LENGTH_SHORT).show()
        }
    }
}
