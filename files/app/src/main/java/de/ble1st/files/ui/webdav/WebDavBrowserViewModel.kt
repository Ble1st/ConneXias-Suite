package de.ble1st.files.ui.webdav

import android.app.Application
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.ble1st.files.data.fileops.FileOperations
import de.ble1st.files.data.fs.StorageRoots
import de.ble1st.files.data.webdav.WebDavAccount
import de.ble1st.files.data.webdav.WebDavClient
import de.ble1st.files.data.webdav.WebDavEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class WebDavUiState(
    val account: WebDavAccount,
    val path: String,
    val entries: List<WebDavEntry> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val statusMessage: String? = null,
    val pendingDialog: WebDavDialog? = null,
)

sealed interface WebDavDialog {
    data object NewFolder : WebDavDialog
    data class Rename(val entry: WebDavEntry) : WebDavDialog
    data class ConfirmDelete(val entry: WebDavEntry) : WebDavDialog
}

/**
 * Ein Ordner eines konfigurierten WebDAV-Servers. Analog zu [de.ble1st.files.ui.browser.FileBrowserViewModel],
 * aber ohne dessen Zwischenablage-/Mehrfachauswahl-Funktionen — das v1-Scope für Netzwerkspeicher
 * beschränkt sich bewusst auf Durchsuchen/Herunterladen/Hochladen/Umbenennen/Löschen/Neuer-Ordner.
 */
class WebDavBrowserViewModel(
    application: Application,
    account: WebDavAccount,
    path: String,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(WebDavUiState(account = account, path = path))
    val uiState: StateFlow<WebDavUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        val account = _uiState.value.account
        val path = _uiState.value.path
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            WebDavClient.list(account, path).fold(
                onSuccess = { entries ->
                    val sorted = entries.sortedWith(compareByDescending<WebDavEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
                    _uiState.update { it.copy(entries = sorted, isLoading = false) }
                },
                onFailure = { error ->
                    _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "Verbindung fehlgeschlagen") }
                },
            )
        }
    }

    fun showDialog(dialog: WebDavDialog?) {
        _uiState.update { it.copy(pendingDialog = dialog) }
    }

    fun consumeStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun createFolder(name: String) {
        showDialog(null)
        if (name.isBlank()) return
        val state = _uiState.value
        viewModelScope.launch {
            WebDavClient.mkdir(state.account, childPath(state.path, name)).fold(
                onSuccess = { refresh() },
                onFailure = { error -> setError(error) },
            )
        }
    }

    fun rename(entry: WebDavEntry, newName: String) {
        showDialog(null)
        if (newName.isBlank() || newName == entry.name) return
        val state = _uiState.value
        val destination = childPath(parentOf(entry.path), newName)
        viewModelScope.launch {
            WebDavClient.move(state.account, entry.path, destination).fold(
                onSuccess = { refresh() },
                onFailure = { error -> setError(error) },
            )
        }
    }

    fun delete(entry: WebDavEntry) {
        showDialog(null)
        val state = _uiState.value
        viewModelScope.launch {
            WebDavClient.delete(state.account, entry.path).fold(
                onSuccess = { refresh() },
                onFailure = { error -> setError(error) },
            )
        }
    }

    /** Lädt eine Datei aus dem SAF-Picker in den aktuellen Ordner hoch — erst in eine temporäre
     * Datei kopiert, weil [WebDavClient.upload] ein [File] erwartet, ein `content://`-Uri aber nur
     * einen Stream liefert. */
    fun uploadFrom(sourceUri: Uri) {
        val state = _uiState.value
        val application = getApplication<Application>()
        viewModelScope.launch {
            val displayName = withContext(Dispatchers.IO) { queryDisplayName(sourceUri) } ?: return@launch
            val tempFile = withContext(Dispatchers.IO) {
                val temp = File(application.cacheDir, "webdav_upload_${UUID.randomUUID()}")
                application.contentResolver.openInputStream(sourceUri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                temp
            }
            val result = WebDavClient.upload(state.account, childPath(state.path, displayName), tempFile)
            withContext(Dispatchers.IO) { tempFile.delete() }
            result.fold(
                onSuccess = { refresh() },
                onFailure = { error -> setError(error) },
            )
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index)
        }
        return uri.lastPathSegment
    }

    /** IMAGE/VIDEO-Einträge: Download in einen frischen, eindeutigen Cache-Unterordner (damit
     * [de.ble1st.files.ui.viewer.ImageViewerScreen] beim Aufbau seiner Geschwister-Liste nicht
     * versehentlich zuvor heruntergeladene, unabhängige Dateien aus demselben Cache-Verzeichnis
     * mit anzeigt) und Rückgabe der lokalen Datei zum direkten Öffnen im vorhandenen Betrachter. */
    fun downloadToCache(entry: WebDavEntry, onReady: (File) -> Unit) {
        val state = _uiState.value
        val application = getApplication<Application>()
        viewModelScope.launch {
            val destination = File(application.cacheDir, "webdav/${state.account.id}/${UUID.randomUUID()}/${entry.name}")
            WebDavClient.download(state.account, entry.path, destination).fold(
                onSuccess = { onReady(destination) },
                onFailure = { error -> setError(error) },
            )
        }
    }

    /** Alle übrigen Kategorien: direkter Download in den Downloads-Ordner des primären
     * Speichers — kein Inline-Editor für WebDAV-Quellen (ein "Speichern" dort würde nur die
     * lokale Kopie überschreiben, nicht den Server, und damit fälschlich Synchronität vortäuschen). */
    fun downloadToDownloads(entry: WebDavEntry) {
        val state = _uiState.value
        val application = getApplication<Application>()
        viewModelScope.launch {
            val primaryRoot = StorageRoots.list(application).firstOrNull()?.path
            if (primaryRoot == null) {
                setError(IllegalStateException("Kein Speicher gefunden"))
                return@launch
            }
            val downloadsDir = File(primaryRoot, Environment.DIRECTORY_DOWNLOADS)
            val targetName = withContext(Dispatchers.IO) {
                downloadsDir.mkdirs()
                FileOperations.uniqueName(downloadsDir, entry.name)
            }
            val destination = File(downloadsDir, targetName)
            WebDavClient.download(state.account, entry.path, destination).fold(
                onSuccess = { _uiState.update { it.copy(statusMessage = "Heruntergeladen nach Downloads/$targetName") } },
                onFailure = { error -> setError(error) },
            )
        }
    }

    private fun setError(error: Throwable) {
        _uiState.update { it.copy(errorMessage = error.message ?: "Vorgang fehlgeschlagen") }
    }

    private fun childPath(parent: String, name: String): String =
        if (parent == "/" || parent.isEmpty()) "/$name" else "$parent/$name"

    private fun parentOf(path: String): String = path.substringBeforeLast('/', "").ifEmpty { "/" }

    /** Eigene, kleine Factory statt der generischen `viewModelFactory { initializer { } }`-DSL —
     * die bräuchte Account+Pfad über CreationExtras, hier reichen zwei direkt übergebene Werte. */
    class Factory(
        private val application: Application,
        private val account: WebDavAccount,
        private val path: String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WebDavBrowserViewModel(application, account, path) as T
    }
}
