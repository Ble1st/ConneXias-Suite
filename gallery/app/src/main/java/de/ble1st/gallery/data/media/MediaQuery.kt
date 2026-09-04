package de.ble1st.gallery.data.media

import android.provider.MediaStore

/**
 * Beschreibt einen Ausschnitt des Medienbestands und baut daraus Selection, Argumente und
 * Sortierung einer MediaStore-Abfrage.
 *
 * Reine Zeichenkettenlogik ohne `ContentResolver` — die Spaltennamen in `MediaStore` sind
 * `static final String`-Konstanten und werden vom Compiler eingesetzt, diese Datei läuft deshalb
 * unter einem gewöhnlichen JVM-Unit-Test (s. `MediaQueryTest`) und nicht erst auf einem Gerät.
 *
 * Der Grund, dass es diese Klasse überhaupt gibt: bis zur Paging-Umstellung wurde der gesamte
 * Bestand geladen und danach in Kotlin gefiltert und sortiert (`sortedItems`, clientseitige
 * Namenssuche). Wer nur ein Fenster von 100 Elementen lädt, muss beides der Datenbank überlassen —
 * sonst sortiert er nur innerhalb des Fensters.
 */
data class MediaQuery(
    /** `null` = alle Ordner. Sonst der `BUCKET_ID` eines echten MediaStore-Ordners; die virtuellen
     * Alben ([ALL_BUCKET_ID], [FAVORITES_BUCKET_ID]) und die eigenen Alben werden **nicht** hier
     * abgebildet — sie sind ID-Mengen und werden über
     * [MediaStoreRepository.queryItems] geladen, s. dortige Begründung. */
    val bucketId: Long? = null,
    /** Teilstring-Suche über den Dateinamen, `null`/leer = keine Suche. */
    val search: String? = null,
    val order: SortOrder = SortOrder.DATE,
    /** Welche Medienarten die Abfrage liefern soll. Der Betrachter und die Diashow zeigen nur
     * Bilder — vorher wurde dafür die vollständig geladene Liste in Kotlin gefiltert, seitenweise
     * muss die Einschränkung in die Abfrage. */
    val mediaTypes: Set<MediaType> = setOf(MediaType.IMAGE, MediaType.VIDEO),
) {

    init {
        require(mediaTypes.isNotEmpty()) { "mediaTypes darf nicht leer sein — die Abfrage hätte kein Ergebnis" }
    }

    fun selection(): String = buildString {
        val placeholders = mediaTypes.joinToString(", ") { "?" }
        append("${MediaStore.Files.FileColumns.MEDIA_TYPE} IN ($placeholders)")
        if (bucketId != null) append(" AND ${MediaStore.Files.FileColumns.BUCKET_ID} = ?")
        if (!search.isNullOrBlank()) append(" AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'")
    }

    fun selectionArgs(): Array<String> = buildList {
        // Reihenfolge stabil an der Enum-Deklaration statt an der Set-Iteration — sonst hinge die
        // Argumentfolge an der Set-Implementierung und wäre nicht testbar.
        MediaType.entries.filter { it in mediaTypes }.forEach { add(mediaTypeValue(it).toString()) }
        if (bucketId != null) add(bucketId.toString())
        if (!search.isNullOrBlank()) add("%${escapeLike(search)}%")
    }.toTypedArray()

    /** Sortierung inklusive `_ID` als Stichentscheid. Ohne den ist die Reihenfolge zweier
     * Aufnahmen mit identischem Zeitstempel (Serienbild, per Skript kopierte Dateien) undefiniert
     * — bei einer vollständig geladenen Liste war das harmlos, bei seitenweisem Laden führt es zu
     * doppelten oder verschluckten Einträgen an der Seitengrenze. */
    fun sortOrder(): String = when (order) {
        SortOrder.DATE -> "$DATE_SORT_EXPRESSION DESC, ${MediaStore.Files.FileColumns._ID} DESC"
        SortOrder.NAME ->
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} COLLATE NOCASE ASC, " +
                "${MediaStore.Files.FileColumns._ID} ASC"
        SortOrder.SIZE -> "${MediaStore.Files.FileColumns.SIZE} DESC, ${MediaStore.Files.FileColumns._ID} DESC"
    }

    /** Ersatzsortierung, falls der MediaProvider [sortOrder] ablehnt — s. [DATE_SORT_EXPRESSION].
     * Nur reine Spaltennamen, nichts, woran eine Validierung Anstoß nehmen könnte. */
    fun fallbackSortOrder(): String = when (order) {
        SortOrder.DATE -> "${MediaStore.Files.FileColumns.DATE_ADDED} DESC, ${MediaStore.Files.FileColumns._ID} DESC"
        SortOrder.NAME -> "${MediaStore.Files.FileColumns.DISPLAY_NAME} ASC, ${MediaStore.Files.FileColumns._ID} ASC"
        SortOrder.SIZE -> "${MediaStore.Files.FileColumns.SIZE} DESC, ${MediaStore.Files.FileColumns._ID} DESC"
    }

    companion object {
        /**
         * Das angezeigte Datum ist `DATE_TAKEN` (Aufnahmezeitpunkt aus EXIF) mit
         * `DATE_ADDED * 1000` als Rückfall, wenn `DATE_TAKEN` fehlt — s. [MediaItem.dateSortMillis]
         * zur Begründung. Solange der gesamte Bestand im Speicher lag, ließ sich diese Regel in
         * Kotlin anwenden; seitenweise geladen muss sie in die `ORDER BY`-Klausel.
         *
         * Ein reines `datetaken DESC` wäre falsch: für importierte Dateien ohne EXIF ist die Spalte
         * 0/NULL, sie würden geschlossen ans Ende rutschen statt nach Aufnahmedatum eingeordnet zu
         * werden.
         *
         * `CASE WHEN` statt `COALESCE(NULLIF(...))`, weil beide Varianten dasselbe leisten und
         * `CASE WHEN` in älteren MediaProvider-Fassungen der unauffälligere Ausdruck ist. Der
         * MediaProvider prüft `ORDER BY`-Zeichenketten (`SQLiteQueryBuilder.setStrict`) und darf
         * einen Ausdruck grundsätzlich ablehnen; [MediaStoreRepository] fängt das ab und wiederholt
         * die Abfrage mit [fallbackSortOrder]. Ob eine reale Android-Fassung tatsächlich ablehnt,
         * ist ohne Gerät nicht feststellbar — `MediaQueryInstrumentedTest` prüft es auf dem
         * Emulator.
         */
        val DATE_SORT_EXPRESSION: String =
            "CASE WHEN ${MediaStore.Files.FileColumns.DATE_TAKEN} > 0 " +
                "THEN ${MediaStore.Files.FileColumns.DATE_TAKEN} " +
                "ELSE ${MediaStore.Files.FileColumns.DATE_ADDED} * 1000 END"

        fun mediaTypeValue(type: MediaType): Int = when (type) {
            MediaType.IMAGE -> MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE
            MediaType.VIDEO -> MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
        }

        /** `%` und `_` sind LIKE-Platzhalter — ein Dateiname darf sie enthalten, ohne dass die
         * Suche danach zum Platzhalter wird. Der Backslash ist als ESCAPE-Zeichen vereinbart
         * (s. [selection]) und muss deshalb selbst verdoppelt werden. */
        fun escapeLike(value: String): String = buildString {
            for (char in value) {
                when (char) {
                    '\\', '%', '_' -> append('\\').append(char)
                    else -> append(char)
                }
            }
        }
    }
}
