package de.ble1st.files.data.fs

import android.content.Context
import android.os.Environment
import java.io.File

data class StorageRoot(val label: String, val path: File)

data class QuickAccessFolder(val label: String, val path: File)

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
            val label = if (index == 0) "Interner Speicher" else "SD-Karte ${index + 1}"
            StorageRoot(label, root)
        }
    }

    /** Direkte Verknüpfungen auf dem Home-Bildschirm — alle relativ zum internen Speicher. */
    fun quickAccessFolders(primaryRoot: File): List<QuickAccessFolder> = listOf(
        QuickAccessFolder("Downloads", File(primaryRoot, Environment.DIRECTORY_DOWNLOADS)),
        QuickAccessFolder("Bilder", File(primaryRoot, Environment.DIRECTORY_PICTURES)),
        QuickAccessFolder("DCIM", File(primaryRoot, Environment.DIRECTORY_DCIM)),
        QuickAccessFolder("Videos", File(primaryRoot, Environment.DIRECTORY_MOVIES)),
        QuickAccessFolder("Dokumente", File(primaryRoot, Environment.DIRECTORY_DOCUMENTS)),
        QuickAccessFolder("Audio", File(primaryRoot, Environment.DIRECTORY_MUSIC)),
    )
}
