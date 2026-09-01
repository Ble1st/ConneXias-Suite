package de.ble1st.files.data.fileops

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

data class OperationRequest(
    val type: OperationType,
    val sourcePaths: List<String>,
    /** Zielordner für COPY/MOVE/EXTRACT; für COMPRESS der Ordner, in dem die Zip-Datei entsteht. */
    val destinationDirPath: String,
    /** Nur für COMPRESS: gewünschter Zip-Dateiname. Für EXTRACT: Pfad der zu entpackenden Datei
     * (liegt bereits als einziger Eintrag in [sourcePaths], hier nicht nochmal benötigt). */
    val archiveName: String? = null,
    /** Nur für COPY/MOVE relevant — vom Nutzer im Konflikt-Dialog gewählt, bevor der Job
     * enqueued wird (s. [ConflictPolicy]-Doc). */
    val conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
)

/**
 * In-Process-Kanal zwischen [FileOperationService] und der UI. Kein AIDL/Messenger nötig wie bei
 * Wardens Concord-Bus — Service und UI laufen hier immer im selben Prozess (kein Grund zur
 * Prozesstrennung, s. FileOperationService-Klassendoc), ein simples Singleton-StateFlow reicht.
 */
object FileOperationQueue {
    private val _state = MutableStateFlow<OperationState>(OperationState.Idle)
    val state: StateFlow<OperationState> = _state

    internal val cancelRequested = AtomicBoolean(false)

    internal fun publish(state: OperationState) {
        _state.value = state
    }

    fun requestCancel() {
        cancelRequested.set(true)
    }

    fun acknowledgeTerminalState() {
        _state.value = OperationState.Idle
    }
}
