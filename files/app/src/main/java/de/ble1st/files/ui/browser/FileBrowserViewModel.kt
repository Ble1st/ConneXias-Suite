package de.ble1st.files.ui.browser

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import de.ble1st.files.data.fileops.ClipboardContent
import de.ble1st.files.data.fileops.ClipboardHolder
import de.ble1st.files.data.fileops.ClipboardMode
import de.ble1st.files.data.fileops.ConflictPolicy
import de.ble1st.files.data.fileops.FileOperationRequestBuilder
import de.ble1st.files.data.fileops.FileOperationService
import de.ble1st.files.data.fileops.FileOperations
import de.ble1st.files.data.fs.FileEntry
import de.ble1st.files.data.fs.LocalFileSystem
import de.ble1st.files.data.fs.SortOrder
import de.ble1st.files.data.fs.sortedByOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class FileBrowserUiState(
    val directory: File,
    val entries: List<FileEntry> = emptyList(),
    val sortOrder: SortOrder = SortOrder(),
    val searchQuery: String = "",
    val selectedPaths: Set<String> = emptySet(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val pendingDialog: BrowserDialog? = null,
    val viewMode: ViewMode = ViewMode.LIST,
) {
    val isSelectionMode: Boolean get() = selectedPaths.isNotEmpty()
    val visibleEntries: List<FileEntry> by lazy {
        val filtered = if (searchQuery.isBlank()) {
            entries
        } else {
            entries.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
        filtered.sortedByOrder(sortOrder)
    }
}

sealed interface BrowserDialog {
    data object NewFolder : BrowserDialog
    data object NewFile : BrowserDialog
    data class Rename(val entry: FileEntry) : BrowserDialog
    data class ConfirmDelete(val entries: List<FileEntry>) : BrowserDialog
    data class Properties(val entry: FileEntry) : BrowserDialog
    data class Compress(val entries: List<FileEntry>) : BrowserDialog

    /** Top-Level-Namenskollisionen zwischen Zwischenablage und Zielordner — [pasteFromClipboard]
     * zeigt diesen Dialog nur, wenn [conflictingNames] nicht leer ist; ohne Konflikt wird wie
     * bisher direkt eingefügt (KEEP_BOTH). */
    data class ConflictResolution(val conflictingNames: List<String>) : BrowserDialog
}

class FileBrowserViewModel(application: Application, private val directory: File) :
    AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        FileBrowserUiState(directory = directory, viewMode = ViewModePreference.get(application)),
    )
    val uiState: StateFlow<FileBrowserUiState> = _uiState
    val clipboard: StateFlow<ClipboardContent?> = ClipboardHolder.content

    init {
        refresh()
    }

    /** Global statt pro Ordner persistiert (s. [ViewModePreference]-Klassendoc) — jede offene
     * Ordner-Instanz übernimmt den neuen Modus erst beim nächsten eigenen Aufruf, das ist für eine
     * reine Anzeige-Präferenz ohne spürbare Verzögerung unproblematisch. */
    fun setViewMode(mode: ViewMode) {
        ViewModePreference.set(getApplication(), mode)
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            val listing = withContext(Dispatchers.IO) { LocalFileSystem.list(directory) }
            _uiState.update {
                if (listing == null) {
                    it.copy(isLoading = false, errorMessage = "Ordner nicht lesbar", entries = emptyList())
                } else {
                    it.copy(isLoading = false, entries = listing)
                }
            }
        }
    }

    fun setSearchQuery(query: String) = _uiState.update { it.copy(searchQuery = query) }

    fun setSortOrder(order: SortOrder) = _uiState.update { it.copy(sortOrder = order) }

    fun toggleSelection(entry: FileEntry) = _uiState.update { state ->
        val path = entry.file.path
        val next = if (path in state.selectedPaths) state.selectedPaths - path else state.selectedPaths + path
        state.copy(selectedPaths = next)
    }

    fun clearSelection() = _uiState.update { it.copy(selectedPaths = emptySet()) }

    fun selectAll() = _uiState.update { state ->
        state.copy(selectedPaths = state.visibleEntries.map { it.file.path }.toSet())
    }

    fun selectedEntries(): List<FileEntry> {
        val state = _uiState.value
        return state.entries.filter { it.file.path in state.selectedPaths }
    }

    fun showDialog(dialog: BrowserDialog?) = _uiState.update { it.copy(pendingDialog = dialog) }

    fun createFolder(name: String) = runFileOpAndRefresh { FileOperations.createFolder(directory, name) }

    fun createFile(name: String) = runFileOpAndRefresh { FileOperations.createFile(directory, name) }

    fun rename(entry: FileEntry, newName: String) = runFileOpAndRefresh { FileOperations.rename(entry.file, newName) }

    private fun runFileOpAndRefresh(block: () -> Result<File>) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            _uiState.update { it.copy(pendingDialog = null, errorMessage = result.exceptionOrNull()?.message) }
            refresh()
        }
    }

    fun copySelectionToClipboard() {
        ClipboardHolder.set(_uiState.value.selectedPaths.toList(), ClipboardMode.COPY)
        clearSelection()
    }

    fun cutSelectionToClipboard() {
        ClipboardHolder.set(_uiState.value.selectedPaths.toList(), ClipboardMode.CUT)
        clearSelection()
    }

    /** Prüft vor dem Einfügen auf Top-Level-Namenskollisionen mit dem Zielordner — bei einem
     * Treffer entscheidet der Nutzer per Dialog statt der Job still per KEEP_BOTH umzubenennen
     * (s. [BrowserDialog.ConflictResolution]-Doc). */
    fun pasteFromClipboard() {
        val clip = ClipboardHolder.content.value ?: return
        val conflicts = FileOperations.findConflicts(clip.paths.map { File(it) }, directory)
        if (conflicts.isEmpty()) {
            enqueuePaste(clip, ConflictPolicy.KEEP_BOTH)
        } else {
            showDialog(BrowserDialog.ConflictResolution(conflicts))
        }
    }

    /** Wird vom Konflikt-Dialog aufgerufen, nachdem der Nutzer sich für eine Auflösung
     * entschieden hat (oder mit `null` bei "Abbrechen", was den Paste-Vorgang komplett verwirft). */
    fun resolveConflictAndPaste(policy: ConflictPolicy?) {
        showDialog(null)
        if (policy == null) return
        val clip = ClipboardHolder.content.value ?: return
        enqueuePaste(clip, policy)
    }

    private fun enqueuePaste(clip: ClipboardContent, policy: ConflictPolicy) {
        val request = FileOperationRequestBuilder.forClipboard(clip, directory, policy)
        FileOperationService.enqueue(getApplication(), request)
        if (clip.mode == ClipboardMode.CUT) ClipboardHolder.clear()
    }

    /** Verschiebt in den Papierkorb (s. `data/trash/TrashEntry.kt`) statt endgültig zu löschen —
     * seit Einführung des Papierkorbs der normale "Löschen"-Pfad im Datei-Browser. */
    fun deleteEntries(entries: List<FileEntry>) {
        val request = FileOperationRequestBuilder.forTrash(entries)
        FileOperationService.enqueue(getApplication(), request)
        clearSelection()
        showDialog(null)
    }

    /** `forCompress` wirft jetzt über `sanitizeName` (analyse.md-Fix, s. dortiger Kommentar), z. B.
     * bei einem Archivnamen, der nach Entfernen der Pfadanteile leer/"."/".." wäre — `NameInputDialog`
     * verhindert nur eine komplett leere Eingabe, nicht diese Fälle. `runCatching` statt eines
     * ungefangenen Absturzes, `errorMessage` dasselbe Feld wie bei jedem anderen fehlgeschlagenen
     * Namens-Dialog (s. `runFileOpAndRefresh`). */
    fun compress(entries: List<FileEntry>, archiveName: String) {
        val request = runCatching { FileOperationRequestBuilder.forCompress(entries, directory, archiveName) }
            .onFailure { _uiState.update { state -> state.copy(pendingDialog = null, errorMessage = it.message) } }
            .getOrNull() ?: return
        FileOperationService.enqueue(getApplication(), request)
        clearSelection()
        showDialog(null)
    }

    /** Entpackt in einen neuen, nach dem Archiv benannten Unterordner statt lose in den aktuellen
     * Ordner — sonst vermischen sich die entpackten Einträge sofort mit dem Rest des Inhalts. */
    fun extractHere(entry: FileEntry) {
        val targetName = FileOperations.uniqueName(directory, entry.file.nameWithoutExtension)
        val targetDir = File(directory, targetName)
        targetDir.mkdirs()
        val request = FileOperationRequestBuilder.forExtract(entry, targetDir)
        FileOperationService.enqueue(getApplication(), request)
    }

    /**
     * SAF-Import (Nutzer wählt Dateien über den System-Picker, z. B. aus einem Cloud-Anbieter oder
     * einer per SAF eingebundenen SD-Karte) — die ausgewählten `content://`-Uris lassen sich nicht
     * direkt als java.io.File behandeln, deshalb ein expliziter ContentResolver-Stream-Kopiervorgang
     * statt des sonst genutzten [FileOperations.copy].
     */
    fun importUris(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            for (uri in uris) {
                val displayName = queryDisplayName(uri) ?: continue
                // displayName kommt aus einer fremden ContentProvider-DISPLAY_NAME-Spalte — nicht
                // vertrauenswürdig, ein präparierter Wert mit "../" könnte sonst außerhalb von
                // [directory] schreiben (s. FileOperations.sanitizeName-Doc). Ungültige Namen
                // werden übersprungen statt den ganzen Import abzubrechen.
                val safeName = runCatching { FileOperations.sanitizeName(displayName) }.getOrNull() ?: continue
                val target = File(directory, FileOperations.uniqueName(directory, safeName))
                runCatching {
                    resolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            refresh()
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

    /** Eigene, kleine Factory statt der generischen `viewModelFactory { initializer { } }`-DSL —
     * die bräuchte den Ordnerpfad über CreationExtras, hier reicht ein direkt übergebenes File. */
    class Factory(private val application: Application, private val directory: File) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FileBrowserViewModel(application, directory) as T
    }
}
