package de.ble1st.gallery.data.sync

import android.content.Context
import de.ble1st.gallery.data.media.MediaItem
import de.ble1st.gallery.data.webdav.WebDavAccount
import de.ble1st.gallery.data.webdav.WebDavClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class SyncProgress(
    val total: Int = 0,
    val uploaded: Int = 0,
    val failed: Int = 0,
    val currentName: String? = null,
    val running: Boolean = false,
    val done: Boolean = false,
)

/**
 * Ein-Wege-Sicherung: lädt jedes MediaStore-Element, das laut [CloudSyncState] noch nicht
 * hochgeladen wurde, in einen festen Zielordner auf dem konfigurierten WebDAV-Server hoch — kein
 * Download/keine Konfliktauflösung in Gegenrichtung (echtes bidirektionales Sync wäre ein
 * eigenständiger, deutlich größerer Ausbauschritt mit Versions-/Konflikterkennung, s. README). Ein
 * einziger globaler [progress]-Zustand statt pro-Aufruf-Rückgabewert, weil nur ein Sync-Lauf
 * gleichzeitig sinnvoll ist (ausgelöst von genau einem Screen).
 */
object CloudSyncManager {
    private const val REMOTE_FOLDER = "ConneXias Galerie Backup"

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress

    // Eigener, prozessweiter SupervisorJob-Scope statt eines von CloudSyncScreen übergebenen
    // rememberCoroutineScope() — der wird beim Verlassen des Screens (Zurück-Navigation,
    // Konfigurationsänderung) gecancelt, ein laufender Sync brach dadurch schon bei einem simplen
    // "Zurück" ab, nicht erst bei echtem Process-Tod. Volle Process-Tod-Sicherheit bräuchte
    // WorkManager (eigenständiger, deutlich größerer Ausbauschritt, s. README) — dieser Scope löst
    // den häufigeren Fall (Navigation weg vom Screen, Rotation), solange der Prozess am Leben
    // bleibt.
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Startet den Sync losgelöst vom aufrufenden Compose-Scope — s. [managerScope]-Doc. Der Aufrufer
     * beobachtet den Fortschritt über [progress] statt auf eine Coroutine zu warten. */
    fun startSync(context: Context, account: WebDavAccount, items: List<MediaItem>) {
        if (_progress.value.running) return
        managerScope.launch { sync(context, account, items) }
    }

    private suspend fun sync(context: Context, account: WebDavAccount, items: List<MediaItem>) {
        val alreadySynced = CloudSyncState.syncedIds(context)
        val pending = items.filter { it.id !in alreadySynced }
        _progress.value = SyncProgress(total = pending.size, running = true)

        // Fehlschlag hier ist kein Abbruchgrund — der Ordner existiert nach dem ersten Sync-Lauf
        // bereits, ein zweites MKCOL meldet dann typischerweise 405/409 statt Erfolg.
        WebDavClient.mkdir(account, REMOTE_FOLDER)

        var uploaded = 0
        var failed = 0
        for (item in pending) {
            _progress.value = _progress.value.copy(currentName = item.displayName)
            val tempFile = withContext(Dispatchers.IO) {
                val temp = File(context.cacheDir, "cloud_sync_${UUID.randomUUID()}")
                context.contentResolver.openInputStream(item.uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                temp
            }
            val mimeType = context.contentResolver.getType(item.uri) ?: "application/octet-stream"
            // Der bloße Dateiname als Remote-Pfad kollidiert leicht — zwei unabhängige "IMG_0001.jpg"
            // (unterschiedliches Album/Datum, selber Name) würden sich auf dem Server sonst
            // gegenseitig überschreiben. Die MediaStore-ID als Präfix macht den Pfad eindeutig,
            // ohne den ursprünglichen Namen für den Menschen am anderen Ende unlesbar zu machen.
            val remotePath = "$REMOTE_FOLDER/${item.id}_${item.displayName}"
            val result = WebDavClient.upload(account, remotePath, tempFile, mimeType)
            withContext(Dispatchers.IO) { tempFile.delete() }

            result.onSuccess {
                CloudSyncState.markSynced(context, item.id)
                uploaded += 1
            }
            result.onFailure { failed += 1 }
            _progress.value = _progress.value.copy(uploaded = uploaded, failed = failed)
        }
        _progress.value = _progress.value.copy(running = false, done = true, currentName = null)
    }

    fun resetProgress() {
        _progress.value = SyncProgress()
    }
}
