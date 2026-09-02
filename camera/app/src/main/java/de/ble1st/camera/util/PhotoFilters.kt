package de.ble1st.camera.util

import android.content.ContentResolver
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
import de.ble1st.camera.data.storage.MediaStoreSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Einfache Nachbearbeitungsfilter für die Kurz-Ansicht ([de.ble1st.camera.ui.review.CaptureReviewScreen])
 * — bewusst reine `ColorMatrix`-Transformationen (Sättigung/Kanalgewichtung) statt einer echten
 * GPU-Shader-Pipeline: deckt den typischen "Sofortfilter"-Anwendungsfall einer Kamera-App ab, ohne
 * eine zusätzliche Rendering-Abhängigkeit (RenderEffect/GLSL) nur für v1 einzuführen. Angewendet
 * wird immer non-destruktiv auf eine neue MediaStore-Kopie (s. [PhotoFilterSaver]) — die
 * Originalaufnahme bleibt unverändert erhalten.
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
    // Reduzierte Sättigung + leichte Kontrastanhebung — grober Ersatz für die
    // Fade-/Korn-Optik klassischer "Vintage"-Filter ohne echte Filmkorn-Textur.
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
    // (postConcat oben ist android.graphics.ColorMatrix.postConcat, kein eigener Code — verkettet
    // Sättigungsreduktion und Farbstich-Matrix zu einer einzigen Transformation.)
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

/** Dieselbe Filtermatrix für die Compose-Live-Vorschau (Filmstrip in [de.ble1st.camera.ui.review.CaptureReviewScreen])
 * — `androidx.compose.ui.graphics.ColorMatrix` nutzt dasselbe 4x5-Zeilenlayout wie
 * `android.graphics.ColorMatrix`, erwartet die Translationsspalte (Index 4/9/14/19) aber im
 * normalisierten 0..1-Farbraum statt im 0..255-Pixelwertebereich — deshalb keine 1:1-Wiederverwendung
 * des rohen `FloatArray`, sondern eine skalierte Kopie. */
fun PhotoFilter.composeColorMatrix(): androidx.compose.ui.graphics.ColorMatrix {
    val values = FloatArray(20)
    colorMatrix().getArray().copyInto(values)
    for (translateIndex in intArrayOf(4, 9, 14, 19)) values[translateIndex] /= 255f
    return androidx.compose.ui.graphics.ColorMatrix(values)
}

/** Zeichnet [source] mit angewendeter [filter]-Matrix auf eine neue Bitmap — die Quelle bleibt
 * unverändert (Aufrufer entscheidet selbst, ob/wie das Original recycelt wird). */
fun applyFilter(source: Bitmap, filter: PhotoFilter): Bitmap {
    val result = createBitmap(source.width, source.height)
    val canvas = Canvas(result)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(filter.colorMatrix())
    }
    canvas.drawBitmap(source, 0f, 0f, paint)
    return result
}

// Obergrenze für die längere Kantenlänge der dekodierten Bitmap — verhindert bei hochauflösenden
// Kameraaufnahmen (z. B. 4000x3000) ein OutOfMemoryError beim vollen Dekodieren + Filtern +
// erneuten Komprimieren (drei Bitmaps gleichzeitig im Speicher). Dieselbe Begründung/Größe wie
// ConneXias Galerie (util/PhotoEditor.kt) — ein Filterergebnis muss nicht pixelgenau die
// Originalauflösung behalten.
private const val MAX_DECODED_DIMENSION = 4096

/**
 * Speichert eine gefilterte Kopie eines bereits aufgenommenen Fotos als neuen MediaStore-Eintrag
 * im selben `DCIM/ConneXias Kamera`-Ordner wie Originalaufnahmen — kein Überschreiben des
 * Originals, damit ein angewendeter Filter jederzeit rückgängig gemacht werden kann (Original
 * bleibt einfach eine zweite Galerie-Kachel).
 */
object PhotoFilterSaver {
    suspend fun saveFiltered(context: Context, sourceUri: Uri, filter: PhotoFilter): Uri? =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val rotationDegrees = readExifRotationDegrees(resolver, sourceUri)
            val decoded = decodeSampledBitmap(resolver, sourceUri, MAX_DECODED_DIMENSION) ?: return@withContext null
            val bitmap = if (rotationDegrees != 0) rotate(decoded, rotationDegrees) else decoded
            val filtered = applyFilter(bitmap, filter)

            val values = contentValuesOf(
                MediaStore.Images.Media.DISPLAY_NAME to MediaStoreSaver.generateDisplayName("IMG_${filter.name.lowercase()}"),
                MediaStore.Images.Media.MIME_TYPE to "image/jpeg",
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, MediaStoreSaver.RELATIVE_PATH)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val outputUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext null
            resolver.openOutputStream(outputUri)?.use { output ->
                filtered.compress(Bitmap.CompressFormat.JPEG, 92, output)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(outputUri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            outputUri
        }
}

// Zweistufiges Dekodieren (erst nur Abmessungen, dann mit passendem inSampleSize) — content://-
// Streams sind nicht seekbar/resetbar, deshalb zwei getrennte openInputStream-Aufrufe statt eines
// mark/reset. Dieselbe Begründung/Umsetzung wie ConneXias Galerie (util/PhotoEditor.kt).
private fun decodeSampledBitmap(resolver: ContentResolver, uri: Uri, maxDimension: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    var sampleSize = 1
    while (bounds.outWidth / (sampleSize * 2) >= maxDimension || bounds.outHeight / (sampleSize * 2) >= maxDimension) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
}

// BitmapFactory ignoriert die EXIF-Orientation selbst — sie muss separat gelesen und als Rotation
// auf die dekodierte Bitmap angewendet werden, sonst würde ein z. B. hochkant aufgenommenes und
// mit EXIF-Tag statt gebackener Rotation gespeichertes Foto (z. B. nach einem "Öffnen mit" aus
// einer anderen App) quer gefiltert und gespeichert.
private fun readExifRotationDegrees(resolver: ContentResolver, uri: Uri): Int {
    val orientation = resolver.openInputStream(uri)?.use { input ->
        ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    return when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}

private fun rotate(source: Bitmap, degrees: Int): Bitmap =
    Bitmap.createBitmap(source, 0, 0, source.width, source.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
