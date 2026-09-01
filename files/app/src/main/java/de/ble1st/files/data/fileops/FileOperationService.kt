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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground-Service für Kopier-/Verschiebe-/Lösch-/Zip-Jobs (FileOperations/ZipOperations).
 * Läuft im Hauptprozess (anders als Wardens ausgelagerter :barbican-Prozess — hier gibt es keine
 * instabile native Engine, deren Absturz den Hauptprozess mitreißen könnte, s. build.gradle.kts-
 * Kommentar). Als Foreground-Service, damit Android einen laufenden Datei-Transfer nicht killt,
 * sobald die App in den Hintergrund wechselt.
 *
 * Nimmt genau einen Job pro Start entgegen ([busy]-Flag weist einen zweiten Start ab, statt ihn zu
 * queuen) — die UI (FileBrowserViewModel) verhindert das ohnehin, indem sie neue Aktionen
 * deaktiviert, solange [FileOperationQueue.state] `Running` ist. Eine echte Warteschlange für
 * mehrere gleichzeitig angestoßene Jobs ist ein Ausbauschritt, kein Tag-1-Bedarf.
 */
class FileOperationService : Service() {

    private val busy = AtomicBoolean(false)
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + scopeJob)
    private var currentJob: Job? = null

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
        if (request == null || !busy.compareAndSet(false, true)) {
            if (!busy.get()) stopSelf(startId)
            return START_NOT_STICKY
        }
        FileOperationQueue.cancelRequested.set(false)
        startForeground(NOTIFICATION_ID, buildNotification(request.type, null))
        currentJob = scope.launch {
            runRequest(request)
            busy.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
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
        FileOperationQueue.publish(finalState)
    }

    private fun updateNotification(type: OperationType, progress: OperationProgress) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(type, progress))
    }

    private fun buildNotification(type: OperationType, progress: OperationProgress?): Notification {
        val cancelIntent = Intent(this, FileOperationService::class.java).setAction(ACTION_CANCEL)
        val cancelPendingIntent = PendingIntent.getService(
            this, 0, cancelIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = when (type) {
            OperationType.COPY -> getString(R.string.notification_title_copy)
            OperationType.MOVE -> getString(R.string.notification_title_move)
            OperationType.DELETE -> getString(R.string.notification_title_delete)
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
            }
            .setOngoing(true)
            .addAction(0, getString(R.string.action_cancel), cancelPendingIntent)
            .build()
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
