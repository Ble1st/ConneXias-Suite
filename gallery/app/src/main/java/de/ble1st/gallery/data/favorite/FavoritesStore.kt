package de.ble1st.gallery.data.favorite

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray

/**
 * Favoriten als lokal gespeicherte Menge von `MediaItem.id`s — dasselbe Referenz-Prinzip wie
 * [de.ble1st.gallery.data.album.CustomAlbumStore] (keine Dateikopie, keine zweite Ablage; ein aus
 * MediaStore verschwundenes Element fällt beim nächsten Laden automatisch heraus).
 *
 * Bewusst **nicht** über `MediaStore.MediaColumns.IS_FAVORITE`, obwohl es diese Spalte ab API 30
 * gibt: Schreiben darf eine App darauf nur über `MediaStore.createFavoriteRequest`, und das ist
 * ein System-Bestätigungsdialog pro Aufruf. Ein Stern, der jedes Mal einen Dialog öffnet, ist als
 * Bedienelement unbrauchbar; der Preis dafür ist, dass diese Markierung app-lokal bleibt und von
 * anderen Galerie-Apps nicht gesehen wird (in der README als Einschränkung vermerkt).
 *
 * `StateFlow` plus einmaliges Laden aus den `SharedPreferences` beim ersten Zugriff — identisch
 * zum CustomAlbumStore, damit beide Speicher sich in Aufbau und Lebensdauer gleich verhalten.
 */
object FavoritesStore {
    private const val PREFS_FILE = "favorites"
    private const val KEY_IDS = "ids"

    private val _favorites = MutableStateFlow<Set<Long>>(emptySet())
    val favorites: StateFlow<Set<Long>> = _favorites

    @Volatile
    private var initialized = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (initialized) return
        _favorites.value = read(context)
        initialized = true
    }

    fun load(context: Context): Set<Long> {
        ensureLoaded(context)
        return _favorites.value
    }

    fun toggle(context: Context, id: Long) {
        ensureLoaded(context)
        _favorites.update { current ->
            val next = if (id in current) current - id else current + id
            persist(context, next)
            next
        }
    }

    /**
     * Setzt oder entfernt die Markierung für mehrere Elemente auf einmal — für die Mehrfachauswahl
     * im Grid. [favorite] wird ausdrücklich übergeben, statt jedes Element einzeln umzuschalten:
     * bei einer gemischten Auswahl (teils markiert, teils nicht) wäre "umschalten" für den Nutzer
     * nicht vorhersehbar.
     */
    fun setAll(context: Context, ids: Set<Long>, favorite: Boolean) {
        ensureLoaded(context)
        _favorites.update { current ->
            val next = if (favorite) current + ids else current - ids
            persist(context, next)
            next
        }
    }

    private fun read(context: Context): Set<Long> {
        val raw = prefs(context).getString(KEY_IDS, null) ?: return emptySet()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptySet()
        return (0 until array.length()).map { array.getLong(it) }.toSet()
    }

    private fun persist(context: Context, ids: Set<Long>) {
        prefs(context).edit { putString(KEY_IDS, JSONArray(ids.toList()).toString()) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
