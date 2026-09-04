package de.ble1st.files.ui.localshare

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import de.ble1st.files.data.localshare.LocalShareService
import de.ble1st.files.data.localshare.LocalShareState
import de.ble1st.files.data.localshare.LocalShareStatus
import java.io.File
import kotlinx.coroutines.flow.StateFlow

/**
 * Dünner Wrapper um [LocalShareState] — der eigentliche Zustand lebt im Service-Prozess-Kanal
 * (s. dortiges Klassendoc), damit er über Screen-Rotationen/Neuaufbauten dieses Bildschirms hinweg
 * erhalten bleibt, solange der Service läuft (genau wie `FileOperationQueue` für Kopier-Jobs).
 */
class LocalShareViewModel(application: Application) : AndroidViewModel(application) {
    val status: StateFlow<LocalShareStatus> = LocalShareState.status

    fun start(directory: File) = LocalShareService.start(getApplication(), directory)

    fun stop() = LocalShareService.stop(getApplication())
}
