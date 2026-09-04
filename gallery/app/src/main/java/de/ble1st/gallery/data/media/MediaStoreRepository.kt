package de.ble1st.gallery.data.media

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * Liest Bilder + Videos über eine einzige Query gegen `MediaStore.Files` (statt zwei getrennte
 * gegen `MediaStore.Images`/`Video` zu kombinieren) — eine `MEDIA_TYPE IN (...)`-Auswahl liefert
 * beide Medientypen bereits gemeinsam nach Datum sortiert, ohne zwei Cursor-Ergebnisse von Hand
 * mischen zu müssen. Jeder Eintrag bekommt trotzdem seine typ-spezifische
 * `content://media/external/images|video/media/<id>`-Uri (über [ContentUris.withAppendedId] auf
 * die passende Collection), weil nur diese — nicht die generische Files-Uri — von Coil/ExoPlayer
 * zuverlässig aufgelöst wird.
 *
 * `RELATIVE_PATH` (für den Info-Dialog-Pfad) existiert erst ab API 29 — auf API 26–28 fällt diese
 * Spalte auf die ältere `DATA`-Spalte (voller Pfad) zurück; ab API 29 wird `DATA` bewusst nicht
 * mehr abgefragt, weil Scoped Storage sie dort für fremde Einträge ohnehin leert/verweigert.
 *
 * ## Seitenweises Laden (analyse.md 6.2 "MediaStore-Paging")
 *
 * Bis 2026-09-04 gab es hier genau eine Methode: „gib mir den gesamten Bestand als Liste". Alles
 * Weitere — Ordnerübersicht, Album-Inhalt, Suche, Sortierung, Geschwister im Betrachter — wurde
 * daraus in Kotlin gefiltert. Bei einer großen Bibliothek hieß das: jedes `MediaItem` samt
 * `Uri` und drei Zeichenketten dauerhaft im Speicher, **und** ein vollständiger Neuaufbau bei
 * jeder einzelnen MediaStore-Änderung (der `ContentObserver` feuert pro Schreibvorgang, bei einer
 * Serienaufnahme oder einem laufenden Cloud-Sync also im Sekundentakt).
 *
 * Jetzt lädt jeder Aufrufer nur, was er wirklich braucht:
 *
 * | Aufrufer | Weg |
 * |---|---|
 * | Raster (alle/ein Ordner) | [MediaPagingSource] über [queryPage] — Fenster von ~100 Einträgen |
 * | Ordnerübersicht | [observeBuckets] — faltet den Cursor zu Ordnerkacheln, ohne ein einziges `MediaItem` zu bauen |
 * | Favoriten, eigene Alben | [queryItems] — ID-Menge, durch die Nutzerauswahl ohnehin begrenzt |
 * | „Alle auswählen" | [queryIds] — nur `_ID`, 8 Byte je Eintrag |
 * | Einzelnes Element (Navigation) | [queryItem] |
 * | Cloud-Sync-Worker | [loadMedia] — braucht tatsächlich alles, läuft aber im Hintergrund und verwirft es sofort wieder |
 */
object MediaStoreRepository {

    private val collection: Uri = MediaStore.Files.getContentUri("external")

    /** Wartezeit, bevor eine MediaStore-Änderung als Signal weitergereicht wird. Der
     * `ContentObserver` feuert pro Schreibvorgang — beim Import eines Albums oder einer
     * Serienaufnahme dutzendfach in Folge. Ohne diese Zusammenfassung würde jede einzelne
     * Änderung eine vollständige Neuladung der sichtbaren Seiten auslösen. */
    private const val CHANGE_DEBOUNCE_MILLIS = 300L

    /** Obergrenze für `_ID IN (...)`: SQLite erlaubt standardmäßig 999 gebundene Variablen je
     * Anweisung. [queryItems] zerlegt größere ID-Mengen entsprechend. */
    private const val ID_CHUNK_SIZE = 900

    // ---------------------------------------------------------------- Änderungssignal

