package de.ble1st.files.data.webdav

import de.ble1st.files.util.FileCategory
import de.ble1st.files.util.resolveFileCategoryForName

/**
 * Ein Eintrag einer WebDAV-PROPFIND-Antwort. [path] ist der server-relative, bereits
 * URL-dekodierte Pfad (z. B. "/Dokumente/Foto.jpg") — Grundlage für jeden weiteren Request
 * (Download/Löschen/Umbenennen) auf genau diesen Eintrag.
 */
data class WebDavEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
) {
    val category: FileCategory get() = resolveFileCategoryForName(name, isDirectory)
}
