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
    /** Sekunden seit Epoch (`MediaStore.MediaColumns.DATE_ADDED`) — dieselbe Einheit, in der
     * MediaStore die Spalte liefert, absichtlich nicht in Millisekunden umgerechnet, um keine
     * Präzision vorzutäuschen, die die Quelle nicht hat. */
    val dateAddedSeconds: Long,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    /** Nutzerlesbarer Pfad für den Info-Dialog — s. [MediaStoreRepository]-Klassendoc zur
     * API-abhängigen Herkunft (RELATIVE_PATH ab API 29, DATA-Spalte davor). */
    val path: String,
)
