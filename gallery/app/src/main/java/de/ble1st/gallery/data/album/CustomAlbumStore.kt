package de.ble1st.gallery.data.album

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistiert benutzerdefinierte Alben als reine `MediaItem.id`-Mengen — MediaStore-Buckets sind
 * durch den Ablageordner einer Datei fest vorgegeben, ein Nutzer kann sie nicht frei umsortieren.
 * Ein "eigenes Album" ist deshalb bewusst kein echter Ordner (kein Verschieben/Kopieren von
 * Dateien, keine zusätzliche Speicherkopie), sondern nur eine hier gespeicherte Liste von
 * Referenzen — verschwindet ein referenziertes Element aus MediaStore (gelöscht), verschwindet es
 * beim nächsten Laden automatisch auch aus jedem Album, ohne dass diese Klasse davon wissen muss.
 * Klartext-`SharedPreferences` statt [de.ble1st.gallery.data.crypto.SecretStore] (anders als
 * [de.ble1st.gallery.data.webdav.WebDavAccountStore]) — hier werden keine Zugangsdaten abgelegt,
 * nur Namen/IDs.
 */
object CustomAlbumStore {
    private const val PREFS_FILE = "custom_albums"
    private const val KEY_ALBUMS = "albums"

    private val _albums = MutableStateFlow<List<CustomAlbum>>(emptyList())
    val albums: StateFlow<List<CustomAlbum>> = _albums

    @Volatile
    private var initialized = false

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (initialized) return
        _albums.value = readAll(context)
        initialized = true
    }

    fun list(context: Context): List<CustomAlbum> {
        ensureLoaded(context)
        return _albums.value
    }

    fun create(context: Context, name: String): CustomAlbum {
        ensureLoaded(context)
        val album = CustomAlbum(name = name)
        _albums.update { current ->
            val next = current + album
            persist(context, next)
            next
        }
        return album
    }

    fun rename(context: Context, albumId: String, name: String) {
        ensureLoaded(context)
        _albums.update { current ->
            val next = current.map { if (it.id == albumId) it.copy(name = name) else it }
            persist(context, next)
            next
        }
    }

    fun delete(context: Context, albumId: String) {
        ensureLoaded(context)
        _albums.update { current ->
            val next = current.filterNot { it.id == albumId }
            persist(context, next)
            next
        }
    }

    fun addItems(context: Context, albumId: String, itemIds: Set<Long>) {
        ensureLoaded(context)
        _albums.update { current ->
            val next = current.map { if (it.id == albumId) it.copy(itemIds = it.itemIds + itemIds) else it }
            persist(context, next)
            next
        }
    }

    fun removeItems(context: Context, albumId: String, itemIds: Set<Long>) {
        ensureLoaded(context)
        _albums.update { current ->
            val next = current.map { if (it.id == albumId) it.copy(itemIds = it.itemIds - itemIds) else it }
            persist(context, next)
            next
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun readAll(context: Context): List<CustomAlbum> {
        val raw = prefs(context).getString(KEY_ALBUMS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                val idsArray = obj.getJSONArray("itemIds")
                val ids = (0 until idsArray.length()).map { idsArray.getLong(it) }.toSet()
                CustomAlbum(id = obj.getString("id"), name = obj.getString("name"), itemIds = ids)
            }.getOrNull()
        }
    }

    private fun persist(context: Context, albums: List<CustomAlbum>) {
        val array = JSONArray()
        albums.forEach { album ->
            array.put(
                JSONObject().apply {
                    put("id", album.id)
                    put("name", album.name)
                    put("itemIds", JSONArray(album.itemIds.toList()))
                },
            )
        }
        prefs(context).edit { putString(KEY_ALBUMS, array.toString()) }
    }
}
