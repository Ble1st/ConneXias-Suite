package de.ble1st.files.data.fs

import java.io.File
import java.nio.file.Files

/**
 * Dünne Fassade über java.io.File-Verzeichnislistung. Eigene Datei statt die zwei Zeilen inline im
 * ViewModel, damit Compress-/Lösch-/Kopier-Größenberechnungen (FileOperations.kt) dieselbe
 * Listing-Logik wiederverwenden statt sie zu duplizieren.
 */
object LocalFileSystem {

    /**
     * `listFiles()` liefert `null` statt einer leeren Liste, wenn `directory` kein lesbares
     * Verzeichnis (mehr) ist — z. B. weil es zwischen Navigation und Rendern gelöscht wurde, oder
     * weil die All-Files-Access-Berechtigung entzogen wurde. Beides ist ein normaler, kein
     * Ausnahme-Fall für die UI (FileBrowserViewModel zeigt dafür einen eigenen Fehlerzustand).
     */
    fun list(directory: File): List<FileEntry>? =
        directory.listFiles()?.map { FileEntry.from(it) }

    /**
     * analyse.md (2. Durchgang, Hoch — "Symlinks werden als Verzeichnisse behandelt"): `File
     * .isDirectory`/`listFiles()` folgen symbolischen Links transparent — ein Symlink auf ein
     * Verzeichnis sieht für den ganzen restlichen Code hier aus wie ein normales Verzeichnis.
     * `Files.isSymbolicLink` (java.nio, `lstat`-basiert statt `stat`) unterscheidet das korrekt,
     * ohne dem Link selbst zu folgen. Jede Stelle, die sonst anhand von `isDirectory` rekursiv in
     * ein Verzeichnis absteigt (Löschen, Kopieren, Größen-/Zählung), muss das hier zuerst prüfen —
     * s. `FileOperations`-Aufrufstellen.
     */
    fun isSymlink(file: File): Boolean = Files.isSymbolicLink(file.toPath())

    /** Rekursive Größe eines Verzeichnisses (für Properties-Dialog und Fortschrittsanzeige). Ein
     * Symlink auf ein Verzeichnis wird nicht verfolgt (s. [isSymlink]) — `length()` liefert dafür
     * einen plausiblen Einzelwert statt endlos/zyklisch zu rekursieren. */
    fun sizeOf(file: File): Long {
        if (!file.isDirectory || isSymlink(file)) return file.length()
        var total = 0L
        file.listFiles()?.forEach { child -> total += sizeOf(child) }
        return total
    }

    /** Alle regulären Dateien unterhalb von [file] (für Fortschritts-Zählung bei Kopie/Löschung).
     * Ein Symlink auf ein Verzeichnis zählt als ein einzelner Eintrag statt verfolgt zu werden
     * (s. [isSymlink]). */
    fun countFiles(file: File): Int {
        if (!file.isDirectory || isSymlink(file)) return 1
        var count = 0
        file.listFiles()?.forEach { child -> count += countFiles(child) }
        return count
    }
}
