package de.ble1st.files.data.fs

import de.ble1st.files.util.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class FileEntrySortingTest {

    // FileEntry.from(File) ruft resolveFileCategory()/MimeTypeMap auf, das unter einem reinen
    // JVM-Unit-Test (kein Robolectric) nicht verfügbar ist. Der Sortier-Test interessiert sich
    // nicht für die MIME-Auflösung, deshalb hier direkt über den Data-Class-Konstruktor statt über
    // die echten Dateien auf der Platte.
    private fun entry(name: String, isDirectory: Boolean = false, sizeBytes: Long = 0, lastModified: Long = 0) =
        FileEntry(
            file = File(name),
            name = name,
            isDirectory = isDirectory,
            sizeBytes = sizeBytes,
            lastModifiedMillis = lastModified,
            isHidden = false,
            category = if (isDirectory) FileCategory.FOLDER else FileCategory.OTHER,
        )

    @Test
    fun `folders first takes precedence over the chosen sort key`() {
        val fileB = entry("b.txt")
        val folderA = entry("a-ordner", isDirectory = true)

        val sorted = listOf(fileB, folderA)
            .sortedByOrder(SortOrder(key = SortKey.NAME, ascending = true, foldersFirst = true))

        assertEquals(listOf("a-ordner", "b.txt"), sorted.map { it.name })
    }

    @Test
    fun `name sort is case insensitive`() {
        val upper = entry("Banane.txt")
        val lower = entry("apfel.txt")

        val sorted = listOf(upper, lower)
            .sortedByOrder(SortOrder(key = SortKey.NAME, ascending = true, foldersFirst = false))

        assertEquals(listOf("apfel.txt", "Banane.txt"), sorted.map { it.name })
    }

    @Test
    fun `descending size sort puts largest file first`() {
        val small = entry("small.bin", sizeBytes = 10)
        val large = entry("large.bin", sizeBytes = 1000)

        val sorted = listOf(small, large)
            .sortedByOrder(SortOrder(key = SortKey.SIZE, ascending = false, foldersFirst = false))

        assertEquals(listOf("large.bin", "small.bin"), sorted.map { it.name })
    }
}
