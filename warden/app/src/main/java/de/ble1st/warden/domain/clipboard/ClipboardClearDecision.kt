package de.ble1st.warden.domain.clipboard

/**
 * Reine Entscheidungslogik für [de.ble1st.warden.clipboard.ClipboardGuardController]
 * (`docs/design-clipboard-guard.md`, Phase 1). Android beschränkt Zwischenablage-Zugriff seit
 * API 29 auf die im Fensterfokus stehende App bzw. die Standard-IME — Warden kann also nur seine
 * *eigene* Sicht auf die Zwischenablage leeren, und nur in Momenten, in denen es selbst
 * nachweislich Fokus hat (Dashboard-Resume, manueller Button). Diese Klasse entscheidet nur *ob*
 * geleert werden soll, nicht *wann* Warden aufgerufen wird — das bleibt Sache der Aufrufer.
 */
object ClipboardClearDecision {

    sealed class Action {
        data object Clear : Action()
        data class Skip(val reason: SkipReason) : Action()
    }

    enum class SkipReason { DISABLED, EMPTY, BELOW_THRESHOLD, AGE_UNKNOWN_KEEP }

    /**
     * [ageMillis] `null` bedeutet: das Alter des aktuellen Eintrags ist nicht bekannt (z. B. weil
     * [android.content.ClipDescription.getTimestamp] keinen Wert lieferte). Bei einem aktiven
     * Schwellenwert > 0 wird in diesem Fall NICHT geleert — fail-closed im Sinne von "lieber
     * frischen, absichtlich kopierten Inhalt stehen lassen als ihn ungefragt löschen", nicht im
     * Sinne der sonstigen Sicherheits-Fail-Safe-Konvention (dort wäre "im Zweifel löschen" das
     * sichere Verhalten — hier ist das Gegenteil das nutzerfreundlichere und wird bewusst gewählt).
     * Ein Schwellenwert von 0 (sofort leeren) ignoriert das Alter ohnehin und braucht es nicht.
     */
    fun action(
        enabled: Boolean,
        hasContent: Boolean,
        ageMillis: Long?,
        thresholdMillis: Long,
    ): Action = when {
        !enabled -> Action.Skip(SkipReason.DISABLED)
        !hasContent -> Action.Skip(SkipReason.EMPTY)
        thresholdMillis <= 0L -> Action.Clear
        ageMillis == null -> Action.Skip(SkipReason.AGE_UNKNOWN_KEEP)
        ageMillis >= thresholdMillis -> Action.Clear
        else -> Action.Skip(SkipReason.BELOW_THRESHOLD)
    }
}
