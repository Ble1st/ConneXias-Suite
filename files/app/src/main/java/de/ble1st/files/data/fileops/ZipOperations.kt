package de.ble1st.files.data.fileops

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipOperations {

    fun compress(
        sources: List<File>,
        destinationZip: File,
        isCancelled: () -> Boolean,
        onProgress: (fileName: String, processed: Int, total: Int) -> Unit,
    ): List<OperationOutcome> {
        val total = sources.sumOf { entryCount(it) }
        var processed = 0
        val outcomes = mutableListOf<OperationOutcome>()
        ZipOutputStream(destinationZip.outputStream().buffered()).use { zipOut ->
            for (source in sources) {
                if (isCancelled()) break
                processed = addToZip(zipOut, source, source.name, processed, total, isCancelled, onProgress, outcomes)
            }
        }
        return outcomes
    }

    private fun entryCount(file: File): Int {
        if (!file.isDirectory) return 1
        var count = 0
        file.listFiles()?.forEach { count += entryCount(it) }
        return count.coerceAtLeast(1)
    }

    private fun addToZip(
        zipOut: ZipOutputStream,
        file: File,
        entryPath: String,
        processed: Int,
        total: Int,
        isCancelled: () -> Boolean,
        onProgress: (String, Int, Int) -> Unit,
        outcomes: MutableList<OperationOutcome>,
    ): Int {
        if (isCancelled()) return processed
        var nextProcessed = processed
        if (file.isDirectory) {
            val children = file.listFiles()
            if (children.isNullOrEmpty()) {
                // Leere Ordner brauchen einen eigenen Eintrag (mit "/"-Suffix), sonst geht die
                // Ordnerstruktur beim Entpacken verloren — ZIP kennt Ordner nur als solche
                // Marker-Einträge, nicht als eigenen Eintragstyp.
                runCatching { zipOut.putNextEntry(ZipEntry("$entryPath/")); zipOut.closeEntry() }
                    .onSuccess { outcomes += OperationOutcome(file.path) }
                    .onFailure { outcomes += OperationOutcome(file.path, it) }
            } else {
                children.forEach { child ->
                    if (isCancelled()) return@forEach
                    nextProcessed = addToZip(zipOut, child, "$entryPath/${child.name}", nextProcessed, total, isCancelled, onProgress, outcomes)
                }
            }
        } else {
            runCatching {
                zipOut.putNextEntry(ZipEntry(entryPath))
                file.inputStream().use { it.copyTo(zipOut) }
                zipOut.closeEntry()
            }.onSuccess {
                outcomes += OperationOutcome(file.path)
            }.onFailure { error ->
                outcomes += OperationOutcome(file.path, error)
            }
        }
        nextProcessed++
        onProgress(file.name, nextProcessed, total)
        return nextProcessed
    }

    /**
     * Entpackt nach [destinationDir]. Prüft für jeden Eintrag, dass der aufgelöste Zielpfad
     * tatsächlich unterhalb von [destinationDir] liegt ("Zip-Slip") — ein bösartiges/kaputtes
     * Archiv mit einem Eintragsnamen wie "../../etc/passwd" könnte sonst Dateien außerhalb des
     * gewählten Zielordners schreiben. `canonicalPath`-Vergleich statt reinem String-Präfix-Check,
     * damit `..`-Segmente nicht erst nach dem Schreiben auffallen.
     */
    fun extract(
        zipFile: File,
        destinationDir: File,
        isCancelled: () -> Boolean,
        onProgress: (fileName: String, processed: Int, total: Int) -> Unit,
    ): List<OperationOutcome> {
        val total = countZipEntries(zipFile)
        var processed = 0
        val outcomes = mutableListOf<OperationOutcome>()
        val destinationCanonical = destinationDir.canonicalFile
        ZipInputStream(zipFile.inputStream().buffered()).use { zipIn ->
            var entry: ZipEntry? = zipIn.nextEntry
            while (entry != null) {
                if (isCancelled()) break
                val currentEntry = entry
                val outcome = runCatching {
                    val target = File(destinationDir, currentEntry.name)
                    val targetCanonical = target.canonicalFile
                    if (!targetCanonical.path.startsWith(destinationCanonical.path + File.separator) &&
                        targetCanonical != destinationCanonical
                    ) {
                        throw IOException("Unsicherer Zip-Eintrag außerhalb des Zielordners: ${currentEntry.name}")
                    }
                    if (currentEntry.isDirectory) {
                        targetCanonical.mkdirs()
                    } else {
                        targetCanonical.parentFile?.mkdirs()
                        targetCanonical.outputStream().use { output -> zipIn.copyTo(output) }
                    }
                }
                processed++
                outcome.onSuccess {
                    outcomes += OperationOutcome(currentEntry.name)
                }.onFailure { error ->
                    outcomes += OperationOutcome(currentEntry.name, error)
                }
                onProgress(currentEntry.name, processed, total)
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }
        return outcomes
    }

    private fun countZipEntries(zipFile: File): Int {
        var count = 0
        runCatching {
            ZipInputStream(zipFile.inputStream().buffered()).use { zipIn ->
                while (zipIn.nextEntry != null) count++
            }
        }
        return count.coerceAtLeast(1)
    }
}
