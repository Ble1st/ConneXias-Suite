package de.ble1st.gallery.data.sync

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

/**
 * Merkt sich lokal, welche `MediaItem.id`s bereits auf den WebDAV-Server hochgeladen wurden —
 * statt vor jedem Sync-Lauf serverseitig den Zielordner aufzulisten und Dateinamen abzugleichen
 * (würde bei vielen Dateien eine große PROPFIND-Antwort bedeuten und bräuchte einen
 * XML-Multistatus-Parser nur für diesen einen Zweck). Bewusster Kompromiss: nach einem
 * App-Datenreset oder auf einem Zweitgerät gilt dieser Stand als leer, ein erneuter Sync lädt
 * dann alles erneut hoch — dokumentiert in der README als bekannte Einschränkung.
 */
object CloudSyncState {
    private const val PREFS_FILE = "cloud_sync_state"
    private const val KEY_SYNCED_IDS = "syncedIds"

    fun syncedIds(context: Context): Set<Long> {
        val raw = prefs(context).getString(KEY_SYNCED_IDS, null) ?: return emptySet()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptySet()
        return (0 until array.length()).map { array.getLong(it) }.toSet()
    }

    fun markSynced(context: Context, id: Long) {
        val next = syncedIds(context) + id
        persist(context, next)
    }

    fun reset(context: Context) {
        prefs(context).edit { remove(KEY_SYNCED_IDS) }
    }

    private fun persist(context: Context, ids: Set<Long>) {
        prefs(context).edit { putString(KEY_SYNCED_IDS, JSONArray(ids.toList()).toString()) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
