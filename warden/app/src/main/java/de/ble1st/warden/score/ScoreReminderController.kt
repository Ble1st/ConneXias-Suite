package de.ble1st.warden.score

import android.content.Context
import android.util.Log
import de.ble1st.warden.domain.score.ScoreReminderDecision
import de.ble1st.warden.wardenAuditLog

/**
 * Android-Glue für [ScoreReminderDecision] (2026-09-03, Ideenliste Punkt 7) — angestoßen
 * periodisch von [ScoreReminderWorker]. Kein Ein-/Ausschalter, kein Startup-Sofortlauf: anders als
 * die übrigen lokalen Trigger in diesem Projekt ist eine veralteter Score kein Ereignis, das
 * unmittelbar nach einem Neustart relevant würde.
 */
class ScoreReminderController(private val context: Context) {

    fun checkAndMaybeRemind() {
        val hasRecentEntry = SecurityScoreHistoryStore(context).entriesWithinWindow().isNotEmpty()
        val now = System.currentTimeMillis()
        val shouldRemind = ScoreReminderDecision.shouldRemind(
            hasRecentScoreEntry = hasRecentEntry,
            lastReminderAtMillis = ScoreReminderStorage.loadLastReminderAt(context),
            nowMillis = now,
            dedupWindowDays = DEDUP_WINDOW_DAYS,
        )
        if (!shouldRemind) return

        ScoreReminderStorage.saveLastReminderAt(context, now)
        runCatching { ScoreReminderNotifier(context).send() }
            .onFailure { Log.w(TAG, "Score-Erinnerung fehlgeschlagen", it) }
        wardenAuditLog(context).append(Log.INFO, TAG, "Erinnerung gesendet: Sicherheits-Score seit über 30 Tagen nicht neu berechnet")
    }

    private companion object {
        const val TAG = "ScoreReminder"
        const val DEDUP_WINDOW_DAYS = 7
    }
}
