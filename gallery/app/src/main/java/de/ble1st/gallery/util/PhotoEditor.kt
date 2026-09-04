package de.ble1st.gallery.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.contentValuesOf
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foto-Nachbearbeitung ([de.ble1st.gallery.ui.editor.PhotoEditorScreen]) — dieselben
 * `ColorMatrix`-Filter wie ConneXias Kameras `util/PhotoFilters.kt` (bewusst dupliziert statt
 * geteilt, s. Plan-Klassendoc "kein Code wird zwischen den drei Apps geteilt"). Zuschnitt ist ein
 * fester Seitenverhältnis-Zentrumszuschnitt statt frei ziehbarer Eckgriffe — deckt den typischen
 * "Quadrat/16:9 für Social Media"-Anwendungsfall ab, ohne eine eigene Ziehgesten-Erkennung für
 * v1 zu bauen (möglicher Ausbauschritt, s. README).
 */
enum class PhotoFilter { NONE, BW, SEPIA, VINTAGE, COOL, WARM }

fun PhotoFilter.colorMatrix(): ColorMatrix = when (this) {
    PhotoFilter.NONE -> ColorMatrix()
    PhotoFilter.BW -> ColorMatrix().apply { setSaturation(0f) }
    PhotoFilter.SEPIA -> ColorMatrix(
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    PhotoFilter.VINTAGE -> ColorMatrix().apply {
        setSaturation(0.55f)
        postConcat(
            ColorMatrix(
                floatArrayOf(
                    1.1f, 0f, 0f, 0f, 10f,
                    0f, 1.05f, 0f, 0f, 5f,
                    0f, 0f, 0.9f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
    PhotoFilter.COOL -> ColorMatrix(
        floatArrayOf(
            0.9f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1.15f, 0f, 10f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
    PhotoFilter.WARM -> ColorMatrix(
        floatArrayOf(
            1.15f, 0f, 0f, 0f, 10f,
            0f, 1.05f, 0f, 0f, 0f,
            0f, 0f, 0.9f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/** s. ConneXias Kameras `PhotoFilters.kt`-Kommentar zur Skalierung der Translationsspalte:
 * Compose-`ColorMatrix` erwartet sie normalisiert (0..1), `android.graphics.ColorMatrix` im
 * 0..255-Pixelwertebereich. */
fun PhotoFilter.composeColorMatrix(): androidx.compose.ui.graphics.ColorMatrix {
    val values = FloatArray(20)
    colorMatrix().getArray().copyInto(values)
    for (translateIndex in intArrayOf(4, 9, 14, 19)) values[translateIndex] /= 255f
    return androidx.compose.ui.graphics.ColorMatrix(values)
}

fun applyFilter(source: Bitmap, filter: PhotoFilter): Bitmap {
    val result = createBitmap(source.width, source.height)
    val canvas = Canvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(filter.colorMatrix()) }
    canvas.drawBitmap(source, 0f, 0f, paint)
    return result
}

enum class CropAspect(val ratio: Float?) {
    ORIGINAL(null),
    SQUARE(1f),
    FOUR_THREE(4f / 3f),
    SIXTEEN_NINE(16f / 9f),
}

/** Zentrierter Zuschnitt auf das Zielseitenverhältnis — schneidet nur, verzerrt nie. */
fun centerCrop(source: Bitmap, aspect: CropAspect): Bitmap {
    val targetRatio = aspect.ratio ?: return source
    val sourceRatio = source.width.toFloat() / source.height.toFloat()
    return if (sourceRatio > targetRatio) {
        val newWidth = (source.height * targetRatio).toInt().coerceAtLeast(1)
        val x = (source.width - newWidth) / 2
        Bitmap.createBitmap(source, x, 0, newWidth, source.height)
    } else {
        val newHeight = (source.width / targetRatio).toInt().coerceAtLeast(1)
        val y = (source.height - newHeight) / 2
        Bitmap.createBitmap(source, 0, y, source.width, newHeight)
    }
}

/** Speichert immer als neue MediaStore-Kopie (`Pictures/ConneXias Galerie`, Suffix `_edited`) —
 * nie als Überschreiben des Originals, damit eine Bearbeitung jederzeit rückgängig gemacht werden
 * kann (Original bleibt einfach ein zweites Element in der Galerie). */
object PhotoEditSaver {
    private val timestampFormat: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.US)
    }

    /** Obergrenze für die längste Kante des dekodierten Bitmaps — ohne sie wurde jedes Foto in
     * voller Sensorauflösung decodiert (bei z. B. 50 MP als ARGB_8888 weit über 100 MB allein für
     * dieses eine Bitmap, plus Kopie fürs Croppen, plus Kopie fürs Filtern), reines OOM-Risiko auf
     * schwächeren Geräten. 4096px liegt weit über jeder sinnvollen Anzeige-/Social-Media-Auflösung. */
    private const val MAX_DECODED_DIMENSION = 4096

    suspend fun saveEdited(context: Context, sourceUri: Uri, filter: PhotoFilter, aspect: CropAspect): Uri? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            // analyse.md (2. Durchgang, Mittel): Filter NONE + Crop ORIGINAL verändern das Bild gar
            // nicht — ein Decode+Recompress-Durchlauf lief bisher trotzdem, verlustbehaftet neu
            // komprimiert (Bitmap.compress) UND ohne jede EXIF-Metadaten (das Ausgangsbild trägt
            // z. B. Aufnahmedatum/GPS, `resolver.insert` beginnt ohne sie). "Speichern" ohne
            // tatsächliche Änderung erzeugte so eine schlechtere Kopie des Originals.
            if (filter == PhotoFilter.NONE && aspect == CropAspect.ORIGINAL) {
                return@withContext copyUnedited(resolver, sourceUri)
            }
            val rotationDegrees = readExifRotationDegrees(resolver, sourceUri)
            val decoded = decodeSampledBitmap(resolver, sourceUri, MAX_DECODED_DIMENSION) ?: return@withContext null
            // BitmapFactory decodiert die rohen Pixel unabhängig von der EXIF-Orientation — ohne
            // diese Korrektur landete ein im Hochformat aufgenommenes, aber mit gedrehtem
            // Sensor-Bild gespeichertes Foto (der übliche Fall auf den meisten Telefonkameras)
            // seitwärts in der bearbeiteten Kopie.
            val original = if (rotationDegrees != 0) rotate(decoded, rotationDegrees) else decoded
            val cropped = centerCrop(original, aspect)
            val edited = applyFilter(cropped, filter)

            val displayName = "IMG_edited_${timestampFormat.get()!!.format(Date())}"
            val values = contentValuesOf(
                MediaStore.Images.Media.DISPLAY_NAME to displayName,
                MediaStore.Images.Media.MIME_TYPE to "image/jpeg",
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ConneXias Galerie")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
            // analyse.md (2. Durchgang, Mittel): "Dieselbe Pending-Leiche wie Kamera-Filter" — s.
            // ConneXias Kameras PhotoFilterSaver-Kommentar für die beiden vorherigen kaputten
            // Zwischenzustände (Exception → für immer IS_PENDING=1 / null-Stream → leerer, aber
            // sichtbarer Eintrag). Bei Fehlschlag wird der halbfertige Eintrag jetzt gelöscht.
            val written = runCatching {
                resolver.openOutputStream(outputUri)?.use { output -> edited.compress(Bitmap.CompressFormat.JPEG, 92, output) }
            }.getOrNull() == true
            if (!written) {
                runCatching { resolver.delete(outputUri, null, null) }
                return@withContext null
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(outputUri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            outputUri
        }

    /** Reine Byte-Kopie ohne Decode/Recompress — für den Fall, dass Filter/Zuschnitt das Bild gar
     * nicht verändern (s. [saveEdited]-Kommentar): erhält EXIF-Metadaten und Originalqualität, statt
     * sie durch einen unnötigen JPEG-Recompress-Durchlauf zu verlieren. */
    private fun copyUnedited(resolver: android.content.ContentResolver, sourceUri: Uri): Uri? {
        val mimeType = resolver.getType(sourceUri) ?: "image/jpeg"
        val displayName = "IMG_edited_${timestampFormat.get()!!.format(Date())}"
        val values = contentValuesOf(
            MediaStore.Images.Media.DISPLAY_NAME to displayName,
            MediaStore.Images.Media.MIME_TYPE to mimeType,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ConneXias Galerie")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        val copied = runCatching {
            resolver.openInputStream(sourceUri)?.use { input ->
                resolver.openOutputStream(outputUri)?.use { output -> input.copyTo(output); true }
            } ?: false
        }.getOrDefault(false)
        if (!copied) {
            runCatching { resolver.delete(outputUri, null, null) }
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(outputUri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        return outputUri
    }

    /** Zwei-Pass-Decodierung (erst nur die Maße lesen, dann mit passendem `inSampleSize`
     * herunterskaliert decodieren) statt `BitmapFactory.decodeStream(input)` direkt — Standard-
     * Android-Muster gegen OOM bei großen Fotos, s. [MAX_DECODED_DIMENSION]-Doc. Zwei separate
     * `openInputStream`-Aufrufe statt eines wiederverwendeten Streams, weil ein MediaStore-
     * `content://`-Stream nicht zurückgespult werden kann (kein `reset()`). */
    private fun decodeSampledBitmap(resolver: android.content.ContentResolver, uri: Uri, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= maxDimension || bounds.outHeight / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    private fun readExifRotationDegrees(resolver: android.content.ContentResolver, uri: Uri): Int {
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }

    private fun rotate(source: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
