package de.ble1st.files.ui.trash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.ble1st.files.data.fileops.FileOperations
import de.ble1st.files.data.trash.TrashEntry
import de.ble1st.files.data.trash.TrashOperations
import de.ble1st.files.data.trash.TrashStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TrashUiState(
    val entries: List<TrashEntry> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val pendingDialog: TrashDialog? = null,
)

sealed interface TrashDialog {
    data class ConfirmDeleteForever(val entry: TrashEntry) : TrashDialog
    data object ConfirmEmpty : TrashDialog
}

/**
 * [TrashStore.purgeExpired] läuft opportunistisch bei jedem [refresh] statt über einen eigenen
 * periodischen Worker (s. dortiges Klassendoc) — dieser Bildschirm ist der einzige Ort, an dem ein
 * abgelaufener Eintrag überhaupt sichtbar würde, ein Aufruf beim Öffnen reicht also aus.
 */
class TrashViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(TrashUiState())
    val uiState: StateFlow<TrashUiState> = _uiState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val entries = withContext(Dispatchers.IO) {
                TrashStore.purgeExpired(getApplication())
                TrashStore.list(getApplication()).sortedByDescending { it.deletedAtMillis }
            }
            _uiState.update { it.copy(isLoading = false, entries = entries) }
        }
    }

    fun showDialog(dialog: TrashDialog?) = _uiState.update { it.copy(pendingDialog = dialog) }

    fun restore(entry: TrashEntry) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { TrashOperations.restore(entry) }
            if (result.isSuccess) {
                withContext(Dispatchers.IO) { TrashStore.remove(getApplication(), entry.id) }
            }
            _uiState.update { it.copy(errorMessage = result.exceptionOrNull()?.message) }
            refresh()
        }
    }

    fun deleteForever(entry: TrashEntry) {
        showDialog(null)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Derselbe rekursive, Symlink-sichere Lösch-Pfad wie überall sonst — der Eintrag
                // liegt bereits im Papierkorb, hier gibt es kein "Wiederherstellen" mehr danach.
                FileOperations.delete(listOf(entry.trashFile), { false }, { _, _, _ -> })
                TrashStore.remove(getApplication(), entry.id)
            }
            refresh()
        }
    }

    fun emptyTrash() {
        showDialog(null)
        val entries = _uiState.value.entries
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                FileOperations.delete(entries.map { it.trashFile }, { false }, { _, _, _ -> })
                entries.forEach { TrashStore.remove(getApplication(), it.id) }
            }
            refresh()
        }
    }
}
