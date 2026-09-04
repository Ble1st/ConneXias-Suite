package de.ble1st.files.data.fileops

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipOperationsTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File.createTempFile("zip-test", "").apply {
            delete()
            mkdirs()
        }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `compress then extract round-trips file content`() {
        val source = File(tempDir, "quelle").apply { mkdirs() }
        File(source, "a.txt").writeText("Inhalt A")
        val archive = File(tempDir, "archiv.zip")

        ZipOperations.compress(listOf(source), archive, isCancelled = { false }) { _, _, _ -> }
        assertTrue(archive.exists())

        val extractDir = File(tempDir, "extrahiert").apply { mkdirs() }
        ZipOperations.extract(archive, extractDir, isCancelled = { false }) { _, _, _ -> }

        assertEquals("Inhalt A", File(extractDir, "quelle/a.txt").readText())
    }

    @Test
    fun `extract rejects zip-slip entries outside the destination`() {
        val archive = File(tempDir, "boese.zip")
        ZipOutputStream(archive.outputStream()).use { zipOut ->
            zipOut.putNextEntry(ZipEntry("../ausserhalb.txt"))
            zipOut.write("sollte nicht ankommen".toByteArray())
            zipOut.closeEntry()
        }
        val destination = File(tempDir, "ziel").apply { mkdirs() }

        val outcomes = ZipOperations.extract(archive, destination, isCancelled = { false }) { _, _, _ -> }

        assertTrue(outcomes.none { it.succeeded })
        assertTrue(!File(tempDir, "ausserhalb.txt").exists())
    }
}
