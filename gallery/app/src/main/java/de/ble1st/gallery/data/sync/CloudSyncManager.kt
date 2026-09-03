package de.ble1st.gallery.data.sync

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class SyncProgress(
    val total: Int = 0,
    val uploaded: Int = 0,
    val failed: Int = 0,
    val currentName: String? = null,
    val running: Boolean = false,
    val done: Boolean = false,
    /** Gesetzt, wenn der Auftrag ohne Upload endete — s. [CloudSyncWorker.SyncFailure]. */
    val failure: CloudSyncWorker.SyncFailure? = null,
)

/**
 * Ein-Wege-Sicherung: lädt jedes MediaStore-Element, das laut [CloudSyncState] noch nicht
 * hochgeladen wurde, in einen festen Zielordner auf dem konfigurierten WebDAV-Server hoch — kein
 * Download/keine Konfliktauflösung in Gegenrichtung (echtes bidirektionales Sync wäre ein
 * eigenständiger, deutlich größerer Ausbauschritt mit Versions-/Konflikterkennung, s. README).
 *
 * Diese Klasse ist seit 2026-09-03 nur noch die Fassade davor; die Arbeit macht
 * [CloudSyncWorker] (Begründung für den Umstieg auf WorkManager s. dort). Der Zustand wird
 * deshalb auch nicht mehr hier gehalten, sondern aus WorkManager gelesen — ein eigener
 * `StateFlow` daneben wäre eine zweite Wahrheit, die nach einem Prozess-Tod als Erste falsch
 * würde.
 *
 * [ExistingWorkPolicy.KEEP] statt REPLACE: Ein zweites Tippen auf "Jetzt sichern" soll den
 * laufenden Upload nicht von vorn beginnen lassen.
 */
object CloudSyncManager {

    private const val WORK_NAME = "cloud_sync"

    fun startSync(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CloudSyncWorker>().build(),
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * Fortschritt des aktuellen bzw. zuletzt gelaufenen Auftrags. Liefert auch nach einem
     * Neustart der App noch das Ergebnis des letzten Laufs — anders als der frühere prozessweite
     * `StateFlow`, der dabei auf den Anfangswert zurückfiel.
     */
    fun progress(context: Context): Flow<SyncProgress> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(WORK_NAME)
            .map { infos -> infos.firstOrNull()?.let(::toProgress) ?: SyncProgress() }

    private fun toProgress(info: WorkInfo): SyncProgress {
        // Bei einem beendeten Auftrag steht der Endstand in outputData, bei einem laufenden in
        // progress — outputData ist bei laufenden Aufträgen leer und umgekehrt.
        val data = if (info.state.isFinished) info.outputData else info.progress
        val failure = data.getString(CloudSyncWorker.KEY_FAILURE)
            ?.let { name -> runCatching { CloudSyncWorker.SyncFailure.valueOf(name) }.getOrNull() }
        return SyncProgress(
            total = data.getInt(CloudSyncWorker.KEY_TOTAL, 0),
            uploaded = data.getInt(CloudSyncWorker.KEY_UPLOADED, 0),
            failed = data.getInt(CloudSyncWorker.KEY_FAILED, 0),
            currentName = data.getString(CloudSyncWorker.KEY_CURRENT_NAME),
            running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED,
            done = info.state == WorkInfo.State.SUCCEEDED,
            failure = failure,
        )
    }

    /** Löscht den Auftragsverlauf, damit der Bildschirm nach "Konto entfernen" nicht weiter das
     * Ergebnis eines Laufs anzeigt, der zu einem nicht mehr existierenden Konto gehört. */
    fun resetProgress(context: Context) {
        WorkManager.getInstance(context).pruneWork()
    }
}
