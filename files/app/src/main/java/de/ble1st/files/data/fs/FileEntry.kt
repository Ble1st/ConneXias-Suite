package de.ble1st.files.data.fs

import de.ble1st.files.util.FileCategory
import de.ble1st.files.util.resolveFileCategory
import java.io.File

/**
 * Ein Eintrag in der Ordnerliste. Wrapper um [java.io.File] statt die Rohdatei direkt durch die
 * UI zu reichen — `isDirectory`/`length()`/`lastModified()` sind auf java.io.File jeweils ein
 * eigener Syscall (`stat`); pro Zeile einmal einsammeln statt bei jeder Recomposition erneut.
 */
data class FileEntry(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val isHidden: Boolean,
    val category: FileCategory,
) {
    companion object {
        fun from(file: File): FileEntry = FileEntry(
            file = file,
            name = file.name,
            isDirectory = file.isDirectory,
            sizeBytes = if (file.isDirectory) 0L else file.length(),
            lastModifiedMillis = file.lastModified(),
            isHidden = file.isHidden || file.name.startsWith("."),
            category = resolveFileCategory(file),
        )
    }
}

enum class SortKey { NAME, DATE, SIZE, TYPE }

data class SortOrder(
    val key: SortKey = SortKey.NAME,
    val ascending: Boolean = true,
    val foldersFirst: Boolean = true,
)

fun List<FileEntry>.sortedByOrder(order: SortOrder): List<FileEntry> {
    val comparator = when (order.key) {
        SortKey.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { entry: FileEntry -> entry.name }
        SortKey.DATE -> compareBy { entry: FileEntry -> entry.lastModifiedMillis }
        SortKey.SIZE -> compareBy { entry: FileEntry -> entry.sizeBytes }
        SortKey.TYPE -> compareBy(String.CASE_INSENSITIVE_ORDER) { entry: FileEntry -> entry.file.extension }
    }
    val directed = if (order.ascending) comparator else comparator.reversed()
    val effective = if (order.foldersFirst) {
        compareByDescending<FileEntry> { it.isDirectory }.then(directed)
    } else {
        directed
    }
    return sortedWith(effective)
}
