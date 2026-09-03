package de.ble1st.gallery.data.media

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.contentValuesOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Importiert eine per "Teilen mit ConneXias Galerie" (ACTION_SEND/ACTION_SEND_MULTIPLE) von einer
 * fremden App empfangene Bild-/Video-Uri als eigenständige MediaStore-Kopie — analyse.md
 * Abschnitt 5 ("Gallery ohne ACTION_SEND"). Reine Byte-Kopie ohne Decode/Recompress (anders als
 * [de.ble1st.gallery.util.PhotoEditSaver], das Filter/Zuschnitt anwendet und deshalb neu
 * komprimieren muss) — ein Teilen-Empfang verändert das Bild/Video nicht, nur seinen Speicherort,
 * erhält also Originalqualität und EXIF-Metadaten wie `PhotoEditSaver.copyUnedited`.
 */
object SharedMediaImporter {
    private val timestampFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US)
    }

    /** Obergrenze pro geteilter Datei — die Quelle ist eine fremde App, nicht der Nutzer selbst
     * (dieselbe Abwägung wie `WebDavClient.MAX_DOWNLOAD_BYTES` in ConneXias Files). */
    private const val MAX_IMPORT_BYTES = 10L * 1024 * 1024 * 1024

    data class Result(val imported: List<Uri>, val failedCount: Int)

    /**
     * [requestMimeType] ist `Intent.type` des empfangenen SEND-Intents — bei ACTION_SEND_MULTIPLE
     * gilt er für alle Uris gemeinsam, deshalb nur als Fallback verwendet, falls eine einzelne Uri
     * selbst keinen (oder nur einen unspezifischen Wildcard-Typ) über den ContentResolver
     * preisgibt.
     */
    suspend fun importAll(context: Context, sourceUris: List<Uri>, requestMimeType: String?): Result =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val imported = mutableListOf<Uri>()
            var failedCount = 0
            for (source in sourceUris) {
                val uri = runCatching { importOne(resolver, source, requestMimeType) }.getOrNull()
                if (uri != null) imported += uri else failedCount++
            }
            Result(imported, failedCount)
        }

    private fun importOne(resolver: ContentResolver, sourceUri: Uri, requestMimeType: String?): Uri? {
        val mimeType = requestMimeType?.takeIf { it != "*/*" } ?: resolver.getType(sourceUri) ?: return null
        val isVideo = mimeType.startsWith("video/")
        // Nur Bild/Video — die App zeigt/verwaltet nichts anderes (s. Manifest-Intent-Filter, der
        // ohnehin nur image/*, video/* anbietet). Ein unerwarteter Typ wird abgelehnt statt
        // stillschweigend als Bild fehlinterpretiert zu werden.
        if (!isVideo && !mimeType.startsWith("image/")) return null

        val prefix = if (isVideo) "VID" else "IMG"
        val displayName = "${prefix}_shared_${timestampFormat.get()!!.format(Date())}"
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val relativePath = if (isVideo) "Movies/ConneXias Galerie" else "Pictures/ConneXias Galerie"
        val displayNameColumn = if (isVideo) MediaStore.Video.Media.DISPLAY_NAME else MediaStore.Images.Media.DISPLAY_NAME
        val mimeTypeColumn = if (isVideo) MediaStore.Video.Media.MIME_TYPE else MediaStore.Images.Media.MIME_TYPE
        val relativePathColumn = if (isVideo) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Images.Media.RELATIVE_PATH
        val isPendingColumn = if (isVideo) MediaStore.Video.Media.IS_PENDING else MediaStore.Images.Media.IS_PENDING

        val values = contentValuesOf(
            displayNameColumn to displayName,
            mimeTypeColumn to mimeType,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(relativePathColumn, relativePath)
                put(isPendingColumn, 1)
            }
        }
        val outputUri = resolver.insert(collection, values) ?: return null
        // Dieselbe "Pending-Leiche"-Absicherung wie PhotoEditSaver/ConneXias Kameras Filter-
        // Speichern: bei jedem Fehlschlag (Stream nicht lesbar, Budget überschritten,
        // Schreibfehler) wird der halbfertige Eintrag gelöscht statt für immer als IS_PENDING=1
        // unsichtbar liegen zu bleiben oder als leerer, aber sichtbarer Eintrag stehen zu bleiben.
        val written = runCatching {
            resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(outputUri)?.use { output -> copyLimited(input, output, MAX_IMPORT_BYTES) }
            } != null
        }.getOrDefault(false)
        if (!written) {
            runCatching { resolver.delete(outputUri, null, null) }
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(outputUri, ContentValues().apply { put(isPendingColumn, 0) }, null, null)
        }
        return outputUri
    }

    private fun copyLimited(input: InputStream, output: OutputStream, remainingBudget: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            if (copied > remainingBudget) throw IOException("Geteilte Datei überschreitet die maximale Größe")
            output.write(buffer, 0, read)
        }
    }
}
