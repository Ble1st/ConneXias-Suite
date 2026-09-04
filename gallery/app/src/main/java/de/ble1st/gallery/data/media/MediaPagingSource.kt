package de.ble1st.gallery.data.media

import android.content.Context
import androidx.paging.PagingSource
import androidx.paging.PagingSource.LoadResult.Page.Companion.COUNT_UNDEFINED
import androidx.paging.PagingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Lädt den durch [query] beschriebenen Ausschnitt des Medienbestands seitenweise.
 *
 * Schlüssel ist der Zeilen-Offset. Ein `LIMIT`/`OFFSET`-Fenster (und nicht ein Weiterwandern über
 * den letzten gesehenen Sortierwert) deshalb, weil die Sortierung je nach Nutzerwahl über Datum,
 * Name oder Größe läuft — ein Schlüssel-basiertes Weiterblättern bräuchte für jede dieser
 * Ordnungen ein eigenes, zum `ORDER BY` passendes Vergleichsprädikat, also dieselbe Ordnung
 * zweimal formuliert. Der Preis des Offsets ist, dass die Datenbank bis zur Startzeile zählt; bei
 * den Größenordnungen einer Gerätemediathek und einer Seite von [PAGE_SIZE] Einträgen ist das
 * nicht der begrenzende Faktor.
 *
 * Der `_ID`-Stichentscheid in [MediaQuery.sortOrder] ist hier keine Kosmetik: ohne ihn ist die
 * Reihenfolge zweier Aufnahmen mit identischem Zeitstempel undefiniert und kann zwischen zwei
 * Seitenabfragen wechseln — ein Eintrag erschiene dann doppelt, ein anderer gar nicht.
 *
 * [invalidationScope] verbindet die Quelle mit dem Änderungssignal des MediaStore: eine gelöschte
 * oder hinzugekommene Aufnahme verschiebt alle nachfolgenden Offsets, die geladenen Seiten sind
 * damit ungültig. Paging lädt nach [invalidate] die sichtbare Umgebung neu.
 */
class MediaPagingSource(
    private val context: Context,
    private val query: MediaQuery,
    invalidationScope: CoroutineScope,
    /**
     * Ob die Quelle Platzhalter für noch nicht geladene Einträge meldet.
     *
     * Das Raster braucht sie nicht — es wird von oben nach unten gescrollt, und die
     * Datums-Überschriften entstehen zwischen zwei tatsächlich geladenen Nachbarn
     * (`insertSeparators`); über eine Platzhalter-Lücke hinweg wäre die Nachbarschaft nicht
     * bestimmbar.
     *
     * Der Betrachter braucht sie: er öffnet auf der angetippten Aufnahme, die an Position 4.500
     * stehen kann. Ohne Platzhalter kennt der Pager nur die geladenen Einträge und würde die
     * Startposition auf das Ende des ersten Fensters zurechtstutzen. Mit Platzhaltern (und
     * [jumpingSupported]) lädt Paging stattdessen direkt um die angefragte Position herum.
     */
    private val placeholders: Boolean = false,
) : PagingSource<Int, MediaItem>() {

    /** Gesamtzahl für die Platzhalter-Rechnung. Einmal je Quelle ermittelt — nach einer
     * MediaStore-Änderung wird die Quelle ohnehin invalidiert und neu gebaut. */
    private var totalCount: Int? = null

    /** Ein Sprung über mehrere Seiten hinweg (Betrachter, s. [placeholders]) soll nicht als Kette
     * einzelner Nachladungen ablaufen, sondern als Neuladen an der Zielposition. */
    override val jumpingSupported: Boolean get() = placeholders

    init {
        MediaStoreRepository.observeChanges(context)
            .onEach { invalidate() }
            .launchIn(invalidationScope)
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MediaItem> {
        val offset = params.key ?: 0
        return try {
            val items = withContext(Dispatchers.IO) {
                MediaStoreRepository.queryPage(context, query, limit = params.loadSize, offset = offset)
            }
            val total = if (placeholders) {
                totalCount ?: withContext(Dispatchers.IO) { MediaStoreRepository.count(context, query) }
                    .also { totalCount = it }
            } else {
                null
            }
            LoadResult.Page(
                data = items,
                prevKey = if (offset == 0) null else (offset - params.loadSize).coerceAtLeast(0),
                // Eine kürzere Seite als angefordert heißt "Ende erreicht". Eine volle Seite heißt
                // nicht zwingend, dass noch etwas kommt — dann liefert die nächste Abfrage eben
                // eine leere Seite und beendet dort.
                nextKey = if (items.size < params.loadSize) null else offset + items.size,
                itemsBefore = if (total != null) offset else COUNT_UNDEFINED,
                itemsAfter = if (total != null) (total - offset - items.size).coerceAtLeast(0) else COUNT_UNDEFINED,
            )
        } catch (error: Exception) {
            LoadResult.Error(error)
        }
    }

    /**
     * Nach einer Invalidierung dort weiterladen, wo der Nutzer gerade steht. `anchorPosition` ist
     * die Position in der *bisherigen* Liste; sie kann nach dem Löschen mehrerer Aufnahmen zu weit
     * hinten liegen — [MediaStoreRepository.queryPage] liefert dann eine leere Seite und Paging
     * korrigiert nach oben. Eine Position vor dem Listenanfang kann dabei nicht entstehen, weil
     * `coerceAtLeast(0)` sie abfängt.
     */
    override fun getRefreshKey(state: PagingState<Int, MediaItem>): Int? =
        state.anchorPosition?.let { anchor ->
            (anchor - state.config.initialLoadSize / 2).coerceAtLeast(0)
        }

    companion object {
        /** Seitengröße. Ein Raster zeigt je nach Bildschirm 12–30 Kacheln gleichzeitig; 100
         * Einträge sind damit mehrere Bildschirmhöhen Vorlauf, ohne dass eine einzelne Abfrage
         * spürbar würde. */
        const val PAGE_SIZE = 100
    }
}