    /**
     * Meldet — zusammengefasst, s. [CHANGE_DEBOUNCE_MILLIS] — dass sich am Medienbestand etwas
     * geändert hat. Trägt bewusst keine Daten: wer etwas braucht, fragt danach selbst neu ab.
     */
    fun observeChanges(context: Context): Flow<Unit> = callbackFlow {
        var pending: Job? = null
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            // onChange feuert auf dem Main-Thread (Handler an Looper.getMainLooper() gebunden, von
            // registerContentObserver so verlangt) — hier wird deshalb nur ein Timer gesetzt, nie
            // abgefragt.
            override fun onChange(selfChange: Boolean) {
                pending?.cancel()
                pending = launch {
                    delay(CHANGE_DEBOUNCE_MILLIS)
                    send(Unit)
                }
            }
        }
        context.contentResolver.registerContentObserver(collection, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }

    // ---------------------------------------------------------------- Seitenweises Laden

    /** Eine Seite des durch [query] beschriebenen Ausschnitts. Blockierend — Aufrufer ist
     * [MediaPagingSource], der bereits auf [Dispatchers.IO] läuft. */
    fun queryPage(context: Context, query: MediaQuery, limit: Int, offset: Int): List<MediaItem> {
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        return runQuery(context, query, projection(useRelativePath), limit, offset) { cursor ->
            parseCursor(cursor, useRelativePath)
        } ?: emptyList()
    }

    /** Anzahl der Einträge des Ausschnitts, ohne einen davon zu materialisieren. */
    fun count(context: Context, query: MediaQuery): Int =
        runQuery(context, query, arrayOf(MediaStore.Files.FileColumns._ID), null, null) { it.count } ?: 0

    /** Nur die IDs des Ausschnitts, in Anzeigereihenfolge — für „Alle auswählen" und für die
     * Positionsbestimmung im Betrachter. 8 Byte je Eintrag statt eines vollen [MediaItem]. */
    fun queryIds(context: Context, query: MediaQuery): List<Long> =
        runQuery(context, query, arrayOf(MediaStore.Files.FileColumns._ID), null, null) { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            buildList(cursor.count) {
                while (cursor.moveToNext()) add(cursor.getLong(idIdx))
            }
        } ?: emptyList()

    /**
     * Position eines Eintrags innerhalb des Ausschnitts, oder `-1`, wenn er nicht dazugehört.
     *
     * Über [queryIds] statt über ein `COUNT(*)` mit nachgebautem Sortierprädikat: der Zählweg
     * müsste den Sortierausdruck aus [MediaQuery.sortOrder] als Vergleich ein zweites Mal
     * formulieren (inklusive des `_ID`-Stichentscheids) — zwei Formulierungen derselben Ordnung,
     * die auseinanderlaufen können. Der ID-Weg kann per Konstruktion nicht abweichen; er kostet
     * einen einmaligen Durchlauf beim Öffnen des Betrachters.
     */
    fun indexOf(context: Context, query: MediaQuery, id: Long): Int =
        queryIds(context, query).indexOf(id)

    /**
     * Lädt genau die angegebenen IDs — für die Favoriten und die eigenen Alben.
     *
     * Diese beiden sind bewusst **nicht** seitenweise: sie sind ID-Mengen, die durch die
     * Nutzerauswahl begrenzt sind (wer 20.000 Aufnahmen einzeln markiert, hat ein anderes
     * Problem), und eine Seite daraus zu schneiden hieße, die IDs in die `ORDER BY`-Klausel zu
     * übersetzen. Die Sortierung geschieht hier deshalb in Kotlin über [sortedItems].
     *
     * Große Mengen werden auf mehrere `IN`-Abfragen verteilt, s. [ID_CHUNK_SIZE].
     */
    fun queryItems(context: Context, ids: Set<Long>, order: SortOrder): List<MediaItem> {
        if (ids.isEmpty()) return emptyList()
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val items = mutableListOf<MediaItem>()
        for (chunk in ids.chunked(ID_CHUNK_SIZE)) {
            val placeholders = chunk.joinToString(", ") { "?" }
            val selection = "${MediaStore.Files.FileColumns._ID} IN ($placeholders)"
            val cursor = context.contentResolver.query(
                collection,
                projection(useRelativePath),
                selection,
                chunk.map { it.toString() }.toTypedArray(),
                null,
            )
            cursor?.use { items += parseCursor(it, useRelativePath) }
        }
        return sortedItems(items, order)
    }

