package de.ble1st.camera.data.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.video.MediaStoreOutputOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Baut die MediaStore-Ausgabeziele für CameraX' [ImageCapture]/[androidx.camera.video.Recorder] —
 * beide übernehmen darüber selbst das `ContentResolver.insert`/Pending-Handling, kein manuelles
 * `java.io.File`/`OutputStream`-Management nötig. Scoped-Storage-konform: keine
 * MANAGE_EXTERNAL_STORAGE-Sonderberechtigung wie ConneXias Files, weil diese App nur ihr eigenes
 * Aufnahmeverzeichnis unter DCIM braucht, nicht den kompletten Speicher.
 */
object MediaStoreSaver {

    // Eigener Unterordner statt direkt DCIM/Camera — landet nicht vermischt mit Aufnahmen der
    // System-Kamera-App, bleibt aber unter DCIM (dort erwarten Galerie-Apps klassischerweise
    // Kamera-Aufnahmen, anders als z. B. Pictures/).
    const val RELATIVE_PATH = "DCIM/ConneXias Kamera"

    private val timestampFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US)
    }

    fun generateDisplayName(prefix: String, timestamp: Date = Date()): String =
        "${prefix}_${timestampFormat.get()!!.format(timestamp)}"

    fun imageOutputOptions(context: Context): ImageCapture.OutputFileOptions {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, generateDisplayName("IMG"))
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
                // CameraX löscht dieses Flag selbst wieder, sobald der Schreibvorgang
                // abgeschlossen ist — bis dahin bleibt die Datei für andere Apps (auch die eigene
                // Galerie) unsichtbar, verhindert also ein halb geschriebenes Foto in Listen.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        return ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values,
        ).build()
    }

    fun videoOutputOptions(context: Context): MediaStoreOutputOptions {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, generateDisplayName("VID"))
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH)
            }
        }
        return MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
    }
}
