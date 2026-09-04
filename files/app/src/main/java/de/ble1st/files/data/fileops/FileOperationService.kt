package de.ble1st.files.data.fileops

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import de.ble1st.files.R
import de.ble1st.files.data.fs.StorageRoots
import de.ble1st.files.data.trash.TrashEntry
import de.ble1st.files.data.trash.TrashStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.ArrayDeque

/**
 * Foreground-Service für Kopier-/Verschiebe-/Lösch-/Zip-Jobs (FileOperations/ZipOperations).
 * Läuft im Hauptprozess (anders als Wardens ausgelagerter :barbican-Prozess — hier gibt es keine
 * instabile native Engine, deren Absturz den Hauptprozess mitreißen könnte, s. build.gradle.kts-
 * Kommentar). Als Foreground-Service, damit Android einen laufenden Datei-Transfer nicht killt,
 * sobald die App in den Hintergrund wechselt.
 *
 * Arbeitet die Aufträge **der Reihe nach aus einer Warteschlange** ab (seit 2026-09-03; vorher
 * wies ein `busy`-Flag jeden zweiten Auftrag stillschweigend ab, weshalb die UI alle
 * Datei-Aktionen sperren musste, solange irgendetwas lief — bei einem großen Kopiervorgang über
 * mehrere Minuten war die App damit faktisch nur noch zum Zusehen zu gebrauchen).
 *
 * Bewusst **streng nacheinander** und nicht parallel: mehrere gleichzeitige Kopiervorgänge auf
 * demselben Datenträger sind zusammen nicht schneller als nacheinander, machen aber jede
 * Fortschrittsanzeige mehrdeutig und können sich gegenseitig ins Gehege kommen (ein Job löscht
 * einen Ordner, den ein anderer gerade schreibt).
 *
 * Der Zugriff auf [pending] ist synchronisiert, weil er aus zwei Richtungen kommt: [onStartCommand]
 * läuft auf dem Hauptthread, [drain] auf [Dispatchers.IO].
 */
class FileOperationService : Service() {

    private val lock = Any()

    /** Wartende Aufträge, ohne den gerade laufenden. */
    private val pending = ArrayDeque<OperationRequest>()

    /** Ob gerade eine [drain]-Schleife läuft. Verhindert, dass ein zweiter Auftrag eine zweite
     * Schleife startet — er wird stattdessen von der bestehenden mit abgearbeitet. */
    private var draining = false

    /** startId des zuletzt zugestellten Auftrags; nur damit ein [stopSelf] nicht einen Auftrag
     * mit abräumt, der zwischen "Warteschlange leer" und dem Stopp noch hereinkam. */
    private var latestStartId = 0

