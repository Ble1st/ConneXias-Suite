package de.ble1st.gallery.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [foldIntoBuckets] ist reine Faltungslogik ohne MediaStore-Zugriff.
 *
 * Seit der Paging-Umstellung (analyse.md 6.2) kommt die Ordnerübersicht nicht mehr aus dem
 * vollständig geladenen Medienbestand, sondern entsteht direkt beim Durchlaufen des Cursors —
 * [BucketRow] trägt deshalb nur `itemId` + `type` statt einer fertigen `Uri`. Angenehmer
 * Nebeneffekt: dieser Test braucht keinen `Uri`-Platzhalter mehr (`Uri` liefert unter einem
 * JVM-Unit-Test nur `RuntimeException("Stub!")`).
 */
class BucketGroupingTest {

    private fun row(itemId: Long, bucketId: Long, bucketName: String, type: MediaType = MediaType.IMAGE) =
        BucketRow(itemId = itemId, bucketId = bucketId, bucketName = bucketName, type = type)

    @Test
    fun `gruppiert Zeilen nach Ordner und zaehlt sie`() {
        val folds = foldIntoBuckets(
            listOf(
                row(1, bucketId = 10, bucketName = "Camera"),
                row(2, bucketId = 10, bucketName = "Camera"),
                row(3, bucketId = 20, bucketName = "Screenshots"),
            ),
        )

        assertEquals(2, folds.size)
        assertEquals(2, folds.first { it.id == 10L }.itemCount)
        assertEquals(1, folds.first { it.id == 20L }.itemCount)
        assertEquals("Camera", folds.first { it.id == 10L }.name)
    }

    @Test
    fun `das erste Element eines Ordners liefert das Titelbild`() {
        // Die Eingabe ist bereits absteigend nach Datum sortiert (das leistet die ORDER-BY-Klausel
        // der Abfrage), die erste Zeile eines Ordners ist damit dessen neueste Aufnahme.
        val folds = foldIntoBuckets(
            listOf(
                row(99, bucketId = 10, bucketName = "Camera", type = MediaType.VIDEO),
                row(98, bucketId = 10, bucketName = "Camera"),
            ),
        )

        assertEquals(1, folds.size)
        assertEquals(99L, folds.first().coverItemId)
        // Der Typ entscheidet, gegen welche MediaStore-Collection die Uri gebaut wird — ein Video
        // als Titelbild braucht eine video/media-Uri, sonst löst Coil sie nicht auf.
        assertEquals(MediaType.VIDEO, folds.first().coverType)
    }

    @Test
    fun `Ordner behalten die Reihenfolge ihres neuesten Elements`() {
        val folds = foldIntoBuckets(
            listOf(
                row(5, bucketId = 20, bucketName = "Screenshots"),
                row(4, bucketId = 10, bucketName = "Camera"),
                row(3, bucketId = 20, bucketName = "Screenshots"),
            ),
        )

        assertEquals(listOf(20L, 10L), folds.map { it.id })
    }

    @Test
    fun `leere Eingabe ergibt keine Ordner`() {
        assertEquals(0, foldIntoBuckets(emptyList()).size)
    }
}
