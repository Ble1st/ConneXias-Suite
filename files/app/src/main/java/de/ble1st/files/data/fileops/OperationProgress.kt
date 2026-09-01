package de.ble1st.files.data.fileops

enum class OperationType { COPY, MOVE, DELETE, COMPRESS, EXTRACT }

/**
 * Entscheidet, was mit COPY/MOVE-Zielen passiert, die im Zielordner bereits existieren
 * (Top-Level-Konflikte — Konflikte innerhalb zusammengeführter Unterordner folgen weiterhin dem
 * bisherigen Verhalten von [FileOperations.copyRecursive]). Wird einmal vor dem Start des Jobs
 * festgelegt (s. [de.ble1st.files.ui.browser.FileBrowserViewModel.pasteFromClipboard]), nicht
 * interaktiv währenddessen — der Service läuft ohne UI-Anbindung.
 */
enum class ConflictPolicy {
    /** Bisheriges Standardverhalten: Konflikt wird durch Anhängen von " (1)" etc. aufgelöst. */
    KEEP_BOTH,

    /** Vorhandene Ziel-Datei/-Ordner wird vor dem Kopieren/Verschieben gelöscht. */
    OVERWRITE,

    /** Die betroffene Quelle wird komplett übersprungen (bei MOVE bleibt sie unangetastet an der
     * Quelle liegen, wird also nicht gelöscht). */
    SKIP,
}

/** Fortschritt nach Dateianzahl, nicht nach Bytes — reicht für eine Notification/Fortschrittszeile
 * und erspart eine zweite, byte-genaue Traversierung nur für die Anzeige. */
data class OperationProgress(
    val type: OperationType,
    val currentItemName: String,
    val processedCount: Int,
    val totalCount: Int,
)

sealed interface OperationState {
    data object Idle : OperationState
    data class Running(val progress: OperationProgress) : OperationState
    data class Completed(
        val type: OperationType,
        val successCount: Int,
        val failedCount: Int,
        val skippedCount: Int = 0,
    ) : OperationState
    data class Failed(val type: OperationType, val message: String) : OperationState
}

/** Ergebnis eines einzelnen Datei-/Ordner-Vorgangs — Sammlung davon ergibt Completed.failedCount. */
data class OperationOutcome(val source: String, val error: Throwable? = null, val skipped: Boolean = false) {
    val succeeded: Boolean get() = error == null && !skipped
}