    /** Die zuletzt gebaute Notification — [onStartCommand] muss auf jedes
     * `startForegroundService` mit einem `startForeground` antworten, soll dabei aber nicht die
     * Anzeige des gerade laufenden Auftrags durch die des neu eingereihten ersetzen. */
    @Volatile
    private var lastNotification: Notification? = null

    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + scopeJob)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_file_operations),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            FileOperationQueue.requestCancel()
            return START_NOT_STICKY
        }
        val request = intent?.toOperationRequest()
        if (request == null) {
            // Kaputter/leerer Intent — nichts einzureihen. stopSelf(startId) nur, wenn seitdem
            // kein echter Auftrag hereinkam (das prüft Android anhand der startId selbst).
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val startDraining: Boolean
        synchronized(lock) {
            latestStartId = startId
            pending.addLast(request)
            FileOperationQueue.publishPending(pending.size)
            startDraining = !draining
            if (startDraining) {
                draining = true
                FileOperationQueue.cancelRequested.set(false)
            }
            // Pflicht-Antwort auf startForegroundService, s. lastNotification-Kommentar oben.
            startForeground(NOTIFICATION_ID, lastNotification ?: buildNotification(request.type, null, pending.size - 1))
        }
        if (startDraining) scope.launch { drain() }
        return START_NOT_STICKY
    }

    /**
     * Arbeitet die Warteschlange ab, bis sie leer ist oder abgebrochen wurde, und beendet danach
     * den Service.
     *
     * Läuft immer nur einmal gleichzeitig (s. [draining]). Ein währenddessen eingereihter Auftrag
     * wird von der laufenden Schleife mitgenommen, ohne dass es dafür ein Wecksignal bräuchte —
     * die Schleife schaut nach jedem Auftrag ohnehin nach.
     */
    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                val head = pending.pollFirst()
                if (head == null) draining = false
                FileOperationQueue.publishPending(pending.size)
                head
            } ?: break

            runRequest(next)

            if (FileOperationQueue.cancelRequested.get()) {
                // Abbruch gilt für die gesamte Warteschlange (s. FileOperationQueue.requestCancel).
                val dropped = synchronized(lock) {
                    val count = pending.size
                    pending.clear()
                    draining = false
                    FileOperationQueue.publishPending(0)
                    count
                }
                FileOperationQueue.publishResult(OperationState.Cancelled(dropped))
                break
            }
        }
        FileOperationQueue.publish(OperationState.Idle)
        // Stopp-Entscheidung und Stopp unter demselben Lock wie das Einreihen: sonst könnte
        // zwischen "Warteschlange ist leer" und stopForeground ein neuer Auftrag anlaufen, dem
        // hier die Notification unter den Füßen weggezogen würde.
        synchronized(lock) {
            if (!draining && pending.isEmpty()) {
                lastNotification = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(latestStartId)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scopeJob.cancel()
    }

    private fun runRequest(request: OperationRequest) {
        val sources = request.sourcePaths.map { File(it) }
        val destination = File(request.destinationDirPath)
        val isCancelled = { FileOperationQueue.cancelRequested.get() }
        val onProgress = { name: String, processed: Int, total: Int ->
            val progress = OperationProgress(request.type, name, processed, total)
            FileOperationQueue.publish(OperationState.Running(progress))
            updateNotification(request.type, progress)
        }
        val outcomes = runCatching {
            when (request.type) {
                OperationType.COPY ->
                    FileOperations.copy(sources, destination, request.conflictPolicy, isCancelled, onProgress)
                OperationType.MOVE ->
                    FileOperations.move(sources, destination, request.conflictPolicy, isCancelled, onProgress)
                OperationType.DELETE -> FileOperations.delete(sources, isCancelled, onProgress)
                OperationType.TRASH -> {
                    val trashOutcomes = FileOperations.moveToTrash(
                        targets = sources,
                        trashDirFor = { file -> StorageRoots.trashDirFor(applicationContext, file) },
                        isCancelled = isCancelled,
                        onProgress = onProgress,
                    )
                    // TrashStore braucht einen Context (SharedPreferences) — deshalb hier statt in
                    // FileOperations selbst persistiert, s. TrashMoveOutcome-Klassendoc. Nur
                    // erfolgreiche Verschiebungen bekommen einen Papierkorb-Eintrag; ein
                    // Fehlschlag (kein passendes Volume, IO-Fehler) landet trotzdem korrekt als
                    // fehlgeschlagenes OperationOutcome unten, ohne einen Papierkorb-Eintrag ohne
                    // echte Datei dahinter zu hinterlassen.
                    val now = System.currentTimeMillis()
                    trashOutcomes.filter { it.succeeded }.forEach { outcome ->
                        TrashStore.add(
                            applicationContext,
                            TrashEntry(
                                id = java.util.UUID.randomUUID().toString(),
                                trashPath = outcome.trashPath!!,
                                originalPath = outcome.originalPath,
                                originalParentPath = File(outcome.originalPath).parent ?: outcome.originalPath,
                                deletedAtMillis = now,
                                isDirectory = outcome.isDirectory,
                            ),
                        )
                    }
                    trashOutcomes.map { outcome -> OperationOutcome(outcome.originalPath, outcome.error) }
                }
                OperationType.COMPRESS -> {
                    val zipFile = File(destination, request.archiveName ?: "Archiv.zip")
                    ZipOperations.compress(sources, zipFile, isCancelled, onProgress)
                }
                OperationType.EXTRACT -> ZipOperations.extract(sources.first(), destination, isCancelled, onProgress)
            }
        }
        val finalState = outcomes.fold(
            onSuccess = { list ->
                OperationState.Completed(
                    type = request.type,
                    successCount = list.count { it.succeeded },
                    failedCount = list.count { it.error != null },
                    skippedCount = list.count { it.skipped },
                )
            },
            onFailure = { error -> OperationState.Failed(request.type, error.message ?: "Unbekannter Fehler") },
        )
        FileOperationQueue.publishResult(finalState)
    }

    private fun updateNotification(type: OperationType, progress: OperationProgress) {
        val manager = getSystemService(NotificationManager::class.java)
        val queued = synchronized(lock) { pending.size }
        manager.notify(NOTIFICATION_ID, buildNotification(type, progress, queued))
    }

    private fun buildNotification(
        type: OperationType,
        progress: OperationProgress?,
        queuedCount: Int,
    ): Notification {
        val cancelIntent = Intent(this, FileOperationService::class.java).setAction(ACTION_CANCEL)
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when (type) {
            OperationType.COPY -> getString(R.string.notification_title_copy)
            OperationType.MOVE -> getString(R.string.notification_title_move)
            OperationType.DELETE -> getString(R.string.notification_title_delete)
            OperationType.TRASH -> getString(R.string.notification_title_trash)
            OperationType.COMPRESS -> getString(R.string.notification_title_compress)
            OperationType.EXTRACT -> getString(R.string.notification_title_extract)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(progress?.currentItemName ?: "")
            .apply {
                if (progress != null && progress.totalCount > 0) {
                    setProgress(progress.totalCount, progress.processedCount, false)
                }
                // Nur anzeigen, wenn tatsächlich etwas wartet — sonst stünde bei jedem einzelnen
                // Auftrag eine "0 warten"-Zeile in der Notification.
                if (queuedCount > 0) {
                    setSubText(resources.getString(R.string.notification_queue_pending, queuedCount))
                }
            }
            .setOngoing(true)
            .addAction(0, getString(R.string.action_cancel), cancelPendingIntent)
            .build()
            .also { lastNotification = it }
    }

    companion object {
        private const val CHANNEL_ID = "file_operations"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_CANCEL = "de.ble1st.files.action.CANCEL_OPERATION"

        private const val EXTRA_TYPE = "type"
        private const val EXTRA_SOURCES = "sources"
        private const val EXTRA_DESTINATION = "destination"
        private const val EXTRA_ARCHIVE_NAME = "archive_name"
        private const val EXTRA_CONFLICT_POLICY = "conflict_policy"

        fun enqueue(context: Context, request: OperationRequest) {
            val intent = Intent(context, FileOperationService::class.java)
                .putExtra(EXTRA_TYPE, request.type.name)
                .putExtra(EXTRA_SOURCES, request.sourcePaths.toTypedArray())
                .putExtra(EXTRA_DESTINATION, request.destinationDirPath)
                .putExtra(EXTRA_ARCHIVE_NAME, request.archiveName)
                .putExtra(EXTRA_CONFLICT_POLICY, request.conflictPolicy.name)
            context.startForegroundService(intent)
        }

        private fun Intent.toOperationRequest(): OperationRequest? {
            val type = getStringExtra(EXTRA_TYPE)?.let { runCatching { OperationType.valueOf(it) }.getOrNull() }
                ?: return null
            val sources = getStringArrayExtra(EXTRA_SOURCES)?.toList() ?: return null
            val destination = getStringExtra(EXTRA_DESTINATION) ?: return null
            val conflictPolicy = getStringExtra(EXTRA_CONFLICT_POLICY)
                ?.let { runCatching { ConflictPolicy.valueOf(it) }.getOrNull() }
                ?: ConflictPolicy.KEEP_BOTH
            return OperationRequest(type, sources, destination, getStringExtra(EXTRA_ARCHIVE_NAME), conflictPolicy)
        }
    }
}
