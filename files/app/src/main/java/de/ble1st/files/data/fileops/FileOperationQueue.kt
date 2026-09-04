package de.ble1st.files.data.fileops

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
 * Abgeschlossener Auftrag, der der UI noch gemeldet werden muss. [id] existiert, damit zwei
 * inhaltlich identische Ergebnisse hintereinander (zweimal "Fertig (3)") von der UI als zwei
 * getrennte Meldungen erkannt werden — ohne ihn wäre der zweite von der ersten Meldung nicht zu
 * unterscheiden und würde verschluckt.
 */
data class OperationResult(val id: Long, val state: OperationState)

/**
 * In-Process-Kanal zwischen [FileOperationService] und der UI. Kein AIDL/Messenger nötig wie bei
 * Wardens Concord-Bus — Service und UI laufen hier immer im selben Prozess (kein Grund zur
 * Prozesstrennung, s. FileOperationService-Klassendoc), ein simples Singleton-StateFlow reicht.
 *
 * **Seit 2026-09-03 mit echter Warteschlange** (vorher wies der Service einen zweiten Auftrag ab
 * und die UI sperrte deshalb alle Aktionen, solange ein Job lief). Daraus folgen die zwei
 * zusätzlichen Zustände hier:
 *
 * - [pendingCount] — wie viele Aufträge noch warten. Nur für die Anzeige; die Reihenfolge selbst
 *   verwaltet der Service.
 * - [results] — die *fertigen* Aufträge, die die UI noch nicht quittiert hat. Bewusst eine Liste
 *   und nicht der [state]-Flow: bei zwei zügig hintereinander abgearbeiteten Aufträgen würde ein
 *   einzelnes Zustandsfeld die erste Abschlussmeldung überschreiben, bevor sie jemand gesehen hat.
 *   Ein `SharedFlow` wäre die andere naheliegende Lösung, verliert aber Ereignisse, während keine
 *   UI sammelt (App im Hintergrund) — genau der Normalfall bei einem Hintergrund-Kopierjob.
 */
object FileOperationQueue {
    private val _state = MutableStateFlow<OperationState>(OperationState.Idle)

    /** Der gerade laufende Auftrag — nur [OperationState.Idle] oder [OperationState.Running].
     * Abschluss-/Fehlermeldungen laufen über [results]. */
    val state: StateFlow<OperationState> = _state

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    private val _results = MutableStateFlow<List<OperationResult>>(emptyList())
    val results: StateFlow<List<OperationResult>> = _results

    private val nextResultId = AtomicLong(0)

    internal val cancelRequested = AtomicBoolean(false)

    internal fun publish(state: OperationState) {
        _state.value = state
    }

    internal fun publishPending(count: Int) {
        _pendingCount.value = count
    }

    internal fun publishResult(state: OperationState) {
        _results.update { it + OperationResult(nextResultId.incrementAndGet(), state) }
    }

    /**
     * Bricht den laufenden Auftrag ab **und verwirft alles, was noch wartet**.
     *
     * Die Alternative — nur den laufenden Auftrag abbrechen, mit dem nächsten weitermachen — wäre
     * aus der Notification heraus nicht vermittelbar: dort steht ein einziger Abbrechen-Knopf, und
     * wer ihn drückt, will einen Dateivorgang stoppen und nicht zusehen, wie unmittelbar der
     * nächste anläuft. Wie viele Aufträge dabei verworfen wurden, meldet
     * [OperationState.Cancelled] anschließend an die UI.
     */
    fun requestCancel() {
        cancelRequested.set(true)
    }

    /** Entfernt [result] aus [results], nachdem die UI ihn angezeigt hat. */
    fun acknowledgeResult(result: OperationResult) {
        _results.update { list -> list.filterNot { it.id == result.id } }
    }
}
