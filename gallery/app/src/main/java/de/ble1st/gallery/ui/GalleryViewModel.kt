package de.ble1st.gallery.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import de.ble1st.gallery.data.album.CustomAlbum
import de.ble1st.gallery.data.album.CustomAlbumStore
import de.ble1st.gallery.data.favorite.FavoritesStore
import de.ble1st.gallery.data.media.ALL_BUCKET_ID
import de.ble1st.gallery.data.media.Bucket
import de.ble1st.gallery.data.media.FAVORITES_BUCKET_ID
import de.ble1st.gallery.data.media.MediaItem
import de.ble1st.gallery.data.media.MediaListItem
import de.ble1st.gallery.data.media.MediaPagingSource
import de.ble1st.gallery.data.media.MediaQuery
import de.ble1st.gallery.data.media.MediaStoreRepository
import de.ble1st.gallery.data.media.MediaType
import de.ble1st.gallery.data.media.SortOrder
import de.ble1st.gallery.data.media.headerBetween
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Welcher Ausschnitt der Mediathek gerade gezeigt wird.
 *
 * Die Unterscheidung ist nicht kosmetisch: [All] und [Folder] sind MediaStore-Abfragen und werden
 * **seitenweise** geladen, [Favorites] und [Album] sind ID-Mengen aus den eigenen Speichern und
 * werden vollständig geladen — sie sind durch die Nutzerauswahl von sich aus begrenzt, und eine
 * Seite aus einer ID-Menge zu schneiden hieße, die IDs in eine `ORDER BY`-Klausel zu übersetzen
 * (s. [MediaStoreRepository.queryItems]).
 */
sealed interface MediaScope {
    data object All : MediaScope
    data class Folder(val bucketId: Long) : MediaScope
    data object Favorites : MediaScope
    data class Album(val albumId: String) : MediaScope

    companion object {
        /** Übersetzt die über die Navigation gereichte Bucket-ID (bzw. Album-ID) in einen Scope.
         * Die virtuellen Alben sind damit an genau einer Stelle bekannt — vorher kannte sowohl
         * `itemsForBucket` als auch der Betrachter ihre Sonderbehandlung. */
        fun of(bucketId: Long, customAlbumId: String? = null): MediaScope = when {
            customAlbumId != null -> Album(customAlbumId)
            bucketId == ALL_BUCKET_ID -> All
            bucketId == FAVORITES_BUCKET_ID -> Favorites
            else -> Folder(bucketId)
        }
    }
}

