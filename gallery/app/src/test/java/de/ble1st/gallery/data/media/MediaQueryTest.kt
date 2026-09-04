package de.ble1st.gallery.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MediaQuery] baut Selection, Argumente und Sortierung — die Stelle, an der seit der
 * Paging-Umstellung (analyse.md 6.2) Filterung und Sortierung stattfinden. Vorher lag beides als
 * Kotlin-Code über einer vollständig geladenen Liste und war damit offensichtlich richtig; jetzt
 * ist es eine Zeichenkette, die stimmen muss.
 *
 * Läuft als gewöhnlicher JVM-Unit-Test: die `MediaStore`-Spaltennamen sind
 * `static final String`-Konstanten, die der Compiler einsetzt — es wird keine Android-Methode
 * aufgerufen.
 */
class MediaQueryTest {

    @Test
    fun `Standardabfrage beschraenkt auf Bilder und Videos`() {
        val query = MediaQuery()

        assertEquals("media_type IN (?, ?)", query.selection())
        assertEquals(
            listOf(
                MediaQuery.mediaTypeValue(MediaType.IMAGE).toString(),
                MediaQuery.mediaTypeValue(MediaType.VIDEO).toString(),
            ),
            query.selectionArgs().toList(),
        )
    }

    @Test
    fun `nur Bilder ergibt genau einen Platzhalter`() {
        val query = MediaQuery(mediaTypes = setOf(MediaType.IMAGE))

        assertEquals("media_type IN (?)", query.selection())
        assertEquals(listOf(MediaQuery.mediaTypeValue(MediaType.IMAGE).toString()), query.selectionArgs().toList())
    }

    @Test
    fun `Ordnerfilter haengt eine Bedingung und ein Argument an`() {
        val query = MediaQuery(bucketId = 42)

        assertTrue(query.selection().endsWith("AND bucket_id = ?"))
        assertEquals("42", query.selectionArgs().last())
    }

    @Test
    fun `Suche wird als LIKE mit Prozentzeichen gebunden`() {
        val query = MediaQuery(search = "urlaub")

        assertTrue(query.selection().contains("_display_name LIKE ?"))
        assertEquals("%urlaub%", query.selectionArgs().last())
    }

    @Test
    fun `leere Suche erzeugt keine Bedingung`() {
        assertFalse(MediaQuery(search = "   ").selection().contains("LIKE"))
        assertEquals(2, MediaQuery(search = "").selectionArgs().size)
    }

    @Test
    fun `Platzhalterzeichen im Suchbegriff werden entwertet`() {
        // Ohne Maskierung würde die Suche nach "100%" jede Zeichenkette treffen, die mit "100"
        // beginnt — "%" ist in LIKE der Platzhalter für beliebig viele Zeichen.
        assertEquals("100\\%", MediaQuery.escapeLike("100%"))
        assertEquals("a\\_b", MediaQuery.escapeLike("a_b"))
        // Der Backslash ist als ESCAPE-Zeichen vereinbart und muss deshalb selbst maskiert werden.
        assertEquals("\\\\", MediaQuery.escapeLike("\\"))
        assertEquals("%100\\%%", MediaQuery(search = "100%").selectionArgs().last())
    }

    @Test
    fun `Selection nennt das ESCAPE-Zeichen`() {
        // Ohne die ESCAPE-Klausel wäre die Maskierung aus escapeLike wirkungslos: SQLite kennt
        // für LIKE per Vorgabe gar kein Escape-Zeichen.
        assertTrue(MediaQuery(search = "x").selection().contains("ESCAPE '\\'"))
    }

    @Test
    fun `Datumssortierung nutzt Aufnahmezeit mit Rueckfall auf Hinzufuegezeit`() {
        val sortOrder = MediaQuery(order = SortOrder.DATE).sortOrder()

        assertTrue(sortOrder.startsWith("CASE WHEN datetaken > 0"))
        assertTrue(sortOrder.contains("ELSE date_added * 1000 END"))
    }

    @Test
    fun `jede Sortierung endet mit dem ID-Stichentscheid`() {
        // Ohne ihn ist die Reihenfolge zweier Aufnahmen mit identischem Sortierwert undefiniert;
        // beim seitenweisen Laden kann derselbe Eintrag dann auf zwei Seiten oder auf keiner
        // erscheinen.
        for (order in SortOrder.entries) {
            assertTrue(order.name, MediaQuery(order = order).sortOrder().contains("_id"))
            assertTrue(order.name, MediaQuery(order = order).fallbackSortOrder().contains("_id"))
        }
    }

    @Test
    fun `Namenssortierung ignoriert Gross- und Kleinschreibung`() {
        assertTrue(MediaQuery(order = SortOrder.NAME).sortOrder().contains("COLLATE NOCASE"))
    }

    @Test
    fun `Ersatzsortierung kommt ohne Ausdruck aus`() {
        // Sie ist der Rückfall für den Fall, dass der MediaProvider den CASE-WHEN-Ausdruck
        // ablehnt — sie darf deshalb selbst keinen enthalten.
        for (order in SortOrder.entries) {
            val fallback = MediaQuery(order = order).fallbackSortOrder()
            assertFalse(order.name, fallback.contains("CASE"))
            assertFalse(order.name, fallback.contains("("))
        }
    }

    @Test
    fun `leere Medientypmenge ist unzulaessig`() {
        // Sie würde zu "media_type IN ()" führen — syntaktisch gültig, liefert aber nie ein
        // Ergebnis, und der Fehler wäre erst an der leeren Ansicht zu sehen.
        val error = runCatching { MediaQuery(mediaTypes = emptySet()) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }
}
