package de.ble1st.warden.domain.score

import java.util.concurrent.TimeUnit

/**
 * "Score-Berechnung-Erinnerung" (2026-09-03, Ideenliste Punkt 7) — reine Entscheidungslogik für
 * [de.ble1st.warden.score.ScoreReminderController]: soll gerade eine Erinnerung gesendet werden,
 * dass der Sicherheits-Score seit über 30 Tagen nicht neu berechnet wurde?
 *
 * Keine automatische Neuberechnung (das würde genau die bewusste Kostenentscheidung aufheben, die
 * [de.ble1st.warden.score.SecurityScoreHistoryStore]s Klassendoc dokumentiert — mehrere hundert
 * `PackageManager`-Aufrufe allein für den Rechte-Audit-Teil) — nur eine Erinnerung, die der Nutzer
 * selbst in eine echte Berechnung umsetzt.
 *
 * `dedupWindowDays` verhindert, dass ein dauerhaft veralteter Score bei jedem periodischen
 * Prüflauf erneut erinnert — ohne diese Sperre würde ein Nutzer, der die Erinnerung wochenlang
 * ignoriert, sie stattdessen jeden Tag erneut sehen.
 */
object ScoreReminderDecision {
    fun shouldRemind(
        hasRecentScoreEntry: Boolean,
        lastReminderAtMillis: Long?,
        nowMillis: Long,
        dedupWindowDays: Int,
    ): Boolean {
        if (hasRecentScoreEntry) return false
        val last = lastReminderAtMillis ?: return true
        return nowMillis - last >= TimeUnit.DAYS.toMillis(dedupWindowDays.toLong())
    }
}
