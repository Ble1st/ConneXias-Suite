package de.ble1st.files.data.trash

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Ein in den Papierkorb verschobener Eintrag. [trashPath] liegt im `.crx-trash`-Ordner desselben
 * Speichervolumes wie [originalPath] ursprünglich (s. [de.ble1st.files.data.fileops.FileOperations
 * .moveToTrash]-Doc, warum "pro Volume" statt eines einzelnen globalen Papierkorb-Ordners) — nur
 * so bleibt das Verschieben ein billiges `renameTo` statt einer vollen Kopie über
 * Mountpoint-Grenzen hinweg.
 */
data class TrashEntry(
    val id: String,
    val trashPath: String,
    val originalPath: String,
    val originalParentPath: String,
    val deletedAtMillis: Long,
    val isDirectory: Boolean,
) {
    val trashFile: File get() = File(trashPath)
    val originalName: String get() = File(originalPath).name
}

/**
 * Persistiert die Papierkorb-Metadaten. Klartext-SharedPreferences (kein `EnvelopeFile`/
 * `EncryptedSharedPreferences` wie bei den WebDAV-Zugangsdaten) — Pfade/Zeitstempel sind kein
 * Geheimnis, dasselbe Argument wie bei [de.ble1st.files.data.webdav.WebDavAccountStore] für die
 * unverschlüsselten Felder dort. Gleiches org.json-Array-Muster wie dort: eine Handvoll bis
 * einige Dutzend Einträge rechtfertigen kein eigenes Room-Schema.
 */
object TrashStore {
    private const val PREFS_FILE = "trash_entries"
    private const val KEY_ENTRIES = "entries"

    /** Automatische endgültige Löschung nach dieser Frist (s. [purgeExpired]) — 30 Tage, dieselbe
     * Retention wie Wardens Security-Score-Historie, ein plausibler Standard ohne eigene
     * Konfigurations-UI dafür (Ausbauschritt, kein Tag-1-Bedarf). */
    const val RETENTION_DAYS = 30L
    private val RETENTION_MILLIS = RETENTION_DAYS * 24 * 60 * 60 * 1000

    private val _entries = MutableStateFlow<List<TrashEntry>>(emptyList())
    val entries: StateFlow<List<TrashEntry>> = _entries

    @Volatile
    private var initialized = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (initialized) return
        _entries.value = readAll(context)
        initialized = true
    }

    fun list(context: Context): List<TrashEntry> {
        ensureLoaded(context)
        return _entries.value
    }

    fun add(context: Context, entry: TrashEntry) {
        ensureLoaded(context)
        _entries.update { current ->
            val next = current + entry
            persist(context, next)
            next
        }
    }

    fun remove(context: Context, id: String) {
        ensureLoaded(context)
        _entries.update { current ->
            val next = current.filterNot { it.id == id }
            persist(context, next)
            next
        }
    }

    /**
     * Löscht Einträge, die länger als [RETENTION_DAYS] im Papierkorb liegen, tatsächlich
     * (physische Datei über [FileOperations.delete]-Pfad, nicht nur den Metadaten-Eintrag) und
     * entfernt sie aus dem Store. Wird opportunistisch beim Öffnen des Papierkorb-Bildschirms
     * aufgerufen statt über einen periodischen WorkManager-Worker — dieselbe "kein Hintergrund-
     * Mechanismus für etwas, das beim nächsten Öffnen ohnehin nachgeholt wird"-Haltung wie
     * Wardens Security-Score-Historie (kein periodischer Sampling-Worker dafür). Läuft auf dem
     * aufrufenden Dispatcher — Aufrufer ist dafür verantwortlich, auf `Dispatchers.IO` zu sein.
     */
    fun purgeExpired(context: Context, nowMillis: Long = System.currentTimeMillis()) {
        ensureLoaded(context)
        val (expired, remaining) = _entries.value.partition { nowMillis - it.deletedAtMillis > RETENTION_MILLIS }
        if (expired.isEmpty()) return
        expired.forEach { entry -> deleteRecursivePermanently(entry.trashFile) }
        _entries.value = remaining
        persist(context, remaining)
    }

    private fun deleteRecursivePermanently(file: File) {
        if (file.isDirectory && !java.nio.file.Files.isSymbolicLink(file.toPath())) {
            file.listFiles()?.forEach { deleteRecursivePermanently(it) }
        }
        file.delete()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun readAll(context: Context): List<TrashEntry> {
        val raw = prefs(context).getString(KEY_ENTRIES, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                TrashEntry(
                    id = obj.getString("id"),
                    trashPath = obj.getString("trashPath"),
                    originalPath = obj.getString("originalPath"),
                    originalParentPath = obj.getString("originalParentPath"),
                    deletedAtMillis = obj.getLong("deletedAtMillis"),
                    isDirectory = obj.getBoolean("isDirectory"),
                )
            }.getOrNull()
        }
    }

    private fun persist(context: Context, entries: List<TrashEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("trashPath", entry.trashPath)
                    put("originalPath", entry.originalPath)
                    put("originalParentPath", entry.originalParentPath)
                    put("deletedAtMillis", entry.deletedAtMillis)
                    put("isDirectory", entry.isDirectory)
                },
            )
        }
        prefs(context).edit { putString(KEY_ENTRIES, array.toString()) }
    }
}
