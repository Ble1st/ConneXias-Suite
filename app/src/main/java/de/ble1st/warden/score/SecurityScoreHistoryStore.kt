package de.ble1st.warden.score

import android.content.Context
import androidx.core.content.edit
import de.ble1st.warden.domain.score.SecurityScoreBreakdown
import de.ble1st.warden.domain.score.SecurityScoreDecision
import de.ble1st.warden.domain.score.SecurityScoreLevel
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * 30-Tage-Verlauf für den Sicherheits-Score (Nachreichung 2026-08-30 zur ursprünglich bewusst
 * ausgelassenen Historie, s. [de.ble1st.warden.domain.score.SecurityScoreDecision]-Klassendoc
 * "keine 30-Tage-Historie... bei Bedarf später ergänzbar, ohne dass sich an dieser
 * Berechnungslogik etwas ändert" — genau das passiert hier: [SecurityScoreDecision]/
 * [SecurityScoreCalculator] bleiben unverändert, dieser Store ist ein reiner Anbau).
 *
 * **Bewusst nur bei einer echten manuellen Berechnung geschrieben, nie über einen periodischen
 * Worker** — [SecurityScoreScreen] existiert überhaupt nur als eigener Bildschirm mit explizitem
 * "Berechnen"-Button, *weil* die vier zugrunde liegenden Lesepfade zusammen zu teuer sind, um
 * automatisch mitzulaufen (`PermissionAuditScanner` allein: mehrere hundert
 * `PackageManager`-Aufrufe). Ein periodischer Sampling-Worker wie
 * [de.ble1st.warden.performance.BatterySamplingWorker] würde genau diese bewusste
 * Kostenentscheidung wieder aufheben — die Historie füllt sich stattdessen organisch mit jeder
 * echten Nutzung des Bildschirms.
 *
 * Gleiches Speichermuster wie [de.ble1st.warden.performance.BatteryHistoryStore]: ein
 * Klartext-JSON-Array in `SharedPreferences`, kein `EnvelopeFile` — ein Score-Verlauf verrät keine
 * neue Information gegenüber dem, was die vier Einzelbildschirme (Scanner/Audit/Integrität/
 * Safeguards) ohnehin schon zeigen, ein zurückgesetzter Verlauf ist höchstens ärgerlich, kein
 * Sicherheitsverlust.
 *
 * Retention ist zeitbasiert statt zählbasiert (anders als [de.ble1st.warden.performance
 * .BatteryHistoryStore]s `takeLast(MAX_SAMPLES)`) — der Name der Funktion ist die Zusage
 * "30-Tage-Verlauf", nicht "die letzten N Berechnungen". [MAX_ENTRIES] bleibt als reine
 * Verteidigung gegen eine entartete Uhr/wiederholtes Mashing des Buttons, nicht als normaler
 * Begrenzer.
 */
class SecurityScoreHistoryStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun record(breakdown: SecurityScoreBreakdown, timestampMillis: Long = System.currentTimeMillis()) {
        val pruned = pruneToWindow(allEntries(), timestampMillis)
        val updated = (pruned + HistoryEntry(timestampMillis, breakdown)).takeLast(MAX_ENTRIES)
        val array = JSONArray()
        for (entry in updated) {
            val row = JSONArray()
            row.put(entry.timestampMillis)
            row.put(entry.total)
            row.put(entry.threatScore)
            row.put(entry.permissionScore)
            row.put(entry.integrityScore)
            row.put(entry.hardeningScore)
            array.put(row)
        }
        prefs.edit { putString(KEY_ENTRIES, array.toString()) }
    }

    /** Aufsteigend nach Zeitstempel, beschränkt auf die letzten [windowDays] Tage ab [now] —
     * genau die Ansicht, die [SecurityScoreScreen] zeigt. */
    fun entriesWithinWindow(windowDays: Int = RETENTION_DAYS, now: Long = System.currentTimeMillis()): List<HistoryEntry> =
        pruneToWindow(allEntries(), now, windowDays)

    private fun pruneToWindow(entries: List<HistoryEntry>, now: Long, windowDays: Int = RETENTION_DAYS): List<HistoryEntry> {
        val cutoff = now - TimeUnit.DAYS.toMillis(windowDays.toLong())
        return entries.filter { it.timestampMillis >= cutoff }
    }

    private fun allEntries(): List<HistoryEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val row = array.getJSONArray(i)
                HistoryEntry(
                    timestampMillis = row.getLong(0),
                    total = row.getInt(1),
                    threatScore = row.getInt(2),
                    permissionScore = row.getInt(3),
                    integrityScore = row.getInt(4),
                    hardeningScore = row.getInt(5),
                )
            }
        }.getOrDefault(emptyList())
    }

    data class HistoryEntry(
        val timestampMillis: Long,
        val total: Int,
        val threatScore: Int,
        val permissionScore: Int,
        val integrityScore: Int,
        val hardeningScore: Int,
    ) {
        constructor(timestampMillis: Long, breakdown: SecurityScoreBreakdown) : this(
            timestampMillis = timestampMillis,
            total = breakdown.total,
            threatScore = breakdown.threatScore,
            permissionScore = breakdown.permissionScore,
            integrityScore = breakdown.integrityScore,
            hardeningScore = breakdown.hardeningScore,
        )

        val level: SecurityScoreLevel get() = SecurityScoreDecision.levelFor(total)
    }

    private companion object {
        const val PREFS_NAME = "warden_security_score_history"
        const val KEY_ENTRIES = "entries"
        const val RETENTION_DAYS = 30
        // Verteidigung gegen eine entartete Uhr oder wiederholtes Button-Mashing, s. Klassendoc —
        // kein normaler Begrenzer, 30 Tage täglicher Nutzung liegen weit darunter.
        const val MAX_ENTRIES = 500
    }
}
