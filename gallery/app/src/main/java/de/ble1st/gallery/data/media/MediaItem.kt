package de.ble1st.gallery.data.media

import android.net.Uri

enum class MediaType { IMAGE, VIDEO }

data class MediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val bucketId: Long,
    val bucketName: String,
    val type: MediaType,
    /** Millisekunden seit Epoch — `DATE_TAKEN` (Aufnahmezeitpunkt, von der Kamera/EXIF geliefert),
     * mit `DATE_ADDED * 1000` als Fallback, wenn `DATE_TAKEN` fehlt (0 oder NULL, z. B. bei per SAF
     * importierten Dateien ohne EXIF). Vorher wurde ausschließlich `DATE_ADDED` genutzt — für Fotos,
     * die z. B. per WebDAV-Sync/Import erst später auf dieses Gerät gelangten, zeigte "Datum" damit
     * den Import- statt den Aufnahmezeitpunkt und sortierte die Galerie entsprechend falsch ein. */
    val dateSortMillis: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    /** Nutzerlesbarer Pfad für den Info-Dialog — s. [MediaStoreRepository]-Klassendoc zur
     * API-abhängigen Herkunft (RELATIVE_PATH ab API 29, DATA-Spalte davor). */
    val path: String,
)
