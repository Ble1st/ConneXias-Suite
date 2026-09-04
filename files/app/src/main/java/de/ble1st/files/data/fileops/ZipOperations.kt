package de.ble1st.files.data.fileops

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipOperations {

    /** Harte Obergrenze für die entpackte Gesamtgröße eines Archivs — eine winzige Zip-Datei mit
     * absurd hoher Kompressionsrate ("Zip-Bombe") könnte sonst den gesamten Speicher füllen, bevor
     * jemand eingreifen kann. 10 GiB liegt weit über jeder realistischen legitimen Nutzung dieser
     * App (Fotos/Dokumente/Backups), aber weit unter dem, was eine Bombe typischerweise anrichtet. */
    private const val MAX_EXTRACTED_BYTES = 10L * 1024 * 1024 * 1024

    /** analyse.md (2. Durchgang, Mittel — "Zip-Bombe über Eintragsanzahl"): [MAX_EXTRACTED_BYTES]
     * deckelt nur die Gesamt-Byte-Summe, nicht die Anzahl der Einträge — ein Archiv aus Millionen
     * winziger (oder sogar leerer) Einträge bliebe unterhalb des Byte-Limits, würde aber genauso
     * Speicher/Dateisystem-Ressourcen erschöpfen (jeder Eintrag ein eigenes `mkdirs()`/
     * `outputStream()`). 1 Million liegt weit über jeder realistischen legitimen Nutzung. */
    private const val MAX_ENTRY_COUNT = 1_000_000

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
        var totalExtractedBytes = 0L
        val outcomes = mutableListOf<OperationOutcome>()
        val destinationCanonical = destinationDir.canonicalFile
        ZipInputStream(zipFile.inputStream().buffered()).use { zipIn ->
            var entry: ZipEntry? = zipIn.nextEntry
            while (entry != null) {
                if (isCancelled()) break
                if (processed >= MAX_ENTRY_COUNT) {
                    outcomes += OperationOutcome(
                        zipFile.path,
                        IOException("Archiv überschreitet die maximale Eintragsanzahl ($MAX_ENTRY_COUNT) — möglicherweise eine Zip-Bombe"),
                    )
                    break
                }
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
                        targetCanonical.outputStream().use { output ->
                            // Laufender Budget-Abzug statt eines simplen zipIn.copyTo(output): eine
                            // Zip-Bombe kann aus einem einzigen riesigen Eintrag bestehen — ein
                            // Größencheck erst *nach* dem vollständigen Kopieren dieses einen
                            // Eintrags käme dann zu spät (Speicher wäre schon voll gelaufen).
                            totalExtractedBytes = copyLimited(zipIn, output, MAX_EXTRACTED_BYTES - totalExtractedBytes) + totalExtractedBytes
                        }
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

    /** Kopiert wie [java.io.InputStream.copyTo], bricht aber mit [IOException] ab, sobald
     * [remainingBudget] überschritten würde — Grundlage für die [MAX_EXTRACTED_BYTES]-Prüfung in
     * [extract], die so auch innerhalb eines einzelnen großen Eintrags greift statt erst danach. */
    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, remainingBudget: Long): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            if (copied > remainingBudget) {
                throw IOException("Archiv überschreitet die maximale Entpackgröße — möglicherweise eine Zip-Bombe")
            }
            output.write(buffer, 0, read)
        }
        return copied
    }

    private fun countZipEntries(zipFile: File): Int {
        var count = 0
        runCatching {
            ZipInputStream(zipFile.inputStream().buffered()).use { zipIn ->
                // s. MAX_ENTRY_COUNT-Klassendoc: bricht den reinen Zähl-Durchlauf selbst ebenfalls
                // ab, statt bei einer absurden Eintragsanzahl trotzdem bis zum Ende durchzuzählen.
                while (count < MAX_ENTRY_COUNT && zipIn.nextEntry != null) count++
            }
        }
        return count.coerceAtLeast(1)
    }
}
