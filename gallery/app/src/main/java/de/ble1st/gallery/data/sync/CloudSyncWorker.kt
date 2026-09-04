package de.ble1st.gallery.data.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import de.ble1st.gallery.R
import de.ble1st.gallery.data.media.MediaItem
import de.ble1st.gallery.data.media.MediaStoreRepository
import de.ble1st.gallery.data.webdav.WebDavAccount
import de.ble1st.gallery.data.webdav.WebDavAccountStore
import de.ble1st.gallery.data.webdav.WebDavClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Die eigentliche Ein-Wege-Sicherung, seit 2026-09-03 als WorkManager-Auftrag statt als Coroutine
 * in einem app-eigenen Scope.
 *
 * Der Grund ist die Lebensdauer: Der alte prozessweite Scope überlebte zwar das Verlassen des
 * Sync-Bildschirms, aber nicht das Beenden der App durch den Nutzer oder das Wegräumen des
 * Prozesses durch das System — mitten in einem Upload der gesamten Mediathek ist genau das der
 * wahrscheinlichste Fall, und der Nutzer erfuhr davon nichts. WorkManager persistiert den Auftrag
 * und nimmt ihn nach einem Prozess-Tod von selbst wieder auf.
 *
 * **Foreground-Worker** ([getForegroundInfo]) und nicht der gewöhnliche Hintergrundpfad: ein
 * normaler Worker wird nach zehn Minuten gestoppt, was für eine mehrere Gigabyte große Mediathek
 * garantiert zu kurz ist. Der Preis ist die sichtbare Benachrichtigung — für einen minutenlangen
 * Upload ist die ohnehin die ehrlichere Anzeige als gar nichts.
 *
 * **Zugangsdaten kommen aus [WebDavAccountStore], nicht aus den Worker-Eingabedaten.** Die
 * `Data`-Eingabe eines Auftrags liegt in der WorkManager-Datenbank im Klartext; ein Passwort
 * gehört dort nicht hinein. Daraus folgt für die Bedienung: gesichert werden kann erst, wenn das
 * Konto einmal erfolgreich getestet und damit gespeichert wurde (s. `CloudSyncScreen`).
 *
 * Die zu sichernden Elemente fragt der Worker selbst ab, statt sie mitgegeben zu bekommen: Er
 * startet unter Umständen erst deutlich später, und dann zählt der aktuelle Bestand.
 */
class CloudSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    override suspend fun doWork(): Result {
        val context = applicationContext
        val account = WebDavAccountStore.get(context) ?: return Result.failure(
            resultData(SyncFailure.NO_ACCOUNT),
        )

        setForeground(foregroundInfo(0, 0))

        val alreadySynced = CloudSyncState.syncedIds(context)
        val pending = withContext(Dispatchers.IO) {
            MediaStoreRepository.loadMedia(context).filter { it.id !in alreadySynced }
        }
        setProgress(progressData(total = pending.size, uploaded = 0, failed = 0, currentName = null))

        // Fehlschlag hier ist kein Abbruchgrund — der Ordner existiert nach dem ersten Sync-Lauf
        // bereits, ein zweites MKCOL meldet dann typischerweise 405/409 statt Erfolg.
        WebDavClient.mkdir(account, REMOTE_FOLDER)

        var uploaded = 0
        var failed = 0
        for (item in pending) {
            // Ein abgebrochener Auftrag (Nutzer tippt "Abbrechen", System räumt auf) soll nicht
            // noch die restlichen Dateien hochladen — die Schleife ist die einzige Stelle, an der
            // das zwischen zwei Uploads überhaupt geprüft werden kann.
            if (isStopped) return Result.failure(resultData(SyncFailure.CANCELLED))

            setProgress(progressData(pending.size, uploaded, failed, item.displayName))
            setForeground(foregroundInfo(uploaded + failed, pending.size))

            val result = uploadOne(context, account, item)
            if (result) uploaded += 1 else failed += 1
            setProgress(progressData(pending.size, uploaded, failed, item.displayName))
        }

