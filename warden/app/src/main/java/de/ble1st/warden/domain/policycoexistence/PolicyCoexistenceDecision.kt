package de.ble1st.warden.domain.policycoexistence

/** Eine einzelne Rückmeldung des Systems zu einer von Warden gesetzten Richtlinie. */
data class PolicyConflictRecord(
    /** Androids eigener Richtlinien-Bezeichner (`DevicePolicyIdentifiers.*`), unübersetzt
     * übernommen — er ist der einzige stabile Schlüssel, unter dem sich dieselbe Meldung später
     * auch in `adb shell dumpsys device_policy` wiederfinden lässt. */
    val policyIdentifier: String,
    val outcome: PolicyUpdateOutcome,
    val timestampMillis: Long,
)

/**
 * Verdichtet den Strom der Richtlinien-Rückmeldungen zu dem, was die Systemdiagnose zeigen soll
 * (Tier 3 der DPC-Recherche, 2026-09-05).
 *
 * **Nur der jeweils jüngste Eintrag pro Bezeichner zählt.** Der Verlauf ist hier bewusst kein
 * Protokoll: eine Richtlinie, die vor einer Stunde überstimmt wurde und seitdem wieder greift, ist
 * *kein* offenes Problem, und beide Zeilen nebeneinander anzuzeigen wäre nicht mehr, sondern
 * weniger Information. Wer den vollständigen Verlauf braucht, findet ihn im Audit-Log — dort
 * schreibt [de.ble1st.warden.admin.WardenPolicyUpdateReceiver] jeden Problemfall zusätzlich hin.
 */
object PolicyCoexistenceDecision {

    /** Die aktuell offenen Problemfälle, neueste zuerst. Leer = alles, was Warden gesetzt hat,
     * hat das System auch angenommen (oder es kam noch nie eine Rückmeldung — s.
     * [hasEverReported]). */
    fun currentProblems(records: List<PolicyConflictRecord>): List<PolicyConflictRecord> =
        latestPerIdentifier(records)
            .filter { it.outcome.isProblem }
            .sortedByDescending { it.timestampMillis }

    /**
     * Ob überhaupt je eine Rückmeldung eingetroffen ist. Wichtig für die Anzeige, und exakt die
     * "Unsicherheit nicht als Entwarnung verkaufen"-Haltung des Projekts: eine leere
     * [currentProblems]-Liste bedeutet bei `false` **nicht** "keine Konflikte", sondern "Warden
     * weiß es nicht" — etwa weil seit der Installation noch keine Richtlinie neu gesetzt wurde
     * oder das Gerät den Broadcast gar nicht liefert.
     */
    fun hasEverReported(records: List<PolicyConflictRecord>): Boolean = records.isNotEmpty()

    private fun latestPerIdentifier(records: List<PolicyConflictRecord>): List<PolicyConflictRecord> =
        records
            .groupBy { it.policyIdentifier }
            .map { (_, forIdentifier) -> forIdentifier.maxBy { it.timestampMillis } }
}
