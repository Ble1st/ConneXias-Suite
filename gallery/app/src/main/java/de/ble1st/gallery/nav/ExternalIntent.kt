package de.ble1st.gallery.nav

import android.net.Uri

/**
 * Erkannter Startgrund, wenn die App nicht regulär über den Launcher, sondern von einer fremden
 * App aufgerufen wurde (s. MainActivity.resolveExternalIntent) — vorher hatte die App überhaupt
 * keine Intent-Filter außer MAIN/LAUNCHER (analyse.md Abschnitt 3/5): weder "Öffnen mit" von
 * ConneXias Kamera noch eine Bild-/Video-Auswahl für eine fremde App waren möglich.
 */
sealed interface ExternalIntent {
    /** ACTION_VIEW auf ein einzelnes MediaStore-Element (`content://media/.../<id>`) — z. B.
     * ConneXias Kameras "In Galerie öffnen". [isVideo] steuert, ob der Bild- oder Video-Betrachter
     * angesteuert wird; die Sibling-Liste ist bewusst das virtuelle "Alle"-Album, nicht ein
     * einzelnes Bucket, weil der Aufrufer keinen Bucket-Kontext mitgibt. */
    data class ViewItem(val itemId: Long, val isVideo: Boolean) : ExternalIntent

    /** ACTION_PICK/ACTION_GET_CONTENT — ein Tap auf ein Element liefert dessen Uri per
     * `setResult()` an den Aufrufer zurück, statt den regulären Betrachter zu öffnen.
     * [mimeTypeFilter] ist der vom Aufrufer gewünschte Typ (z. B. "image/*"); `null` oder "*/*"
     * akzeptiert beides. */
    data class Pick(val mimeTypeFilter: String?) : ExternalIntent

    /** ACTION_SEND/ACTION_SEND_MULTIPLE — "Teilen mit ConneXias Galerie" aus einer Fremd-App
     * (analyse.md Abschnitt 5 — "Gallery ohne ACTION_SEND"). [mimeType] ist `Intent.type` des
     * empfangenen Intents, als Fallback für Uris, die selbst keinen (oder nur einen
     * unspezifischen Wildcard-Typ) über den ContentResolver preisgeben — s.
     * [de.ble1st.gallery.data.media.SharedMediaImporter]. */
    data class Send(val uris: List<Uri>, val mimeType: String?) : ExternalIntent
}
