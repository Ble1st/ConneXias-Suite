package de.ble1st.gallery.nav

import android.content.ContentUris
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

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

    companion object {
        /**
         * Wertet den Start-Intent aus. Lag bis 2026-09-04 als privater Block in
         * [de.ble1st.gallery.MainActivity] — dort war die Authority-Prüfung aus analyse.md 4-08
         * nur über einen echten Activity-Start prüfbar, also praktisch gar nicht. Als reine
         * Funktion auf `Intent` ist sie einzeln testbar (s. `ExternalIntentInstrumentedTest`),
         * ohne dass sich am Verhalten etwas ändert.
         *
         * [resolveMimeType] ist der Rückfall auf `ContentResolver.getType(uri)`, wenn der Intent
         * selbst keinen Typ mitbringt — als Parameter statt als direkter Zugriff, damit die
         * Funktion keinen `Context` braucht.
         */
        fun from(intent: Intent?, resolveMimeType: (Uri) -> String?): ExternalIntent? =
            when (intent?.action) {
                Intent.ACTION_VIEW -> viewItemFromUri(intent, intent.data, resolveMimeType)
                Intent.ACTION_PICK, Intent.ACTION_GET_CONTENT -> Pick(intent.type)
                Intent.ACTION_SEND -> sendUri(intent)?.let { Send(listOf(it), intent.type) }
                Intent.ACTION_SEND_MULTIPLE -> sendMultipleUris(intent)?.let { Send(it, intent.type) }
                else -> null
            }

        /** "Teilen mit ConneXias Galerie" — dasselbe EXTRA_STREAM-Muster wie ConneXias Files'
         * `data/share/IncomingShare.kt`, hier unabhängig dupliziert (kein Code wird zwischen den
         * Apps geteilt, s. Plan-Klassendoc). */
        private fun sendUri(intent: Intent): Uri? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

        private fun sendMultipleUris(intent: Intent): List<Uri>? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }

        private fun viewItemFromUri(
            intent: Intent,
            uri: Uri?,
            resolveMimeType: (Uri) -> String?,
        ): ViewItem? {
            if (uri == null) return null
            // analyse.md (2. Durchgang, Hoch): vorher akzeptierte diese Funktion JEDE Uri, deren
            // letztes Pfadsegment zufällig eine Zahl war — ContentUris.parseId kennt keine
            // Authority-Prüfung. Ein `content://com.other.provider/item/42` hätte
            // MediaStore-Element 42 geöffnet (falsches Bild, keine erkennbare Ablehnung) statt
            // regulär zu Albums zu gehen wie bei einer wirklich unbrauchbaren Uri.
            // `MediaStore.AUTHORITY` ("media") ist die einzige Authority, unter der `parseId` hier
            // je einen sinnvollen Treffer liefern kann.
            if (uri.authority != MediaStore.AUTHORITY) return null
            // ContentUris.parseId schlägt für eine Uri fehl, die nicht mit einer numerischen ID
            // endet (z. B. ein fremder DocumentsProvider-Pfad) — in dem Fall gibt es hier keinen
            // sinnvollen MediaStore-Eintrag zu zeigen, also regulär zu Albums statt abzustürzen.
            val id = runCatching { ContentUris.parseId(uri) }.getOrNull() ?: return null
            val mimeType = intent.type ?: runCatching { resolveMimeType(uri) }.getOrNull()
            return ViewItem(id, isVideo = mimeType?.startsWith("video/") == true)
        }
    }
}
