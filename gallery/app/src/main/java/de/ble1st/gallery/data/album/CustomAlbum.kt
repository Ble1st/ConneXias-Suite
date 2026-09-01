package de.ble1st.gallery.data.album

import java.util.UUID

/**
 * Ein benutzerdefiniertes Album — rein virtuell (verweist nur auf `MediaItem.id`s, s.
 * [CustomAlbumStore]-Klassendoc), keine echte MediaStore-`BUCKET`/kein Verschieben von Dateien.
 * Ein Element kann in beliebig vielen Alben gleichzeitig liegen (anders als MediaStore-Buckets,
 * die durch den Ablageordner der Datei fest vorgegeben sind).
 */
data class CustomAlbum(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val itemIds: Set<Long> = emptySet(),
)
