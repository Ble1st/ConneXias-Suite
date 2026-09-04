package de.ble1st.gallery.data.media

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [sectionKeyOf] und [headerBetween] sind bewusst framework-frei (nur `java.time`) und bekommen
 * Zeitzone und "heute" als Parameter — dieser Test läuft dadurch unabhängig von Zeitzone und Datum
 * des ausführenden Rechners, was für eine Datumslogik sonst die klassische Quelle sporadisch roter
 * Tests ist.
 *
 * Geprüft wird über [headersAndEntries] genau der Weg, den auch die Anzeige geht: das Raster wird
 * seitenweise geladen (analyse.md 6.2), die Datums-Überschriften entstehen deshalb nicht mehr aus
 * einer vorab gebauten Abschnittsliste, sondern zwischen zwei benachbarten Einträgen des
 * Paging-Stroms.
 *
 * [MediaItem.uri] wird nie inhaltlich ausgewertet, ein Mockito-Mock genügt als Platzhalter (s.
 * [BucketGroupingTest]).
 */
class MediaGroupingTest {

    private val zone: ZoneId = ZoneId.of("Europe/Berlin")
    private val today: LocalDate = LocalDate.of(2026, 9, 3)

    private var nextId = 1L

    private fun itemAt(year: Int, month: Int, day: Int, hour: Int = 12) = MediaItem(
        id = nextId++,
        uri = mock(Uri::class.java),
        displayName = "item",
        bucketId = 1,
        bucketName = "Camera",
        type = MediaType.IMAGE,
        dateSortMillis = LocalDateTime.of(year, month, day, hour, 0)
            .atZone(zone).toInstant().toEpochMilli(),
        sizeBytes = 0,
        width = 0,
        height = 0,
        path = "",
    )

    /** Baut den Strom so auf, wie `PagingData.insertSeparators` es tut: für jeden Eintrag wird
     * gefragt, ob zwischen ihm und seinem Vorgänger eine Überschrift gehört. */
    private fun headersAndEntries(items: List<MediaItem>): List<MediaListItem> = buildList {
        items.forEachIndexed { index, item ->
            headerBetween(items.getOrNull(index - 1), item, zone, today)?.let { add(it) }
            add(MediaListItem.Entry(item))
        }
    }

    private fun headers(items: List<MediaItem>): List<SectionKey> =
        headersAndEntries(items).filterIsInstance<MediaListItem.Header>().map { it.key }

    @Test
    fun `leere Eingabe ergibt keine Ueberschriften`() {
        assertTrue(headers(emptyList()).isEmpty())
    }

    @Test
    fun `am Ende der geladenen Liste steht keine Ueberschrift`() {
        // after == null heißt "hinter dem letzten geladenen Eintrag" — eine Überschrift ohne
        // Inhalt darunter wäre sinnlos.
        assertNull(headerBetween(itemAt(2026, 9, 3), null, zone, today))
    }

    @Test
    fun `Aufnahmen desselben Tages bekommen nur eine Ueberschrift`() {
        val keys = headers(listOf(itemAt(2026, 9, 3, hour = 20), itemAt(2026, 9, 3, hour = 8)))

        assertEquals(1, keys.size)
        assertFalse(keys.first().monthOnly)
    }

    @Test
    fun `verschiedene Tage im laufenden Jahr ergeben Tagesabschnitte`() {
        val keys = headers(listOf(itemAt(2026, 9, 3), itemAt(2026, 9, 2), itemAt(2026, 1, 15)))

        assertEquals(3, keys.size)
        assertTrue(keys.none { it.monthOnly })
    }

    @Test
    fun `aeltere Jahre werden zu Monatsabschnitten zusammengefasst`() {
        // Zwei Tage desselben Monats, aber aus einem vergangenen Jahr — genau der Fall, für den
        // die Monatsgruppierung existiert (s. sectionKeyOf-Doc).
        val keys = headers(listOf(itemAt(2024, 7, 20), itemAt(2024, 7, 3)))

        assertEquals(1, keys.size)
        assertTrue(keys.first().monthOnly)
        assertEquals(
            LocalDate.of(2024, 7, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            keys.first().startMillis,
        )
    }

    @Test
    fun `verschiedene Monate eines alten Jahres bleiben getrennt`() {
        val keys = headers(listOf(itemAt(2024, 8, 1), itemAt(2024, 7, 31)))

        assertEquals(2, keys.size)
        assertTrue(keys.all { it.monthOnly })
    }

    @Test
    fun `Jahreswechsel trennt Tages- von Monatsabschnitten`() {
        val keys = headers(listOf(itemAt(2026, 1, 1), itemAt(2025, 12, 31)))

        assertEquals(2, keys.size)
        assertFalse(keys[0].monthOnly)
        assertTrue(keys[1].monthOnly)
    }

    @Test
    fun `Reihenfolge der Eintraege bleibt erhalten`() {
        val newest = itemAt(2026, 9, 3)
        val middle = itemAt(2026, 9, 1)
        val oldest = itemAt(2023, 4, 5)

        val stream = headersAndEntries(listOf(newest, middle, oldest))

        assertEquals(
            listOf(newest.id, middle.id, oldest.id),
            stream.filterIsInstance<MediaListItem.Entry>().map { it.item.id },
        )
    }

    @Test
    fun `Abschnittsbeginn ist der Tagesanfang in der uebergebenen Zeitzone`() {
        val key = sectionKeyOf(itemAt(2026, 9, 3, hour = 23), zone, today)

        assertEquals(
            LocalDate.of(2026, 9, 3).atStartOfDay(zone).toInstant().toEpochMilli(),
            key.startMillis,
        )
        assertFalse(key.monthOnly)
    }

    @Test
    fun `erster geladener Eintrag bekommt immer eine Ueberschrift`() {
        // before == null tritt bei seitenweisem Laden nicht nur ganz am Anfang auf, sondern auch
        // am oberen Rand des geladenen Fensters — dort ist eine Überschrift richtig, weil der
        // Nutzer sonst einen Abschnitt ohne Titel vor sich hätte.
        val header = headerBetween(null, itemAt(2026, 9, 3), zone, today)

        assertEquals(sectionKeyOf(itemAt(2026, 9, 3), zone, today), header?.key)
    }
}
