package de.ble1st.gallery.data.media

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Ein Abschnitt der Grid-Ansicht: eine Datums-Überschrift und die darunter gezeigten Aufnahmen.
 *
 * [startMillis] ist der Beginn des Tages bzw. Monats in der Zeitzone des Geräts — die UI
 * formatiert daraus die Überschrift (die Formatierung selbst gehört nicht hierher, sie braucht
 * `Context`/`Locale`; diese Datei bleibt dadurch framework-frei und unit-testbar). [monthOnly]
 * sagt der UI, welche der beiden Formatierungen gemeint ist.
 */
data class MediaSection(
    val startMillis: Long,
    val monthOnly: Boolean,
    val items: List<MediaItem>,
)

/**
 * Gruppiert nach Datum absteigend sortierte [items] in Tages- bzw. Monatsabschnitte — die
 * Zeitleiste, die eine Galerie von einem beliebigen Bilderraster unterscheidet: ohne sie ist bei
 * mehreren tausend Aufnahmen nicht erkennbar, wo ein Tag aufhört und der nächste anfängt.
 *
 * **Tage für das laufende Jahr, Monate für alles Ältere.** Eine reine Tagesgruppierung erzeugt
 * über zehn Jahre Fotos hinweg tausende Überschriften, zwischen denen oft nur ein einziges Bild
 * steht; eine reine Monatsgruppierung verliert dagegen genau die Auflösung, die für die letzten
 * Wochen gebraucht wird. Die Grenze ist das Jahr von [today], nicht "vor 365 Tagen" — ein
 * gleitendes Fenster würde Abschnitte mitten im Monat zerschneiden.
 *
 * Die Eingabe muss bereits absteigend nach [MediaItem.dateSortMillis] sortiert sein (das ist sie
 * im einzigen Aufrufer, `MediaGridScreen`, bei Sortierung nach Datum). Diese Funktion sortiert
 * bewusst nicht selbst nach: bei Sortierung nach Name oder Größe wäre eine Datumsgruppierung
 * sinnlos, dort wird sie gar nicht erst aufgerufen.
 *
 * [zone] und [today] sind Parameter statt fester Systemwerte, damit der Test nicht von der
 * Zeitzone und dem Datum des ausführenden Rechners abhängt.
 */
fun groupByTime(
    items: List<MediaItem>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): List<MediaSection> {
    if (items.isEmpty()) return emptyList()
    val currentYear = today.year

    val sections = mutableListOf<MediaSection>()
    var currentKey: Pair<Long, Boolean>? = null
    var currentItems = mutableListOf<MediaItem>()

    for (item in items) {
        val date = Instant.ofEpochMilli(item.dateSortMillis).atZone(zone).toLocalDate()
        val monthOnly = date.year != currentYear
        val start = if (monthOnly) date.withDayOfMonth(1) else date
        val key = start.atStartOfDay(zone).toInstant().toEpochMilli() to monthOnly

        if (key != currentKey) {
            currentKey?.let { (startMillis, isMonth) ->
                sections += MediaSection(startMillis, isMonth, currentItems)
            }
            currentKey = key
            currentItems = mutableListOf()
        }
        currentItems += item
    }
    currentKey?.let { (startMillis, isMonth) ->
        sections += MediaSection(startMillis, isMonth, currentItems)
    }
    return sections
}