/**
 * Ein einziges, am `NavHost` gehaltenes ViewModel statt eines eigenen pro Screen
 * (Alben/Grid/Betrachter) — alle drei Screens arbeiten auf demselben Medienbestand, ein
 * `ContentObserver` pro Screen-Instanz wäre unnötig redundant. `AndroidViewModel` (statt
 * ViewModel + Application separat durchreichen) für den Application-Context, den
 * [MediaStoreRepository] braucht.
 *
 * ## Kein `allItems` mehr (analyse.md 6.2 „MediaStore-Paging")
 *
 * Bis 2026-09-04 hielt dieses ViewModel den **gesamten** Medienbestand als `StateFlow<List<MediaItem>>`
 * und bediente daraus jede Ansicht per Kotlin-Filter. Das war der Grund, aus dem die Galerie die
 * ganze Bibliothek im Speicher hatte — und jede MediaStore-Änderung baute die Liste vollständig neu
 * auf. Jetzt fragt jede Ansicht das ab, was sie zeigt: das Raster seitenweise, die Ordnerübersicht
 * als Faltung im Cursor, ID-Mengen gezielt, ein einzelnes Element einzeln.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val _sortOrder = MutableStateFlow(SortOrder.DATE)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    val customAlbums: StateFlow<List<CustomAlbum>> = CustomAlbumStore.albums

    val favorites: StateFlow<Set<Long>> = FavoritesStore.favorites

    /** Ordnerkacheln der Übersicht — kommen aus einer eigenen, schlanken Abfrage und nicht mehr
     * aus dem Gesamtbestand, s. [MediaStoreRepository.observeBuckets]. */
    val buckets: StateFlow<List<Bucket>> = MediaStoreRepository.observeBuckets(application)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Signal für Ansichten, die nach jeder MediaStore-Änderung selbst nachfragen müssen
     * (Album-Kachelzahlen, einzelne Elemente in der Navigation). */
    private val changes: Flow<Unit> = MediaStoreRepository.observeChanges(application).onStart { emit(Unit) }

    // ---------------------------------------------------------------- Raster

    private val gridRequest = MutableStateFlow(GridRequest())

    /**
     * Der Inhalt des Rasters als Paging-Strom, inklusive der Datums-Überschriften.
     *
     * `cachedIn(viewModelScope)`, damit ein Drehen des Geräts oder ein Rückweg aus dem Betrachter
     * nicht die ganze erste Seite neu lädt und die Scrollposition verliert.
     */
    val gridItems: Flow<PagingData<MediaListItem>> =
        combine(gridRequest, _sortOrder, favorites, customAlbums) { request, order, favoriteIds, albums ->
            ResolvedRequest(
                scope = request.scope,
                search = request.search,
                order = order,
                // Nur für die ID-Mengen-Scopes aufgelöst; für Ordner-Scopes bleibt es null, sonst
                // würde jede Favoriten-Änderung auch dort den Pager neu starten.
                ids = when (val scope = request.scope) {
                    is MediaScope.Favorites -> favoriteIds
                    is MediaScope.Album -> albums.find { it.id == scope.albumId }?.itemIds ?: emptySet()
                    else -> null
                },
            )
        }
            .distinctUntilChanged()
            .flatMapLatest { request -> pagingFlow(request) }
            .cachedIn(viewModelScope)

    /** Der Rasterbildschirm meldet hiermit, was er gerade zeigt. Getrennt von [setSortOrder],
     * weil die Sortierung bildschirmübergreifend gilt. */
    fun setGridRequest(scope: MediaScope, search: String) {
        gridRequest.value = GridRequest(scope, search)
    }

    private fun pagingFlow(request: ResolvedRequest): Flow<PagingData<MediaListItem>> {
        val base: Flow<PagingData<MediaItem>> = if (request.ids != null) {
            // Begrenzte ID-Menge: vollständig laden und als eine Seite ausliefern. Der Umweg über
            // PagingData ist Absicht — so hat der Rasterbildschirm genau einen Renderpfad statt
            // einer Liste hier und eines Paging-Stroms dort.
            flowOf(request.ids).map { ids ->
                val items = withContext(Dispatchers.IO) {
                    MediaStoreRepository.queryItems(getApplication(), ids, request.order)
                }
                PagingData.from(items.filterBySearch(request.search))
            }
        } else {
            Pager(
                config = PagingConfig(
                    pageSize = MediaPagingSource.PAGE_SIZE,
                    enablePlaceholders = false,
                ),
                pagingSourceFactory = {
                    MediaPagingSource(getApplication(), request.toMediaQuery(), viewModelScope)
                },
            ).flow
        }

        return base.map { pagingData ->
            if (request.order != SortOrder.DATE) {
                // Zeitleiste nur bei Sortierung nach Datum — nach Name oder Größe sortiert wäre
                // eine Datums-Überschrift zwischen zwei beliebigen Einträgen sinnlos.
                pagingData.map { MediaListItem.Entry(it) }
            } else {
                pagingData
                    .map { MediaListItem.Entry(it) }
                    // Der Strom besteht an dieser Stelle ausschließlich aus Entry-Elementen (das
                    // map darüber), die Überschriften kommen erst hier dazu.
                    .insertSeparators { before, after -> headerBetween(before?.item, after?.item) }
            }
        }
    }

    /**
     * Alle IDs des gerade gezeigten Ausschnitts — für „Alle auswählen".
     *
     * Muss abgefragt werden statt aus der Liste gelesen: der Paging-Strom kennt immer nur die
     * geladenen Seiten, „alle auswählen" hätte sonst nur ausgewählt, was der Nutzer schon
     * gescrollt hat.
     */
    suspend fun selectAllInScope() {
        val request = ResolvedRequest(
            scope = gridRequest.value.scope,
            search = gridRequest.value.search,
            order = _sortOrder.value,
            ids = when (val scope = gridRequest.value.scope) {
                is MediaScope.Favorites -> favorites.value
                is MediaScope.Album -> customAlbums.value.find { it.id == scope.albumId }?.itemIds ?: emptySet()
                else -> null
            },
        )
        val ids = withContext(Dispatchers.IO) {
            if (request.ids != null) {
                MediaStoreRepository.queryItems(getApplication(), request.ids, request.order)
                    .filterBySearch(request.search)
                    .map { it.id }
            } else {
                MediaStoreRepository.queryIds(getApplication(), request.toMediaQuery())
            }
        }
        _selection.value = ids.toSet()
    }

    /** Die Uris der ausgewählten Elemente. Gezielt abgefragt statt aus der sichtbaren Liste
     * gefiltert — nach „Alle auswählen" sind darunter Elemente, die nie geladen waren. */
    suspend fun selectedUris(): List<Uri> = withContext(Dispatchers.IO) {
        MediaStoreRepository.queryItems(getApplication(), _selection.value, SortOrder.DATE).map { it.uri }
    }

    /** Das einzelne ausgewählte Element (für den Info-Dialog bei genau einer Auswahl). */
    suspend fun itemById(id: Long): MediaItem? = withContext(Dispatchers.IO) {
        MediaStoreRepository.queryItem(getApplication(), id)
    }

    // ---------------------------------------------------------------- Betrachter / Diashow

    /**
     * Die Bilder eines Ausschnitts als Paging-Strom — die Wisch-Geschwister des Betrachters und
     * die Bilder der Diashow. Videos bleiben außen vor (der Betrachter zeigt nur Bilder), und
     * zwar in der Abfrage statt als Kotlin-Filter, weil sonst jede Seite nach dem Filtern
     * unterschiedlich groß wäre.
     *
     * Immer nach Datum sortiert, unabhängig von der Nutzerwahl im Raster: das ist die
     * Wischreihenfolge im Betrachter, sie war auch vorher fest.
     */
    fun pagedImages(scope: MediaScope, ids: Set<Long>?): Flow<PagingData<MediaItem>> =
        if (ids != null) {
            flowOf(ids).map { idSet ->
                val items = withContext(Dispatchers.IO) {
                    MediaStoreRepository.queryItems(getApplication(), idSet, SortOrder.DATE)
                }
                PagingData.from(items.filter { it.type == MediaType.IMAGE })
            }
        } else {
            Pager(
                config = PagingConfig(
                    pageSize = MediaPagingSource.PAGE_SIZE,
                    // Platzhalter + Sprungschwelle: der Betrachter öffnet auf einer beliebigen
                    // Position, nicht am Anfang — s. MediaPagingSource.placeholders.
                    enablePlaceholders = true,
                    jumpThreshold = MediaPagingSource.PAGE_SIZE * 3,
                ),
                pagingSourceFactory = {
                    MediaPagingSource(getApplication(), imageQuery(scope), viewModelScope, placeholders = true)
                },
            ).flow
        }

    /** Die ID-Menge eines Scopes, oder `null`, wenn er seitenweise geladen wird. Der Betrachter
     * und die Diashow brauchen sie, um [pagedImages] und [imageIndexOf] gleich zu parametrisieren. */
    fun idsForScope(scope: MediaScope): Set<Long>? = when (scope) {
        is MediaScope.Favorites -> favorites.value
        is MediaScope.Album -> customAlbums.value.find { it.id == scope.albumId }?.itemIds ?: emptySet()
        else -> null
    }

    /**
     * Position eines Bildes innerhalb der Wisch-Reihenfolge — der Betrachter muss beim Öffnen auf
     * der angetippten Aufnahme stehen, kennt aber nur deren ID.
     *
     * Liefert `-1`, wenn das Bild nicht (mehr) zum Ausschnitt gehört.
     */
    suspend fun imageIndexOf(scope: MediaScope, ids: Set<Long>?, itemId: Long): Int =
        withContext(Dispatchers.IO) {
            if (ids != null) {
                MediaStoreRepository.queryItems(getApplication(), ids, SortOrder.DATE)
                    .filter { it.type == MediaType.IMAGE }
                    .indexOfFirst { it.id == itemId }
            } else {
                MediaStoreRepository.indexOf(getApplication(), imageQuery(scope), itemId)
            }
        }

    private fun imageQuery(scope: MediaScope) = MediaQuery(
        bucketId = (scope as? MediaScope.Folder)?.bucketId,
        order = SortOrder.DATE,
        mediaTypes = setOf(MediaType.IMAGE),
    )

    // ---------------------------------------------------------------- Alben

    /** Titelbild und Anzahl für die „Alle"-Kachel der Übersicht. */
    val allAlbumSummary: StateFlow<AlbumSummary> = changes
        .map {
            withContext(Dispatchers.IO) {
                val application = getApplication<Application>()
                val query = MediaQuery(order = SortOrder.DATE)
                val cover = MediaStoreRepository.queryPage(application, query, limit = 1, offset = 0).firstOrNull()
                AlbumSummary(cover?.uri, MediaStoreRepository.count(application, query))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumSummary(null, 0))

    /**
     * Titelbild und Anzahl je eigenem Album sowie für die Favoriten — jeweils über die tatsächlich
     * noch vorhandenen Elemente.
     *
     * [CustomAlbum.itemIds] wird nie automatisch bereinigt, wenn eine referenzierte Aufnahme in
     * MediaStore gelöscht wird; die Kachel zeigte vorher `itemIds.size` direkt an und lag nach dem
     * Löschen referenzierter Fotos dauerhaft zu hoch. Deshalb wird hier gegen den tatsächlichen
     * Bestand aufgelöst — und deshalb hängt dieser Flow zusätzlich am Änderungssignal.
     */
    val albumSummaries: StateFlow<Map<String, AlbumSummary>> =
        combine(customAlbums, favorites, changes) { albums, favoriteIds, _ -> albums to favoriteIds }
            .map { (albums, favoriteIds) ->
                withContext(Dispatchers.IO) {
                    val application = getApplication<Application>()
                    buildMap {
                        put(FAVORITES_SUMMARY_KEY, summaryOf(application, favoriteIds))
                        for (album in albums) put(album.id, summaryOf(application, album.itemIds))
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private fun summaryOf(application: Application, ids: Set<Long>): AlbumSummary {
        val items = MediaStoreRepository.queryItems(application, ids, SortOrder.DATE)
        return AlbumSummary(items.firstOrNull()?.uri, items.size)
    }

    /** Der Inhalt eines eigenen Albums — begrenzte ID-Menge, deshalb vollständig geladen.
     * Die Schnittmenge mit dem tatsächlichen Bestand entsteht dabei von selbst: eine gelöschte
     * Aufnahme liefert die Abfrage schlicht nicht mehr mit. */
    fun customAlbumItems(albumId: String): Flow<List<MediaItem>> =
        combine(customAlbums, changes) { albums, _ -> albums.find { it.id == albumId }?.itemIds ?: emptySet() }
            .map { ids ->
                withContext(Dispatchers.IO) {
                    MediaStoreRepository.queryItems(getApplication(), ids, _sortOrder.value)
                }
            }

    // ---------------------------------------------------------------- Zustand

    fun toggleFavorite(id: Long) = FavoritesStore.toggle(getApplication(), id)

    /** [favorite] wird ausdrücklich mitgegeben statt je Element umzuschalten — Begründung s.
     * [FavoritesStore.setAll]. */
    fun setFavorites(ids: Set<Long>, favorite: Boolean) =
        FavoritesStore.setAll(getApplication(), ids, favorite)

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun toggleSelection(id: Long) {
        _selection.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /** Der ContentObserver lässt die betroffenen Seiten ohnehin neu laden, sobald das Löschen
     * abgeschlossen ist — hier nur die Auswahl bereinigen, damit gelöschte IDs nicht als
     * "ausgewählt" hängen bleiben. */
    fun onItemsDeleted() {
        _selection.value = emptySet()
    }

    fun createCustomAlbum(name: String): CustomAlbum = CustomAlbumStore.create(getApplication(), name)

    fun renameCustomAlbum(albumId: String, name: String) = CustomAlbumStore.rename(getApplication(), albumId, name)

    fun deleteCustomAlbum(albumId: String) = CustomAlbumStore.delete(getApplication(), albumId)

    fun addToCustomAlbum(albumId: String, itemIds: Set<Long>) =
        CustomAlbumStore.addItems(getApplication(), albumId, itemIds)

    fun removeFromCustomAlbum(albumId: String, itemIds: Set<Long>) =
        CustomAlbumStore.removeItems(getApplication(), albumId, itemIds)

    init {
        CustomAlbumStore.list(application) // löst das einmalige Laden aus SharedPreferences aus
        FavoritesStore.load(application)
    }

    private data class GridRequest(
        val scope: MediaScope = MediaScope.All,
        val search: String = "",
    )

    private data class ResolvedRequest(
        val scope: MediaScope,
        val search: String,
        val order: SortOrder,
        /** `null` = seitenweise Abfrage; sonst die vollständig zu ladende ID-Menge. */
        val ids: Set<Long>?,
    ) {
        fun toMediaQuery() = MediaQuery(
            bucketId = (scope as? MediaScope.Folder)?.bucketId,
            search = search.ifBlank { null },
            order = order,
        )
    }

    companion object {
        /** Schlüssel der Favoriten in [albumSummaries] — die Favoriten sind kein eigenes Album mit
         * ID, brauchen aber dieselbe Kachel-Auflösung. */
        const val FAVORITES_SUMMARY_KEY = "__favorites__"
    }
}

/** Titelbild und tatsächliche Elementanzahl einer Album-Kachel. */
data class AlbumSummary(val coverUri: Uri?, val itemCount: Int)

/** Namenssuche über eine bereits geladene, begrenzte Liste. Für die seitenweisen Ausschnitte
 * übernimmt das die Abfrage selbst (`DISPLAY_NAME LIKE`, s. [MediaQuery]). */
private fun List<MediaItem>.filterBySearch(search: String): List<MediaItem> =
    if (search.isBlank()) this else filter { it.displayName.contains(search, ignoreCase = true) }
