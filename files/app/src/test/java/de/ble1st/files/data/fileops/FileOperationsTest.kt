package de.ble1st.files.data.fileops

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Reine JVM-Unit-Tests (kein Robolectric/Instrumentation nötig) — FileOperations ist bewusst frei
 * von Android-Abhängigkeiten (s. Klassendoc), java.io.File funktioniert identisch unter der JVM.
 */
class FileOperationsTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File.createTempFile("files-test", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `uniqueName returns desired name when free`() {
        assertEquals("neu.txt", FileOperations.uniqueName(tempDir, "neu.txt"))
    }

    @Test
    fun `uniqueName appends counter on collision`() {
        File(tempDir, "bild.jpg").createNewFile()
        assertEquals("bild (1).jpg", FileOperations.uniqueName(tempDir, "bild.jpg"))
    }

    @Test
    fun `uniqueName increments past multiple collisions`() {
        File(tempDir, "bild.jpg").createNewFile()
        File(tempDir, "bild (1).jpg").createNewFile()
        File(tempDir, "bild (2).jpg").createNewFile()
        assertEquals("bild (3).jpg", FileOperations.uniqueName(tempDir, "bild.jpg"))
    }

    @Test
    fun `uniqueName preserves names without extension`() {
        File(tempDir, "README").createNewFile()
        assertEquals("README (1)", FileOperations.uniqueName(tempDir, "README"))
    }

    @Test
    fun `copy duplicates a directory tree recursively`() {
        val source = File(tempDir, "quelle").apply { mkdirs() }
        File(source, "a.txt").writeText("A")
        File(source, "unterordner").mkdirs()
        File(source, "unterordner/b.txt").writeText("B")
        val destination = File(tempDir, "ziel").apply { mkdirs() }

        val outcomes = FileOperations.copy(listOf(source), destination, isCancelled = { false }) { _, _, _ -> }

        assertTrue(outcomes.all { it.succeeded })
        assertEquals("A", File(destination, "quelle/a.txt").readText())
        assertEquals("B", File(destination, "quelle/unterordner/b.txt").readText())
        assertTrue(source.exists()) // Kopie lässt die Quelle unangetastet, anders als move().
    }

    @Test
    fun `move relocates and removes the source`() {
        val source = File(tempDir, "quelle.txt").apply { writeText("Inhalt") }
        val destination = File(tempDir, "ziel").apply { mkdirs() }

        FileOperations.move(listOf(source), destination, isCancelled = { false }) { _, _, _ -> }

        assertTrue(!source.exists())
        assertEquals("Inhalt", File(destination, "quelle.txt").readText())
    }

    @Test
    fun `findConflicts reports only names that already exist in the destination`() {
        val source1 = File(tempDir, "a.txt").apply { writeText("neu") }
        val source2 = File(tempDir, "b.txt").apply { writeText("neu") }
        val destination = File(tempDir, "ziel").apply { mkdirs() }
        File(destination, "a.txt").writeText("alt")

        val conflicts = FileOperations.findConflicts(listOf(source1, source2), destination)

        assertEquals(listOf("a.txt"), conflicts)
    }

    @Test
    fun `copy with OVERWRITE replaces the existing destination entry`() {
        val source = File(tempDir, "a.txt").apply { writeText("neu") }
        val destination = File(tempDir, "ziel").apply { mkdirs() }
        File(destination, "a.txt").writeText("alt")

        val outcomes = FileOperations.copy(
            listOf(source), destination, ConflictPolicy.OVERWRITE, isCancelled = { false },
        ) { _, _, _ -> }

        assertTrue(outcomes.all { it.succeeded })
        assertEquals("neu", File(destination, "a.txt").readText())
    }

    @Test
    fun `copy with SKIP leaves the existing destination entry untouched`() {
        val source = File(tempDir, "a.txt").apply { writeText("neu") }
        val destination = File(tempDir, "ziel").apply { mkdirs() }
        File(destination, "a.txt").writeText("alt")

        val outcomes = FileOperations.copy(
            listOf(source), destination, ConflictPolicy.SKIP, isCancelled = { false },
        ) { _, _, _ -> }

        assertTrue(outcomes.single().skipped)
        assertEquals("alt", File(destination, "a.txt").readText())
    }

    @Test
    fun `move with SKIP does not delete the source`() {
        val source = File(tempDir, "a.txt").apply { writeText("neu") }
        val destination = File(tempDir, "ziel").apply { mkdirs() }
        File(destination, "a.txt").writeText("alt")

        val outcomes = FileOperations.move(
            listOf(source), destination, ConflictPolicy.SKIP, isCancelled = { false },
        ) { _, _, _ -> }

        assertTrue(outcomes.single().skipped)
        assertTrue(source.exists())
        assertEquals("alt", File(destination, "a.txt").readText())
    }

    @Test
    fun `move with OVERWRITE replaces the destination and removes the source`() {
        val source = File(tempDir, "a.txt").apply { writeText("neu") }
        val destination = File(tempDir, "ziel").apply { mkdirs() }
        File(destination, "a.txt").writeText("alt")

        FileOperations.move(
            listOf(source), destination, ConflictPolicy.OVERWRITE, isCancelled = { false },
        ) { _, _, _ -> }

        assertTrue(!source.exists())
        assertEquals("neu", File(destination, "a.txt").readText())
    }

    @Test
    fun `delete removes nested content`() {
        val target = File(tempDir, "loeschen").apply { mkdirs() }
        File(target, "datei.txt").writeText("x")

        FileOperations.delete(listOf(target), isCancelled = { false }) { _, _, _ -> }

        assertTrue(!target.exists())
    }

    /** analyse.md (2. Durchgang, Hoch — "Symlinks werden als Verzeichnisse behandelt"): vorher
     * folgte `deleteRecursiveCounting` dem Link, listete den Inhalt des ZIELS auf und löschte ihn
     * — der eigentliche Zielordner (außerhalb der Löschauswahl) verlor seinen kompletten Inhalt,
     * bevor am Ende nur noch der Link selbst entfernt wurde. */
    @Test
    fun `delete of a symlinked directory removes only the link, not the target contents`() {
        val target = File(tempDir, "ziel").apply { mkdirs() }
        val untouched = File(target, "wichtig.txt").apply { writeText("nicht löschen") }
        val link = File(tempDir, "link")
        Files.createSymbolicLink(link.toPath(), target.toPath())

        FileOperations.delete(listOf(link), isCancelled = { false }) { _, _, _ -> }

        assertFalse(link.exists())
        assertTrue(target.exists())
        assertTrue(untouched.exists())
    }

    /** Symmetrisches Kopier-Gegenstück: ein Symlink auf ein Verzeichnis im Quellbaum darf nicht
     * verfolgt werden — sonst könnte ein Link zurück auf einen Vorfahren der Quelle (oder einen
     * beliebig großen fremden Baum) den Kopiervorgang endlos/riesig werden lassen. */
    @Test
    fun `copy refuses to follow a directory symlink and reports failure`() {
        val target = File(tempDir, "ziel").apply { mkdirs() }
        File(target, "datei.txt").writeText("x")
        val source = File(tempDir, "quelle").apply { mkdirs() }
        Files.createSymbolicLink(File(source, "link").toPath(), target.toPath())
        val destination = File(tempDir, "kopie").apply { mkdirs() }

        val outcomes = FileOperations.copy(listOf(source), destination, isCancelled = { false }) { _, _, _ -> }

        assertTrue(outcomes.any { !it.succeeded })
        assertFalse(File(destination, "quelle/link/datei.txt").exists())
    }

    /** analyse.md (2. Durchgang, Mittel — "Abbruch eines Copy-Jobs zählt den Ordner trotzdem als
     * Erfolg"): ein Abbruch, der mitten in einem Ordner mit mehreren Kindern eintrifft, durfte
     * diesen Ordner selbst nicht mehr als erfolgreich kopiert melden. */
    @Test
    fun `cancelling mid-directory reports the directory itself as failed, not succeeded`() {
        val source = File(tempDir, "quelle").apply { mkdirs() }
        File(source, "a.txt").writeText("A")
        File(source, "b.txt").writeText("B")
        val destination = File(tempDir, "ziel").apply { mkdirs() }

        var processedCount = 0
        val outcomes = FileOperations.copy(
            listOf(source),
            destination,
            isCancelled = { processedCount >= 1 },
        ) { _, processed, _ -> processedCount = processed }

        val sourceOutcome = outcomes.first { it.source == source.path }
        assertFalse(sourceOutcome.succeeded)
    }
}
