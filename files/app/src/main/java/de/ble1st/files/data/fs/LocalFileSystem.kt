package de.ble1st.files.data.fs

import java.io.File

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

    /** Rekursive Größe eines Verzeichnisses (für Properties-Dialog und Fortschrittsanzeige). */
    fun sizeOf(file: File): Long {
        if (!file.isDirectory) return file.length()
        var total = 0L
        file.listFiles()?.forEach { child -> total += sizeOf(child) }
        return total
    }

    /** Alle regulären Dateien unterhalb von [file] (für Fortschritts-Zählung bei Kopie/Löschung). */
    fun countFiles(file: File): Int {
        if (!file.isDirectory) return 1
        var count = 0
        file.listFiles()?.forEach { child -> count += countFiles(child) }
        return count
    }
}
