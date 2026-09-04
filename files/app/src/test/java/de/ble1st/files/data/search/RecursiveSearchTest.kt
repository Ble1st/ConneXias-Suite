package de.ble1st.files.data.search

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Reine JVM-Unit-Tests — [RecursiveSearch] ist bewusst framework-frei (s. Klassendoc), java.io/nio
 * verhalten sich unter der JVM wie unter Android.
 *
 * Der Symlink-Test läuft nur dort, wo das Dateisystem symbolische Links zulässt; unter Linux (CI
 * wie Entwicklungsrechner) ist das der Fall, andernfalls überspringt er sich selbst statt
 * fälschlich rot zu werden.
 */
class RecursiveSearchTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = File.createTempFile("search-test", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(relativePath: String): File =
        File(root, relativePath).apply {
            parentFile?.mkdirs()
            writeText("x")
        }

    private fun directory(relativePath: String): File =
        File(root, relativePath).apply { mkdirs() }

    @Test
    fun `findet Treffer in Unterordnern`() {
        file("bericht.txt")
        file("a/b/bericht-kopie.txt")
        file("a/andere.txt")

        val result = RecursiveSearch.search(root, "bericht")

        assertEquals(
            listOf("bericht-kopie.txt", "bericht.txt"),
            result.files.map { it.name }.sorted(),
        )
        assertFalse(result.truncated)
        assertFalse(result.cancelled)
    }

    @Test
    fun `Treffer naeher am Startordner kommen zuerst`() {
        // Der eigentliche Grund für die Breitensuche (s. Klassendoc) — ohne sie stünde der tief
        // vergrabene Treffer möglicherweise vor dem direkt daneben liegenden.
        file("a/b/c/d/treffer-tief.txt")
        file("treffer-oben.txt")

        val result = RecursiveSearch.search(root, "treffer")

        assertEquals(listOf("treffer-oben.txt", "treffer-tief.txt"), result.files.map { it.name })
    }

    @Test
    fun `Suche ignoriert Gross-Kleinschreibung`() {
        file("Urlaub/BILD.jpg")

        assertEquals(listOf("BILD.jpg"), RecursiveSearch.search(root, "bild").files.map { it.name })
    }

    @Test
    fun `Ordner sind selbst Treffer`() {
        directory("Rechnungen")

        assertEquals(listOf("Rechnungen"), RecursiveSearch.search(root, "rechnung").files.map { it.name })
    }

    @Test
    fun `leerer Suchbegriff liefert nichts`() {
        file("bericht.txt")

        assertTrue(RecursiveSearch.search(root, "   ").files.isEmpty())
    }

    @Test
    fun `Startordner selbst ist nie Treffer`() {
        // Der Name des Startordners enthält "search-test" — er darf sich nicht selbst finden,
        // auch nicht, wenn unter ihm tatsächlich etwas zu durchsuchen ist.
        file("a/b/egal.txt")
        assertTrue(RecursiveSearch.search(root, "search-test").files.isEmpty())
    }

    @Test
    fun `zu tiefe Ordner werden nicht mehr betreten`() {
        val deep = (0..RecursiveSearch.MAX_DEPTH).joinToString("/") { "ebene$it" }
        file("$deep/treffer.txt")

        assertTrue(RecursiveSearch.search(root, "treffer").files.isEmpty())
    }

    @Test
    fun `Ergebnis wird bei Erreichen der Obergrenze als gekuerzt gemeldet`() {
        repeat(RecursiveSearch.MAX_RESULTS + 5) { file("treffer-$it.txt") }

        val result = RecursiveSearch.search(root, "treffer")

        assertEquals(RecursiveSearch.MAX_RESULTS, result.files.size)
        assertTrue(result.truncated)
    }

    @Test
    fun `Abbruch liefert cancelled statt eines Teilergebnisses`() {
        file("a/treffer.txt")

        val result = RecursiveSearch.search(root, "treffer", isCancelled = { true })

        assertTrue(result.cancelled)
        assertFalse(result.truncated)
    }

    @Test
    fun `Verzeichnis-Symlinks werden gefunden aber nicht durchsucht`() {
        val target = directory("ziel")
        File(target, "treffer-im-ziel.txt").writeText("x")
        val link = File(root, "treffer-link")
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (_: Exception) {
            return // Dateisystem ohne Symlink-Unterstützung — nichts zu prüfen.
        }

        // Der Link selbst passt auf "treffer" und soll erscheinen; sein Inhalt darf nicht ein
        // zweites Mal (über den Link) durchlaufen werden.
        val names = RecursiveSearch.search(root, "treffer").files.map { it.name }
        assertEquals(listOf("treffer-link", "treffer-im-ziel.txt"), names)
    }

    @Test
    fun `relativer Elternpfad`() {
        val direkt = file("direkt.txt")
        val tief = file("a/b/tief.txt")

        assertNull(RecursiveSearch.relativeParentPath(root, direkt))
        assertEquals("a${File.separator}b", RecursiveSearch.relativeParentPath(root, tief))
    }

    @Test
    fun `relativer Elternpfad ausserhalb des Startordners bleibt absolut`() {
        // Kann bei einem über einen Link erreichten Treffer passieren — dann ist der volle Pfad
        // die einzige sinnvolle Angabe, ein ".." -Konstrukt wäre für den Nutzer unlesbar.
        val outside = File(root.parentFile, "anderswo${File.separator}datei.txt")
        assertEquals(outside.parent, RecursiveSearch.relativeParentPath(root, outside))
    }
}
