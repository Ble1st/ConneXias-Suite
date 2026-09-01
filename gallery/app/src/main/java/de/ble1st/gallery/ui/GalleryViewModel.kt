package de.ble1st.gallery.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.ble1st.gallery.data.album.CustomAlbum
import de.ble1st.gallery.data.album.CustomAlbumStore
import de.ble1st.gallery.data.media.Bucket
import de.ble1st.gallery.data.media.MediaItem
import de.ble1st.gallery.data.media.MediaStoreRepository
import de.ble1st.gallery.data.media.SortOrder
import de.ble1st.gallery.data.media.groupIntoBuckets
import de.ble1st.gallery.data.media.sortedItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Ein einziges, am `NavHost` gehaltenes ViewModel statt eines eigenen pro Screen
 * (Alben/Grid/Betrachter) — alle drei Screens brauchen denselben MediaStore-Gesamtbestand,
 * ein `ContentObserver` pro Screen-Instanz wäre unnötig redundant. `AndroidViewModel` (statt
 * ViewModel + Application separat durchreichen) für den Application-Context, den
 * [MediaStoreRepository.observeMedia] braucht.
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val _allItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val allItems: StateFlow<List<MediaItem>> = _allItems.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.DATE)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    val buckets: StateFlow<List<Bucket>> = allItems
        .map { groupIntoBuckets(sortedItems(it, SortOrder.DATE)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val customAlbums: StateFlow<List<CustomAlbum>> = CustomAlbumStore.albums

    init {
        viewModelScope.launch {
            MediaStoreRepository.observeMedia(application).collect { items -> _allItems.value = items }
        }
        CustomAlbumStore.list(application) // löst das einmalige Laden aus SharedPreferences aus
    }

    fun itemsForBucket(bucketId: Long): List<MediaItem> {
        val items = if (bucketId == de.ble1st.gallery.data.media.ALL_BUCKET_ID) {
            _allItems.value
        } else {
            _allItems.value.filter { it.bucketId == bucketId }
        }
        return sortedItems(items, _sortOrder.value)
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun toggleSelection(id: Long) {
        _selection.update { if (id in it) it - id else it + id }
    }

    fun selectAll(ids: List<Long>) {
        _selection.value = ids.toSet()
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /** ContentObserver aktualisiert [allItems] ohnehin automatisch, sobald das Löschen
     * abgeschlossen ist — hier nur die Auswahl bereinigen, damit gelöschte IDs nicht als
     * "ausgewählt" hängen bleiben. */
    fun onItemsDeleted() {
        _selection.value = emptySet()
    }

    /** Schnittmenge aus Album-`itemIds` und tatsächlich noch vorhandenen [allItems] — verschwindet
     * ein Element aus MediaStore, verschwindet es automatisch mit aus jeder Album-Ansicht, ohne
     * dass [de.ble1st.gallery.data.album.CustomAlbumStore] selbst etwas davon merkt. */
    fun itemsForCustomAlbum(albumId: String): List<MediaItem> {
        val ids = customAlbums.value.find { it.id == albumId }?.itemIds ?: emptySet()
        return sortedItems(_allItems.value.filter { it.id in ids }, _sortOrder.value)
    }

    fun createCustomAlbum(name: String): CustomAlbum = CustomAlbumStore.create(getApplication(), name)

    fun renameCustomAlbum(albumId: String, name: String) = CustomAlbumStore.rename(getApplication(), albumId, name)

    fun deleteCustomAlbum(albumId: String) = CustomAlbumStore.delete(getApplication(), albumId)

    fun addToCustomAlbum(albumId: String, itemIds: Set<Long>) =
        CustomAlbumStore.addItems(getApplication(), albumId, itemIds)

    fun removeFromCustomAlbum(albumId: String, itemIds: Set<Long>) =
        CustomAlbumStore.removeItems(getApplication(), albumId, itemIds)
}
