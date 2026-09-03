package de.ble1st.files.data.fileops

import de.ble1st.files.data.fs.FileEntry
import java.io.File

/**
 * Übersetzt UI-nahe Auswahllisten ([FileEntry]) in [OperationRequest]s für den
 * [FileOperationService] — an einer Stelle gebündelt, damit ViewModel-Code nicht bei jeder Aktion
 * erneut `.map { it.file.path }` und Zieldateinamen-Logik dupliziert.
 */
object FileOperationRequestBuilder {

    fun forClipboard(
        clipboard: ClipboardContent,
        destinationDir: File,
        conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    ): OperationRequest {
        val type = if (clipboard.mode == ClipboardMode.CUT) OperationType.MOVE else OperationType.COPY
        return OperationRequest(type, clipboard.paths, destinationDir.path, conflictPolicy = conflictPolicy)
    }

    fun forDelete(entries: List<FileEntry>): OperationRequest =
        OperationRequest(OperationType.DELETE, entries.map { it.file.path }, destinationDirPath = "")

    /** analyse.md (2. Durchgang, Hoch — "ZIP-Name und WebDAV-Download ohne sanitizeName"): anders
     * als Create/Rename/Import/Upload lief der Dialogname hier bislang ungefiltert bis zu
     * `File(destination, archiveName)` in [FileOperationService] durch — ein Name wie
     * "../../Download/pwned.zip" hätte außerhalb von [destinationDir] geschrieben. [FileOperations
     * .sanitizeName] entfernt Pfadanteile, exakt dieselbe Behandlung wie bei den anderen vier
     * Aktionen. */
    fun forCompress(entries: List<FileEntry>, destinationDir: File, archiveName: String): OperationRequest {
        val sanitized = FileOperations.sanitizeName(archiveName)
        val name = if (sanitized.endsWith(".zip")) sanitized else "$sanitized.zip"
        return OperationRequest(OperationType.COMPRESS, entries.map { it.file.path }, destinationDir.path, name)
    }

    fun forExtract(archiveEntry: FileEntry, destinationDir: File): OperationRequest =
        OperationRequest(OperationType.EXTRACT, listOf(archiveEntry.file.path), destinationDir.path)
}
