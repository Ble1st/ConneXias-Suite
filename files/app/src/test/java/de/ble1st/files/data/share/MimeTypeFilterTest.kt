package de.ble1st.files.data.share

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reiner JVM-Unit-Test — [MimeTypeFilter] ist bewusst framework-frei (s. Klassendoc). Die Muster
 * kommen aus einem fremden Intent, deshalb liegt der Schwerpunkt hier auf den Randfällen
 * (unbekannter Typ, kaputtes Muster) und nicht auf dem offensichtlichen Gutfall.
 */
class MimeTypeFilterTest {

    @Test
    fun `leere Musterliste laesst alles durch`() {
        assertTrue(MimeTypeFilter.matches("image/jpeg", emptyList()))
        assertTrue(MimeTypeFilter.matches(null, emptyList()))
    }

    @Test
    fun `Untertyp-Wildcard passt auf den ganzen Haupttyp`() {
        assertTrue(MimeTypeFilter.matches("image/jpeg", listOf("image/*")))
        assertTrue(MimeTypeFilter.matches("image/png", listOf("image/*")))
        assertFalse(MimeTypeFilter.matches("video/mp4", listOf("image/*")))
    }

    @Test
    fun `Haupttyp-Praefix allein reicht nicht`() {
        // "image/*" darf nicht auf "imagex/foo" passen — der Schrägstrich gehört zum Vergleich.
        assertFalse(MimeTypeFilter.matches("imagex/foo", listOf("image/*")))
    }

    @Test
    fun `exakter Typ passt nur auf sich selbst`() {
        assertTrue(MimeTypeFilter.matches("application/pdf", listOf("application/pdf")))
        assertFalse(MimeTypeFilter.matches("application/zip", listOf("application/pdf")))
    }

    @Test
    fun `Gross-Kleinschreibung ist egal`() {
        assertTrue(MimeTypeFilter.matches("IMAGE/JPEG", listOf("image/*")))
        assertTrue(MimeTypeFilter.matches("application/pdf", listOf(" Application/PDF ")))
    }

    @Test
    fun `mehrere Muster - eines muss passen`() {
        val patterns = listOf("application/pdf", "image/*")
        assertTrue(MimeTypeFilter.matches("image/webp", patterns))
        assertTrue(MimeTypeFilter.matches("application/pdf", patterns))
        assertFalse(MimeTypeFilter.matches("audio/mpeg", patterns))
    }

    @Test
    fun `unbekannter Typ passt nur bei Allmuster`() {
        // Der Kern der Entscheidung in matches(): lieber nicht anbieten als einer App, die
        // ausdrücklich Bilder verlangt hat, etwas Unbekanntes unterschieben.
        assertFalse(MimeTypeFilter.matches(null, listOf("image/*")))
        assertTrue(MimeTypeFilter.matches(null, listOf(MimeTypeFilter.WILDCARD)))
    }

    @Test
    fun `kaputte Muster werfen nicht und passen nicht`() {
        val broken = listOf("", "   ", "image", "a/b/c", "/", "/*")
        assertFalse(MimeTypeFilter.matches("image/jpeg", broken))
        assertFalse(MimeTypeFilter.matches(null, broken))
    }
}
