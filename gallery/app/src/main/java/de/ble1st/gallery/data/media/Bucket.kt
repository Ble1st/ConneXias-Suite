package de.ble1st.gallery.data.media

import android.net.Uri

/** Virtuelles Album, das nicht als eigener MediaStore-Bucket existiert — steht für "alle Fotos
 * und Videos, album-übergreifend" auf [de.ble1st.gallery.ui.albums.AlbumsScreen]. */
const val ALL_BUCKET_ID = -1L

/** Zweites virtuelles Album: die mit einem Stern markierten Aufnahmen (s.
 * [de.ble1st.gallery.data.favorite.FavoritesStore]). Als Bucket-ID modelliert und nicht als
 * eigener Bildschirm, damit die Favoriten dieselbe Grid-Ansicht samt Sortierung, Suche,
 * Mehrfachauswahl und Diashow bekommen wie jedes andere Album — ein zweiter, fast identischer
 * Bildschirm wäre reine Verdopplung. */
const val FAVORITES_BUCKET_ID = -2L

data class Bucket(
    val id: Long,
    val name: String,
    val coverUri: Uri,
    val itemCount: Int,
)

/** Gruppiert bereits nach Datum absteigend sortierte [items] nach `bucketId` — das jeweils erste
 * (= neueste) Element eines Buckets liefert das Titelbild. Reine Listenlogik ohne MediaStore-
 * Zugriff, deshalb ohne Instrumentierung testbar (s. BucketGroupingTest). */
fun groupIntoBuckets(items: List<MediaItem>): List<Bucket> =
    items.groupBy { it.bucketId }.map { (bucketId, bucketItems) ->
        Bucket(
            id = bucketId,
            name = bucketItems.first().bucketName,
            coverUri = bucketItems.first().uri,
            itemCount = bucketItems.size,
        )
    }
