package de.ble1st.files.data.trash

import de.ble1st.files.data.fileops.FileOperations
import java.io.File
import java.io.IOException

/**
 * Die beiden Aktionen des Papierkorb-Bildschirms, die nicht schon über [FileOperations] laufen —
 * "endgültig löschen" und "Papierkorb leeren" brauchen dafür keinen eigenen Code, ein einzelner
 * [FileOperations.delete]-Aufruf auf [TrashEntry.trashFile] reicht (dieselbe rekursive,
 * Symlink-sichere Lösch-Logik wie überall sonst — s. dortiges Klassendoc). Nur "Wiederherstellen"
 * ist neu, weil [FileOperations.move] den *aktuellen* Dateinamen behalten würde (hier der
 * UUID-präfigierte Papierkorb-Name, s. [FileOperations.moveToTrash]-Doc), nicht den
 * ursprünglichen — [TrashEntry.originalName] muss also explizit wieder eingesetzt werden.
 */
object TrashOperations {

    /**
     * Verschiebt [entry] von seinem Papierkorb-Pfad zurück in [TrashEntry.originalParentPath],
     * unter dem ursprünglichen Namen ([TrashEntry.originalName], nicht dem UUID-präfigierten
     * Papierkorb-Namen). Legt den ursprünglichen Elternordner neu an, falls er inzwischen selbst
     * gelöscht wurde — sonst wäre eine Datei aus einem gelöschten Ordner nie wiederherstellbar.
     * [FileOperations.uniqueName] löst einen Namenskonflikt auf, falls der ursprüngliche Platz
     * inzwischen durch eine andere, gleichnamige Datei belegt ist, statt sie zu überschreiben.
     */
    fun restore(entry: TrashEntry): Result<File> = runCatching {
        val trashFile = entry.trashFile
        if (!trashFile.exists()) throw IOException("Nicht mehr im Papierkorb vorhanden: ${entry.originalName}")
        val parentDir = File(entry.originalParentPath)
        if (!parentDir.isDirectory && !parentDir.mkdirs() && !parentDir.isDirectory) {
            throw IOException("Ursprünglicher Ordner konnte nicht wiederhergestellt werden: ${parentDir.path}")
        }
        val target = File(parentDir, FileOperations.uniqueName(parentDir, entry.originalName))
        if (!trashFile.renameTo(target)) {
            throw IOException("Wiederherstellen fehlgeschlagen: ${entry.originalName}")
        }
        target
    }
}
