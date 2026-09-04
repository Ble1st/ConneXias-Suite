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

/**
 * Eine Cursor-Zeile der Ordner-Abfrage, reduziert auf das, was eine Ordnerkachel braucht.
 *
 * Trägt bewusst `itemId` + `type` statt einer fertigen [Uri]: so bleibt [foldIntoBuckets] frei von
 * Android-Klassen und damit unter einem gewöhnlichen JVM-Unit-Test lauffähig (`Uri` liefert dort
 * nur `RuntimeException("Stub!")`). Die Uri baut [MediaStoreRepository] daraus.
 */
data class BucketRow(
    val itemId: Long,
    val bucketId: Long,
    val bucketName: String,
    val type: MediaType,
)

/** Zwischenergebnis von [foldIntoBuckets] — wie [Bucket], aber mit der Titelbild-Referenz statt
 * einer fertigen Uri. */
data class BucketFold(
    val id: Long,
    val name: String,
    val coverItemId: Long,
    val coverType: MediaType,
    val itemCount: Int,
)

/**
 * Faltet die nach Datum absteigend sortierten [rows] zu Ordnerkacheln — das jeweils erste
 * (= neueste) Element eines Ordners liefert das Titelbild, die Ordner selbst bleiben in der
 * Reihenfolge ihres neuesten Elements.
 *
 * Läuft in einem Durchgang über den Cursor, ohne die Zeilen vorher als Liste zu sammeln oder
 * `MediaItem`s zu bauen: die Ordnerübersicht war bis 2026-09-04 der teuerste Einzelgrund, den
 * gesamten Medienbestand im Speicher zu halten (`groupIntoBuckets(allItems)`), obwohl sie am Ende
 * nur so viele Objekte braucht, wie es Ordner gibt.
 */
fun foldIntoBuckets(rows: Iterable<BucketRow>): List<BucketFold> {
    // LinkedHashMap: Einfügereihenfolge = Reihenfolge des jeweils neuesten Elements.
    val folds = LinkedHashMap<Long, BucketFold>()
    for (row in rows) {
        val existing = folds[row.bucketId]
        folds[row.bucketId] = if (existing == null) {
            BucketFold(
                id = row.bucketId,
                name = row.bucketName,
                coverItemId = row.itemId,
                coverType = row.type,
                itemCount = 1,
            )
        } else {
            existing.copy(itemCount = existing.itemCount + 1)
        }
    }
    return folds.values.toList()
}