    /** Ein einzelner Eintrag, oder `null`, wenn er nicht (mehr) existiert. */
    fun queryItem(context: Context, id: Long): MediaItem? =
        queryItems(context, setOf(id), SortOrder.DATE).firstOrNull()

    // ---------------------------------------------------------------- Ordnerübersicht

    /**
     * Die Ordnerkacheln der Übersicht, neu berechnet bei jeder (zusammengefassten) Änderung.
     *
     * Faltet den Cursor direkt zu [Bucket]s, ohne ein einziges [MediaItem] zu bauen: die Abfrage
     * holt vier Spalten statt elf, und im Speicher bleiben am Ende so viele Objekte, wie es Ordner
     * gibt — nicht so viele, wie es Aufnahmen gibt. Vorher entstand die Übersicht aus dem
     * vollständig geladenen Bestand (`groupIntoBuckets(allItems)`), war also der teuerste
     * Einzelgrund, den Gesamtbestand überhaupt im Speicher zu halten.
     */
    fun observeBuckets(context: Context): Flow<List<Bucket>> = callbackFlow {
        suspend fun emitBuckets() {
            send(queryBuckets(context))
        }
        emitBuckets()

        var pending: Job? = null
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                pending?.cancel()
                pending = launch(Dispatchers.IO) {
                    delay(CHANGE_DEBOUNCE_MILLIS)
                    emitBuckets()
                }
            }
        }
        context.contentResolver.registerContentObserver(collection, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.flowOn(Dispatchers.IO)

    private fun queryBuckets(context: Context): List<Bucket> {
        val query = MediaQuery(order = SortOrder.DATE)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.BUCKET_ID,
            MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val rows = runQuery(context, query, projection, null, null) { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val bucketIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
            val bucketNameIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val typeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            buildList(cursor.count) {
                while (cursor.moveToNext()) {
                    val type = mediaTypeOf(cursor.getInt(typeIdx)) ?: continue
                    add(
                        BucketRow(
                            itemId = cursor.getLong(idIdx),
                            bucketId = cursor.getLong(bucketIdIdx),
                            bucketName = cursor.getString(bucketNameIdx).orEmpty(),
                            type = type,
                        ),
                    )
                }
            }
        } ?: emptyList()
        return foldIntoBuckets(rows).map { fold ->
            Bucket(
                id = fold.id,
                name = fold.name,
                coverUri = mediaUri(fold.coverItemId, fold.coverType),
                itemCount = fold.itemCount,
            )
        }
    }

    // ---------------------------------------------------------------- Vollbestand / Papierkorb

    /**
     * Einmalige, blockierende Abfrage des vollständigen Medienbestands — für Aufrufer außerhalb
     * der UI, die tatsächlich alles brauchen (der Cloud-Sync-Worker, s.
     * [de.ble1st.gallery.data.sync.CloudSyncWorker]). Der Worker startet unter Umständen erst
     * Minuten nach dem Tippen auf "Jetzt sichern" und muss deshalb den dann gültigen Bestand
     * lesen, nicht eine beim Tippen mitgegebene Liste.
     *
     * Bleibt bewusst unseitenweise: der Worker läuft im Hintergrund, arbeitet die Liste einmal ab
     * und verwirft sie — es ist kein dauerhaft gehaltener Zustand wie der frühere `allItems`.
     */
    fun loadMedia(context: Context): List<MediaItem> = queryPage(context, MediaQuery(), limit = 0, offset = 0)

    /** Papierkorb existiert als MediaStore-Konzept erst ab API 30 (`MediaStore.createTrashRequest`)
     * — auf älteren Versionen bleibt dieser Flow dauerhaft leer, [de.ble1st.gallery.ui.trash.TrashScreen]
     * ist dort ohnehin nicht erreichbar (Einstiegspunkt in [de.ble1st.gallery.ui.albums.AlbumsScreen]
     * per SDK-Prüfung ausgeblendet).
     *
     * Unseitenweise, anders als das Raster: der Papierkorb hält nur, was in den letzten 30 Tagen
     * gelöscht wurde, und ist damit von sich aus begrenzt. */
    @RequiresApi(Build.VERSION_CODES.R)
    fun observeTrashedMedia(context: Context): Flow<List<MediaItem>> = callbackFlow {
        suspend fun emitTrashed() {
            send(queryTrashed(context))
        }
        emitTrashed()

        var pending: Job? = null
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                pending?.cancel()
                pending = launch(Dispatchers.IO) {
                    delay(CHANGE_DEBOUNCE_MILLIS)
                    emitTrashed()
                }
            }
        }
        context.contentResolver.registerContentObserver(collection, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.flowOn(Dispatchers.IO)

    @RequiresApi(Build.VERSION_CODES.R)
    private fun queryTrashed(context: Context): List<MediaItem> {
        // Kein SDK_INT-Vergleich für RELATIVE_PATH: der Papierkorb setzt ohnehin API 30 voraus
        // (s. @RequiresApi), die Spalte gibt es dort immer.
        val useRelativePath = true
        val query = MediaQuery(order = SortOrder.DATE)
        // Trashed-Einträge sind für eine gewöhnliche Query unsichtbar — nur der
        // Bundle-Argumente-Weg mit QUERY_ARG_MATCH_TRASHED=MATCH_ONLY liefert sie.
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, query.selection())
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, query.selectionArgs())
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, query.fallbackSortOrder())
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
        }
        val cursor = context.contentResolver.query(collection, projection(useRelativePath), args, null)
        return cursor?.use { parseCursor(it, useRelativePath) } ?: emptyList()
    }

    // ---------------------------------------------------------------- Abfrage-Mechanik

    private fun projection(useRelativePath: Boolean): Array<String> = buildList {
        add(MediaStore.Files.FileColumns._ID)
        add(MediaStore.Files.FileColumns.DISPLAY_NAME)
        add(MediaStore.Files.FileColumns.BUCKET_ID)
        add(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
        add(MediaStore.Files.FileColumns.MEDIA_TYPE)
        add(MediaStore.Files.FileColumns.DATE_ADDED)
        add(MediaStore.Files.FileColumns.DATE_TAKEN)
        add(MediaStore.Files.FileColumns.SIZE)
        add(MediaStore.Files.FileColumns.WIDTH)
        add(MediaStore.Files.FileColumns.HEIGHT)
        if (useRelativePath) add(MediaStore.Files.FileColumns.RELATIVE_PATH) else add(MediaStore.Files.FileColumns.DATA)
    }.toTypedArray()

    /**
     * Führt eine Abfrage aus und wertet den Cursor mit [read] aus.
     *
     * Zwei Dinge passieren hier, die sonst an jeder Abfragestelle stünden:
     *
     * 1. **`LIMIT`/`OFFSET`.** Ab API 30 gibt es dafür offizielle Bundle-Argumente. Darunter
     *    bleibt nur, beides an die Sortierzeichenkette anzuhängen — der Weg, den vor API 30 jede
     *    App gegangen ist, weil der MediaProvider die Zeichenkette unverändert in das
     *    SQL-`ORDER BY` einsetzt. [limit] `0` bedeutet „ohne Begrenzung".
     * 2. **Rückfall auf eine einfache Sortierung.** [MediaQuery.sortOrder] enthält bei Sortierung
     *    nach Datum einen `CASE WHEN`-Ausdruck (Begründung dort). Lehnt der MediaProvider ihn ab,
     *    wird die Abfrage einmal mit [MediaQuery.fallbackSortOrder] wiederholt, statt die
     *    Ansicht mit einer Ausnahme abstürzen zu lassen. Sichtbare Folge wäre dann nur, dass
     *    importierte Dateien ohne EXIF nach Importzeitpunkt statt nach Aufnahmezeitpunkt
     *    einsortiert werden — das Verhalten vor 2026-09-03.
     */
    private fun <T> runQuery(
        context: Context,
        query: MediaQuery,
        projection: Array<String>,
        limit: Int?,
        offset: Int?,
        read: (Cursor) -> T,
    ): T? {
        fun attempt(sortOrder: String): T? {
            val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val args = Bundle().apply {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, query.selection())
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, query.selectionArgs())
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    if (limit != null && limit > 0) {
                        putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
                        putInt(ContentResolver.QUERY_ARG_OFFSET, offset ?: 0)
                    }
                }
                context.contentResolver.query(collection, projection, args, null)
            } else {
                val withLimit = if (limit != null && limit > 0) {
                    "$sortOrder LIMIT $limit OFFSET ${offset ?: 0}"
                } else {
                    sortOrder
                }
                context.contentResolver.query(
                    collection,
                    projection,
                    query.selection(),
                    query.selectionArgs(),
                    withLimit,
                )
            }
            return cursor?.use(read)
        }

        return try {
            attempt(query.sortOrder())
        } catch (_: IllegalArgumentException) {
            // Der MediaProvider wirft IllegalArgumentException, wenn er eine ORDER-BY-Zeichenkette
            // nicht durchlässt (SQLiteQueryBuilder.setStrict). Genau dafür ist der Rückfall da.
            attempt(query.fallbackSortOrder())
        } catch (_: android.database.sqlite.SQLiteException) {
            attempt(query.fallbackSortOrder())
        }
    }

    private fun mediaTypeOf(value: Int): MediaType? = when (value) {
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaType.IMAGE
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaType.VIDEO
        else -> null
    }

    private fun mediaUri(id: Long, type: MediaType): Uri {
        val base = if (type == MediaType.IMAGE) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(base, id)
    }

    private fun parseCursor(cursor: Cursor, useRelativePath: Boolean): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val bucketIdIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_ID)
        val bucketNameIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
        val typeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val dateAddedIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
        val dateTakenIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_TAKEN)
        val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
        val widthIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.WIDTH)
        val heightIdx = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.HEIGHT)
        val pathColumn = if (useRelativePath) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
        val pathIdx = cursor.getColumnIndexOrThrow(pathColumn)

        // Der Ordnername wiederholt sich über alle Aufnahmen eines Ordners. cursor.getString()
        // liefert für jede Zeile eine neue Zeichenkette — bei einer Seite von 100 Einträgen aus
        // demselben Ordner also 100 gleiche Objekte. Die Map hält je Ordner eines.
        val bucketNames = mutableMapOf<Long, String>()

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idIdx)
            val type = mediaTypeOf(cursor.getInt(typeIdx)) ?: continue
            val displayName = cursor.getString(nameIdx).orEmpty()
            val rawPath = cursor.getString(pathIdx).orEmpty()
            val path = if (useRelativePath) "$rawPath$displayName" else rawPath.ifEmpty { displayName }
            val dateTakenMillis = cursor.getLong(dateTakenIdx)
            val dateSortMillis = if (dateTakenMillis > 0) dateTakenMillis else cursor.getLong(dateAddedIdx) * 1000
            val bucketId = cursor.getLong(bucketIdIdx)

            items += MediaItem(
                id = id,
                uri = mediaUri(id, type),
                displayName = displayName,
                bucketId = bucketId,
                bucketName = bucketNames.getOrPut(bucketId) { cursor.getString(bucketNameIdx).orEmpty() },
                type = type,
                dateSortMillis = dateSortMillis,
                sizeBytes = cursor.getLong(sizeIdx),
                width = cursor.getInt(widthIdx),
                height = cursor.getInt(heightIdx),
                path = path,
            )
        }
        return items
    }
}
