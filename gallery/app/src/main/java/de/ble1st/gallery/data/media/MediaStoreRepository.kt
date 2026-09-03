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
import kotlinx.coroutines.channels.awaitClose
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
 */
object MediaStoreRepository {

    private val collection: Uri = MediaStore.Files.getContentUri("external")

    fun observeMedia(context: Context): Flow<List<MediaItem>> = callbackFlow {
        fun queryAndSend() {
            trySend(queryMedia(context, matchTrashed = false))
        }
        queryAndSend()

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            // onChange feuert auf dem Main-Thread (Handler ist an Looper.getMainLooper() gebunden,
            // von registerContentObserver so verlangt) — die blockierende MediaStore-Query
            // synchron direkt hier auszuführen würde bei einer großen Bibliothek zum ANR führen.
            // launch(Dispatchers.IO) verschiebt sie in eine an diesen Flow gebundene Coroutine;
            // .flowOn(Dispatchers.IO) unten betrifft nur den initialen queryAndSend()-Aufruf oben,
            // nicht spätere Callback-Aufrufe.
            override fun onChange(selfChange: Boolean) {
                launch(Dispatchers.IO) { queryAndSend() }
            }
        }
        context.contentResolver.registerContentObserver(collection, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.flowOn(Dispatchers.IO)

    /** Papierkorb existiert als MediaStore-Konzept erst ab API 30 (`MediaStore.createTrashRequest`)
     * — auf älteren Versionen bleibt dieser Flow dauerhaft leer, [de.ble1st.gallery.ui.trash.TrashScreen]
     * ist dort ohnehin nicht erreichbar (Einstiegspunkt in [de.ble1st.gallery.ui.albums.AlbumsScreen]
     * per SDK-Prüfung ausgeblendet). */
    @RequiresApi(Build.VERSION_CODES.R)
    fun observeTrashedMedia(context: Context): Flow<List<MediaItem>> = callbackFlow {
        fun queryAndSend() {
            trySend(queryMedia(context, matchTrashed = true))
        }
        queryAndSend()

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                launch(Dispatchers.IO) { queryAndSend() }
            }
        }
        context.contentResolver.registerContentObserver(collection, true, observer)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.flowOn(Dispatchers.IO)

    /**
     * Einmalige, blockierende Abfrage des aktuellen Medienbestands — für Aufrufer außerhalb der
     * UI, die keinen dauerhaft beobachteten Flow brauchen (der Cloud-Sync-Worker, s.
     * [de.ble1st.gallery.data.sync.CloudSyncWorker]). Der Worker startet unter Umständen erst
     * Minuten nach dem Tippen auf "Jetzt sichern" und muss deshalb den dann gültigen Bestand
     * lesen, nicht eine beim Tippen mitgegebene Liste.
     */
    fun loadMedia(context: Context): List<MediaItem> = queryMedia(context, matchTrashed = false)

    private fun queryMedia(context: Context, matchTrashed: Boolean): List<MediaItem> {
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val projection = buildList {
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

        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?)"
        val selectionArgs = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )
        val sortOrder = "${MediaStore.Files.FileColumns.DATE_ADDED} DESC"

        val cursor = if (matchTrashed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Trashed-Einträge sind für eine gewöhnliche Query unsichtbar — nur der
            // Bundle-Argumente-Weg mit QUERY_ARG_MATCH_TRASHED=MATCH_ONLY liefert sie.
            val queryArgs = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            }
            context.contentResolver.query(collection, projection, queryArgs, null)
        } else {
            context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)
        }

        val items = mutableListOf<MediaItem>()
        cursor?.use { items += parseCursor(it, useRelativePath) }
        return items
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

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idIdx)
            val mediaTypeValue = cursor.getInt(typeIdx)
            val type = when (mediaTypeValue) {
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE -> MediaType.IMAGE
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO -> MediaType.VIDEO
                else -> continue
            }
            val baseCollection = if (type == MediaType.IMAGE) {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val displayName = cursor.getString(nameIdx).orEmpty()
            val rawPath = cursor.getString(pathIdx).orEmpty()
            val path = if (useRelativePath) "$rawPath$displayName" else rawPath.ifEmpty { displayName }
            val dateTakenMillis = cursor.getLong(dateTakenIdx)
            val dateSortMillis = if (dateTakenMillis > 0) dateTakenMillis else cursor.getLong(dateAddedIdx) * 1000

            items += MediaItem(
                id = id,
                uri = ContentUris.withAppendedId(baseCollection, id),
                displayName = displayName,
                bucketId = cursor.getLong(bucketIdIdx),
                bucketName = cursor.getString(bucketNameIdx).orEmpty(),
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
