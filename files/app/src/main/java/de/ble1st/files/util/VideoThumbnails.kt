package de.ble1st.files.util

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import java.io.File

/**
 * Miniaturbilder für Videodateien in der Grid-Ansicht. Die Grid-Ansicht zeigte bis 2026-09-03 nur
 * für Bilder eine Vorschau; Videos bekamen wie jeder andere Nicht-Bild-Typ das generische Icon.
 *
 * Bewusst **ohne** Coils `coil-video`-Artefakt, obwohl das der naheliegende Weg wäre: dafür würde
 * eine neue Abhängigkeit plus ein eigener `ImageLoader` mit registriertem Decoder nötig, während
 * die Plattform mit [MediaMetadataRetriever] bereits genau das mitbringt, was hier gebraucht wird
 * — ein einzelnes Vorschaubild aus einer lokalen Datei. Der Preis dafür ist der Cache unten, den
 * Coil sonst mitgebracht hätte.
 *
 * [cache] ist ein reiner Speicher-Cache (kein Datenträger-Cache): ein Frame neu zu dekodieren
 * kostet Bruchteile einer Sekunde, das lohnt kein Schreiben auf den Datenträger. Er existiert
 * ausschließlich, damit nicht beim Scrollen jede wieder sichtbar werdende Kachel neu dekodiert.
 * Gewichtung nach tatsächlicher Bitmap-Größe statt nach Eintragsanzahl, weil sich die
 * Bildabmessungen je nach Quellvideo stark unterscheiden.
 *
 * Der Schlüssel enthält den Änderungszeitstempel: eine an derselben Stelle ersetzte Datei bekommt
 * dadurch ein neues Vorschaubild, statt das alte weiterzuzeigen.
 */
object VideoThumbnails {

    /** Kantenlänge des angeforderten Vorschaubilds. Die Kacheln sind knapp 100 dp breit; mehr
     * Auflösung würde nur Speicher kosten. */
    private const val TARGET_SIZE_PX = 256

    private val cache = object : LruCache<String, Bitmap>(CACHE_SIZE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Liefert ein Vorschaubild für [file] oder `null`, wenn sich keins gewinnen lässt (kaputte
     * Datei, nicht unterstützter Codec, kein Lesezugriff). Blockierend — der Aufrufer sorgt für
     * einen Hintergrund-Thread.
     *
     * `null` wird bewusst **nicht** im Cache vermerkt: der Fall ist selten, und ein negativer
     * Eintrag müsste beim Ersetzen der Datei wieder ungültig werden — der Aufwand steht nicht
     * dafür, dass eine kaputte Datei beim Scrollen ein zweites Mal erfolglos geöffnet wird.
     */
    fun get(file: File): Bitmap? {
        val key = "${file.path}:${file.lastModified()}"
        cache.get(key)?.let { return it }
        val bitmap = runCatching { extractFrame(file) }.getOrNull() ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private fun extractFrame(file: File): Bitmap? {
        // `use` gibt es für MediaMetadataRetriever erst ab API 29 (Closeable), minSdk ist hier 26 —
        // deshalb try/finally von Hand. Ein nicht freigegebener Retriever hält einen nativen
        // Decoder offen, das ist kein optionales Aufräumen.
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            // getScaledFrameAtTime skaliert bereits im Decoder (API 27+) und erspart damit das
            // Dekodieren eines vollen 4K-Frames für eine 100-dp-Kachel. Auf API 26 bleibt nur
            // getFrameAtTime; das Ergebnis wird dort nachträglich verkleinert.
            val frame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    FRAME_TIME_MICROS,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    TARGET_SIZE_PX,
                    TARGET_SIZE_PX,
                )
            } else {
                retriever.getFrameAtTime(FRAME_TIME_MICROS, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?.let { full -> scaleDown(full) }
            }
            frame
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Verkleinert unter Beibehaltung des Seitenverhältnisses auf höchstens [TARGET_SIZE_PX] je
     * Kante — nur für den API-26-Pfad oben. */
    private fun scaleDown(source: Bitmap): Bitmap {
        val longestEdge = maxOf(source.width, source.height)
        if (longestEdge <= TARGET_SIZE_PX) return source
        val factor = TARGET_SIZE_PX.toFloat() / longestEdge
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * factor).toInt().coerceAtLeast(1),
            (source.height * factor).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    /** Erste Sekunde statt Position 0: viele Videos beginnen mit einem schwarzen oder fast
     * schwarzen Bild, das als Vorschau nichts aussagt. */
    private const val FRAME_TIME_MICROS = 1_000_000L
}

/** 8 MiB — bei 256×256 in ARGB_8888 (256 KiB je Bild) rund 32 Vorschaubilder, also deutlich mehr
 * als gleichzeitig auf dem Bildschirm sichtbar sein können, aber wenig genug, um auf einem Gerät
 * mit knappem Speicher nicht ins Gewicht zu fallen. Als Top-Level-Konstante, weil sie im
 * Objekt-Initialisierer oben schon gebraucht wird. */
private const val CACHE_SIZE_BYTES = 8 * 1024 * 1024
