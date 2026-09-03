package de.ble1st.gallery.data.media

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * [groupByTime] ist bewusst framework-frei (nur `java.time`) und bekommt Zeitzone und "heute" als
 * Parameter — dieser Test läuft dadurch unabhängig von Zeitzone und Datum des ausführenden
 * Rechners, was für eine Datumslogik sonst die klassische Quelle sporadisch roter Tests ist.
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

    private fun group(items: List<MediaItem>) = groupByTime(items, zone, today)

    @Test
    fun `leere Eingabe ergibt keine Abschnitte`() {
        assertTrue(group(emptyList()).isEmpty())
    }

    @Test
    fun `Aufnahmen desselben Tages landen in einem Abschnitt`() {
        val sections = group(listOf(itemAt(2026, 9, 3, hour = 20), itemAt(2026, 9, 3, hour = 8)))

        assertEquals(1, sections.size)
        assertEquals(2, sections.first().items.size)
        assertFalse(sections.first().monthOnly)
    }

    @Test
    fun `verschiedene Tage im laufenden Jahr ergeben Tagesabschnitte`() {
        val sections = group(listOf(itemAt(2026, 9, 3), itemAt(2026, 9, 2), itemAt(2026, 1, 15)))

        assertEquals(3, sections.size)
        assertTrue(sections.none { it.monthOnly })
    }

    @Test
    fun `aeltere Jahre werden zu Monatsabschnitten zusammengefasst`() {
        // Zwei Tage desselben Monats, aber aus einem vergangenen Jahr — genau der Fall, für den
        // die Monatsgruppierung existiert (s. groupByTime-Doc).
        val sections = group(listOf(itemAt(2024, 7, 20), itemAt(2024, 7, 3)))

        assertEquals(1, sections.size)
        assertTrue(sections.first().monthOnly)
        assertEquals(2, sections.first().items.size)
        assertEquals(
            LocalDate.of(2024, 7, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            sections.first().startMillis,
        )
    }

    @Test
    fun `verschiedene Monate eines alten Jahres bleiben getrennt`() {
        val sections = group(listOf(itemAt(2024, 8, 1), itemAt(2024, 7, 31)))

        assertEquals(2, sections.size)
        assertTrue(sections.all { it.monthOnly })
    }

    @Test
    fun `Jahreswechsel trennt Tages- von Monatsabschnitten`() {
        val sections = group(listOf(itemAt(2026, 1, 1), itemAt(2025, 12, 31)))

        assertEquals(2, sections.size)
        assertFalse(sections[0].monthOnly)
        assertTrue(sections[1].monthOnly)
    }

    @Test
    fun `Reihenfolge der Eingabe bleibt erhalten`() {
        val newest = itemAt(2026, 9, 3)
        val middle = itemAt(2026, 9, 1)
        val oldest = itemAt(2023, 4, 5)

        val sections = group(listOf(newest, middle, oldest))

        assertEquals(
            listOf(newest.id, middle.id, oldest.id),
            sections.flatMap { section -> section.items.map { it.id } },
        )
    }

    @Test
    fun `Abschnittsbeginn ist der Tagesanfang in der uebergebenen Zeitzone`() {
        val sections = group(listOf(itemAt(2026, 9, 3, hour = 23)))

        assertEquals(
            LocalDate.of(2026, 9, 3).atStartOfDay(zone).toInstant().toEpochMilli(),
            sections.first().startMillis,
        )
    }
}
