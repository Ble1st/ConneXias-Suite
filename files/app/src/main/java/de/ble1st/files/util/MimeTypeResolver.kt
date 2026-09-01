package de.ble1st.files.util

import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale

/**
 * Grobe Kategorie einer Datei — steuert sowohl das angezeigte Icon (FileBrowserScreen) als auch,
 * wohin ein Tap auf die Datei navigiert (Platzhalter-Betrachter vs. direkter "Öffnen mit"-Chooser,
 * s. FilesNavHost). Enger gefasst als der volle MIME-Typ, weil die UI nur zwischen "hat später
 * einen eigenen Betrachter" und "immer an eine Fremd-App abgeben" unterscheiden muss.
 */
enum class FileCategory {
    FOLDER,
    IMAGE,
    VIDEO,
    AUDIO,
    TEXT,
    ARCHIVE,
    APK,
    OTHER,
}

// Nicht per MimeTypeMap erkennbar (kein offizieller, in AOSP hinterlegter Extension→MIME-Eintrag),
// aber die mit Abstand üblichsten Klartext-/Code-Dateiendungen für einen eigenen Texteditor —
// deshalb hart hinterlegt statt sich auf MimeTypeMap.getMimeTypeFromExtension() zu verlassen.
private val PLAIN_TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "log", "json", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf",
    "csv", "kt", "kts", "java", "py", "js", "ts", "c", "cpp", "h", "hpp", "rs", "go", "sh", "gradle",
    "properties", "html", "css", "sql",
)

private val ARCHIVE_EXTENSIONS = setOf("zip", "jar", "tar", "gz", "bz2", "7z", "rar", "xz")

fun resolveMimeType(file: File): String? = resolveMimeTypeForName(file.name)

fun resolveMimeTypeForName(name: String): String? {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    if (extension.isEmpty()) return null
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
}

fun resolveFileCategory(file: File): FileCategory = resolveFileCategoryForName(file.name, file.isDirectory)

/** Namensbasierte Variante von [resolveFileCategory] — für Einträge ohne lokales [File] (WebDAV-
 * Verzeichnislistung, s. data/webdav/WebDavEntry.kt), die serverseitig nur Name + Ist-Ordner-Flag
 * liefert. */
fun resolveFileCategoryForName(name: String, isDirectory: Boolean): FileCategory {
    if (isDirectory) return FileCategory.FOLDER
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val mimeType = resolveMimeTypeForName(name)
    return when {
        extension == "apk" -> FileCategory.APK
        extension in ARCHIVE_EXTENSIONS -> FileCategory.ARCHIVE
        mimeType?.startsWith("image/") == true -> FileCategory.IMAGE
        mimeType?.startsWith("video/") == true -> FileCategory.VIDEO
        mimeType?.startsWith("audio/") == true -> FileCategory.AUDIO
        extension in PLAIN_TEXT_EXTENSIONS || mimeType?.startsWith("text/") == true -> FileCategory.TEXT
        else -> FileCategory.OTHER
    }
}
