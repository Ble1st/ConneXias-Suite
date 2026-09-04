package de.ble1st.gallery.data.media

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Der Zeitabschnitt, in den eine Aufnahme fällt.
 *
 * [startMillis] ist der Beginn des Tages bzw. Monats in der Zeitzone des Geräts — die UI
 * formatiert daraus die Überschrift (die Formatierung selbst gehört nicht hierher, sie braucht
 * `Context`/`Locale`; diese Datei bleibt dadurch framework-frei und unit-testbar). [monthOnly]
 * sagt der UI, welche der beiden Formatierungen gemeint ist.
 */
data class SectionKey(val startMillis: Long, val monthOnly: Boolean)

/**
 * Ein Eintrag der Rasterliste: entweder eine Datums-Überschrift oder eine Aufnahme.
 *
 * Bis zur Paging-Umstellung wurden die Abschnitte vorab als `List<MediaSection>` gebaut — das
 * setzte voraus, dass der gesamte Bestand vorliegt. Seitenweise geladen entstehen die
 * Überschriften stattdessen zwischen zwei benachbarten Einträgen des Datenstroms
 * (`PagingData.insertSeparators`, s. [headerBetween]).
 */
sealed interface MediaListItem {
    data class Header(val key: SectionKey) : MediaListItem
    data class Entry(val item: MediaItem) : MediaListItem
}

/**
 * Der Zeitabschnitt einer Aufnahme: **Tage für das laufende Jahr, Monate für alles Ältere.**
 *
 * Die Zeitleiste ist das, was eine Galerie von einem beliebigen Bilderraster unterscheidet: ohne
 * sie ist bei mehreren tausend Aufnahmen nicht erkennbar, wo ein Tag aufhört und der nächste
 * anfängt. Eine reine Tagesgruppierung erzeugt über zehn Jahre Fotos hinweg tausende
 * Überschriften, zwischen denen oft nur ein einziges Bild steht; eine reine Monatsgruppierung
 * verliert dagegen genau die Auflösung, die für die letzten Wochen gebraucht wird. Die Grenze ist
 * das Jahr von [today], nicht "vor 365 Tagen" — ein gleitendes Fenster würde Abschnitte mitten im
 * Monat zerschneiden.
 *
 * [zone] und [today] sind Parameter statt fester Systemwerte, damit der Test nicht von der
 * Zeitzone und dem Datum des ausführenden Rechners abhängt.
 */
fun sectionKeyOf(
    item: MediaItem,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): SectionKey {
    val date = Instant.ofEpochMilli(item.dateSortMillis).atZone(zone).toLocalDate()
    val monthOnly = date.year != today.year
    val start = if (monthOnly) date.withDayOfMonth(1) else date
    return SectionKey(start.atStartOfDay(zone).toInstant().toEpochMilli(), monthOnly)
}

/**
 * Die Überschrift, die zwischen [before] und [after] gehört — oder `null`, wenn dort keine
 * hingehört.
 *
 * Zugeschnitten auf `PagingData.insertSeparators`: [before] ist `null` am Anfang der geladenen
 * Liste (dann bekommt [after] eine Überschrift), [after] ist `null` an ihrem Ende (dann nie eine,
 * eine Überschrift ohne Inhalt darunter wäre sinnlos).
 *
 * Setzt voraus, dass der Strom nach Datum absteigend sortiert ist — bei Sortierung nach Name oder
 * Größe wäre eine Datumsgruppierung sinnlos und die UI ruft diese Funktion gar nicht erst auf.
 */
fun headerBetween(
    before: MediaItem?,
    after: MediaItem?,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(zone),
): MediaListItem.Header? {
    if (after == null) return null
    val afterKey = sectionKeyOf(after, zone, today)
    if (before == null) return MediaListItem.Header(afterKey)
    return if (sectionKeyOf(before, zone, today) == afterKey) null else MediaListItem.Header(afterKey)
}
