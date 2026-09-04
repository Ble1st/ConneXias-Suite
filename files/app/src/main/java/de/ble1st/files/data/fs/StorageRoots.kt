package de.ble1st.files.data.fs

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Ein Speichervolume. [kind] und [ordinal] statt einer fertigen Beschriftung: die Beschriftung ist
 * eine Anzeige-Entscheidung und liegt deshalb in `res/values/strings.xml`, aufgelöst in
 * `ui/home/HomeScreen.kt`. Vorher stand hier der deutsche Text — und `HomeScreen` hat daran
 * zusätzlich per `label.startsWith("SD")` das Icon festgemacht, was eine Übersetzung lautlos
 * kaputtgemacht hätte.
 *
 * [ordinal] ist die 1-basierte Nummer unter den entfernbaren Volumes (erste SD-Karte = 1) und für
 * [Kind.INTERNAL] immer 0.
 */
data class StorageRoot(val kind: Kind, val ordinal: Int, val path: File) {
    enum class Kind { INTERNAL, REMOVABLE }
}

/** Schnellzugriff auf einen der Standardordner des internen Speichers — [kind] aus demselben
 * Grund wie bei [StorageRoot]. */
data class QuickAccessFolder(val kind: Kind, val path: File) {
    enum class Kind { DOWNLOADS, PICTURES, DCIM, MOVIES, DOCUMENTS, MUSIC }
}

object StorageRoots {

    /**
     * Interner Speicher + jede eingelegte SD-Karte/jeder USB-Stick. `getExternalFilesDirs(null)`
     * (statt der ab API 24 eigentlich saubereren `StorageManager.getStorageVolumes()`) deshalb,
     * weil `StorageVolume.getDirectory()` erst ab API 30 existiert — bei minSdk 26 bräuchte der
     * API-30-Ansatz sonst zusätzlich Reflection für API 26–29. Jeder Eintrag zeigt auf
     * `.../Android/data/<pkg>/files`; das Abschneiden ab `/Android/` liefert die eigentliche
     * Laufwerkswurzel, auf die MANAGE_EXTERNAL_STORAGE vollen Zugriff gewährt.
     */
    fun list(context: Context): List<StorageRoot> {
        // Context.getExternalFilesDirs() selbst ist seit API 29 als deprecated markiert (Empfehlung
        // Richtung Storage-Access-Framework/MediaStore für app-eigene Dateien) — hier aber bewusst
        // weiter genutzt, nur um die vorhandenen Speichervolumes samt Mountpunkt zu enumerieren,
        // nicht um tatsächlich in den app-eigenen Ordner zu schreiben (s. Klassendoc oben).
        @Suppress("DEPRECATION")
        val dirs = context.getExternalFilesDirs(null)
        return dirs.filterNotNull().mapIndexedNotNull { index, appSpecificDir ->
            val marker = "/Android/"
            val markerIndex = appSpecificDir.path.indexOf(marker)
            if (markerIndex < 0) return@mapIndexedNotNull null
            val root = File(appSpecificDir.path.substring(0, markerIndex))
            if (!root.isDirectory) return@mapIndexedNotNull null
            if (index == 0) {
                StorageRoot(StorageRoot.Kind.INTERNAL, ordinal = 0, path = root)
            } else {
                StorageRoot(StorageRoot.Kind.REMOVABLE, ordinal = index, path = root)
            }
        }
    }

    /** Ordnername für den Papierkorb (s. [de.ble1st.files.data.fileops.FileOperations
     * .moveToTrash]) — führender Punkt, damit er in der normalen Ordneransicht wie jede andere
     * versteckte Datei/jeder andere versteckte Ordner (`FileEntry.isHidden`) unauffällig bleibt,
     * statt als scheinbar normaler Nutzerordner zwischen den echten Inhalten aufzutauchen. */
    private const val TRASH_DIR_NAME = ".crx-trash"

    /**
     * Liefert den `.crx-trash`-Ordner auf demselben Speichervolume wie [file] — oder `null`, wenn
     * [file] unter keinem der über [list] erkannten Volumes liegt (sollte für einen über den
     * eigenen Datei-Browser erreichten Pfad praktisch nie vorkommen). Kanonische Pfade, damit
     * `..`/ein Symlink die Volume-Zuordnung nicht verfälschen kann (dasselbe Muster wie
     * `FileOperations.isSameOrDescendant`).
     */
    fun trashDirFor(context: Context, file: File): File? {
        val fileCanonical = runCatching { file.canonicalFile }.getOrDefault(file.absoluteFile)
        val root = list(context).firstOrNull { root ->
            val rootCanonical = runCatching { root.path.canonicalFile }.getOrDefault(root.path.absoluteFile)
            fileCanonical == rootCanonical || fileCanonical.path.startsWith(rootCanonical.path + File.separator)
        } ?: return null
        return File(root.path, TRASH_DIR_NAME)
    }

    /** Direkte Verknüpfungen auf dem Home-Bildschirm — alle relativ zum internen Speicher. */
    fun quickAccessFolders(primaryRoot: File): List<QuickAccessFolder> = listOf(
        QuickAccessFolder(QuickAccessFolder.Kind.DOWNLOADS, File(primaryRoot, Environment.DIRECTORY_DOWNLOADS)),
        QuickAccessFolder(QuickAccessFolder.Kind.PICTURES, File(primaryRoot, Environment.DIRECTORY_PICTURES)),
        QuickAccessFolder(QuickAccessFolder.Kind.DCIM, File(primaryRoot, Environment.DIRECTORY_DCIM)),
        QuickAccessFolder(QuickAccessFolder.Kind.MOVIES, File(primaryRoot, Environment.DIRECTORY_MOVIES)),
        QuickAccessFolder(QuickAccessFolder.Kind.DOCUMENTS, File(primaryRoot, Environment.DIRECTORY_DOCUMENTS)),
        QuickAccessFolder(QuickAccessFolder.Kind.MUSIC, File(primaryRoot, Environment.DIRECTORY_MUSIC)),
    )
}
