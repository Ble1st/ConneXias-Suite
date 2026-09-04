package de.ble1st.files.data.recent

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Ein zuletzt geöffneter Eintrag — nur der Pfad plus Zeitpunkt, alles andere (Name, Größe, Icon)
 * lässt sich daraus jederzeit frisch über [FileEntry.from][de.ble1st.files.data.fs.FileEntry.from]
 * ableiten, ein Cache dafür würde nur veralten (z. B. wenn die Datei zwischenzeitlich umbenannt
 * oder verschoben wurde). */
data class RecentEntry(val path: String, val openedAtMillis: Long)

/**
 * Persistiert die zuletzt über [de.ble1st.files.nav.FilesNavHost]s zentralen Tap-Dispatch
 * (`handleFileOpen`) geöffneten Dateien — dasselbe org.json-über-SharedPreferences-Muster wie
 * [de.ble1st.files.data.webdav.WebDavAccountStore]/`TrashStore`. Ordner werden bewusst nicht
 * mitgezählt (dafür gibt es bereits Home/den Browser selbst als Navigationspfad); nur tatsächlich
 * *geöffnete Dateien* sind ein Home-Bildschirm-Mehrwert.
 */
object RecentFilesStore {
    private const val PREFS_FILE = "recent_files"
    private const val KEY_ENTRIES = "entries"

    /** Genug für eine Übersicht auf Home, ohne dass die Liste unübersichtlich wird oder die
     * SharedPreferences-Datei unbegrenzt wächst (jede geöffnete Datei würde sonst für immer bleiben,
     * auch nach Jahren ungenutzt). */
    private const val MAX_ENTRIES = 20

    private val _entries = MutableStateFlow<List<RecentEntry>>(emptyList())
    val entries: StateFlow<List<RecentEntry>> = _entries

    @Volatile
    private var initialized = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (initialized) return
        _entries.value = readAll(context)
        initialized = true
    }

    fun list(context: Context): List<RecentEntry> {
        ensureLoaded(context)
        return _entries.value
    }

    /** Ein erneutes Öffnen derselben Datei springt in der Liste nach vorn, statt einen zweiten
     * Eintrag anzulegen — sonst würde eine oft genutzte Datei die Liste mit sich selbst zumüllen. */
    fun recordOpened(context: Context, file: File) {
        ensureLoaded(context)
        _entries.update { current ->
            val next = (listOf(RecentEntry(file.path, System.currentTimeMillis())) +
                current.filterNot { it.path == file.path }).take(MAX_ENTRIES)
            persist(context, next)
            next
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun readAll(context: Context): List<RecentEntry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                RecentEntry(path = obj.getString("path"), openedAtMillis = obj.getLong("openedAtMillis"))
            }.getOrNull()
        }
    }

    private fun persist(context: Context, entries: List<RecentEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(JSONObject().apply { put("path", entry.path); put("openedAtMillis", entry.openedAtMillis) })
        }
        prefs(context).edit { putString(KEY_ENTRIES, array.toString()) }
    }
}