        setProgress(progressData(pending.size, uploaded, failed, null))
        // Ein Lauf mit einigen Erfolgen und einigen Fehlschlägen ist "durchgelaufen" — schon
        // hochgeladene Elemente sind in CloudSyncState vermerkt und würden beim Wiederholen
        // übersprungen, ein persistenter Fehlschlag (unlesbare Datei, Server 4xx) verhält sich
        // beim Wiederholen meist genauso. Ein Lauf mit **null Erfolgen aber >0 Fehlern** ist
        // dagegen ein starkes Signal für einen transienten Grund (Netz weg, Server kurzfristig
        // nicht erreichbar) — Result.retry() lässt WorkManager den Auftrag nach Backoff erneut
        // anstoßen, und da Erfolge vermerkt sind, ist ein Retry sicher und nicht verschwenderisch.
        return if (uploaded == 0 && failed > 0) {
            Result.retry()
        } else {
            Result.success(resultDataDone(pending.size, uploaded, failed))
        }
    }

    /** Lädt genau ein Element hoch. `true` = erfolgreich (und in [CloudSyncState] vermerkt). */
    private suspend fun uploadOne(context: Context, account: WebDavAccount, item: MediaItem): Boolean {
        // analyse.md (2. Durchgang, Hoch): `openInputStream` kann `null` liefern (Element
        // zwischenzeitlich gelöscht, Berechtigung entzogen) — vorher wurde der `?.use`-Block
        // dann einfach übersprungen, `tempFile` blieb ein reines File-Objekt ohne Datei auf der
        // Platte. `WebDavClient.upload` konnte darauf trotzdem `asRequestBody` aufrufen
        // (Content-Length 0 für eine nicht existierende Datei), der Server antwortete auf ein
        // leeres PUT oft trotzdem mit 2xx — der Upload galt dann als erfolgreich abgeschlossen
        // (`markSynced`), obwohl der Server nichts oder nur eine leere Datei erhalten hat. Ein
        // nicht lesbares Quellelement gilt deshalb als Fehlschlag, bevor überhaupt hochgeladen
        // wird.
        val tempFile = withContext(Dispatchers.IO) {
            val temp = File(context.cacheDir, "cloud_sync_${UUID.randomUUID()}")
            val ok = context.contentResolver.openInputStream(item.uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
                temp.length() > 0
            } ?: false
            if (ok) temp else { temp.delete(); null }
        } ?: return false

        val mimeType = context.contentResolver.getType(item.uri) ?: "application/octet-stream"
        // Der bloße Dateiname als Remote-Pfad kollidiert leicht — zwei unabhängige "IMG_0001.jpg"
        // (unterschiedliches Album/Datum, selber Name) würden sich auf dem Server sonst
        // gegenseitig überschreiben. Die MediaStore-ID als Präfix macht den Pfad eindeutig,
        // ohne den ursprünglichen Namen für den Menschen am anderen Ende unlesbar zu machen.
        // analyse.md (2. Durchgang, Hoch): `displayName` kann ein "/" enthalten (MediaStore
        // erzwingt das nicht) — `WebDavClient.urlFor` splittet den Remote-Pfad an jedem "/" in
        // eigene Segmente, ein Name wie "a/b.jpg" hätte den Upload also in einen zusätzlichen,
        // ungewollten Unterordner "a" gelenkt. "/" wird hier durch "_" ersetzt.
        val safeDisplayName = item.displayName.replace('/', '_')
        val remotePath = "$REMOTE_FOLDER/${item.id}_$safeDisplayName"
        val result = WebDavClient.upload(account, remotePath, tempFile, mimeType)
        withContext(Dispatchers.IO) { tempFile.delete() }

        return if (result.isSuccess) {
            CloudSyncState.markSynced(context, item.id)
            true
        } else {
            false
        }
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.cloud_sync_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.cloud_sync_notification_title))
            .setOngoing(true)
            .apply { if (total > 0) setProgress(total, done, false) }
            .addAction(
                0,
                context.getString(R.string.action_cancel),
                androidx.work.WorkManager.getInstance(context).createCancelPendingIntent(id),
            )
            .build()

        // Ab API 34 muss ein Foreground-Worker seinen Diensttyp nennen; DATA_SYNC ist der
        // passende (Upload eigener Daten auf einen Server). Auf älteren Versionen gibt es den
        // Parameter nicht.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun progressData(total: Int, uploaded: Int, failed: Int, currentName: String?): Data =
        Data.Builder()
            .putInt(KEY_TOTAL, total)
            .putInt(KEY_UPLOADED, uploaded)
            .putInt(KEY_FAILED, failed)
            .putString(KEY_CURRENT_NAME, currentName)
            .build()

    private fun resultDataDone(total: Int, uploaded: Int, failed: Int): Data =
        progressData(total, uploaded, failed, null)

    private fun resultData(failure: SyncFailure): Data =
        Data.Builder().putString(KEY_FAILURE, failure.name).build()

    /** Warum ein Auftrag ohne Upload endete — die UI unterscheidet "kein Konto gespeichert" von
     * "vom Nutzer abgebrochen", weil nur der erste Fall eine Handlungsanweisung braucht. */
    enum class SyncFailure { NO_ACCOUNT, CANCELLED }

    companion object {
        const val REMOTE_FOLDER = "ConneXias Galerie Backup"

        const val KEY_TOTAL = "total"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_FAILED = "failed"
        const val KEY_CURRENT_NAME = "currentName"
        const val KEY_FAILURE = "failure"

        private const val CHANNEL_ID = "cloud_sync"
        private const val NOTIFICATION_ID = 4711
    }
}
