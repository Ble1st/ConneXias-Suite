package de.ble1st.warden.policycoexistence

import android.content.Context
import androidx.core.content.edit
import de.ble1st.warden.domain.policycoexistence.PolicyConflictRecord
import de.ble1st.warden.domain.policycoexistence.PolicyUpdateOutcome

/**
 * Ringpuffer der letzten Richtlinien-Rückmeldungen des Systems (Tier 3 der DPC-Recherche,
 * 2026-09-05).
 *
 * **Device-Protected Storage**, wie [de.ble1st.warden.registry.RegistryStorage] und
 * [de.ble1st.warden.logging.LogStorage]: der Broadcast von `system_server` kann Warden schon vor
 * der ersten Entsperrung erreichen (der Empfänger ist `directBootAware`, genau wie
 * [de.ble1st.warden.admin.WardenDeviceAdminReceiver] seit dem BFU-Befund). Läge dieser Store im
 * credential-verschlüsselten Bereich, gingen genau die Meldungen verloren, die im BFU-Fenster
 * entstehen.
 *
 * **Klartext-`SharedPreferences` statt [de.ble1st.warden.crypto.EnvelopeFile]**, dieselbe
 * Begründung wie bei [de.ble1st.warden.appmanagement.RevokedPermissionStore]: Androids eigene
 * Richtlinien-Bezeichner sind öffentliche Konstantennamen und tragen kein Geheimnis. Ein Verlust
 * des Inhalts bedeutet "Warden weiß nichts über Konflikte", nicht "es gibt keine" — und genau so
 * unterscheidet [de.ble1st.warden.domain.policycoexistence.PolicyCoexistenceDecision.hasEverReported]
 * die beiden Fälle auch in der Anzeige.
 */
object PolicyConflictStore {
    private const val PREFS_NAME = "warden_policy_conflicts"
    private const val KEY_RECORDS = "records"

    /** Bewusst klein: die Anzeige zeigt ohnehin nur den jüngsten Eintrag je Bezeichner, und die
     * Zahl der von Warden gesetzten Richtlinien liegt in derselben Größenordnung. Der vollständige
     * Verlauf steht im Audit-Log, nicht hier. */
    const val MAX_RECORDS = 60

    fun record(context: Context, entry: PolicyConflictRecord) {
        val prefs = prefs(context)
        val updated = (load(context) + entry).takeLast(MAX_RECORDS)
        prefs.edit { putStringSet(KEY_RECORDS, updated.map { encode(it) }.toSet()) }
    }

    fun load(context: Context): List<PolicyConflictRecord> =
        prefs(context).getStringSet(KEY_RECORDS, emptySet())
            .orEmpty()
            .mapNotNull { decode(it) }
            // `getStringSet` gibt keine Reihenfolge zurück — die Zeitstempel sind die einzige
            // verlässliche Ordnung, und `record()`s `takeLast` verlässt sich darauf.
            .sortedBy { it.timestampMillis }

    fun clear(context: Context) {
        prefs(context).edit { remove(KEY_RECORDS) }
    }

    private fun encode(entry: PolicyConflictRecord): String =
        "${entry.timestampMillis}|${entry.outcome.name}|${entry.policyIdentifier}"

    /** Eine unlesbare Zeile wird übersprungen statt den ganzen Store zu verwerfen — dieselbe
     * bewusste Ausnahme von der Fail-Safe-Regel wie in
     * [de.ble1st.warden.logging.SecurityLogCodec], und aus demselben Grund: das hier ist eine
     * Diagnosekopie dessen, was das System ohnehin weiß, kein Nachweis über Wardens eigene
     * Entscheidungen. */
    private fun decode(raw: String): PolicyConflictRecord? {
        val parts = raw.split("|", limit = 3)
        if (parts.size != 3) return null
        val timestamp = parts[0].toLongOrNull() ?: return null
        val outcome = runCatching { PolicyUpdateOutcome.valueOf(parts[1]) }.getOrNull() ?: return null
        return PolicyConflictRecord(policyIdentifier = parts[2], outcome = outcome, timestampMillis = timestamp)
    }

    private fun prefs(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
